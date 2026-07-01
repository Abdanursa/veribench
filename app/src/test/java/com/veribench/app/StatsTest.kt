package com.veribench.app

import com.veribench.app.core.Stats
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsTest {

    @Test
    fun medianOdd() {
        assertEquals(3.0, Stats.median(listOf(5.0, 1.0, 3.0)), 0.0)
    }

    @Test
    fun medianEven() {
        assertEquals(2.5, Stats.median(listOf(4.0, 1.0, 2.0, 3.0)), 0.0)
    }

    @Test
    fun medianIgnoresOutlier() {
        // The whole point of using the median: one throttled iteration
        // cannot drag the reported result.
        assertEquals(100.0, Stats.median(listOf(99.0, 100.0, 101.0, 100.0, 5.0)), 0.0)
    }

    @Test
    fun stdDevOfConstantIsZero() {
        assertEquals(0.0, Stats.stdDev(listOf(7.0, 7.0, 7.0)), 0.0)
    }

    @Test
    fun coefficientOfVariation() {
        // mean = 10, sample stdev of {8, 12} = sqrt(8) ≈ 2.8284
        assertEquals(0.28284, Stats.coefficientOfVariation(listOf(8.0, 12.0)), 1e-4)
    }

    @Test
    fun geometricMean() {
        assertEquals(4.0, Stats.geometricMean(listOf(2.0, 8.0)), 1e-9)
        assertEquals(1000.0, Stats.geometricMean(listOf(1000.0, 1000.0, 1000.0)), 1e-6)
    }
}
