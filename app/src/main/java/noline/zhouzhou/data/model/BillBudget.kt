package noline.zhouzhou.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 按名字存，但认不出来的名字退回「每月」而不是报错。
 *
 * 默认那个枚举序列化器遇到未知值会整个文件解码失败，而预算文件解码失败等于
 * 「没设预算」——新版本加了档位、老版本再读到，用户看到的是预算凭空没了。
 */
private object BillBudgetPeriodSerializer : KSerializer<BillBudgetPeriod> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BillBudgetPeriod", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BillBudgetPeriod) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): BillBudgetPeriod = BillBudgetPeriod.of(decoder.decodeString())
}

/**
 * 预算周期。选的是**自然周期**，不是「每月 5 号起」那种自定义区间——
 * 站点的概览接口只按周一 / 月初 / 年初切好三格（`week` / `month` / `year`），
 * 要自定义就得自己按日拉流水汇总，代价和收益不成比例。
 *
 * `subject` 是界面上对这一期的称呼，进度条、通知、桌面卡片共用它，别各写各的。
 */
enum class BillBudgetPeriod(val label: String, val subject: String) {
    Week("每周", "本周"),
    Month("每月", "本月"),
    Year("每年", "本年");

    companion object {
        /** 老数据里根本没有这个键，认不出来的一律当「每月」——那是它以前的唯一口径。 */
        fun of(name: String?): BillBudgetPeriod = entries.firstOrNull { it.name == name } ?: Month
    }
}

/**
 * 预算。它是**手机上的偏好**，不是站点数据——NoLine 的记账接口没有预算端点，
 * 所以它不进队列、不参与同步，换台手机得重新设。
 *
 * `limit`（存进文件的键仍叫 `monthly`，改了会让老数据掉成「没设预算」）或某个分类的
 * 额度填 0 就是「不设」，超支判断只看设了的那几项。
 */
@Serializable
data class BillBudget(
    @Serializable(with = BillBudgetPeriodSerializer::class)
    val period: BillBudgetPeriod = BillBudgetPeriod.Month,
    @SerialName("monthly") val limit: Double = 0.0,
    val perKind: Map<String, Double> = emptyMap()
) {
    val active: Boolean get() = limit > 0 || perKind.values.any { it > 0 }

    /** 只有分类额度、没设总额时，卡片不该画一条没有意义的总进度。 */
    val hasLimit: Boolean get() = limit > 0

    val subject: String get() = period.subject
    val periodLabel: String get() = period.label

    /** 设了额度的分类，按接口那 8 个代码的顺序。 */
    val limitedKinds: List<String>
        get() = BillKind.Codes.filter { (perKind[it] ?: 0.0) > 0 }

    /**
     * 分类进度只在「每月」这一档算得出来：概览接口只汇总本月的分类
     * （`kinds_month`），本周 / 本年想要分类明细就得整月整年地翻流水。
     * 所以额度照旧存着，只是换档时不画那条会骗人的进度。
     */
    val kindsVisible: Boolean get() = period == BillBudgetPeriod.Month

    fun normalized(): BillBudget = BillBudget(
        period = period,
        limit = if (limit > 0) roundBillAmount(limit) else 0.0,
        perKind = BillKind.Codes
            .mapNotNull { code -> perKind[code]?.takeIf { it > 0 }?.let { code to roundBillAmount(it) } }
            .toMap()
    )
}

/** 这一期过了多少天。进度条上的参考线和「18/31 天」那句文案共用它。 */
data class BillPeriodProgress(val goneDays: Int = 0, val totalDays: Int = 0) {
    val fraction: Float get() = if (totalDays <= 0) 0f else (goneDays.toFloat() / totalDays.toFloat()).coerceIn(0f, 1f)
    val label: String get() = "$goneDays/$totalDays 天"
}

/** 一个分类的预算进度。 */
data class BillKindBudget(
    val kind: String = "",
    val spent: Double = 0.0,
    val limit: Double = 0.0
) {
    val label: String get() = BillKind.label(kind)
    val over: Boolean get() = limit > 0 && spent - limit > 0.004
    val overBy: Double get() = if (over) roundBillAmount(spent - limit) else 0.0
    val usedFraction: Float
        get() = if (limit <= 0) 0f else (spent / limit).toFloat().coerceIn(0f, 1f)
}

/** 这一期到目前为止离预算还有多远。金额都由调用方按同一个口径算好传进来。 */
data class BillBudgetStatus(
    val budget: BillBudget = BillBudget(),
    val spent: Double = 0.0,
    val kinds: List<BillKindBudget> = emptyList(),
    /** 这一期过了多少；换周期、翻月报都跟着走，只有当期算得出来。 */
    val elapsed: BillPeriodProgress? = null,
    /**
     * 底数不是这一期的完整流水（缓存的概览还是上个周期抓的，只能退回近几日那几笔）。
     * 这时候的金额只会**偏少**，卡片必须说清楚，不能画一条看着很省的进度。
     */
    val partialBase: Boolean = false
) {
    val active: Boolean get() = budget.active
    val over: Boolean get() = budget.hasLimit && spent - budget.limit > 0.004
    val left: Double get() = roundBillAmount(budget.limit - spent)
    val overBy: Double get() = if (over) roundBillAmount(spent - budget.limit) else 0.0
    val usedFraction: Float
        get() = if (!budget.hasLimit) 0f else (spent / budget.limit).toFloat().coerceIn(0f, 1f)
    val kindsOver: List<BillKindBudget> get() = kinds.filter { it.over }
    val anyOver: Boolean get() = over || kindsOver.isNotEmpty()

    /**
     * 常驻通知上那句超支提醒。没超就返回空串——「还剩很多」不是要打扰人的事。
     * 只给当期用，「本」字头由 budget.subject 带出来，换周期这句跟着变。
     */
    fun alert(): String {
        if (!active) return ""
        val parts = mutableListOf<String>()
        if (over) parts += "${budget.subject}买菜超预算 ${billAmountText(overBy)} 元"
        val overKinds = kindsOver
        if (overKinds.isNotEmpty()) {
            val head = overKinds.take(2).joinToString("、") { "${it.label}超了 ${billAmountText(it.overBy)} 元" }
            parts += head + if (overKinds.size > 2) " 等 ${overKinds.size} 类" else ""
        }
        return parts.joinToString("，")
    }
}

/**
 * 算预算进度。
 *
 * @param kindSpent 各分类已花，代码 -> 金额；没设额度的分类不会出现在结果里。
 *   周期不是「每月」时传进来的分类金额一律忽略——见 [BillBudget.kindsVisible]。
 */
fun billBudgetStatus(
    budget: BillBudget,
    spent: Double,
    kindSpent: Map<String, Double>,
    elapsed: BillPeriodProgress? = null,
    partialBase: Boolean = false
): BillBudgetStatus = BillBudgetStatus(
    budget = budget,
    spent = roundBillAmount(spent),
    kinds = if (!budget.kindsVisible) emptyList() else budget.limitedKinds.map { code ->
        BillKindBudget(code, roundBillAmount(kindSpent[code] ?: 0.0), budget.perKind[code] ?: 0.0)
    },
    elapsed = elapsed,
    partialBase = partialBase
)

/** 预算的输入门槛：留空=不设，数字，0 ~ 100 万。越界没必要发请求，虽然它根本不发。 */
fun parseBillBudgetText(text: String): Double? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0.0
    val value = trimmed.toDoubleOrNull() ?: return null
    if (value < 0 || value > 1_000_000) return null
    return roundBillAmount(value)
}

/**
 * 这一期的起止日期（含首尾）。一周从周一起算，和概览接口的 `week_start` 口径一致。
 *
 * 区间是客户端按 `today` 现算的，而不是直接抄接口给的 `week_start` / `month_start`：
 * 换周期后「未同步的那几笔落在不在本期里」也要用同一对边界，两处必须是一个来源，
 * 否则进度条和「+N 笔待同步」会各说各话。`today` 一律取接口那句服务器日期。
 */
fun billPeriodRange(period: BillBudgetPeriod, today: String): Pair<String, String> {
    val d = today.toLocalDateOrNone() ?: return "" to ""
    return when (period) {
        BillBudgetPeriod.Week -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString() to
            d.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).toString()
        BillBudgetPeriod.Month -> d.withDayOfMonth(1).toString() to d.withDayOfMonth(d.lengthOfMonth()).toString()
        BillBudgetPeriod.Year -> d.withDayOfYear(1).toString() to d.withDayOfYear(d.lengthOfYear()).toString()
    }
}

/**
 * 这一期已经过了多少。进度条上拿它画一条参考线：
 * 钱花到线左边才是「比时间慢」，光看「用掉了 60%」说明不了任何事。
 *
 * 算不出来就回 null——宁可不画，也不能把「不知道过了几天」画成「刚开头」。
 */
fun billPeriodProgress(budget: BillBudget, today: String): BillPeriodProgress? {
    if (!budget.active) return null
    val (start, end) = billPeriodRange(budget.period, today)
    return billRangeProgress(start, end, today)
}

/** 纯日期字符串版，单测直接喂原文，不用造 BillSpan。含首尾：起始那天就算过了 1 天。 */
fun billRangeProgress(start: String, end: String, today: String): BillPeriodProgress? {
    val s = start.toLocalDateOrNone() ?: return null
    val e = end.toLocalDateOrNone() ?: return null
    val d = today.toLocalDateOrNone() ?: return null
    val total = ChronoUnit.DAYS.between(s, e) + 1
    if (total <= 0) return null
    val gone = ChronoUnit.DAYS.between(s, d.coerceIn(s, e)) + 1
    return BillPeriodProgress(gone.toInt(), total.toInt())
}

/** 这条日期落在不在本期里；边界为空（还没拿到概览）时一律算不在，别把 0 说成超支。 */
fun billDateInPeriod(date: String, range: Pair<String, String>): Boolean {
    val d = date.takeIf { it.length >= 10 } ?: return false
    val (start, end) = range
    if (start.length < 10 || end.length < 10) return false
    return d >= start && d <= end
}

private fun String.toLocalDateOrNone(): LocalDate? =
    takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
