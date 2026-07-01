package com.veribench.app.suites

import com.veribench.app.core.Benchmark
import com.veribench.app.core.Category
import com.veribench.app.core.IterationResult

/**
 * Pure memory-subsystem kernels + their benchmark wrappers.
 * Kernels are Android-free so JVM unit tests can pin the checksums.
 */
object MemoryWorkloads {

    fun makeLongData(seed: Long, n: Int): LongArray {
        val rng = SplitMix64(seed)
        return LongArray(n) { rng.nextLong() }
    }

    fun copyChecksum(src: LongArray, dst: LongArray): Long {
        System.arraycopy(src, 0, dst, 0, src.size)
        var acc = 0L
        var i = 0
        while (i < dst.size) {
            acc = foldChecksum(acc, dst[i])
            i += 65521 // prime stride
        }
        return acc
    }

    /**
     * Single random cycle over [0, n) via Sattolo's algorithm. Chasing it
     * defeats hardware prefetchers, so the chase measures memory latency.
     */
    fun makeCycle(seed: Long, n: Int): IntArray {
        val rng = SplitMix64(seed)
        val perm = IntArray(n) { it }
        for (i in n - 1 downTo 1) {
            val j = rng.nextInt(i) // Sattolo: j < i, guarantees one full cycle
            val t = perm[i]; perm[i] = perm[j]; perm[j] = t
        }
        return perm
    }

    fun chase(cycle: IntArray, steps: Int): Long {
        var idx = 0
        for (s in 0 until steps) idx = cycle[idx]
        return idx.toLong()
    }

    /** Values are small integers, so every triad result and sum is exact. */
    fun makeSmallIntDoubles(seed: Long, n: Int): DoubleArray {
        val rng = SplitMix64(seed)
        return DoubleArray(n) { (rng.nextInt(1024) - 512).toDouble() }
    }

    /** STREAM-style triad: a[i] = b[i] + 3·c[i]. Returns exact Σa. */
    fun triadChecksum(a: DoubleArray, b: DoubleArray, c: DoubleArray): Long {
        for (i in a.indices) a[i] = b[i] + 3.0 * c[i]
        var sum = 0.0
        for (v in a) sum += v
        return sum.toLong()
    }
}

class SequentialCopyBenchmark : Benchmark {
    override val id = "mem.seqcopy"
    override val name = "Sequential copy (64 MiB)"
    override val category = Category.MEMORY
    override val unit = "MB/s"
    override val expectedChecksum = Expected.MEM_SEQCOPY

    private val n = 8 shl 20 // 8M longs = 64 MiB
    private val repeats = 5
    private lateinit var src: LongArray
    private lateinit var dst: LongArray

    override fun setUp() {
        src = MemoryWorkloads.makeLongData(77L, n)
        dst = LongArray(n)
    }

    override fun runIteration(): IterationResult {
        val (cs, nanos) = repeatVerified(repeats) { MemoryWorkloads.copyChecksum(src, dst) }
        // Copy traffic: n longs read + written per repeat.
        val mb = 2.0 * n * 8 * repeats / (1024.0 * 1024.0)
        return IterationResult(mb / (nanos / 1e9), cs)
    }
}

class RandomLatencyBenchmark : Benchmark {
    override val id = "mem.randlat"
    override val name = "Random access latency"
    override val category = Category.MEMORY
    override val unit = "Macc/s"
    override val expectedChecksum = Expected.MEM_RANDLAT

    private val n = 8 shl 20   // 8M-entry cycle = 32 MiB, far beyond L3
    // Not a multiple of the cycle length, so the final index is a
    // non-trivial function of the whole permutation (a full lap would
    // trivially end at 0 and verify nothing).
    private val steps = (8 shl 20) - 1237
    private lateinit var cycle: IntArray

    override fun setUp() {
        cycle = MemoryWorkloads.makeCycle(88L, n)
    }

    override fun runIteration(): IterationResult {
        val t0 = System.nanoTime()
        val cs = MemoryWorkloads.chase(cycle, steps)
        val nanos = System.nanoTime() - t0
        return IterationResult(steps / (nanos / 1e9) / 1e6, cs)
    }
}

class TriadBenchmark : Benchmark {
    override val id = "mem.triad"
    override val name = "STREAM triad"
    override val category = Category.MEMORY
    override val unit = "MB/s"
    override val expectedChecksum = Expected.MEM_TRIAD

    private val n = 4 shl 20 // 4M doubles per array
    private val repeats = 6
    private lateinit var a: DoubleArray
    private lateinit var b: DoubleArray
    private lateinit var c: DoubleArray

    override fun setUp() {
        a = DoubleArray(n)
        b = MemoryWorkloads.makeSmallIntDoubles(99L, n)
        c = MemoryWorkloads.makeSmallIntDoubles(111L, n)
    }

    override fun runIteration(): IterationResult {
        val (cs, nanos) = repeatVerified(repeats) { MemoryWorkloads.triadChecksum(a, b, c) }
        // Traffic per repeat: read b, read c, write a.
        val mb = 3.0 * n * 8 * repeats / (1024.0 * 1024.0)
        return IterationResult(mb / (nanos / 1e9), cs)
    }
}
