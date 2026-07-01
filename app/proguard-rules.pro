# Benchmark workloads must never be optimized away or restructured by R8 in a
# way that changes what is being measured. Keep the whole suites package intact.
-keep class com.veribench.app.suites.** { *; }
-keep class com.veribench.app.core.** { *; }
