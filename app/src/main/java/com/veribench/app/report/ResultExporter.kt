package com.veribench.app.report

import com.veribench.app.core.RunReport
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes a [RunReport] to JSON. The export contains *everything* used to
 * produce the score — per-iteration raw throughputs, environment samples,
 * scoring version — so a third party can recompute and audit the score.
 */
object ResultExporter {

    fun toJson(report: RunReport): JSONObject {
        val root = JSONObject()
        root.put("app", "VeriBench")
        root.put("appVersion", report.appVersion)
        root.put("scoringVersion", report.scoringVersion)
        root.put("startedAtEpochMs", report.startedAtMs)
        root.put("durationMs", report.durationMs)
        root.put("totalScore", report.totalScore)
        root.put("validity", report.validity.name)
        root.put("validityReasons", JSONArray(report.validityReasons))

        val device = JSONObject()
        report.deviceInfo.forEach { (k, v) -> device.put(k, v) }
        root.put("device", device)

        val categories = JSONArray()
        for (cat in report.categories) {
            val c = JSONObject()
            c.put("category", cat.category.name)
            c.put("displayName", cat.category.displayName)
            c.put("weight", cat.category.weight)
            c.put("score", Math.round(cat.score))
            c.put("worstCvPercent", cat.worstCv * 100.0)
            val tests = JSONArray()
            for (t in cat.tests) {
                val r = t.result
                val o = JSONObject()
                o.put("id", r.benchmarkId)
                o.put("name", r.name)
                o.put("unit", r.unit)
                o.put("score", Math.round(t.score))
                o.put("medianThroughput", r.medianThroughput)
                o.put("cvPercent", r.coefficientOfVariation * 100.0)
                o.put("checksumOk", r.checksumOk)
                o.put("skipped", r.skipped)
                r.skipReason?.let { o.put("skipReason", it) }
                o.put("iterations", JSONArray(r.iterationThroughputs))
                tests.put(o)
            }
            c.put("tests", tests)
            categories.put(c)
        }
        root.put("categories", categories)

        val samples = JSONArray()
        for (s in report.environmentSamples) {
            val o = JSONObject()
            o.put("timestampMs", s.timestampMs)
            o.put("thermalStatus", s.thermalStatus ?: JSONObject.NULL)
            o.put("batteryTempC", s.batteryTempC ?: JSONObject.NULL)
            o.put("batteryLevel", s.batteryLevel)
            o.put("isCharging", s.isCharging)
            o.put("cpuFreqRatio", s.cpuFreqRatio ?: JSONObject.NULL)
            samples.put(o)
        }
        root.put("environmentSamples", samples)
        return root
    }
}
