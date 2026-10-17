package noline.zhouzhou.ui.screens.bills

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import noline.zhouzhou.data.model.BillBudget
import noline.zhouzhou.data.model.BillKind
import noline.zhouzhou.data.model.parseBillBudgetText
import noline.zhouzhou.ui.theme.LocalAccent
import noline.zhouzhou.ui.theme.LocalNoLine

/**
 * 设预算。它是本机的一个数，不发给站点，所以这里没有任何「同步」的话——
 * 存完立刻生效：记账页的进度条和常驻通知那句超支提醒都读它。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillBudgetSheet(
    initial: BillBudget,
    currency: String,
    onDismiss: () -> Unit,
    onSave: (BillBudget) -> Unit
) {
    val t = LocalNoLine.current
    var monthly by remember { mutableStateOf(budgetText(initial.monthly)) }
    var perKind by remember { mutableStateOf(initial.perKind.mapValues { budgetText(it.value) }) }
    var showKinds by remember { mutableStateOf(initial.perKind.isNotEmpty()) }
    var localError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                // 分类预算展开后这页比屏幕还高，滚到底时「存下」会压在全面屏手势条底下点不动。
                .navigationBarsPadding()
        ) {
            Text("每月买菜预算", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
            Spacer(Modifier.height(4.dp))
            Text(
                "只存在这台手机里。超了会在常驻通知上提醒一句，不响",
                fontSize = 11.5.sp,
                color = t.textLight
            )

            Spacer(Modifier.height(14.dp))
            Text("总预算（${currencyLabel(currency)}）", fontSize = 12.5.sp, color = t.textSecondary)
            Spacer(Modifier.height(7.dp))
            BillField(
                value = monthly,
                onValueChange = {
                    if (it.length <= 10) {
                        monthly = it
                        localError = null
                    }
                },
                placeholder = "比如 800，留空就是不设预算",
                keyboardType = KeyboardType.Decimal,
                error = localError
            )

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (showKinds) "收起分类预算" else "也给某几类单独设额度",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = LocalAccent.current,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { showKinds = !showKinds }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            if (showKinds) {
                Spacer(Modifier.height(6.dp))
                BillKind.Codes.forEach { code ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text(
                            BillKind.label(code),
                            fontSize = 12.5.sp,
                            color = t.textMain,
                            modifier = Modifier.width(64.dp)
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            BillField(
                                value = perKind[code].orEmpty(),
                                onValueChange = {
                                    if (it.length <= 10) {
                                        perKind = perKind + (code to it)
                                        localError = null
                                    }
                                },
                                placeholder = "不设",
                                keyboardType = KeyboardType.Decimal,
                                error = null
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "分类额度只用来判断「哪一类超了」，加起来不必等于总预算",
                    fontSize = 10.5.sp,
                    color = t.textLight,
                    lineHeight = 16.sp
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "取消",
                    fontSize = 14.sp,
                    color = t.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 20.dp, vertical = 9.dp)
                )
                Text(
                    "存下",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(LocalAccent.current)
                        .clickable {
                            val m = parseBillBudgetText(monthly)
                            val kinds = perKind.mapValues { (_, text) -> parseBillBudgetText(text) }
                            localError = when {
                                m == null -> "总预算得是个数字，或者留空"
                                kinds.values.any { it == null } -> "分类预算得是个数字，或者留空"
                                else -> null
                            }
                            if (localError == null) {
                                onSave(
                                    BillBudget(
                                        monthly = m ?: 0.0,
                                        perKind = perKind.mapNotNull { (code, text) ->
                                            parseBillBudgetText(text)?.takeIf { v -> v > 0 }?.let { code to it }
                                        }.toMap()
                                    ).normalized()
                                )
                            }
                        }
                        .padding(horizontal = 22.dp, vertical = 9.dp)
                )
            }
        }
    }
}

/** 输入框里不带一串零：66.50 保持两位，66 就写 66，0（= 不设）干脆留空。 */
private fun budgetText(value: Double): String = when {
    value <= 0.0 -> ""
    value % 1.0 == 0.0 -> value.toInt().toString()
    else -> "%.2f".format(value)
}

private fun currencyLabel(currency: String): String {
    val symbol = symbolOf(currency)
    return when {
        symbol.isEmpty() -> currency
        currency.uppercase() in listOf("CNY", "RMB") -> "元"
        else -> symbol
    }
}
