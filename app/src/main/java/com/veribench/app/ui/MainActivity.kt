package com.veribench.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.veribench.app.BuildConfig
import com.veribench.app.R
import com.veribench.app.core.Benchmark
import com.veribench.app.core.BenchmarkRunner
import com.veribench.app.core.RunReport
import com.veribench.app.core.Validity
import com.veribench.app.monitor.EnvironmentGuard
import com.veribench.app.report.ResultExporter
import com.veribench.app.suites.SuiteRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var guard: EnvironmentGuard
    private var lastReport: RunReport? = null

    private val runButton get() = findViewById<Button>(R.id.runButton)
    private val shareButton get() = findViewById<Button>(R.id.shareButton)
    private val progressSection get() = findViewById<View>(R.id.progressSection)
    private val progressBar get() = findViewById<ProgressBar>(R.id.progressBar)
    private val progressText get() = findViewById<TextView>(R.id.progressText)
    private val resultsSection get() = findViewById<View>(R.id.resultsSection)
    private val totalScoreText get() = findViewById<TextView>(R.id.totalScoreText)
    private val validityText get() = findViewById<TextView>(R.id.validityText)
    private val categoriesContainer get() = findViewById<LinearLayout>(R.id.categoriesContainer)
    private val warningsText get() = findViewById<TextView>(R.id.warningsText)
    private val deviceInfoText get() = findViewById<TextView>(R.id.deviceInfoText)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        guard = EnvironmentGuard(this)

        deviceInfoText.text = DeviceInfo.summaryLine(this)
        refreshWarnings()

        runButton.setOnClickListener { startRun() }
        shareButton.setOnClickListener { shareReport() }
    }

    override fun onResume() {
        super.onResume()
        refreshWarnings()
    }

    private fun refreshWarnings() {
        val warnings = guard.preRunWarnings()
        if (warnings.isEmpty()) {
            warningsText.text = getString(R.string.conditions_ok)
            warningsText.setTextColor(ContextCompat.getColor(this, R.color.status_good))
        } else {
            warningsText.text = warnings.joinToString("\n") { "⚠ $it" }
            warningsText.setTextColor(ContextCompat.getColor(this, R.color.status_warn))
        }
    }

    private fun startRun() {
        runButton.isEnabled = false
        resultsSection.visibility = View.GONE
        progressSection.visibility = View.VISIBLE
        progressBar.progress = 0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val benchmarks = SuiteRegistry.all(cacheDir)
        val deviceInfo = DeviceInfo.collect(this)

        lifecycleScope.launch(Dispatchers.Default) {
            val listener = object : BenchmarkRunner.Listener {
                override fun onTestStarted(index: Int, total: Int, benchmark: Benchmark) {
                    runOnUiThread {
                        progressBar.max = total
                        progressBar.progress = index
                        progressText.text =
                            getString(R.string.progress_format, index + 1, total, benchmark.name)
                    }
                }

                override fun onIterationDone(benchmark: Benchmark, iteration: Int, totalIterations: Int) {
                    // Coarse per-test progress is enough; per-iteration UI churn
                    // would only add jank to the device under test.
                }
            }

            val report = BenchmarkRunner(guard, listener)
                .run(benchmarks, deviceInfo, BuildConfig.VERSION_NAME)

            withContext(Dispatchers.Main) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                progressSection.visibility = View.GONE
                runButton.isEnabled = true
                lastReport = report
                showReport(report)
            }
        }
    }

    private fun showReport(report: RunReport) {
        resultsSection.visibility = View.VISIBLE
        totalScoreText.text = report.totalScore.toString()

        val (label, color) = when (report.validity) {
            Validity.VALID -> getString(R.string.validity_valid) to R.color.status_good
            Validity.DEGRADED -> getString(R.string.validity_degraded) to R.color.status_warn
            Validity.INVALID -> getString(R.string.validity_invalid) to R.color.status_bad
        }
        val reasons =
            if (report.validityReasons.isEmpty()) ""
            else "\n" + report.validityReasons.joinToString("\n") { "• $it" }
        validityText.text = label + reasons
        validityText.setTextColor(ContextCompat.getColor(this, color))

        categoriesContainer.removeAllViews()
        for (cat in report.categories) {
            val header = TextView(this).apply {
                text = getString(
                    R.string.category_format,
                    cat.category.displayName,
                    Math.round(cat.score),
                )
                textSize = 17f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setPadding(0, dp(14), 0, dp(4))
            }
            categoriesContainer.addView(header)

            for (t in cat.tests) {
                val r = t.result
                val line = when {
                    r.skipped -> "  ${r.name} — skipped (${r.skipReason})"
                    !r.checksumOk -> "  ${r.name} — INVALID (checksum mismatch)"
                    else -> "  %s — %d  (%.1f %s, ±%.1f%%)".format(
                        r.name, Math.round(t.score), r.medianThroughput, r.unit,
                        r.coefficientOfVariation * 100.0,
                    )
                }
                val tv = TextView(this).apply {
                    text = line
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    setPadding(0, dp(2), 0, dp(2))
                }
                categoriesContainer.addView(tv)
            }
        }
    }

    private fun shareReport() {
        val report = lastReport ?: return
        val json = ResultExporter.toJson(report).toString(2)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "VeriBench report — ${report.totalScore}")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
