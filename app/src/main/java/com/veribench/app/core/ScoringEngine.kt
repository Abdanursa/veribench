package com.veribench.app.core

/**
 * Turns raw throughputs into scores. The entire formula is public:
 *
 *   test score      = 1000 * (median throughput / baseline throughput)
 *   category score  = geometric mean of its test scores
 *   total score     = sum(category weight * category score) / sum(weights present)
 *
 * Baselines are calibration constants representing a hypothetical reference
 * device that scores exactly 1000 on every test. They are versioned: any
 * change to a baseline, a weight, or a workload bumps [SCORING_VERSION],
 * so two scores are only ever compared under the same version. This is the
 * opposite of AnTuTu's approach, where the formula is secret and shifts
 * silently between app versions.
 */
object ScoringEngine {

    const val SCORING_VERSION = "1.0"

    /**
     * Reference throughputs (in each test's own unit) that map to a score of
     * exactly 1000. Calibrated so a 2023 mid-range phone lands near 1000 per
     * category. See docs/SCORING.md for the calibration procedure.
     */
    val BASELINES: Map<String, Double> = mapOf(
        // CPU single-core
        "cpu.sieve" to 120.0,        // Mnumbers/s sieved
        "cpu.matmul" to 900.0,       // MFLOPS
        "cpu.fft" to 220.0,          // MFLOPS
        "cpu.sha256" to 55.0,        // MB/s (pure-Kotlin implementation)
        "cpu.sort" to 9.0,           // Melem/s
        "cpu.deflate" to 28.0,       // MB/s
        // CPU multi-core
        "mc.sieve" to 480.0,         // Mnumbers/s
        "mc.matmul" to 3600.0,       // MFLOPS
        "mc.sha256" to 220.0,        // MB/s
        // Memory
        "mem.seqcopy" to 3500.0,     // MB/s
        "mem.randlat" to 28.0,       // Maccess/s
        "mem.triad" to 1800.0,       // MB/s
        // Storage
        "io.seqwrite" to 250.0,      // MB/s
        "io.seqread" to 900.0,       // MB/s
        "io.randread" to 45.0,       // MB/s (4 KiB blocks)
        "io.randwrite" to 18.0,      // MB/s (4 KiB blocks, synced)
        // GPU
        "gpu.shader" to 900.0,       // Mpixel/s of heavy fragment work
        // App & UX
        "ux.json" to 42.0,           // MB/s parsed
        "ux.bitmap" to 55.0,         // Mpixel/s decoded
        "ux.collections" to 7.5,     // Mops/s
    )

    fun score(results: List<TestResult>): Pair<Long, List<CategoryScore>> {
        val scored = results.map { r ->
            val baseline = BASELINES[r.benchmarkId]
            val score = when {
                r.skipped || !r.checksumOk || baseline == null -> 0.0
                else -> 1000.0 * (r.medianThroughput / baseline)
            }
            ScoredTest(r, score)
        }

        val categories = Category.entries.mapNotNull { cat ->
            val inCat = scored.filter { it.result.category == cat }
            if (inCat.isEmpty()) return@mapNotNull null
            val valid = inCat.filter { it.score > 0.0 }
            // A category with any skipped/invalid test still reports the tests,
            // but its score comes only from tests that actually ran and verified.
            val catScore =
                if (valid.isEmpty()) 0.0 else Stats.geometricMean(valid.map { it.score })
            val worstCv = valid.maxOfOrNull { it.result.coefficientOfVariation } ?: 0.0
            CategoryScore(cat, catScore, worstCv, inCat)
        }

        // Categories that produced no score (e.g. GPU unavailable on an
        // emulator) are excluded and the remaining weights renormalized,
        // rather than silently counting them as zero.
        val present = categories.filter { it.score > 0.0 }
        val weightSum = present.sumOf { it.category.weight }
        val total =
            if (weightSum == 0.0) 0.0
            else present.sumOf { it.category.weight * it.score } / weightSum

        // Total is scaled so "reference device everywhere" = 10 000 points,
        // giving a familiar magnitude without a hidden formula behind it.
        return Pair(Math.round(total * 10.0), categories)
    }
}
