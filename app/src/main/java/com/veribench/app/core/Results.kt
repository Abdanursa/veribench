package com.veribench.app.core

import com.veribench.app.monitor.EnvironmentSample

/** Outcome of a single benchmark after all iterations. */
data class TestResult(
    val benchmarkId: String,
    val name: String,
    val category: Category,
    val unit: String,
    /** Measured throughputs, one per iteration (warm-up excluded). */
    val iterationThroughputs: List<Double>,
    /** Median throughput — the value that gets scored. */
    val medianThroughput: Double,
    /** Run-to-run noise of this test (stdDev / mean). */
    val coefficientOfVariation: Double,
    /** True iff every iteration reproduced the expected checksum. */
    val checksumOk: Boolean,
    val skipped: Boolean = false,
    val skipReason: String? = null,
)

/** A [TestResult] with its score attached by the scoring engine. */
data class ScoredTest(
    val result: TestResult,
    /** 1000 = reference-device performance for this test. 0 if skipped/invalid. */
    val score: Double,
)

data class CategoryScore(
    val category: Category,
    /** Geometric mean of the category's test scores. */
    val score: Double,
    /** Worst per-test coefficient of variation inside the category. */
    val worstCv: Double,
    val tests: List<ScoredTest>,
)

enum class Validity { VALID, DEGRADED, INVALID }

/** Everything a completed run produced, ready for display or JSON export. */
data class RunReport(
    val scoringVersion: String,
    val appVersion: String,
    val totalScore: Long,
    val categories: List<CategoryScore>,
    val validity: Validity,
    val validityReasons: List<String>,
    val environmentSamples: List<EnvironmentSample>,
    val deviceInfo: Map<String, String>,
    val startedAtMs: Long,
    val durationMs: Long,
)
