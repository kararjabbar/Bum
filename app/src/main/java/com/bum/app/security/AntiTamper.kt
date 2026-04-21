package com.bum.app.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Debug
import java.io.File

// فحص التلاعب بالتطبيق
object AntiTamper {


    private val SUSPECT_LIBS = arrayOf(
        "frida",
        "gum-js-loop",
        "gmain",
        "linjector",
        "libfrida",
        "libxposed",
        "xposed",
        "substrate",
        "libsubstrate"
    )


    private val SUSPECT_FILES = arrayOf(
        "/data/data/de.robv.android.xposed.installer",
        "/data/data/io.github.lsposed.manager",
        "/system/framework/XposedBridge.jar",
        "/data/local/tmp/frida-server",
        "/data/local/tmp/re.frida.server"
    )

    data class TamperReport(
        val debuggerAttached: Boolean,
        val traced: Boolean,
        val hookingLib: Boolean,
        val xposedFile: Boolean,
        val debuggableBuild: Boolean
    ) {
        val isTampered: Boolean
            get() = debuggerAttached || traced || hookingLib || xposedFile || debuggableBuild
    }


    fun quickCheck(context: Context): TamperReport {
        return TamperReport(
            debuggerAttached = isDebuggerConnected(),
            traced           = isBeingTraced(),
            hookingLib       = hasHookingLibLoaded(),
            xposedFile       = hasXposedArtifacts(),
            debuggableBuild  = isAppDebuggable(context)
        )
    }


    private fun isDebuggerConnected(): Boolean {
        return try {
            Debug.isDebuggerConnected() || Debug.waitingForDebugger()
        } catch (_: Throwable) { false }
    }


    private fun isBeingTraced(): Boolean {
        return try {
            val status = File("/proc/self/status")
            if (!status.exists()) return false
            status.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("TracerPid:")) {
                        val pid = line.substringAfter(":").trim().toIntOrNull() ?: 0
                        return@useLines pid != 0
                    }
                }
                false
            }
        } catch (_: Throwable) { false }
    }


    private fun hasHookingLibLoaded(): Boolean {
        return try {
            val maps = File("/proc/self/maps")
            if (!maps.exists()) return false
            maps.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val lower = line.lowercase()
                    for (s in SUSPECT_LIBS) {
                        if (lower.contains(s)) return@useLines true
                    }
                }
                false
            }
        } catch (_: Throwable) { false }
    }

    private fun hasXposedArtifacts(): Boolean {
        return try {
            SUSPECT_FILES.any { File(it).exists() }
        } catch (_: Throwable) { false }
    }


    private fun isAppDebuggable(context: Context): Boolean {
        return try {
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Throwable) { false }
    }
}
