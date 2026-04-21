package com.bum.app.utils

import java.util.Calendar

// أدوات التاريخ
object DateUtils {

    private val ARABIC_DAYS = arrayOf(
        "الأحد", "الاثنين", "الثلاثاء", "الأربعاء",
        "الخميس", "الجمعة", "السبت"
    )

    private val ARABIC_MONTHS = arrayOf(
        "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
        "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"
    )

    
    fun getArabicDate(): String {
        val cal = Calendar.getInstance()
        val dayName = ARABIC_DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
        val dayNum = cal.get(Calendar.DAY_OF_MONTH)
        val monthName = ARABIC_MONTHS[cal.get(Calendar.MONTH)]
        return "$dayName، $dayNum $monthName"
    }

    
    fun getTimeGreeting(): String {
        return when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11  -> "صباح التمرد"
            in 12..16 -> "عصر الأفكار الهادئة"
            in 17..20 -> "مساء الفناء الجميل"
            else      -> "ليل الأسرار"
        }
    }

    // تنسيق الوقت بشكل ودي: "قبل 5 دقائق"، "أمس"، إلخ
    fun friendlyFromMillis(millis: Long): String {
        if (millis <= 0L) return "—"
        val now = System.currentTimeMillis()
        val diff = now - millis
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour

        return when {
            diff < 60_000L -> "قبل لحظات"
            diff < hour -> "قبل ${diff / minute} دقيقة"
            diff < day -> "قبل ${diff / hour} ساعة"
            diff < 2 * day -> "أمس"
            diff < 7 * day -> "قبل ${diff / day} أيام"
            else -> {
                val cal = Calendar.getInstance().apply { timeInMillis = millis }
                val d = cal.get(Calendar.DAY_OF_MONTH)
                val m = ARABIC_MONTHS[cal.get(Calendar.MONTH)]
                val y = cal.get(Calendar.YEAR)
                "$d $m $y"
            }
        }
    }

    fun hasNewDayStarted(lastTimestamp: Long): Boolean {
        if (lastTimestamp == 0L) return true
        val last = Calendar.getInstance().apply { timeInMillis = lastTimestamp }
        val now = Calendar.getInstance()
        return last.get(Calendar.DAY_OF_YEAR) != now.get(Calendar.DAY_OF_YEAR) ||
               last.get(Calendar.YEAR) != now.get(Calendar.YEAR)
    }
}
