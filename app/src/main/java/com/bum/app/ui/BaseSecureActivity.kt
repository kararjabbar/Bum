package com.bum.app.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bum.app.security.ScreenshotBlocker
import com.bum.app.utils.AppSettings
import com.bum.app.utils.ThemeManager

// أساس الأمان لكل شاشات التطبيق
abstract class BaseSecureActivity : AppCompatActivity() {

    /**
     * يطبّق padding علوي ديناميكي على أي View (header/toolbar) بحيث
     * يكون متناسقاً مع ارتفاع status bar الفعلي على جميع الأجهزة.
     *
     * استخدام: استدعِ هذه الدالة في onCreate() بعد setContentView()
     * مرِّر الـ View المُراد ضبطه واختياريّاً الـ padding الإضافي.
     *
     * مثال:
     *   applyStatusBarPadding(binding.header, extraPaddingDp = 12)
     */
    fun applyStatusBarPadding(headerView: View, extraPaddingDp: Int = 12) {
        val extraPx = (extraPaddingDp * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(headerView) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(
                view.paddingLeft,
                statusBarHeight + extraPx,
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }
    }


    private var appliedThemeKey: String? = null

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase)
        try {
            window?.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } catch (_: Throwable) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyCurrentTheme(this)
        appliedThemeKey = AppSettings.getInstance(this).selectedThemeKey
        ScreenshotBlocker.enable(this)
        super.onCreate(savedInstanceState)
        ScreenshotBlocker.enable(this)
        applyHideOverlays()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ScreenshotBlocker.enable(this)
        applyHideOverlays()
    }

    override fun onResume() {
        super.onResume()
        ScreenshotBlocker.enable(this)
        applyHideOverlays()
        val currentKey = AppSettings.getInstance(this).selectedThemeKey
        if (appliedThemeKey != null && appliedThemeKey != currentKey) {
            appliedThemeKey = currentKey
            recreate()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            ScreenshotBlocker.enable(this)
            applyHideOverlays()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ScreenshotBlocker.enable(this)
        applyHideOverlays()
    }


    private fun applyHideOverlays() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window?.setHideOverlayWindows(true)
            }
        } catch (_: Throwable) { }
    }
}
