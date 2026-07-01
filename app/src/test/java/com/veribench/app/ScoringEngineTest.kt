package com.veribench.app

import com.veribench.app.core.Category
import com.veribench.app.core.ScoringEngine
import com.veribench.app.core.TestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringEngineTest {

    private fun result(
        id: String,
        category: Category,
        median: Double,
        checksumOk: Boolean = true,
        skipped: Boolean = false,
    ) = TestResult(
        benchmarkId = id,
        name = id,
        category = category,
        unit = "u",
        iterationThroughputs = listOf(median),
        medianThroughput = median,
        coefficientOfVariation = 0.01,
        checksumOk = checksumOk,
        skipped = skipped,
    )

    @Test
    fun baselineThroughputScoresExactly1000() {
        val baseline = ScoringEngine.BASELINES.getValue("cpu.sieve")
        val (_, cats) = ScoringEngine.score(
            listOf(result("cpu.sieve", Category.CPU_SINGLE, baseline)),
        )
        assertEquals(1000.0, cats.single().tests.single().score, 1e-9)
    }

    @Test
    fun doubleThroughputDoublesScore() {
        val baseline = ScoringEngine.BASELINES.getValue("cpu.sieve")
        val (_, cats) = ScoringEngine.score(
            listOf(result("cpu.sieve", Category.CPU_SINGLE, baseline * 2)),
        )
        assertEquals(2000.0, cats.single().tests.single().score, 1e-9)
    }

    @Test
    fun failedChecksumScoresZero() {
        val baseline = ScoringEngine.BASELINES.getValue("cpu.sieve")
        val (total, cats) = ScoringEngine.score(
            listOf(result("cpu.sieve", Category.CPU_SINGLE, baseline, checksumOk = false)),
        )
        assertEquals(0.0, cats.single().tests.single().score, 0.0)
        assertEquals(0L, total)
    }

    @Test
    fun referenceDeviceTotalsExactly10000() {
        // A device hitting every baseline exactly must total 10 000,
        // regardless of category weights.
        val results = ScoringEngine.BASELINES.map { (id, base) ->
            val cat = when (id.substringBefore('.')) {
                "cpu" -> Category.CPU_SINGLE
                "mc" -> Category.CPU_MULTI
                "mem" -> Category.MEMORY
                "io" -> Category.STORAGE
                "gpu" -> Category.GPU
                else -> Category.UX
            }
            result(id, cat, base)
        }
        val (total, _) = ScoringEngine.score(results)
        assertEquals(10_000L, total)
    }

    @Test
    fun missingGpuRenormalizesInsteadOfZeroing() {
        // Same device with GPU tests absent should still total 10 000 —
        // the category is excluded, not counted as zero.
        val results = ScoringEngine.BASELINES.filterKeys { !it.startsWith("gpu") }
            .map { (id, base) ->
                val cat = when (id.substringBefore('.')) {
                    "cpu" -> Category.CPU_SINGLE
                    "mc" -> Category.CPU_MULTI
                    "mem" -> Category.MEMORY
                    "io" -> Category.STORAGE
                    else -> Category.UX
                }
                result(id, cat, base)
            }
        val (total, _) = ScoringEngine.score(results)
        assertEquals(10_000L, total)
    }

    @Test
    fun categoryWeightsSumToOne() {
        assertEquals(1.0, Category.entries.sumOf { it.weight }, 1e-9)
    }

    @Test
    fun everyRegisteredBaselineIsPositive() {
        assertTrue(ScoringEngine.BASELINES.values.all { it > 0.0 })
    }
}
