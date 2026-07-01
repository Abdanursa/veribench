package com.veribench.app.core

import android.os.SystemClock
import com.veribench.app.monitor.EnvironmentGuard
import com.veribench.app.monitor.EnvironmentSample

/**
 * Executes a list of benchmarks sequentially and assembles a [RunReport].
 *
 * Reliability mechanics, in order of execution per test:
 *  1. [Benchmark.setUp] (untimed).
 *  2. Warm-up iterations — results discarded, JIT reaches steady state.
 *  3. Measured iterations — throughput recorded, checksum verified each time.
 *  4. Environment sampled (thermal status, CPU frequency, battery) so the
 *     report can prove under which conditions each number was produced.
 *
 * A crashing or non-verifying test is reported as skipped/invalid instead of
 * aborting the run or, worse, being silently averaged in.
 */
class BenchmarkRunner(
    private val guard: EnvironmentGuard,
    private val listener: Listener,
) {

    interface Listener {
        fun onTestStarted(index: Int, total: Int, benchmark: Benchmark)
        fun onIterationDone(benchmark: Benchmark, iteration: Int, totalIterations: Int)
    }

    fun run(benchmarks: List<Benchmark>, deviceInfo: Map<String, String>, appVersion: String): RunReport {
        val startedAt = System.currentTimeMillis()
        val startElapsed = SystemClock.elapsedRealtime()
        val samples = ArrayList<EnvironmentSample>()
        val results = ArrayList<TestResult>()

        samples += guard.sample()

        benchmarks.forEachIndexed { index, bench ->
            listener.onTestStarted(index, benchmarks.size, bench)
            results += runSingle(bench)
            samples += guard.sample()
        }

        val (total, categories) = ScoringEngine.score(results)
        val (validity, reasons) = judgeValidity(results, samples)

        return RunReport(
            scoringVersion = ScoringEngine.SCORING_VERSION,
            appVersion = appVersion,
            totalScore = total,
            categories = categories,
            validity = validity,
            validityReasons = reasons,
            environmentSamples = samples,
            deviceInfo = deviceInfo,
            startedAtMs = startedAt,
            durationMs = SystemClock.elapsedRealtime() - startElapsed,
        )
    }

    private fun runSingle(bench: Benchmark): TestResult {
        val totalIters = bench.warmupIterations + bench.measuredIterations
        try {
            bench.setUp()
            try {
                var checksumOk = true

                repeat(bench.warmupIterations) { i ->
                    val r = bench.runIteration()
                    if (r.checksum != bench.expectedChecksum) checksumOk = false
                    listener.onIterationDone(bench, i + 1, totalIters)
                }

                val throughputs = ArrayList<Double>(bench.measuredIterations)
                repeat(bench.measuredIterations) { i ->
                    val r = bench.runIteration()
                    if (r.checksum != bench.expectedChecksum) checksumOk = false
                    throughputs += r.throughput
                    listener.onIterationDone(bench, bench.warmupIterations + i + 1, totalIters)
                }

                return TestResult(
                    benchmarkId = bench.id,
                    name = bench.name,
                    category = bench.category,
                    unit = bench.unit,
                    iterationThroughputs = throughputs,
                    medianThroughput = Stats.median(throughputs),
                    coefficientOfVariation = Stats.coefficientOfVariation(throughputs),
                    checksumOk = checksumOk,
                )
            } finally {
                bench.tearDown()
            }
        } catch (t: Throwable) {
            return TestResult(
                benchmarkId = bench.id,
                name = bench.name,
                category = bench.category,
                unit = bench.unit,
                iterationThroughputs = emptyList(),
                medianThroughput = 0.0,
                coefficientOfVariation = 0.0,
                checksumOk = false,
                skipped = true,
                skipReason = "${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }

    private fun judgeValidity(
        results: List<TestResult>,
        samples: List<EnvironmentSample>,
    ): Pair<Validity, List<String>> {
        val reasons = ArrayList<String>()

        // INVALID: a workload ran but produced wrong output — the measurement
        // itself cannot be trusted (broken hardware, hostile ROM, cosmic ray).
        results.filter { !it.skipped && !it.checksumOk }.forEach {
            reasons += "Checksum mismatch in '${it.name}' — output verification failed"
        }
        if (reasons.isNotEmpty()) return Pair(Validity.INVALID, reasons)

        // DEGRADED: numbers are real but conditions were not ideal, so this
        // run may undersell (or oversell) the device. Reported, never hidden.
        if (samples.any { (it.thermalStatus ?: 0) >= EnvironmentSample.THERMAL_MODERATE }) {
            reasons += "OS reported thermal throttling during the run"
        }
        val hotBattery = samples.mapNotNull { it.batteryTempC }.any { it > 40f }
        if (hotBattery) reasons += "Battery temperature exceeded 40 °C during the run"
        if (samples.any { it.isCharging }) {
            reasons += "Device was charging (adds heat, may alter power management)"
        }
        val freqRatios = samples.mapNotNull { it.cpuFreqRatio }
        if (freqRatios.any { it < 0.6 }) {
            reasons += "CPU clocks dropped below 60% of maximum during the run"
        }
        results.filter { !it.skipped && it.coefficientOfVariation > 0.15 }.forEach {
            reasons += "High variance in '${it.name}' (CV ${"%.0f".format(it.coefficientOfVariation * 100)}%)"
        }
        results.filter { it.skipped }.forEach {
            reasons += "'${it.name}' skipped: ${it.skipReason}"
        }

        return if (reasons.isEmpty()) Pair(Validity.VALID, emptyList())
        else Pair(Validity.DEGRADED, reasons)
    }
}
