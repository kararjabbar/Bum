package com.bum.app.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bum.app.BuildConfig
import com.bum.app.utils.AppSettings

// تنظيف البيانات عند إعادة التشغيل
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            if (BuildConfig.DEBUG) Log.d("BootReceiver", "Device booted — purging expired")

            try {
                val settings = AppSettings.getInstance(context)
                val secure = SecureDataManager.getInstance(context)
                secure.performScheduledWipe(settings.wipeAllOnClose)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("BootReceiver", "Boot purge failed", e)
            }
        }
    }
}
