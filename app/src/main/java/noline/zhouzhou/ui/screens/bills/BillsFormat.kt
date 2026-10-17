package noline.zhouzhou.ui.screens.bills

import noline.zhouzhou.data.model.BillsOverview

/**
 * 记账页共用的文案。概览页和月报页得长成同一张脸，
 * 而月报页手上只有货币代码、没有整个 overview，所以按 currency 字符串收口。
 */

internal fun symbolOf(currency: String): String = when (currency.uppercase()) {
    "CNY", "RMB" -> "¥"
    "USD" -> "$"
    "EUR" -> "€"
    else -> ""
}

internal fun moneyText(amount: Double, currency: String): String {
    val symbol = symbolOf(currency)
    return if (symbol.isEmpty()) "${moneyPlain(amount)} $currency" else "$symbol${moneyPlain(amount)}"
}

internal fun moneyText(amount: Double, overview: BillsOverview): String =
    moneyText(amount, overview.currency)

/** 格子里的短格式：超过一万就别拖一串小数。 */
internal fun moneyShort(amount: Double, currency: String): String {
    val symbol = symbolOf(currency)
    val head = if (amount >= 10000) "%.1f万".format(amount / 10000) else moneyPlain(amount)
    return if (symbol.isEmpty()) head else "$symbol$head"
}

internal fun moneyShort(amount: Double, overview: BillsOverview): String =
    moneyShort(amount, overview.currency)

internal fun moneyPlain(amount: Double): String = "%.2f".format(amount)

/** `2026-10` → `10月`，横轴用。 */
internal fun monthLabel(month: String): String =
    if (month.length >= 7) "${month.takeLast(2).trimStart('0')}月" else month

/** `2026-10` → `2026年10月`，标题用。 */
internal fun monthTitle(month: String): String =
    if (month.length >= 7) "${month.take(4)}年${month.takeLast(2).trimStart('0')}月" else month

internal fun shortDate(date: String): String =
    if (date.length >= 10) "${date.substring(5, 7).trimStart('0')}月${date.substring(8, 10).trimStart('0')}日" else date

/** 某一天的横轴标签：只留「几号」，几十格挤在一起写不出月。 */
internal fun dayLabel(date: String): String =
    if (date.length >= 10) date.substring(8, 10).trimStart('0') else date
