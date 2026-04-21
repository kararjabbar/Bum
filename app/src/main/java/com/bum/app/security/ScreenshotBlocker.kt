package com.bum.app.security

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap

object ScreenshotBlocker {

    private const val BLOCK_MESSAGE = "🚫 التقاط الشاشة أو تسجيل الفيديو غير مسموح"
    private const val TAG = "ScreenshotBlocker"

    // Android 14 callback storage (type-safe)
    private val screenCaptureCallbacks =
        ConcurrentHashMap<Activity, Any>()

    private val captureAttempts =
        ConcurrentHashMap<String, Long>()



    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {

        override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
            applyFull(activity)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            applyFull(activity)
            installPerFrameEnforcer(activity)
        }

        override fun onActivityStarted(activity: Activity) {
            applyFull(activity)
            registerScreenCaptureCallback(activity)
        }

        override fun onActivityResumed(activity: Activity) {
            applyFull(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            applyFull(activity)
        }

        override fun onActivityStopped(activity: Activity) {
            unregisterScreenCaptureCallback(activity)
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    fun registerGlobal(app: Application) {
        app.registerActivityLifecycleCallbacks(lifecycleCallbacks)
    }



    fun enable(activity: Activity) {
        applyFull(activity)
    }

    fun enable(dialog: Dialog) {
        dialog.window?.let { applyFlagSecure(it) }
    }

    fun disable(activity: Activity) {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun applyFull(activity: Activity) {
        try {
            val w = activity.window ?: return

            w.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )

            w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

            val attrs = w.attributes
            attrs.flags = attrs.flags or WindowManager.LayoutParams.FLAG_SECURE
            w.attributes = attrs

            // 👇 Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } catch (_: Throwable) {}

                try {
                    val method = Window::class.java.getMethod(
                        "setHideOverlayWindows",
                        Boolean::class.javaPrimitiveType
                    )
                    method.invoke(w, true)
                } catch (_: Throwable) {}
            }

        } catch (e: Throwable) {
            Log.e(TAG, "applyFull failed", e)
        }
    }

    private fun applyFlagSecure(window: Window) {
        try {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )

            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        } catch (e: Throwable) {
            Log.e(TAG, "applyFlagSecure failed", e)
        }
    }



    private fun installPerFrameEnforcer(activity: Activity) {
        try {
            val root = activity.window?.decorView ?: return
            val vto = root.viewTreeObserver
            if (!vto.isAlive) return

            vto.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    try {
                        val w = activity.window ?: return true

                        if ((w.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) == 0) {
                            w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                    } catch (_: Throwable) {}

                    return true
                }
            })

        } catch (e: Throwable) {
            Log.e(TAG, "installPerFrameEnforcer failed", e)
        }
    }



    private fun registerScreenCaptureCallback(activity: Activity) {
        if (Build.VERSION.SDK_INT < 34) return

        try {
            if (screenCaptureCallbacks.containsKey(activity)) return

            val window = activity.window ?: return

            val clazz = Class.forName("android.view.Window\$ScreenCaptureCallback")

            val callback = java.lang.reflect.Proxy.newProxyInstance(
                clazz.classLoader,
                arrayOf(clazz)
            ) { _, _, _ ->

                captureAttempts[activity.localClassName] = System.currentTimeMillis()

                Log.w(TAG, "Screen capture attempt detected")

                Toast.makeText(activity, BLOCK_MESSAGE, Toast.LENGTH_LONG).show()

                null
            }

            val method = Window::class.java.getMethod(
                "registerScreenCaptureCallback",
                java.util.concurrent.Executor::class.java,
                clazz
            )

            method.invoke(window, activity.mainExecutor, callback)

            screenCaptureCallbacks[activity] = callback

        } catch (e: Throwable) {
            Log.e(TAG, "registerScreenCaptureCallback failed", e)
        }
    }

    private fun unregisterScreenCaptureCallback(activity: Activity) {
        if (Build.VERSION.SDK_INT < 34) return

        try {
            val window = activity.window ?: return
            val callback = screenCaptureCallbacks.remove(activity) ?: return

            val clazz = Class.forName("android.view.Window\$ScreenCaptureCallback")

            val method = Window::class.java.getMethod(
                "unregisterScreenCaptureCallback",
                clazz
            )

            method.invoke(window, callback)

        } catch (e: Throwable) {
            Log.e(TAG, "unregisterScreenCaptureCallback failed", e)
        }
    }



    fun isScreenRecorderRunning(context: Context): Boolean {
        return try {
            val manager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

            val processes = manager.runningAppProcesses ?: return false

            val keywords = listOf("record", "screen", "capture", "recorder")

            processes.any { process ->
                keywords.any { key ->
                    process.processName.contains(key, true)
                }
            }

        } catch (_: Throwable) {
            false
        }
    }



    fun getLastCaptureAttempt(activity: Activity): Long? {
        return captureAttempts[activity.localClassName]
    }

    @Suppress("unused")
    private fun View.reapplyFlagSecureIfNeeded(activity: Activity) {
        try {
            val w = activity.window ?: return

            if ((w.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) == 0) {
                applyFlagSecure(w)
            }

        } catch (_: Throwable) {}
    }
}