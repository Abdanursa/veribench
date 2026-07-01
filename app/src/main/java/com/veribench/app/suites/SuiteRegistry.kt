package com.veribench.app.suites

import com.veribench.app.core.Benchmark
import java.io.File

/**
 * The full, ordered benchmark roster. The order interleaves heavy and light
 * categories where possible to spread thermal load, but is fixed — every run
 * on every device executes the identical sequence.
 */
object SuiteRegistry {
    fun all(storageDir: File): List<Benchmark> = listOf(
        // CPU single-core
        SieveBenchmark(),
        MatMulBenchmark(),
        FftBenchmark(),
        Sha256Benchmark(),
        SortBenchmark(),
        DeflateBenchmark(),
        // Memory (light thermal load after CPU block)
        SequentialCopyBenchmark(),
        RandomLatencyBenchmark(),
        TriadBenchmark(),
        // Storage (I/O-bound, lets cores cool)
        SequentialWriteBenchmark(storageDir),
        SequentialReadBenchmark(storageDir),
        RandomReadBenchmark(storageDir),
        RandomWriteBenchmark(storageDir),
        // App & UX
        JsonParseBenchmark(),
        BitmapDecodeBenchmark(),
        CollectionsBenchmark(),
        // GPU
        GpuShaderBenchmark(),
        // Multi-core last — the hottest block, can't skew earlier tests
        MultiSieveBenchmark(),
        MultiMatMulBenchmark(),
        MultiSha256Benchmark(),
    )
}
