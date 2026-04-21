package com.bum.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.bum.app.security.LifecycleObserver
import com.bum.app.security.ScreenshotBlocker
import com.bum.app.security.SecureDataManager

// نقطة تهيئة التطبيق
class BumApplication : Application(), Configuration.Provider {

    companion object {
        lateinit var instance: BumApplication
            private set
        lateinit var lifecycleObserver: LifecycleObserver
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        ScreenshotBlocker.registerGlobal(this)

        lifecycleObserver = LifecycleObserver(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(
                if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.ERROR
            )
            .build()

    override fun onLowMemory() {
        super.onLowMemory()
        SecureDataManager.getInstance(this).clearSessionCache()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) {
            SecureDataManager.getInstance(this).clearSessionCache()
        }
    }
}
