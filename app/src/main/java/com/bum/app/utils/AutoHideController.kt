package com.bum.app.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator

// يُدير إظهار وإخفاء عناصر الواجهة تلقائياً
class AutoHideController(
    private val views: List<View>,
    private val hideDelayMs: Long = DEFAULT_HIDE_MS,
    private val fadeDurationMs: Long = DEFAULT_FADE_MS,
    private val onVisibilityChanged: ((visible: Boolean) -> Unit)? = null
) {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var visible: Boolean = true
    @Volatile private var released: Boolean = false

    private val hideRunnable = Runnable { if (!released) hide(animate = true) }

    fun isVisible(): Boolean = visible

    /** يبدأ الدورة الأولى (Views ظاهرة → تختفي بعد المدة). */
    fun start() {
        if (released) return
        show(animate = false)
        scheduleHide()
    }

    // toggle عند الضغط على الشاشة
    fun tap() {
        if (released) return
        if (visible) hide(animate = true) else show(animate = true)
    }

    // تفاعل مستمر — يُظهِر ويُعيد المؤقّت
    fun onUserInteraction() {
        if (released) return
        if (!visible) show(animate = true) else scheduleHide()
    }


    fun toggle() = tap()

    fun show(animate: Boolean = true) {
        if (released) return
        val wasVisible = visible
        visible = true
        for (v in views) {
            try {
                // إلغاء أي animation قيد التشغيل
                v.animate().cancel()
                v.clearAnimation()

                if (v.visibility == View.VISIBLE && v.alpha >= 0.99f) continue

                v.visibility = View.VISIBLE
                if (animate) {
                    val startAlpha = v.alpha.coerceIn(0f, 1f)
                    v.alpha = startAlpha
                    v.animate()
                        .alpha(1f)
                        .setDuration(fadeDurationMs)
                        .setInterpolator(DecelerateInterpolator())
                        .setListener(null)
                        .start()
                } else {
                    v.alpha = 1f
                }
            } catch (_: Throwable) {
                try { v.alpha = 1f; v.visibility = View.VISIBLE } catch (_: Throwable) {}
            }
        }
        if (!wasVisible) try { onVisibilityChanged?.invoke(true) } catch (_: Throwable) {}
        scheduleHide()
    }

    fun hide(animate: Boolean = true) {
        if (released) return
        val wasVisible = visible
        visible = false
        for (v in views) {
            try {
                v.animate().cancel()
                v.clearAnimation()

                if (v.visibility != View.VISIBLE && v.alpha <= 0.01f) continue

                if (animate) {
                    v.animate()
                        .alpha(0f)
                        .setDuration(fadeDurationMs)
                        .setInterpolator(DecelerateInterpolator())
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                if (!visible && !released) {
                                    try { v.visibility = View.GONE } catch (_: Throwable) {}
                                }
                            }
                            override fun onAnimationCancel(animation: Animator) {
                            }
                        })
                        .start()
                } else {
                    v.alpha = 0f
                    v.visibility = View.GONE
                }
            } catch (_: Throwable) {
                try { v.alpha = 0f; v.visibility = View.GONE } catch (_: Throwable) {}
            }
        }
        cancelHide()
        if (wasVisible) try { onVisibilityChanged?.invoke(false) } catch (_: Throwable) {}
    }

    private fun scheduleHide() {
        cancelHide()
        if (hideDelayMs > 0 && !released) handler.postDelayed(hideRunnable, hideDelayMs)
    }

    private fun cancelHide() {
        try { handler.removeCallbacks(hideRunnable) } catch (_: Throwable) {}
    }

    fun release() {
        released = true
        cancelHide()
        try {
            for (v in views) {
                try { v.animate().cancel() } catch (_: Throwable) {}
                try { v.clearAnimation() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    companion object {
        const val DEFAULT_HIDE_MS = 5000L
        const val DEFAULT_FADE_MS = 220L
    }
}
