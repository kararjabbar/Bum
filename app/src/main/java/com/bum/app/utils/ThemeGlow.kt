package com.bum.app.utils

import android.content.Context
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.PaintDrawable
import android.graphics.drawable.shapes.RectShape
import android.util.TypedValue
import android.view.View

// توهّج شعاعي متلائم مع لون الثيم
object ThemeGlow {

    // تطبيق التوهّج على View معينة
    fun applyAccentGlow(
        view: View,
        ctx: Context,
        alphaHex: Int = 0x14,
        centerY: Float = 0.25f
    ) {
        val accent = resolveThemeColor(ctx, com.bum.app.R.attr.bumAccent)
        val tintedAccent = (accent and 0x00FFFFFF) or ((alphaHex and 0xFF) shl 24)
        val transparent = 0x00000000

        val drawable = object : PaintDrawable() {
            init { shape = RectShape() }
            override fun onBoundsChange(bounds: android.graphics.Rect) {
                super.onBoundsChange(bounds)
                val cx = bounds.width() * 0.5f
                val cy = bounds.height() * centerY
                val radius = maxOf(bounds.width(), bounds.height()) * 0.55f
                val gradient = RadialGradient(
                    cx, cy, radius,
                    tintedAccent, transparent,
                    Shader.TileMode.CLAMP
                )
                paint.shader = gradient
            }
        }
        view.background = drawable
    }

    // توهّج شاشة البداية
    fun applySplashGlow(view: View, ctx: Context) {
        // نعيد استخدام accent بشدة منخفضة من وسط-أسفل الشاشة
        applyAccentGlow(view, ctx, alphaHex = 0x22, centerY = 0.9f)
    }

    private fun resolveThemeColor(ctx: Context, attr: Int): Int {
        val tv = TypedValue()
        ctx.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}
