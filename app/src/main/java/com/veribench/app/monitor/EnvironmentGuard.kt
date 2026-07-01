package com.veribench.app.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File

/**
 * A snapshot of the conditions under which a measurement was taken.
 * One is captured before the run and after every test, and the full series
 * ships inside the exported report — so anyone reading a VeriBench score can
 * see whether the device was cool, throttled, or cooking.
 */
data class EnvironmentSample(
    val timestampMs: Long,
    /** [PowerManager] thermal status (API 29+), null when unavailable. */
    val thermalStatus: Int?,
    /** Battery temperature in °C, null when unavailable. */
    val batteryTempC: Float?,
    /** Battery level 0–100, -1 when unavailable. */
    val batteryLevel: Int,
    val isCharging: Boolean,
    /**
     * Mean of (current frequency / max frequency) across CPU cores, from
     * sysfs. Null when the kernel hides cpufreq from apps (common on newer
     * SELinux policies) — absence is reported, never guessed.
     */
    val cpuFreqRatio: Double?,
) {
    companion object {
        /** Mirrors PowerManager.THERMAL_STATUS_MODERATE without requiring API 29. */
        const val THERMAL_MODERATE = 2
    }
}

class EnvironmentGuard(private val context: Context) {

    fun sample(): EnvironmentSample {
        return EnvironmentSample(
            timestampMs = System.currentTimeMillis(),
            thermalStatus = readThermalStatus(),
            batteryTempC = readBatteryTempC(),
            batteryLevel = readBatteryLevel(),
            isCharging = readIsCharging(),
            cpuFreqRatio = readCpuFreqRatio(),
        )
    }

    /** Human-readable pre-run advice; empty list means conditions look good. */
    fun preRunWarnings(): List<String> {
        val warnings = ArrayList<String>()
        val s = sample()
        if (s.batteryLevel in 0..19) {
            warnings += "Battery below 20% — many devices limit performance to save power."
        }
        if (s.isCharging) {
            warnings += "Device is charging — charging adds heat and can skew results."
        }
        if ((s.thermalStatus ?: 0) >= EnvironmentSample.THERMAL_MODERATE) {
            warnings += "Device is already warm — let it cool down for comparable results."
        }
        s.batteryTempC?.let { if (it > 37f) warnings += "Battery temperature is ${"%.1f".format(it)} °C — results will likely be throttled." }
        return warnings
    }

    private fun readThermalStatus(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.currentThermalStatus
        } catch (_: Throwable) {
            null
        }
    }

    private fun batteryIntent(): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun readBatteryTempC(): Float? {
        val tenths = batteryIntent()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: return null
        return if (tenths == Int.MIN_VALUE) null else tenths / 10f
    }

    private fun readBatteryLevel(): Int {
        val intent = batteryIntent() ?: return -1
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) -1 else (level * 100) / scale
    }

    private fun readIsCharging(): Boolean {
        val status = batteryIntent()?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: return false
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun readCpuFreqRatio(): Double? {
        return try {
            val cpuDirs = File("/sys/devices/system/cpu").listFiles { f ->
                f.name.matches(Regex("cpu\\d+"))
            } ?: return null
            val ratios = cpuDirs.mapNotNull { dir ->
                val cur = readLongFile(File(dir, "cpufreq/scaling_cur_freq")) ?: return@mapNotNull null
                val max = readLongFile(File(dir, "cpufreq/cpuinfo_max_freq")) ?: return@mapNotNull null
                if (max <= 0) null else cur.toDouble() / max.toDouble()
            }
            if (ratios.isEmpty()) null else ratios.sum() / ratios.size
        } catch (_: Throwable) {
            null
        }
    }

    private fun readLongFile(f: File): Long? = try {
        f.readText().trim().toLongOrNull()
    } catch (_: Throwable) {
        null
    }
}
