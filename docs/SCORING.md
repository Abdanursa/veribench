# VeriBench scoring — the complete formula

Scoring version: **1.0** (`ScoringEngine.SCORING_VERSION`)

VeriBench scores are only comparable to other VeriBench scores **with the same scoring
version**. Any change to a workload, a seed, a size, a baseline, or a weight requires a
version bump. This file is the normative description; the implementation is
[`ScoringEngine.kt`](../app/src/main/java/com/veribench/app/core/ScoringEngine.kt).

## 1. Per-test score

```
testScore = 1000 × (medianThroughput / baseline)
```

- `medianThroughput` is the **median** of the measured iterations (3–5 depending on the
  test), after 1–2 discarded warm-up iterations. The median means one throttled or
  interrupted iteration cannot drag the result.
- `baseline` is a fixed constant from the table below.
- A test whose checksum verification failed, or which could not run, scores **0** and is
  excluded from its category's geometric mean.

## 2. Category score

```
categoryScore = geometricMean(testScores of tests that ran and verified)
```

The geometric mean is deliberate: with an arithmetic mean, doubling one test's score adds a
fixed number of points, so a vendor could target one cheap-to-optimize test. With a geometric
mean, only proportional improvement across the board moves the category.

## 3. Total score

```
total = 10 × Σ(weight_c × categoryScore_c) / Σ(weight_c over categories present)
```

| Category | Weight |
|---|---|
| CPU single-core | 0.25 |
| CPU multi-core | 0.25 |
| Memory | 0.15 |
| Storage | 0.15 |
| GPU | 0.10 |
| App & UX | 0.10 |

A category with no valid tests (e.g. GPU on an emulator without EGL) is **excluded and the
remaining weights renormalized**. The run is marked DEGRADED with the reason shown. This
keeps the total meaningful without pretending a missing subsystem scored zero.

**Anchor points:** a device matching every baseline exactly totals **10 000**. The scale is
linear: 20 000 means twice the reference throughput on the weighted mix.

## 4. Baselines (scoring version 1.0)

Baselines are calibration constants defining the hypothetical reference device (roughly a
2023 mid-range phone). They live in `ScoringEngine.BASELINES`, one per test id. Initial
values were set from published measurements of mid-range hardware and are frozen for this
scoring version; recalibration = new scoring version, and old scores stay comparable among
themselves.

| Test id | Baseline | Unit |
|---|---|---|
| cpu.sieve | 120 | Mnum/s |
| cpu.matmul | 900 | MFLOPS |
| cpu.fft | 220 | MFLOPS |
| cpu.sha256 | 55 | MB/s |
| cpu.sort | 9 | Melem/s |
| cpu.deflate | 28 | MB/s |
| mc.sieve | 480 | Mnum/s |
| mc.matmul | 3600 | MFLOPS |
| mc.sha256 | 220 | MB/s |
| mem.seqcopy | 3500 | MB/s |
| mem.randlat | 28 | Macc/s |
| mem.triad | 1800 | MB/s |
| io.seqwrite | 250 | MB/s |
| io.seqread | 900 | MB/s |
| io.randread | 45 | MB/s |
| io.randwrite | 18 | MB/s |
| gpu.shader | 900 | Mpix/s |
| ux.json | 42 | MB/s |
| ux.bitmap | 55 | Mpix/s |
| ux.collections | 7.5 | Mops/s |

## 5. Validity verdicts

Attached to every run, next to the score — a score without its verdict is not a VeriBench
result:

- **VALID** — all workloads verified, no thermal/battery/frequency anomalies, per-test
  variance under 15%.
- **DEGRADED** — workloads verified, but at least one of: OS thermal throttling reported,
  battery > 40 °C, charging during the run, CPU clocks < 60% of max, per-test CV > 15%, or a
  test skipped. All reasons are listed and exported.
- **INVALID** — at least one workload produced output that failed checksum verification.
  The number is not a measurement; it is displayed only with the failure reason.

## 6. Verification model

- **CPU / memory / storage-read / UX** workloads are deterministic down to the bit. Their
  checksums are pinned by [`ChecksumRegressionTest`](../app/src/test/java/com/veribench/app/ChecksumRegressionTest.kt)
  on the JVM: identical constants must reproduce on every device. This works because the
  kernels use only exactly-specified arithmetic — integer ops and strict IEEE-754
  `+ − × ÷` (the FFT computes its own twiddle factors with a Taylor series rather than
  calling `Math.sin`, whose last-bit behaviour is implementation-defined).
- **GPU** output legitimately differs across fp32 pipelines, so sampled pixels are compared
  against a CPU reference of the same smooth (non-chaotic) function within a ±10/255
  tolerance instead of an exact checksum.
- **Storage writes** cannot verify themselves without a read; the read tests read files
  produced by the same writer and verify content against precomputed constants.

## 7. Known, documented limitations

Stated here because a benchmark that hides its limits is the problem we set out to fix:

- **Page cache.** Without root, sequential-read numbers partially reflect the page cache
  (the file is written in `setUp`). Treat `io.seqread` as an upper bound; `io.randread` over
  64 MiB and synced `io.randwrite` are much closer to the flash itself.
- **Managed runtime.** CPU scores measure Kotlin/ART (with explicit JIT warm-up), not
  hand-tuned NDK intrinsics — deliberately, since that is what real apps experience. Scores
  are not comparable to native benchmarks like Geekbench, and don't claim to be.
- **Baselines are conventions.** The reference device is a definition, not a measurement of
  a specific phone. It anchors the scale; relative comparisons between devices are what
  matter.
- **Multi-core totals include big.LITTLE asymmetry.** Slow cores genuinely reduce parallel
  throughput; the multi-core score reflects the whole cluster, not per-core × count.
