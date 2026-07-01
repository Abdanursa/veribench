package com.veribench.app.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/** Collects the device identity block included in every report. */
object DeviceInfo {

    fun collect(context: Context): Map<String, String> {
        val info = LinkedHashMap<String, String>()
        info["manufacturer"] = Build.MANUFACTURER
        info["model"] = Build.MODEL
        info["device"] = Build.DEVICE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info["soc"] = "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
        }
        info["androidVersion"] = Build.VERSION.RELEASE
        info["apiLevel"] = Build.VERSION.SDK_INT.toString()
        info["securityPatch"] = Build.VERSION.SECURITY_PATCH
        info["abis"] = Build.SUPPORTED_ABIS.joinToString(",")
        info["cpuCores"] = Runtime.getRuntime().availableProcessors().toString()
        maxCpuFreqMhz()?.let { info["maxCpuFreqMhz"] = it.toString() }
        totalRamMb(context)?.let { info["totalRamMb"] = it.toString() }
        return info
    }

    fun summaryLine(context: Context): String {
        val i = collect(context)
        val soc = i["soc"]?.let { " · $it" } ?: ""
        return "${i["manufacturer"]} ${i["model"]}$soc\n" +
            "Android ${i["androidVersion"]} (API ${i["apiLevel"]}) · " +
            "${i["cpuCores"]} cores · ${i["totalRamMb"] ?: "?"} MB RAM"
    }

    private fun maxCpuFreqMhz(): Long? = try {
        File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
            ?.mapNotNull { dir ->
                File(dir, "cpufreq/cpuinfo_max_freq").takeIf { it.canRead() }
                    ?.readText()?.trim()?.toLongOrNull()
            }
            ?.maxOrNull()?.let { it / 1000 }
    } catch (_: Throwable) {
        null
    }

    private fun totalRamMb(context: Context): Long? = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.totalMem / (1024 * 1024)
    } catch (_: Throwable) {
        null
    }
}
