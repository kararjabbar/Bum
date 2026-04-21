package com.bum.app.security

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.bum.app.BuildConfig
import com.bum.app.utils.AppSettings
import kotlinx.coroutines.*

// مراقبة دورة حياة التطبيق والقفل التلقائي
class LifecycleObserver(private val context: Context) : DefaultLifecycleObserver {

    private val TAG = "BumLifecycle"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var graceJob: Job? = null

    // هل مؤقّت فترة السماح يعمل حالياً
    @Volatile private var graceActive: Boolean = false


    @Volatile var sessionLocked: Boolean = true
        private set

    @Volatile private var skipNextStopLock: Boolean = false

    fun markAuthenticated() { sessionLocked = false }


    fun allowOneExternalRoundTrip() {
        skipNextStopLock = true
    }

    override fun onStart(owner: LifecycleOwner) {
        if (graceActive) {
            graceJob?.cancel()
            graceJob = null
            graceActive = false
            sessionLocked = false
        } else {
            graceJob?.cancel()
            graceJob = null
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        if (skipNextStopLock) {
            skipNextStopLock = false
            return
        }

        val settings = AppSettings.getInstance(context)
        val graceSec = if (settings.isGraceEnabled) settings.gracePeriodSeconds else 0

        sessionLocked = true

        graceJob?.cancel()

        if (graceSec > 0) {
            graceActive = true
            graceJob = scope.launch {
                try {
                    delay(graceSec * 1000L)
                    graceActive = false
                    performScheduledWipe()
                } catch (_: CancellationException) {}
            }
        } else {
            graceActive = false
            graceJob = scope.launch {
                try {
                    performScheduledWipe()
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "immediate wipe error", e)
                }
            }
        }
    }

    private fun performScheduledWipe() {
        val settings = AppSettings.getInstance(context)
        val mgr = SecureDataManager.getInstance(context)
        val removed = mgr.performScheduledWipe(settings.wipeAllOnClose)
        sessionLocked = true
        if (BuildConfig.DEBUG) Log.d(TAG, "Scheduled wipe done. Removed=$removed. Session locked.")
    }
}
