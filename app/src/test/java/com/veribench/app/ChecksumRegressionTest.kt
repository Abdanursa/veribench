package com.veribench.app

import com.veribench.app.suites.CpuWorkloads
import com.veribench.app.suites.Expected
import com.veribench.app.suites.MemoryWorkloads
import com.veribench.app.suites.Sha256
import com.veribench.app.suites.SplitMix64
import com.veribench.app.suites.StorageWorkloads
import com.veribench.app.suites.UxWorkloads
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins every workload checksum at the EXACT sizes and seeds the app uses.
 * These kernels are Android-free and rely only on exactly-specified
 * arithmetic, so a value computed here on the JVM is the value every ART
 * device must reproduce.
 *
 * If any of these fail after a change, either revert the workload or bump
 * ScoringEngine.SCORING_VERSION and recalibrate — silently changing a
 * workload while keeping scores comparable is exactly the AnTuTu failure
 * mode this project exists to avoid.
 */
class ChecksumRegressionTest {

    @Test
    fun sieve() {
        assertEquals(Expected.SIEVE, CpuWorkloads.sieveChecksum(4_000_000))
    }

    @Test
    fun matmul() {
        val n = 256
        val a = CpuWorkloads.makeMatrix(11L, n)
        val b = CpuWorkloads.makeMatrix(22L, n)
        val c = DoubleArray(n * n)
        assertEquals(Expected.MATMUL, CpuWorkloads.matmul(a, b, c, n))
    }

    @Test
    fun fft() {
        val n = 65536
        val (re, im) = CpuWorkloads.makeSignal(33L, n)
        assertEquals(
            Expected.FFT,
            CpuWorkloads.fftChecksum(re, im, DoubleArray(n), DoubleArray(n)),
        )
    }

    @Test
    fun sha256OfBenchmarkBuffer() {
        val rng = SplitMix64(44L)
        val data = ByteArray(16 shl 20)
        var i = 0
        while (i < data.size) {
            val v = rng.nextLong()
            for (s in 0 until 8) data[i + s] = (v ushr (s * 8)).toByte()
            i += 8
        }
        val hasher = Sha256()
        var off = 0
        while (off < data.size) {
            val chunk = minOf(1 shl 16, data.size - off)
            hasher.update(data, off, chunk)
            off += chunk
        }
        val digest = hasher.digest()
        var cs = 0L
        for (b in 0 until 8) cs = (cs shl 8) or (digest[b].toLong() and 0xff)
        assertEquals(Expected.SHA256, cs)
    }

    @Test
    fun sort() {
        val src = CpuWorkloads.makeIntData(55L, 2_000_000)
        assertEquals(Expected.SORT, CpuWorkloads.sortChecksum(src, IntArray(src.size)))
    }

    @Test
    fun deflateInputFold() {
        // The deflate benchmark's checksum is the fold of the round-tripped
        // (= original) bytes, so the pin is the fold of the generated input.
        assertEquals(Expected.DEFLATE, CpuWorkloads.foldBytes(CpuWorkloads.makeCompressible(66L, 8 shl 20)))
    }

    @Test
    fun memorySequentialCopy() {
        val src = MemoryWorkloads.makeLongData(77L, 8 shl 20)
        assertEquals(Expected.MEM_SEQCOPY, MemoryWorkloads.copyChecksum(src, LongArray(src.size)))
    }

    @Test
    fun memoryRandomLatency() {
        val cycle = MemoryWorkloads.makeCycle(88L, 8 shl 20)
        assertEquals(Expected.MEM_RANDLAT, MemoryWorkloads.chase(cycle, (8 shl 20) - 1237))
    }

    @Test
    fun memoryTriad() {
        val n = 4 shl 20
        val a = DoubleArray(n)
        val b = MemoryWorkloads.makeSmallIntDoubles(99L, n)
        val c = MemoryWorkloads.makeSmallIntDoubles(111L, n)
        assertEquals(Expected.MEM_TRIAD, MemoryWorkloads.triadChecksum(a, b, c))
    }

    @Test
    fun storagePatternFold() {
        val pattern = StorageWorkloads.makePattern(424242L)
        assertEquals(
            Expected.STORAGE_DATA,
            StorageWorkloads.foldChunkSampled(0L, pattern, pattern.size, 65521),
        )
    }

    @Test
    fun storageSequentialRead() {
        val pattern = StorageWorkloads.makePattern(424242L)
        assertEquals(Expected.STORAGE_SEQREAD, StorageWorkloads.expectedSeqRead(pattern))
    }

    @Test
    fun storageRandomRead() {
        val pattern = StorageWorkloads.makePattern(424242L)
        assertEquals(Expected.STORAGE_RANDREAD, StorageWorkloads.expectedRandRead(pattern, 4096))
    }

    @Test
    fun json() {
        val doc = UxWorkloads.makeJsonDocument(1234L, 18_000)
        assertEquals(Expected.JSON, UxWorkloads.jsonTraversalChecksum(JSONArray(doc)))
    }

    @Test
    fun bitmapPixels() {
        assertEquals(
            Expected.BITMAP,
            UxWorkloads.foldPixels(UxWorkloads.makeBitmapPixels(5678L, 1024, 1024)),
        )
    }

    @Test
    fun collections() {
        assertEquals(Expected.COLLECTIONS, UxWorkloads.collectionsChecksum(9999L, 500_000))
    }
}
