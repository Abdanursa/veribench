package com.veribench.app.suites

import com.veribench.app.core.Benchmark
import com.veribench.app.core.Category
import com.veribench.app.core.IterationResult
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Multi-core scaling tests. One worker per hardware thread, each running the
 * same fixed-size kernel on its own private data. Total work therefore scales
 * with the core count, and throughput = total work / wall time captures real
 * parallel throughput (including big.LITTLE asymmetry — slow cores genuinely
 * lower parallel throughput, so they belong in the measurement).
 *
 * Every worker must reproduce the identical single-thread checksum, which
 * keeps the expected value device-independent regardless of core count.
 */
abstract class MultiCoreBenchmark : Benchmark {
    final override val category = Category.CPU_MULTI

    protected val threads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private var pool: ExecutorService? = null

    /** Work units (in this benchmark's own terms) one worker performs per iteration. */
    protected abstract val workPerThread: Double

    /** Per-worker kernel; must return the workload checksum. */
    protected abstract fun workerIteration(workerIndex: Int): Long

    /** Per-worker untimed allocation. */
    protected open fun setUpWorker(workerIndex: Int) {}

    final override fun setUp() {
        pool = Executors.newFixedThreadPool(threads)
        for (i in 0 until threads) setUpWorker(i)
    }

    final override fun tearDown() {
        pool?.shutdownNow()
        pool = null
    }

    final override fun runIteration(): IterationResult {
        val executor = pool ?: error("setUp not called")
        val tasks = (0 until threads).map { idx -> Callable { workerIteration(idx) } }
        val t0 = System.nanoTime()
        val futures = executor.invokeAll(tasks)
        val checksums = futures.map { it.get() }
        val nanos = System.nanoTime() - t0

        val consistent = checksums.all { it == checksums[0] }
        val cs = if (consistent) checksums[0] else checksums[0] xor 1L
        val throughput = workPerThread * threads / (nanos / 1e9)
        return IterationResult(throughput, cs)
    }
}

class MultiSieveBenchmark : MultiCoreBenchmark() {
    override val id = "mc.sieve"
    override val name = "Prime sieve · all cores"
    override val unit = "Mnum/s"
    override val expectedChecksum = Expected.SIEVE

    private val n = 4_000_000
    private val repeats = 4
    override val workPerThread = n.toDouble() * repeats / 1e6

    override fun workerIteration(workerIndex: Int): Long {
        var cs = 0L
        var consistent = true
        for (r in 0 until repeats) {
            val c = CpuWorkloads.sieveChecksum(n)
            if (r == 0) cs = c else if (c != cs) consistent = false
        }
        return if (consistent) cs else cs xor 1L
    }
}

class MultiMatMulBenchmark : MultiCoreBenchmark() {
    override val id = "mc.matmul"
    override val name = "Matrix multiply · all cores"
    override val unit = "MFLOPS"
    override val expectedChecksum = Expected.MATMUL

    private val n = 256
    private val repeats = 6
    override val workPerThread = 2.0 * n * n * n * repeats / 1e6

    private lateinit var a: DoubleArray
    private lateinit var b: DoubleArray
    private lateinit var work: Array<DoubleArray>

    override fun setUpWorker(workerIndex: Int) {
        if (workerIndex == 0) {
            // Inputs are read-only and shared; each worker gets its own output.
            a = CpuWorkloads.makeMatrix(11L, n)
            b = CpuWorkloads.makeMatrix(22L, n)
            work = Array(threads) { DoubleArray(n * n) }
        }
    }

    override fun workerIteration(workerIndex: Int): Long {
        val c = work[workerIndex]
        var cs = 0L
        var consistent = true
        for (r in 0 until repeats) {
            val v = CpuWorkloads.matmul(a, b, c, n)
            if (r == 0) cs = v else if (v != cs) consistent = false
        }
        return if (consistent) cs else cs xor 1L
    }
}

class MultiSha256Benchmark : MultiCoreBenchmark() {
    override val id = "mc.sha256"
    override val name = "SHA-256 · all cores"
    override val unit = "MB/s"
    override val expectedChecksum = Expected.SHA256

    private val sizeMb = 16
    override val workPerThread = sizeMb.toDouble()

    private lateinit var data: ByteArray
    private lateinit var hashers: Array<Sha256>

    override fun setUpWorker(workerIndex: Int) {
        if (workerIndex == 0) {
            // Same seed and size as cpu.sha256 so the digest constant is shared.
            val rng = SplitMix64(44L)
            data = ByteArray(sizeMb shl 20)
            var i = 0
            while (i < data.size) {
                val v = rng.nextLong()
                for (s in 0 until 8) data[i + s] = (v ushr (s * 8)).toByte()
                i += 8
            }
            hashers = Array(threads) { Sha256() }
        }
    }

    override fun workerIteration(workerIndex: Int): Long {
        val hasher = hashers[workerIndex]
        hasher.reset()
        var off = 0
        while (off < data.size) {
            val chunk = minOf(1 shl 16, data.size - off)
            hasher.update(data, off, chunk)
            off += chunk
        }
        val digest = hasher.digest()
        var cs = 0L
        for (i in 0 until 8) cs = (cs shl 8) or (digest[i].toLong() and 0xff)
        return cs
    }
}
