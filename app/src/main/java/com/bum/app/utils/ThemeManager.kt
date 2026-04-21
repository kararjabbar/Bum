package com.bum.app.utils

import android.app.Activity
import com.bum.app.R

// إدارة ثيمات التطبيق
enum class BumTheme(
    val key: String,
    val displayName: String,
    val styleRes: Int
) {
    DEFAULT       ("default",      "الافتراضي (برتقالي ناري)", R.style.Theme_Bum),
    AMOLED        ("amoled",       "AMOLED أسود مطلق",        R.style.Theme_Bum_Amoled),
    LIGHT         ("light",        "فاتح",                     R.style.Theme_Bum_Light),
    HIGH_CONTRAST ("high_contrast","تباين عالٍ",                R.style.Theme_Bum_HighContrast),
    SCARLET       ("scarlet",      "قرمزي ناري",               R.style.Theme_Bum_Scarlet),
    CYBER         ("cyber",        "سايبر نيون",               R.style.Theme_Bum_Cyber),
    MIDNIGHT      ("midnight",     "منتصف الليل",              R.style.Theme_Bum_Midnight),
    FOREST        ("forest",       "غابات داكنة",              R.style.Theme_Bum_Forest),
    SOLAR         ("solar",        "ذهبي شمسي",                R.style.Theme_Bum_Solar),
    ROSE          ("rose",         "وردي داكن",                R.style.Theme_Bum_Rose);

    companion object {
        fun fromKey(key: String?): BumTheme =
            values().firstOrNull { it.key == key } ?: DEFAULT
    }
}

object ThemeManager {

    // طبّق الثيم قبل super.onCreate
    fun applyCurrentTheme(activity: Activity) {
        val settings = AppSettings.getInstance(activity)
        val theme = BumTheme.fromKey(settings.selectedThemeKey)
        activity.setTheme(theme.styleRes)
    }

    // إعادة تشغيل الشاشة بعد تغيير الثيم
    fun recreateActivity(activity: Activity) {
        activity.recreate()
        activity.overridePendingTransition(
            android.R.anim.fade_in,
            android.R.anim.fade_out
        )
    }
}
