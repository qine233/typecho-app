package noline.zhouzhou.ui.screens.bills

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import noline.zhouzhou.data.model.BillBudgetStatus
import noline.zhouzhou.ui.theme.LocalAccent
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.noLineCard
import kotlin.math.roundToInt

/**
 * 预算进度卡。概览页在「本月」下面用它，月报页对任意月份用它——
 * 预算本来就是「每月同一个数」，拿来量过去的哪个月都说得通。
 */
@Composable
internal fun BudgetCard(
    status: BillBudgetStatus,
    currency: String,
    subject: String,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalNoLine.current
    val accent = LocalAccent.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .noLineCard(RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("预算", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.textStrong)
            Spacer(Modifier.width(8.dp))
            Text(
                "${subject}对照",
                fontSize = 11.5.sp,
                color = t.textSecondary,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (status.active) "改" else "设个月预算",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onEdit)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        if (!status.active) {
            Spacer(Modifier.height(6.dp))
            Text(
                "设一个数，超了会在常驻通知上提醒一句；不设的话这里什么都不显示。",
                fontSize = 11.5.sp,
                color = t.textSecondary,
                lineHeight = 17.sp
            )
            return@Column
        }

        if (status.budget.hasMonthly) {
            Spacer(Modifier.height(10.dp))
            BudgetBar(
                label = "${subject}已花",
                spentText = moneyText(status.spent, currency),
                limitText = moneyText(status.budget.monthly, currency),
                fraction = status.usedFraction,
                over = status.monthlyOver,
                tail = if (status.monthlyOver) "超了 ${moneyText(status.monthlyOverBy, currency)}"
                else "还剩 ${moneyText(status.monthlyLeft, currency)}"
            )
        }

        status.kinds.forEach { kind ->
            Spacer(Modifier.height(if (status.budget.hasMonthly) 9.dp else 10.dp))
            BudgetBar(
                label = kind.label,
                spentText = moneyText(kind.spent, currency),
                limitText = moneyText(kind.limit, currency),
                fraction = kind.usedFraction,
                over = kind.over,
                tail = if (kind.over) "超了 ${moneyText(kind.overBy, currency)}"
                else "${(kind.usedFraction * 100).roundToInt()}%"
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            if (status.anyOver) "超支的那几类会在通知里点名，只提醒、不响"
            else "还没超，这里只显示进度",
            fontSize = 10.5.sp,
            color = t.textLight
        )
    }
}

@Composable
private fun BudgetBar(
    label: String,
    spentText: String,
    limitText: String,
    fraction: Float,
    over: Boolean,
    tail: String
) {
    val t = LocalNoLine.current
    val accent = LocalAccent.current
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, fontSize = 11.5.sp, color = t.textSecondary, modifier = Modifier.width(64.dp))
            Text(
                spentText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (over) MaterialTheme.colorScheme.error else t.textStrong
            )
            Text(
                " / $limitText",
                fontSize = 11.5.sp,
                color = t.textSecondary,
                modifier = Modifier.padding(bottom = 1.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                tail,
                fontSize = 11.5.sp,
                fontWeight = if (over) FontWeight.Medium else FontWeight.Normal,
                color = if (over) MaterialTheme.colorScheme.error else t.textSecondary,
                textAlign = TextAlign.End
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(t.surfaceSunken)
        ) {
            // 超支不画「爆表」的长条：条子停在最右，靠颜色说话。
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(7.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (over) MaterialTheme.colorScheme.error else accent.copy(alpha = 0.8f))
            )
        }
    }
}
