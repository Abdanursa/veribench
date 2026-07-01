# VeriBench

**The Android benchmark that shows its work.**

VeriBench is an open-source device benchmark in the spirit of AnTuTu — CPU, memory, storage,
GPU and app-workload scores rolled into one number — but engineered around a single question:
*why should anyone trust a benchmark score?*

[![Android CI](https://img.shields.io/badge/CI-GitHub_Actions-2088FF?logo=githubactions&logoColor=white)](.github/workflows/android-ci.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white)
![minSdk 26](https://img.shields.io/badge/minSdk-26_(Android_8.0)-3DDC84?logo=android&logoColor=white)
![License MIT](https://img.shields.io/badge/license-MIT-blue)
![Permissions: none](https://img.shields.io/badge/permissions-none-brightgreen)

---

## Why not just use AnTuTu?

Popular benchmarks have well-documented credibility problems. VeriBench treats each one as a
design requirement:

| Problem with typical benchmarks | What VeriBench does instead |
|---|---|
| **Secret scoring formula** that changes silently between versions, making scores incomparable | The full formula, weights, and baselines are in [`ScoringEngine.kt`](app/src/main/java/com/veribench/app/core/ScoringEngine.kt) and [docs/SCORING.md](docs/SCORING.md). Any change bumps a public scoring version; scores are only comparable within a version, and the app says so. |
| **Single hero run** — the score is whatever happened that one time | Every test runs warm-up passes (JIT settles) plus 3–5 measured iterations. The **median** is scored and the **coefficient of variation** is reported per test, so noise is visible, not hidden. |
| **Thermal blindness** — a pre-heated or throttling phone quietly produces a lower (or cheated, pre-cooled, higher) score | Thermal status, CPU frequency, and battery state are sampled before the run and after every test. The result carries a **VALID / DEGRADED / INVALID** verdict with explicit reasons ("OS reported throttling", "device was charging", "clocks dropped below 60% of max"). |
| **Unverified workloads** — nothing proves the measured code actually ran correctly | Every workload is deterministic and returns a checksum of its output, verified against constants pinned by [JVM unit tests](app/src/test/java/com/veribench/app/ChecksumRegressionTest.kt). Wrong output ⇒ the test scores **0** and the run is marked INVALID. |
| **Cache-flattered storage numbers** | Random writes use `O_DSYNC`-equivalent (`RandomAccessFile("rwd")`) so every 4 KiB write commits to flash. Reads fold sampled bytes and verify them against precomputed constants. Remaining page-cache limits are *documented*, not denied. |
| **Ads, trackers, network calls** (AnTuTu was pulled from Google Play in 2020) | VeriBench requests **zero permissions**. No INTERNET permission means exfiltration is impossible by construction — check the merged manifest yourself. |
| **Vendor cheating** ("benchmark mode" detection by package name) | Open source makes whitelisting detectable, deterministic workloads make result faking detectable, and the exported report contains raw per-iteration data anyone can audit. |

## What it measures

20 tests across 6 categories (weights in parentheses):

- **CPU single-core (25%)** — prime sieve, FP64 matrix multiply, 64K FFT, pure-Kotlin SHA-256, 2M-integer sort, DEFLATE compression round-trip
- **CPU multi-core (25%)** — sieve / matmul / SHA-256 scaled across all cores
- **Memory (15%)** — 64 MiB sequential copy, pointer-chase random latency, STREAM triad
- **Storage (15%)** — sequential read/write (64 MiB), random 4 KiB read, synced random 4 KiB write
- **GPU (10%)** — offscreen 720p fragment-shader throughput (EGL pbuffer), output verified against a CPU reference within tolerance
- **App & UX (10%)** — 2 MiB JSON parse, lossless PNG decode, HashMap workload

A full run takes about 3 minutes. Categories that cannot run (e.g. GPU on some emulators) are
excluded and the weights renormalized — never silently scored as zero.

## Scores

- **1000** per test ≙ the reference baseline (a hypothetical 2023 mid-range device).
- Category score = geometric mean of its tests (one inflated test can't carry a category).
- **Total: 10 000** ≙ reference device across the board. Higher is better, linearly.

Full formula, baselines, and honesty notes: [docs/SCORING.md](docs/SCORING.md).

## Building

```bash
git clone <this repo>
cd veribench
# Windows
gradlew.bat assembleDebug
# Linux/macOS
./gradlew assembleDebug
```

APK lands in `app/build/outputs/apk/debug/`. Requires JDK 17+ and the Android SDK
(compileSdk 34). CI builds and tests every push — see
[.github/workflows/android-ci.yml](.github/workflows/android-ci.yml).

Run the verification suite (NIST SHA-256 vectors, scoring properties, checksum pins):

```bash
gradlew.bat :app:testDebugUnitTest
```

## Report export

Every run can be shared as JSON containing the total, per-category and per-test scores,
**raw per-iteration throughputs**, all environment samples (thermal status, battery
temperature, CPU frequency ratio), device identity, and the scoring version — everything
needed for a third party to recompute the score.

## Architecture

```
app/src/main/java/com/veribench/app/
├── core/        Benchmark contract, runner, statistics, scoring (pure, unit-tested)
├── monitor/     Thermal / battery / CPU-frequency sampling, run-validity rules
├── suites/      The 20 workloads (deterministic kernels are Android-free)
├── report/      JSON export of the full audit trail
└── ui/          Single-activity UI
```

Design choices worth reading the code for:

- **Kernels are JVM-pure.** The arithmetic in every CPU/memory/storage workload avoids
  platform-dependent operations (no `Math.sin`; the FFT builds twiddle factors from its own
  Taylor series), so the exact checksums are pinned by ordinary unit tests on the JVM and
  must reproduce bit-for-bit on every ART device.
- **Managed-runtime scores are the point, not a compromise.** Scores reflect ART — the
  runtime real apps run on — with JIT warm-up handled explicitly.
- **R8 keeps rules** prevent the optimizer from restructuring measured code paths.

## License

[MIT](LICENSE)
