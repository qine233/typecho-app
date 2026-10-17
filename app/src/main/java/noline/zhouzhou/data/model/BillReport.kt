package noline.zhouzhou.data.model

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月报。服务端只给「当月分类占比 + 近 7 个月的柱子」，翻到任意一个月都得自己取明细
 * （`/api/bills/records?from=&to=&limit=0`，limit=0 才是不限条数，默认的 50 只取区间尾部），
 * 所以这里的所有数字都是客户端算出来的。
 */
@Serializable
data class BillMonthReport(
    val month: String = "",
    val amount: Double = 0.0,
    val count: Int = 0,
    /** 开了几天的火，不是这个月的天数。 */
    val days: Int = 0,
    val avgDay: Double = 0.0,
    val avgBill: Double = 0.0,
    val max: BillMax? = null,
    /** 这个月每一天的花费，一天不落；没花钱的日子是 0，不是缺项。 */
    val series: List<BillDayBar> = emptyList(),
    /** 按 amount 降序，`share` 是 0–1 的小数。 */
    val kinds: List<BillKindStat> = emptyList(),
    /** 最贵的几笔单笔。 */
    val topItems: List<BillRecord> = emptyList(),
    val busiestDay: BillDayBar? = null,
    /** 这些数字里含着「还没上传的本地新增」——离线记的那几笔也是花掉的钱。 */
    val includesPending: Boolean = false
) {
    val kindsWithLabels: List<Pair<BillKindStat, String>>
        get() = kinds.map { it to BillKind.label(it.kind) }

    /** 整月一根柱子都没有：明细请求成功，但那个月确实没买菜。 */
    val isEmptyMonth: Boolean get() = count == 0
}

/** 月报里的一格：某一天的合计。 */
@Serializable
data class BillDayBar(val date: String = "", val amount: Double = 0.0, val count: Int = 0)

/** `YYYY-MM`；不合法返回 null，接口和 UI 都靠它兜住脏输入。 */
fun parseBillMonth(month: String): YearMonth? =
    runCatching { YearMonth.parse(month.trim()) }.getOrNull()

/** 明细接口的闭区间。月份不合法返回 null。 */
fun billMonthRange(month: String): Pair<String, String>? {
    val ym = parseBillMonth(month) ?: return null
    return ym.atDay(1).toString() to ym.atEndOfMonth().toString()
}

/** 报告页的上一月/下一月。基准月不合法时退回当前月做锚点。 */
fun billMonthShift(month: String, deltaMonths: Long): String =
    (parseBillMonth(month) ?: YearMonth.now()).plusMonths(deltaMonths).toString()

/** 该月还可以看吗——未来月份没有意义，画出来是一片空。 */
fun billMonthIsFuture(month: String, today: String): Boolean =
    parseBillMonth(month) != null && month > today.take(7)

fun previousBillMonth(month: String): String = billMonthShift(month, -1)

fun roundBillAmount(value: Double): Double = kotlin.math.round(value * 100) / 100

/**
 * 把一个月的流水汇总成月报。
 *
 * @param today 用来把「本月」的横轴截到今天——未来的日子还没发生，画成 0 元是假数据。
 *   默认取系统当天，测试里显式传。
 */
fun buildBillMonthReport(
    month: String,
    records: List<BillRecord>,
    today: String = LocalDate.now().toString(),
    includesPending: Boolean = false,
    topSize: Int = 5
): BillMonthReport {
    val ym = parseBillMonth(month) ?: return BillMonthReport(month = month)
    val rows = records.filter { it.date.length >= 7 && it.date.take(7) == month }

    val amount = roundBillAmount(rows.sumOf { it.amount })
    val byDay = rows.groupBy { it.date }
    val days = byDay.size
    val top = rows
        .sortedWith(compareByDescending<BillRecord> { it.amount }.thenByDescending { it.time })
        .take(topSize)

    val kinds = rows.groupBy { BillKind.normalize(it.kind) }
        .map { (kind, list) ->
            BillKindStat(
                kind = kind,
                amount = roundBillAmount(list.sumOf { it.amount }),
                count = list.size,
                share = if (amount > 0) list.sumOf { it.amount } / amount else 0.0
            )
        }
        .sortedWith(compareByDescending<BillKindStat> { it.amount }.thenByDescending { it.count })

    // 横轴：过去的月画满，本月的截到今天，未来的月一根都没有。
    val lastDay = runCatching { LocalDate.parse(today) }.getOrNull()?.let { t ->
        when {
            month > t.toString().take(7) -> 0
            month == t.toString().take(7) -> t.dayOfMonth
            else -> ym.lengthOfMonth()
        }
    } ?: ym.lengthOfMonth()

    val series = (1..lastDay).map { day ->
        val date = "%s-%02d".format(month, day)
        val list = byDay[date].orEmpty()
        BillDayBar(date, roundBillAmount(list.sumOf { it.amount }), list.size)
    }

    return BillMonthReport(
        month = month,
        amount = amount,
        count = rows.size,
        days = days,
        avgDay = if (days > 0) roundBillAmount(amount / days) else 0.0,
        avgBill = if (rows.isNotEmpty()) roundBillAmount(amount / rows.size) else 0.0,
        max = rows.maxByOrNull { it.amount }?.let { BillMax(it.date, it.title, it.amount) },
        series = series,
        kinds = kinds,
        topItems = top,
        busiestDay = series.maxByOrNull { it.amount }?.takeIf { it.amount > 0 },
        includesPending = includesPending
    )
}
