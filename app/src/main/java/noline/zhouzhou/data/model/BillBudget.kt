package noline.zhouzhou.data.model

import kotlinx.serialization.Serializable

/**
 * 预算。它是**手机上的偏好**，不是站点数据——NoLine 的记账接口没有预算端点，
 * 所以它不进队列、不参与同步，换台手机得重新设。
 *
 * `monthly` 或某个分类的额度填 0 就是「不设」，超支判断只看设了的那几项。
 */
@Serializable
data class BillBudget(
    val monthly: Double = 0.0,
    val perKind: Map<String, Double> = emptyMap()
) {
    val active: Boolean get() = monthly > 0 || perKind.values.any { it > 0 }

    /** 只有分类额度、没设总额时，卡片不该画一条没有意义的总进度。 */
    val hasMonthly: Boolean get() = monthly > 0

    /** 设了额度的分类，按接口那 8 个代码的顺序。 */
    val limitedKinds: List<String>
        get() = BillKind.Codes.filter { (perKind[it] ?: 0.0) > 0 }

    fun normalized(): BillBudget = BillBudget(
        monthly = if (monthly > 0) roundBillAmount(monthly) else 0.0,
        perKind = BillKind.Codes
            .mapNotNull { code -> perKind[code]?.takeIf { it > 0 }?.let { code to roundBillAmount(it) } }
            .toMap()
    )
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

/** 这个月到目前为止离预算还有多远。金额都由调用方按同一个口径算好传进来。 */
data class BillBudgetStatus(
    val budget: BillBudget = BillBudget(),
    val spent: Double = 0.0,
    val kinds: List<BillKindBudget> = emptyList()
) {
    val active: Boolean get() = budget.active
    val monthlyOver: Boolean get() = budget.hasMonthly && spent - budget.monthly > 0.004
    val monthlyLeft: Double get() = roundBillAmount(budget.monthly - spent)
    val monthlyOverBy: Double get() = if (monthlyOver) roundBillAmount(spent - budget.monthly) else 0.0
    val usedFraction: Float
        get() = if (!budget.hasMonthly) 0f else (spent / budget.monthly).toFloat().coerceIn(0f, 1f)
    val kindsOver: List<BillKindBudget> get() = kinds.filter { it.over }
    val anyOver: Boolean get() = monthlyOver || kindsOver.isNotEmpty()

    /**
     * 常驻通知上那句超支提醒。没超就返回空串——「还剩很多」不是要打扰人的事。
     * 只给当月用（文案里写着「本月」）。
     */
    fun alert(): String {
        if (!active) return ""
        val parts = mutableListOf<String>()
        if (monthlyOver) parts += "本月买菜超预算 ${billAmountText(monthlyOverBy)} 元"
        val over = kindsOver
        if (over.isNotEmpty()) {
            val head = over.take(2).joinToString("、") { "${it.label}超了 ${billAmountText(it.overBy)} 元" }
            parts += head + if (over.size > 2) " 等 ${over.size} 类" else ""
        }
        return parts.joinToString("，")
    }
}

/**
 * 算预算进度。
 *
 * @param kindSpent 各分类已花，代码 -> 金额；没设额度的分类不会出现在结果里。
 */
fun billBudgetStatus(
    budget: BillBudget,
    spent: Double,
    kindSpent: Map<String, Double>
): BillBudgetStatus = BillBudgetStatus(
    budget = budget,
    spent = roundBillAmount(spent),
    kinds = budget.limitedKinds.map { code ->
        BillKindBudget(code, roundBillAmount(kindSpent[code] ?: 0.0), budget.perKind[code] ?: 0.0)
    }
)

/** 预算的输入门槛：留空=不设，数字，0 ~ 100 万。越界没必要发请求，虽然它根本不发。 */
fun parseBillBudgetText(text: String): Double? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0.0
    val value = trimmed.toDoubleOrNull() ?: return null
    if (value < 0 || value > 1_000_000) return null
    return roundBillAmount(value)
}
