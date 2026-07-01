package com.veribench.app.core

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Small, dependency-free statistics helpers used by the runner and the
 * scoring engine. Kept pure so they are unit-testable on the JVM.
 */
object Stats {

    fun median(values: List<Double>): Double {
        require(values.isNotEmpty()) { "median of empty list" }
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    fun mean(values: List<Double>): Double {
        require(values.isNotEmpty()) { "mean of empty list" }
        return values.sum() / values.size
    }

    /** Sample standard deviation (n-1 denominator). Zero for a single value. */
    fun stdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val m = mean(values)
        val sumSq = values.sumOf { (it - m) * (it - m) }
        return sqrt(sumSq / (values.size - 1))
    }

    /**
     * Coefficient of variation (stdDev / mean). This is the run-to-run noise
     * of a test: 0.02 means iterations agreed within ~2%. VeriBench reports
     * it for every test instead of hiding variance behind a single number.
     */
    fun coefficientOfVariation(values: List<Double>): Double {
        val m = mean(values)
        if (m == 0.0) return 0.0
        return stdDev(values) / m
    }

    /**
     * Geometric mean. Used for combining per-test scores into a category
     * score: unlike an arithmetic mean it cannot be gamed by inflating one
     * outlier test, which is exactly the failure mode of naive score sums.
     */
    fun geometricMean(values: List<Double>): Double {
        require(values.isNotEmpty()) { "geometric mean of empty list" }
        require(values.all { it > 0.0 }) { "geometric mean requires positive values" }
        return exp(values.sumOf { ln(it) } / values.size)
    }
}
