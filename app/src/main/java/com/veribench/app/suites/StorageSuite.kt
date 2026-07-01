package com.veribench.app.suites

import com.veribench.app.core.Benchmark
import com.veribench.app.core.Category
import com.veribench.app.core.IterationResult
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Storage tests run against app-private storage (no permissions needed).
 *
 * Honesty notes, also spelled out in docs/SCORING.md:
 *  - Sequential read happens after a fresh write, so part of it may be served
 *    from the page cache; without root the cache cannot be dropped. Every
 *    benchmark without root shares this limit — VeriBench documents it
 *    instead of pretending otherwise.
 *  - Random write opens the file in "rwd" mode, so every 4 KiB write is
 *    committed to flash. This yields much smaller (real) numbers than
 *    cache-backed writes.
 *  - Read tests fold sampled bytes of what was actually read and compare to a
 *    precomputed constant, so a short-read or wrong-data path can't score.
 */
object StorageWorkloads {

    const val PATTERN_SIZE = 4 shl 20            // 4 MiB pattern
    const val FILE_PATTERN_REPEATS = 16          // 64 MiB file
    const val FILE_SIZE = PATTERN_SIZE.toLong() * FILE_PATTERN_REPEATS
    const val BLOCK = 4096

    fun makePattern(seed: Long): ByteArray {
        val rng = SplitMix64(seed)
        val out = ByteArray(PATTERN_SIZE)
        var i = 0
        while (i < out.size) {
            val v = rng.nextLong()
            for (s in 0 until 8) out[i + s] = (v ushr (s * 8)).toByte()
            i += 8
        }
        return out
    }

    /** Sparse fold over a chunk: cheap enough to never pollute I/O timing. */
    fun foldChunkSampled(accIn: Long, buf: ByteArray, len: Int, stride: Int): Long {
        var acc = foldChecksum(accIn, len.toLong())
        var i = 0
        while (i < len) {
            acc = foldChecksum(acc, buf[i].toLong())
            i += stride
        }
        return acc
    }

    /** Expected checksum of sequentially reading the whole 64 MiB file. */
    fun expectedSeqRead(pattern: ByteArray): Long {
        var acc = 0L
        repeat(FILE_PATTERN_REPEATS) {
            acc = foldChunkSampled(acc, pattern, pattern.size, 65521)
        }
        return acc
    }

    /** Deterministic offsets used by the random-read test. */
    fun randReadOffsets(count: Int): LongArray {
        val rng = SplitMix64(123L)
        val totalBlocks = (FILE_SIZE / BLOCK).toInt()
        return LongArray(count) { BLOCK.toLong() * rng.nextInt(totalBlocks) }
    }

    /** Expected checksum of the random-read test, simulated over the pattern. */
    fun expectedRandRead(pattern: ByteArray, count: Int): Long {
        val block = ByteArray(BLOCK)
        var acc = 0L
        for (offset in randReadOffsets(count)) {
            val patOff = (offset % PATTERN_SIZE).toInt()
            pattern.copyInto(block, 0, patOff, patOff + BLOCK)
            acc = foldChunkSampled(acc, block, BLOCK, 1021)
        }
        return acc
    }

    /** Writes the standard 64 MiB pattern file (used untimed in setUp). */
    fun writePatternFile(file: File, pattern: ByteArray) {
        FileOutputStream(file).use { out ->
            repeat(FILE_PATTERN_REPEATS) { out.write(pattern) }
            out.fd.sync()
        }
    }
}

private const val PATTERN_SEED = 424242L

class SequentialWriteBenchmark(private val dir: File) : Benchmark {
    override val id = "io.seqwrite"
    override val name = "Sequential write (64 MiB)"
    override val category = Category.STORAGE
    override val unit = "MB/s"
    override val measuredIterations = 3
    override val warmupIterations = 1
    override val expectedChecksum = Expected.STORAGE_DATA

    private lateinit var pattern: ByteArray
    private lateinit var file: File

    override fun setUp() {
        pattern = StorageWorkloads.makePattern(PATTERN_SEED)
        file = File(dir, "veribench_seqwrite.bin")
    }

    override fun runIteration(): IterationResult {
        val t0 = System.nanoTime()
        StorageWorkloads.writePatternFile(file, pattern)
        val nanos = System.nanoTime() - t0
        val mb = StorageWorkloads.FILE_SIZE / (1024.0 * 1024.0)
        // Write checksum pins the source data; content is verified by the
        // read benchmarks, which read files produced by this same writer.
        val cs = StorageWorkloads.foldChunkSampled(0L, pattern, pattern.size, 65521)
        return IterationResult(mb / (nanos / 1e9), cs)
    }

    override fun tearDown() {
        file.delete()
    }
}

class SequentialReadBenchmark(private val dir: File) : Benchmark {
    override val id = "io.seqread"
    override val name = "Sequential read (64 MiB)"
    override val category = Category.STORAGE
    override val unit = "MB/s"
    override val measuredIterations = 3
    override val warmupIterations = 1
    override val expectedChecksum = Expected.STORAGE_SEQREAD

    private lateinit var file: File

    override fun setUp() {
        file = File(dir, "veribench_seqread.bin")
        StorageWorkloads.writePatternFile(file, StorageWorkloads.makePattern(PATTERN_SEED))
    }

    override fun runIteration(): IterationResult {
        val buf = ByteArray(StorageWorkloads.PATTERN_SIZE)
        var acc = 0L
        val t0 = System.nanoTime()
        file.inputStream().use { input ->
            while (true) {
                var filled = 0
                while (filled < buf.size) {
                    val n = input.read(buf, filled, buf.size - filled)
                    if (n < 0) break
                    filled += n
                }
                if (filled == 0) break
                acc = StorageWorkloads.foldChunkSampled(acc, buf, filled, 65521)
                if (filled < buf.size) break
            }
        }
        val nanos = System.nanoTime() - t0
        val mb = StorageWorkloads.FILE_SIZE / (1024.0 * 1024.0)
        return IterationResult(mb / (nanos / 1e9), acc)
    }

    override fun tearDown() {
        file.delete()
    }
}

class RandomReadBenchmark(private val dir: File) : Benchmark {
    override val id = "io.randread"
    override val name = "Random read (4 KiB)"
    override val category = Category.STORAGE
    override val unit = "MB/s"
    override val measuredIterations = 3
    override val warmupIterations = 1
    override val expectedChecksum = Expected.STORAGE_RANDREAD

    private val count = 4096
    private lateinit var file: File
    private lateinit var offsets: LongArray

    override fun setUp() {
        file = File(dir, "veribench_randread.bin")
        StorageWorkloads.writePatternFile(file, StorageWorkloads.makePattern(PATTERN_SEED))
        offsets = StorageWorkloads.randReadOffsets(count)
    }

    override fun runIteration(): IterationResult {
        val block = ByteArray(StorageWorkloads.BLOCK)
        var acc = 0L
        val t0 = System.nanoTime()
        RandomAccessFile(file, "r").use { raf ->
            for (offset in offsets) {
                raf.seek(offset)
                raf.readFully(block)
                acc = StorageWorkloads.foldChunkSampled(acc, block, block.size, 1021)
            }
        }
        val nanos = System.nanoTime() - t0
        val mb = count.toDouble() * StorageWorkloads.BLOCK / (1024.0 * 1024.0)
        return IterationResult(mb / (nanos / 1e9), acc)
    }

    override fun tearDown() {
        file.delete()
    }
}

class RandomWriteBenchmark(private val dir: File) : Benchmark {
    override val id = "io.randwrite"
    override val name = "Random write (4 KiB, synced)"
    override val category = Category.STORAGE
    override val unit = "MB/s"
    override val measuredIterations = 3
    override val warmupIterations = 1
    override val expectedChecksum = Expected.STORAGE_DATA

    private val count = 512
    private val fileSize = 32L shl 20
    private lateinit var file: File
    private lateinit var pattern: ByteArray
    private lateinit var offsets: LongArray

    override fun setUp() {
        pattern = StorageWorkloads.makePattern(PATTERN_SEED)
        file = File(dir, "veribench_randwrite.bin")
        // Pre-allocate so writes are in-place updates, not appends.
        RandomAccessFile(file, "rw").use { it.setLength(fileSize) }
        val rng = SplitMix64(321L)
        val totalBlocks = (fileSize / StorageWorkloads.BLOCK).toInt()
        offsets = LongArray(count) { StorageWorkloads.BLOCK.toLong() * rng.nextInt(totalBlocks) }
    }

    override fun runIteration(): IterationResult {
        val block = ByteArray(StorageWorkloads.BLOCK)
        pattern.copyInto(block, 0, 0, StorageWorkloads.BLOCK)
        val t0 = System.nanoTime()
        // "rwd" commits each write's data to flash — measures real durable
        // random-write throughput, not the page cache.
        RandomAccessFile(file, "rwd").use { raf ->
            for (offset in offsets) {
                raf.seek(offset)
                raf.write(block)
            }
        }
        val nanos = System.nanoTime() - t0
        val mb = count.toDouble() * StorageWorkloads.BLOCK / (1024.0 * 1024.0)
        val cs = StorageWorkloads.foldChunkSampled(0L, pattern, pattern.size, 65521)
        return IterationResult(mb / (nanos / 1e9), cs)
    }

    override fun tearDown() {
        file.delete()
    }
}
