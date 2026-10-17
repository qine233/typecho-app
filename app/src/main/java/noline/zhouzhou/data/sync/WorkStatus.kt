package noline.zhouzhou.data.sync

/**
 * 常驻通知要显示的那点状态。单独拆出来是因为这段逻辑得能在 JVM 里断言：
 * 「该说什么」比「怎么发通知」更容易写错，而通知本身测不了。
 */
data class WorkStatus(
    val autoSyncOn: Boolean = true,
    val pendingHealth: Int = 0,
    val pendingBills: Int = 0,
    val drafts: Int = 0,
    val lastFlushAt: Long = 0L,
    val lastFlushNote: String = "",
    /** 本月超支的那句话，由记账那边算好传进来；空串表示没设预算或还没超。 */
    val budgetAlert: String = ""
) {
    val pendingTotal: Int get() = pendingHealth + pendingBills

    /** 待同步排在最前——那是唯一能在这条通知里被解决掉的事。 */
    fun headline(): String = when {
        pendingTotal > 0 -> "待同步 $pendingTotal 条"
        budgetAlert.isNotBlank() -> budgetAlert
        drafts > 0 -> "本地草稿 $drafts 份"
        else -> "没有待办"
    }

    /** 超支那条点开要看的是记账页；其余情况落在「数据与存储」，那里是队列的唯一出口。 */
    fun targetLink(): String =
        if (budgetAlert.isNotBlank() && pendingTotal == 0) StatusNotifier.DEEP_LINK_BILLS else StatusNotifier.DEEP_LINK

    /** [timeText] 由调用方格式化好传进来，这里不碰时区和 locale。 */
    fun detail(timeText: String): String {
        val parts = buildList {
            if (!autoSyncOn) add("后台补交已关")
            else if (pendingTotal > 0) add("等有网自动交")
            // 超支那句话已经被当成标题时，正文别再念一遍。
            if (budgetAlert.isNotBlank() && pendingTotal > 0) add(budgetAlert)
            if (pendingTotal == 0 && drafts > 0) add("草稿要在 App 里处理")
            if (lastFlushAt > 0L) add(if (lastFlushNote.isBlank()) "上次补交 $timeText" else "上次补交 $timeText · $lastFlushNote")
        }
        return parts.joinToString(" · ").ifBlank {
            if (budgetAlert.isNotBlank()) "点开看看这个月哪类超了" else "队列是空的"
        }
    }
}
