package com.typecho.app.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object DateUtils {

    private val fullFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val dayFormat = SimpleDateFormat("MM-dd", Locale.getDefault())
    private val yearFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun formatDate(timestamp: Long): String {
        if (timestamp == 0L) return ""
        val date = Date(timestamp * 1000)
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { time = date }

        val diffMs = now.timeInMillis - target.timeInMillis
        val diffMinutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
        val diffHours = TimeUnit.MILLISECONDS.toHours(diffMs)
        val diffDays = TimeUnit.MILLISECONDS.toDays(diffMs)

        return when {
            diffMinutes < 1 -> "刚刚"
            diffMinutes < 60 -> "${diffMinutes}分钟前"
            diffHours < 24 -> "${diffHours}小时前"
            diffDays == 1L -> "昨天 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)}"
            diffDays < 7 -> "${diffDays}天前"
            now.get(Calendar.YEAR) == target.get(Calendar.YEAR) -> dayFormat.format(date)
            else -> yearFormat.format(date)
        }
    }

    fun formatFullDate(timestamp: Long): String {
        if (timestamp == 0L) return ""
        return fullFormat.format(Date(timestamp * 1000))
    }

    private val weekdays = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    /** 时间线日期标题，如「2026年10月12日 · 周一」。 */
    fun formatDiaryDay(timestamp: Long): String {
        if (timestamp == 0L) return "日常"
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp * 1000 }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val today = Calendar.getInstance()
        val isToday = year == today.get(Calendar.YEAR) &&
            month == today.get(Calendar.MONTH) + 1 &&
            day == today.get(Calendar.DAY_OF_MONTH)
        val prefix = if (isToday) "今天" else "${year}年${month}月${day}日"
        return "$prefix · ${weekdays[cal.get(Calendar.DAY_OF_WEEK) - 1]}"
    }

    /** 时间线日期标题右侧的短标记，如「10-12」。 */
    fun formatDiaryBadge(timestamp: Long): String {
        if (timestamp == 0L) return ""
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp * 1000 }
        return "%02d月%02d日".format(cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }
}
