package com.veribench.app.suites

import com.veribench.app.core.Benchmark
import com.veribench.app.core.Category
import com.veribench.app.core.IterationResult
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Expected checksums for every deterministic workload, at the exact sizes the
 * app runs. Pinned by ChecksumRegressionTest, which recomputes each value on
 * the JVM with the same pure-Kotlin kernels: if a workload, a size, or a seed
 * changes without a scoring-version bump, CI fails.
 */
object Expected {
    const val SIEVE = 311322319482678813L
    const val MATMUL = 4162627L
    const val FFT = 8061894132760531638L
    const val SORT = -2150272913546012423L
    const val SHA256 = 6185813990831523410L
    const val DEFLATE = 945386008202720821L
    const val MEM_SEQCOPY = -5490579508039320389L
    const val MEM_RANDLAT = 3660559L
    const val MEM_TRIAD = -7578647L
    const val JSON = -3371271753031993447L
    const val COLLECTIONS = -4145793331530818901L
    const val BITMAP = 2184395595381801141L
    const val STORAGE_DATA = -2007889998537909960L
    const val STORAGE_SEQREAD = -4090393801935149440L
    const val STORAGE_RANDREAD = -1885828986916572215L
}

/** Runs [repeats] kernel executions, checking each repeat reproduces the same checksum. */
internal inline fun repeatVerified(repeats: Int, kernel: () -> Long): Pair<Long, Long> {
    val t0 = System.nanoTime()
    var cs = 0L
    var consistent = true
    for (r in 0 until repeats) {
        val c = kernel()
        if (r == 0) cs = c else if (c != cs) consistent = false
    }
    val elapsed = System.nanoTime() - t0
    return Pair(if (consistent) cs else cs xor 1L, elapsed)
}

private const val NANOS = 1e9

class SieveBenchmark : Benchmark {
    override val id = "cpu.sieve"
    override val name = "Prime sieve"
    override val category = Category.CPU_SINGLE
    override val unit = "Mnum/s"
    override val expectedChecksum = Expected.SIEVE

    private val n = 4_000_000
    private val repeats = 8

    override fun runIteration(): IterationResult {
        val (cs, nanos) = repeatVerified(repeats) { CpuWorkloads.sieveChecksum(n) }
        val throughput = n.toDouble() * repeats / (nanos / NANOS) / 1e6
        return IterationResult(throughput, cs)
    }
}

class MatMulBenchmark : Benchmark {
    override val id = "cpu.matmul"
    override val name = "Matrix multiply (FP64)"
    override val category = Category.CPU_SINGLE
    override val unit = "MFLOPS"
    override val expectedChecksum = Expected.MATMUL

    private val n = 256
    private val repeats = 10
    private lateinit var a: DoubleArray
    private lateinit var b: DoubleArray
    private lateinit var c: DoubleArray

    override fun setUp() {
        a = CpuWorkloads.makeMatrix(11L, n)
        b = CpuWorkloads.makeMatrix(22L, n)
        c = DoubleArray(n * n)
    }

    override fun runIteration(): IterationResult {
        val (cs, nanos) = repeatVerified(repeats) { CpuWorkloads.matmul(a, b, c, n) }
        val flops = 2.0 * n * n * n * repeats
        return IterationResult(flops / (nanos / NANOS) / 1e6, cs)
    }
}

class FftBenchmark : Benchmark {
    override val id = "cpu.fft"
    override val name = "FFT 64K (FP64)"
    override val category = Category.CPU_SINGLE
    override val unit = "MFLOPS"
    override val expectedChecksum = Expected.FFT

    private val n = 65536
    private val repeats = 24
    private lateinit var srcRe: DoubleArray
    private lateinit var srcIm: DoubleArray
    private lateinit var workRe: DoubleArray
    private lateinit var workIm: DoubleArray

    override fun setUp() {
        val (re, im) = CpuWorkloads.makeSignal(33L, n)
        srcRe = re
        srcIm = im
        workRe = DoubleArray(n)
        workIm = DoubleArray(n)
    }

    override fun runIteration(): IterationResult {
        val (cs, nanos) = repeatVerified(repeats) {
            CpuWorkloads.fftChecksum(srcRe, srcIm, workRe, workIm)
        }
        // Standard FFT cost model: 5 * n * log2(n) floating ops per transform.
        val flops = 5.0 * n * 16 * repeats
        return IterationResult(flops / (nanos / NANOS) / 1e6, cs)
    }
}

class Sha256Benchmark : Benchmark {
    override val id = "cpu.sha256"
    override val name = "SHA-256 (pure Kotlin)"
    override val category = Category.CPU_SINGLE
    override val unit = "MB/s"
    override val expectedChecksum = Expected.SHA256

    private val sizeMb = 16
    private lateinit var data: ByteArray
    private val hasher = Sha256()

    override fun setUp() {
        val rng = SplitMix64(44L)
        data = ByteArray(sizeMb shl 20)
        var i = 0
        while (i < data.size) {
            val v = rng.nextLong()
            for (s in 0 until 8) data[i + s] = (v ushr (s * 8)).toByte()
            i += 8
        }
    }

    override fun runIteration(): IterationResult {
        val t0 = System.nanoTime()
        hasher.reset()
        var off = 0
        while (off < data.size) {
            val chunk = minOf(1 shl 16, data.size - off)
            hasher.update(data, off, chunk)
            off += chunk
        }
        val digest = hasher.digest()
        val nanos = System.nanoTime() - t0
        var cs = 0L
        for (i in 0 until 8) cs = (cs shl 8) or (digest[i].toLong() and 0xff)
        val mb = data.size / (1024.0 * 1024.0)
        return IterationResult(mb / (nanos / NANOS), cs)
    }
}

class SortBenchmark : Benchmark {
    override val id = "cpu.sort"
    override val name = "Integer sort (2M)"
    override val category = Category.CPU_SINGLE
    override val unit = "Melem/s"
    override val expectedChecksum = Expected.SORT

    private val n = 2_000_000
    private val repeats = 4
    private lateinit var src: IntArray
    private lateinit var work: IntArray

    override fun setUp() {
        src = CpuWorkloads.makeIntData(55L, n)
        work = IntArray(n)
    }

    override fun runIteration(): IterationResult {
        val (cs, nanos) = repeatVerified(repeats) { CpuWorkloads.sortChecksum(src, work) }
        return IterationResult(n.toDouble() * repeats / (nanos / NANOS) / 1e6, cs)
    }
}

class DeflateBenchmark : Benchmark {
    override val id = "cpu.deflate"
    override val name = "Compression round-trip"
    override val category = Category.CPU_SINGLE
    override val unit = "MB/s"
    override val expectedChecksum = Expected.DEFLATE

    private val sizeMb = 8
    private lateinit var input: ByteArray
    private lateinit var compressed: ByteArray
    private lateinit var restored: ByteArray

    override fun setUp() {
        input = CpuWorkloads.makeCompressible(66L, sizeMb shl 20)
        compressed = ByteArray(input.size + (input.size shr 2) + 64)
        restored = ByteArray(input.size)
    }

    override fun runIteration(): IterationResult {
        val t0 = System.nanoTime()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        deflater.setInput(input)
        deflater.finish()
        var compLen = 0
        while (!deflater.finished()) {
            compLen += deflater.deflate(compressed, compLen, compressed.size - compLen)
        }
        deflater.end()

        val inflater = Inflater()
        inflater.setInput(compressed, 0, compLen)
        var restLen = 0
        while (!inflater.finished() && restLen < restored.size) {
            restLen += inflater.inflate(restored, restLen, restored.size - restLen)
        }
        inflater.end()
        val nanos = System.nanoTime() - t0

        // The checksum verifies the round-trip restored the original bytes —
        // this is device-independent even though compressed size is not.
        val cs = if (restLen == input.size) CpuWorkloads.foldBytes(restored) else -1L
        val mb = input.size / (1024.0 * 1024.0)
        return IterationResult(mb / (nanos / NANOS), cs)
    }
}
