package com.veribench.app.core

/**
 * Contract every VeriBench workload implements.
 *
 * Design rules that make results trustworthy:
 *  - Workloads are deterministic: same inputs on every device, every run.
 *  - Every iteration returns a checksum of its work product. The runner
 *    compares it against [expectedChecksum]; a mismatch invalidates the test.
 *    This guarantees the measured code actually executed and was not
 *    dead-code-eliminated, skipped, or short-circuited.
 *  - Warm-up iterations run first (untimed for scoring) so ART's JIT reaches
 *    steady state before measurement, instead of averaging cold and hot runs.
 */
interface Benchmark {
    /** Stable identifier, also the key into the scoring baseline table. */
    val id: String
    val name: String
    val category: Category

    /** Human-readable throughput unit (e.g. "MB/s", "Mops/s"). */
    val unit: String

    /** Iterations run before measurement so the JIT settles. */
    val warmupIterations: Int get() = 2

    /** Measured iterations; the median is what gets scored. */
    val measuredIterations: Int get() = 5

    /**
     * Checksum every iteration must reproduce. A workload whose output cannot
     * be verified has no place in a benchmark that claims to be reliable.
     */
    val expectedChecksum: Long

    /** One-time untimed preparation (allocate buffers, create files). */
    fun setUp() {}

    /** Runs one full iteration and reports throughput (higher = better). */
    fun runIteration(): IterationResult

    /** One-time cleanup (delete temp files, release buffers). */
    fun tearDown() {}
}

data class IterationResult(
    /** Throughput in [Benchmark.unit]; higher is better. */
    val throughput: Double,
    /** Checksum of the iteration's work product. */
    val checksum: Long,
)

/**
 * Benchmark categories with their weight in the total score.
 * Weights are part of the public scoring contract (see docs/SCORING.md)
 * and only change with a scoring version bump.
 */
enum class Category(val displayName: String, val weight: Double) {
    CPU_SINGLE("CPU · single-core", 0.25),
    CPU_MULTI("CPU · multi-core", 0.25),
    MEMORY("Memory", 0.15),
    STORAGE("Storage", 0.15),
    GPU("GPU", 0.10),
    UX("App & UX", 0.10),
}
