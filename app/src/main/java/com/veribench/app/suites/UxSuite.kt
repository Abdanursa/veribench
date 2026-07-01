package com.veribench.app.suites

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.veribench.app.core.Benchmark
import com.veribench.app.core.Category
import com.veribench.app.core.IterationResult
import org.json.JSONArray
import java.io.ByteArrayOutputStream

/**
 * Pure kernels for the app/UX category. Android-free (JVM-testable) except
 * for the Bitmap wrapper below.
 */
object UxWorkloads {

    // ------------------------------------------------------------------ json

    /** Deterministic ~2 MiB JSON document of user-like records. */
    fun makeJsonDocument(seed: Long, records: Int): String {
        val rng = SplitMix64(seed)
        val sb = StringBuilder(records * 128)
        sb.append('[')
        for (r in 0 until records) {
            if (r > 0) sb.append(',')
            sb.append("{\"id\":").append(r)
            sb.append(",\"score\":").append(rng.nextInt(100000))
            sb.append(",\"active\":").append(rng.nextInt(2) == 1)
            sb.append(",\"name\":\"")
            val nameLen = 8 + rng.nextInt(12)
            for (c in 0 until nameLen) sb.append(('a' + rng.nextInt(26)))
            sb.append("\",\"tags\":[")
            val tags = 1 + rng.nextInt(4)
            for (t in 0 until tags) {
                if (t > 0) sb.append(',')
                sb.append("\"tag").append(rng.nextInt(64)).append('"')
            }
            sb.append("]}")
        }
        sb.append(']')
        return sb.toString()
    }

    /**
     * Schema-driven traversal (fields accessed by name, never by iteration
     * order), so the checksum is identical across org.json implementations.
     */
    fun jsonTraversalChecksum(root: JSONArray): Long {
        var acc = root.length().toLong()
        for (i in 0 until root.length()) {
            val obj = root.getJSONObject(i)
            var v = obj.getLong("id") * 31 + obj.getLong("score")
            if (obj.getBoolean("active")) v += 7
            v += obj.getString("name").length
            val tags = obj.getJSONArray("tags")
            v += tags.length() * 13
            for (t in 0 until tags.length()) v += tags.getString(t).length
            acc = foldChecksum(acc, v)
        }
        return acc
    }

    // ---------------------------------------------------------------- bitmap

    /** Deterministic opaque ARGB pixels (alpha 255 → premultiplication-lossless). */
    fun makeBitmapPixels(seed: Long, width: Int, height: Int): IntArray {
        val rng = SplitMix64(seed)
        return IntArray(width * height) { (0xFF shl 24) or (rng.nextLong().toInt() and 0x00FFFFFF) }
    }

    fun foldPixels(pixels: IntArray): Long {
        var acc = pixels.size.toLong()
        var i = 0
        while (i < pixels.size) {
            acc = foldChecksum(acc, pixels[i].toLong())
            i += 251
        }
        return acc
    }

    // ----------------------------------------------------------- collections

    /** HashMap-heavy kernel: 500k inserts, 1M lookups (half misses). */
    fun collectionsChecksum(seed: Long, entries: Int): Long {
        val rng = SplitMix64(seed)
        val keys = LongArray(entries) { rng.nextLong() }
        val map = HashMap<Long, Long>(entries * 2)
        for (k in keys) map[k] = k * 31 + 7

        var found = 0L
        var sum = 0L
        val probe = SplitMix64(seed + 1)
        for (i in 0 until entries * 2) {
            // Even probes hit (reuse a real key), odd probes are random misses.
            val key = if (i % 2 == 0) keys[probe.nextInt(entries)] else probe.nextLong()
            val v = map[key]
            if (v != null) {
                found++
                sum += v
            }
        }
        return foldChecksum(foldChecksum(map.size.toLong(), found), sum)
    }
}

class JsonParseBenchmark : Benchmark {
    override val id = "ux.json"
    override val name = "JSON parse (2 MiB)"
    override val category = Category.UX
    override val unit = "MB/s"
    override val expectedChecksum = Expected.JSON

    private val records = 18_000
    private val repeats = 3
    private lateinit var document: String

    override fun setUp() {
        document = UxWorkloads.makeJsonDocument(1234L, records)
    }

    override fun runIteration(): IterationResult {
        val (cs, nanos) = repeatVerified(repeats) {
            UxWorkloads.jsonTraversalChecksum(JSONArray(document))
        }
        val mb = document.length.toDouble() * repeats / (1024.0 * 1024.0)
        return IterationResult(mb / (nanos / 1e9), cs)
    }
}

class BitmapDecodeBenchmark : Benchmark {
    override val id = "ux.bitmap"
    override val name = "PNG decode (1024²)"
    override val category = Category.UX
    override val unit = "Mpix/s"
    override val expectedChecksum = Expected.BITMAP

    private val size = 1024
    private val repeats = 4
    private lateinit var png: ByteArray
    private lateinit var pixelBuf: IntArray

    override fun setUp() {
        val pixels = UxWorkloads.makeBitmapPixels(5678L, size, size)
        val bmp = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream(size * size)
        // PNG is lossless, so the decoded pixels must equal the generated
        // pattern exactly — the checksum is pinned on the JVM from the
        // generator alone, no Android needed.
        check(bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG encode failed" }
        bmp.recycle()
        png = out.toByteArray()
        pixelBuf = IntArray(size * size)
    }

    override fun runIteration(): IterationResult {
        val (cs, nanos) = repeatVerified(repeats) {
            val bmp = BitmapFactory.decodeByteArray(png, 0, png.size)
                ?: return@repeatVerified -1L
            bmp.getPixels(pixelBuf, 0, size, 0, 0, size, size)
            bmp.recycle()
            UxWorkloads.foldPixels(pixelBuf)
        }
        val mpix = size.toDouble() * size * repeats / 1e6
        return IterationResult(mpix / (nanos / 1e9), cs)
    }
}

class CollectionsBenchmark : Benchmark {
    override val id = "ux.collections"
    override val name = "HashMap workload"
    override val category = Category.UX
    override val unit = "Mops/s"
    override val expectedChecksum = Expected.COLLECTIONS

    private val entries = 500_000

    override fun runIteration(): IterationResult {
        val t0 = System.nanoTime()
        val cs = UxWorkloads.collectionsChecksum(9999L, entries)
        val nanos = System.nanoTime() - t0
        // Ops: inserts + lookups.
        val ops = entries.toDouble() * 3
        return IterationResult(ops / (nanos / 1e9) / 1e6, cs)
    }
}
