package com.veribench.app.suites

/**
 * Pure CPU workload kernels. No Android types — these run identically on the
 * JVM, which is how the unit tests pin their checksums (see
 * ChecksumRegressionTest). Determinism notes:
 *  - Integer arithmetic is exactly specified by the JLS.
 *  - Double arithmetic (+, -, *, /) is strict IEEE 754 on all Java/ART
 *    runtimes; no fast-math, no auto-FMA. Transcendentals are NOT relied on —
 *    the FFT computes its own twiddle factors with a Taylor series so every
 *    device evaluates the exact same sequence of basic FP operations.
 */
object CpuWorkloads {

    // ---------------------------------------------------------------- sieve

    /** Sieve of Eratosthenes up to n. Returns fold(primeCount, largestPrime). */
    fun sieveChecksum(n: Int): Long {
        val composite = BooleanArray(n + 1)
        var count = 0
        var largest = 0
        var i = 2
        while (i <= n) {
            if (!composite[i]) {
                count++
                largest = i
                var j = i.toLong() * i
                while (j <= n) {
                    composite[j.toInt()] = true
                    j += i
                }
            }
            i++
        }
        return foldChecksum(count.toLong(), largest.toLong())
    }

    // --------------------------------------------------------------- matmul

    /**
     * Matrix values are multiples of 1/16 in [-1, 1). Every product is a
     * multiple of 1/256 and every dot product stays far below 2^53, so all
     * sums are exact in double and the checksum is an exact integer.
     */
    fun makeMatrix(seed: Long, n: Int): DoubleArray {
        val rng = SplitMix64(seed)
        return DoubleArray(n * n) { (rng.nextInt(32) - 16) / 16.0 }
    }

    /** c = a·b (n×n, ikj order). Returns the exact integer 256·Σc. */
    fun matmul(a: DoubleArray, b: DoubleArray, c: DoubleArray, n: Int): Long {
        for (i in 0 until n) {
            val rowBase = i * n
            java.util.Arrays.fill(c, rowBase, rowBase + n, 0.0)
            for (k in 0 until n) {
                val aik = a[rowBase + k]
                val kBase = k * n
                for (j in 0 until n) {
                    c[rowBase + j] += aik * b[kBase + j]
                }
            }
        }
        var sum = 0.0
        for (v in c) sum += v
        return (sum * 256.0).toLong()
    }

    // ------------------------------------------------------------------ fft

    private const val PI = 3.141592653589793

    /** Taylor-series sine for |x| <= PI. Basic FP ops only => deterministic. */
    private fun sinT(xIn: Double): Double {
        var x = xIn
        // Reduce to [-PI/2, PI/2] using sin(PI - x) = sin(x).
        if (x > PI / 2) x = PI - x
        if (x < -PI / 2) x = -PI - x
        val x2 = x * x
        var term = x
        var sum = x
        var k = 1
        while (k <= 9) {
            term = -term * x2 / ((2 * k) * (2 * k + 1)).toDouble()
            sum += term
            k++
        }
        return sum
    }

    private fun cosT(x: Double): Double {
        val shifted = x + PI / 2
        // cos(x) = sin(x + PI/2); reduce into [-PI, PI].
        return sinT(if (shifted > PI) shifted - 2 * PI else shifted)
    }

    fun makeSignal(seed: Long, n: Int): Pair<DoubleArray, DoubleArray> {
        val rng = SplitMix64(seed)
        val re = DoubleArray(n) { (rng.nextInt(32) - 16) / 16.0 }
        val im = DoubleArray(n) { (rng.nextInt(32) - 16) / 16.0 }
        return Pair(re, im)
    }

    /** In-place iterative radix-2 Cooley-Tukey FFT. n must be a power of two. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        // Bit-reversal permutation.
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
            var m = n shr 1
            while (m in 1..j) {
                j -= m
                m = m shr 1
            }
            j += m
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cosT(ang)
            val wIm = sinT(ang)
            var base = 0
            while (base < n) {
                var curRe = 1.0
                var curIm = 0.0
                val half = len shr 1
                for (k in 0 until half) {
                    val i1 = base + k
                    val i2 = i1 + half
                    val vRe = re[i2] * curRe - im[i2] * curIm
                    val vIm = re[i2] * curIm + im[i2] * curRe
                    re[i2] = re[i1] - vRe
                    im[i2] = im[i1] - vIm
                    re[i1] += vRe
                    im[i1] += vIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                base += len
            }
            len = len shl 1
        }
    }

    /**
     * Runs an FFT over a fresh copy of the signal and folds the raw bits of
     * the output sum. Deterministic because the whole pipeline is basic FP
     * arithmetic evaluated in a fixed order.
     */
    fun fftChecksum(srcRe: DoubleArray, srcIm: DoubleArray, workRe: DoubleArray, workIm: DoubleArray): Long {
        srcRe.copyInto(workRe)
        srcIm.copyInto(workIm)
        fft(workRe, workIm)
        var sumRe = 0.0
        var sumIm = 0.0
        for (i in workRe.indices) {
            sumRe += workRe[i]
            sumIm += workIm[i]
        }
        return foldChecksum(
            java.lang.Double.doubleToRawLongBits(sumRe),
            java.lang.Double.doubleToRawLongBits(sumIm),
        )
    }

    // ----------------------------------------------------------------- sort

    fun makeIntData(seed: Long, n: Int): IntArray {
        val rng = SplitMix64(seed)
        return IntArray(n) { rng.nextLong().toInt() }
    }

    /**
     * Sorts a copy of the data. The sorted array is the unique ordered
     * permutation of the multiset, so the checksum is algorithm-independent.
     * Sampled fold + explicit sortedness check.
     */
    fun sortChecksum(src: IntArray, work: IntArray): Long {
        src.copyInto(work)
        java.util.Arrays.sort(work)
        var acc = 0L
        var sorted = true
        var i = 0
        while (i < work.size) {
            acc = foldChecksum(acc, work[i].toLong())
            if (i > 0 && work[i] < work[i - 1]) sorted = false
            i += 997 // prime stride so sampling can't align with block patterns
        }
        acc = foldChecksum(acc, work[work.size - 1].toLong())
        return if (sorted) acc else foldChecksum(acc, -1L)
    }

    // ------------------------------------------------------------- checksum

    /** Folds a byte array (sampled with a prime stride for large inputs). */
    fun foldBytes(data: ByteArray): Long {
        var acc = data.size.toLong()
        var i = 0
        val stride = if (data.size > 1 shl 16) 251 else 1
        while (i < data.size) {
            acc = foldChecksum(acc, data[i].toLong())
            i += stride
        }
        return acc
    }

    /**
     * Realistically compressible input: 256-byte random templates repeated
     * with point mutations, similar to text/serialized-data entropy.
     */
    fun makeCompressible(seed: Long, size: Int): ByteArray {
        val rng = SplitMix64(seed)
        val template = ByteArray(256) { rng.nextLong().toByte() }
        val out = ByteArray(size)
        var i = 0
        while (i < size) {
            val run = minOf(256, size - i)
            template.copyInto(out, i, 0, run)
            // Mutate a few bytes so the stream isn't trivially one repeat.
            out[i + rng.nextInt(run)] = rng.nextLong().toByte()
            i += run
        }
        return out
    }
}
