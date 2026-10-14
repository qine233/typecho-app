package noline.zhouzhou.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * NoLine 买菜记账接口（Typecho 插件）的数据结构。
 * 接口事实来源：APP-API-SPEC.md §4.8–§4.12、§5.6–§5.9。
 */

/** 一条流水。PUT / DELETE 全靠 `id`，而 `id` 不能拿来推日期（改过日期的记录 id 不变）。 */
@Serializable
data class BillRecord(
    val id: String = "",
    val date: String = "",
    val title: String = "",
    val amount: Double = 0.0,
    val kind: String = "other",
    val shop: String = "",
    val note: String = "",
    val time: Long = 0L,
    /** `0` = 从没改过，不是「缺这个字段」。 */
    val updated: Long = 0L
) {
    val kindLabel: String get() = BillKind.label(kind)
}

/** 记账的 8 个分类。代码是稳定的，中文名由 App 自己出（`data.kinds` 里的 label 是站点语言）。 */
object BillKind {
    val Codes = listOf("veg", "fruit", "meat", "fish", "egg", "grain", "seasoning", "other")

    fun label(code: String): String = when (code) {
        "veg" -> "蔬菜"
        "fruit" -> "水果"
        "meat" -> "肉禽"
        "fish" -> "水产"
        "egg" -> "蛋奶"
        "grain" -> "米面粮油"
        "seasoning" -> "调料"
        else -> "其他"
    }

    /** 服务器把不认识的代码静默归到 other，所以选择器只能给这 8 个。 */
    fun normalize(code: String): String = if (code in Codes) code else "other"
}

/** POST 的请求体；可选字段为 null 时整个键不发送。 */
@Serializable
data class BillInput(
    val date: String,
    val title: String,
    val amount: Double,
    val kind: String? = null,
    val shop: String? = null,
    val note: String? = null
)

/** PUT 的请求体：整条重校验，没带的键沿用服务器原值，传 `""` 是清空。 */
@Serializable
data class BillUpdate(
    val id: String,
    val date: String,
    val title: String,
    val amount: Double,
    val kind: String,
    val shop: String,
    val note: String
)

@Serializable
data class BillMax(
    val date: String = "",
    val title: String = "",
    val amount: Double = 0.0
)

/**
 * `totals` / `week` / `prev_week` / `month` / `prev_month` / `year` 在一条记录都没有时
 * **整个对象是 null**，不是「金额是 0」（Bills.php 的空数据分支里显式写 null）；
 * `max` 在区间为空时同样是 null。有数据时它们永远是带键的对象，不会是 `[]`。
 */
@Serializable
data class BillAggregate(
    val start: String = "",
    val end: String = "",
    val amount: Double = 0.0,
    val count: Int = 0,
    /** 有开销的天数，不是区间天数。 */
    val days: Int = 0,
    /** amount / days，即「每个开了火的日子花多少」，不是除以日历天数。 */
    @SerialName("avg_day") val avgDay: Double = 0.0,
    @SerialName("avg_bill") val avgBill: Double = 0.0,
    val max: BillMax? = null
)

/** 近 7 个月（含本月）的柱子，没记录的月份补零出现，不会缺项。 */
@Serializable
data class BillMonthStat(
    val month: String = "",
    val amount: Double = 0.0,
    val count: Int = 0
)

/** 本月分类占比，按 amount 降序；`share` 是 0–1 的小数。 */
@Serializable
data class BillKindStat(
    val kind: String = "",
    val amount: Double = 0.0,
    val count: Int = 0,
    val share: Double = 0.0
)

@Serializable
data class BillKindEntry(val kind: String = "", val label: String = "")

/** 最近 12 个「有记录的日子」的流水分组，新的在前，组内按录入时间倒序。 */
@Serializable
data class BillDayGroup(
    val date: String = "",
    val amount: Double = 0.0,
    val count: Int = 0,
    val items: List<BillRecord> = emptyList()
)

/** 时间口径全部由服务器算好，一周从周一起算，都是服务器时区。 */
@Serializable
data class BillSpan(
    val today: String = "",
    /** ISO：周一 = 1 … 周日 = 7。 */
    val weekday: Int = 1,
    @SerialName("week_start") val weekStart: String = "",
    @SerialName("week_end") val weekEnd: String = "",
    @SerialName("prev_week_start") val prevWeekStart: String = "",
    @SerialName("prev_week_end") val prevWeekEnd: String = "",
    @SerialName("month_start") val monthStart: String = "",
    @SerialName("month_end") val monthEnd: String = "",
    @SerialName("prev_month_start") val prevMonthStart: String = "",
    @SerialName("prev_month_end") val prevMonthEnd: String = "",
    @SerialName("year_start") val yearStart: String = "",
    @SerialName("year_end") val yearEnd: String = "",
    @SerialName("month_days") val monthDays: Int = 30,
    @SerialName("month_day_of") val monthDayOf: Int = 1
) {
    /** 本周已经过了几天，用来给「本周 vs 上周」标注部分区间（3/7 天）。 */
    val weekFraction: String get() = "${weekday.coerceIn(1, 7)}/7 天"
    val monthFraction: String get() = "${monthDayOf.coerceAtLeast(1)}/$monthDays 天"
}

@Serializable
data class BillsOverview(
    val count: Int = 0,
    val currency: String = "CNY",
    val first: String? = null,
    val last: String? = null,
    /** 分类字典，永远是全的 8 项（画选择器用它，别用 kindsMonth）。 */
    val kinds: List<BillKindEntry> = BillKind.Codes.map { BillKindEntry(it, BillKind.label(it)) },
    val totals: BillAggregate? = null,
    val week: BillAggregate? = null,
    @SerialName("prev_week") val prevWeek: BillAggregate? = null,
    val month: BillAggregate? = null,
    @SerialName("prev_month") val prevMonth: BillAggregate? = null,
    val year: BillAggregate? = null,
    val months: List<BillMonthStat> = emptyList(),
    @SerialName("kinds_month") val kindsMonth: List<BillKindStat> = emptyList(),
    @SerialName("by_date") val byDate: List<BillDayGroup> = emptyList(),
    val recent: List<BillRecord> = emptyList(),
    val span: BillSpan = BillSpan()
)

/** 本月的分类占比，按 App 自己的文案出标签。 */
val BillsOverview.kindsMonthWithLabels: List<Pair<BillKindStat, String>>
    get() = kindsMonth.map { it to BillKind.label(it.kind) }

@Serializable
data class BillRecordsPage(
    val total: Int = 0,
    /** 本次返回这 N 条的合计；limit 截断时也只算截断后的部分。 */
    val amount: Double = 0.0,
    val records: List<BillRecord> = emptyList()
)

@Serializable
data class BillSaveResult(
    val saved: Int = 0,
    /** 服务器上现有流水总条数，不是本次的。 */
    val total: Int = 0,
    /** 新分配的 id，顺序和通过校验的记录一一对应；没认领到 id 的本地记录改不了也删不了。 */
    val ids: List<String> = emptyList(),
    /** 被拒条目的**品名**（截到 20 字），无品名的填 `(无品名)`。 */
    val invalid: List<String> = emptyList()
)

@Serializable
data class BillUpdateResult(
    val record: BillRecord? = null,
    val total: Int = 0
)

@Serializable
data class BillDeleteResult(val deleted: String = "", val total: Int = 0)

/** 金额统一 2 位小数显示；币种符号按接口给的 `currency` 走，接口目前只回 CNY。 */
fun billAmountText(amount: Double): String = "%.2f".format(amount)
