package com.veribench.app.suites

/**
 * SplitMix64 — a tiny deterministic PRNG. Every workload derives its input
 * data from this generator with a fixed seed, so every device on Earth
 * benchmarks byte-identical data. (java.util.Random would also be stable,
 * but an explicit 8-line generator is easier to audit.)
 */
class SplitMix64(seed: Long) {
    private var state = seed

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL          // golden gamma 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** Uniform int in [0, bound). bound must be > 0. */
    fun nextInt(bound: Int): Int = ((nextLong() ushr 33) % bound).toInt()
}

/** Order-dependent 64-bit fold used to build workload checksums. */
fun foldChecksum(acc: Long, value: Long): Long = acc * 0x100000001B3L xor value
