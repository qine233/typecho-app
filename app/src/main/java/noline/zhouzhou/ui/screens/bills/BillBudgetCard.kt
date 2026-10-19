package noline.zhouzhou.ui.screens.bills

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * 预算进度卡。概览页按预算自己设的周期用它，月报页只在周期是「每月」时用它——
 * 「每周 200」拿去量一个月没有意义，那种月份宁可什么都不显示。
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
            Spacer(Modifier.width(7.dp))
            // 周期做成小药丸：换档之后这里就是唯一说清「这是每周还是每月」的地方。
            Text(
                status.budget.periodLabel,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                color = accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(accent.copy(alpha = 0.12f))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${subject}对照",
                fontSize = 11.5.sp,
                color = t.textSecondary,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (status.active) "改" else "设预算",
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

        val elapsed = if (status.partialBase) null else status.elapsed?.fraction
        if (status.budget.hasLimit) {
            Spacer(Modifier.height(10.dp))
            BudgetBar(
                label = "${subject}已花",
                spentText = moneyText(status.spent, currency),
                limitText = moneyText(status.budget.limit, currency),
                fraction = status.usedFraction,
                over = status.over,
                elapsed = elapsed,
                tail = if (status.over) "超了 ${moneyText(status.overBy, currency)}"
                else "还剩 ${moneyText(status.left, currency)}"
            )
        }

        status.kinds.forEach { kind ->
            Spacer(Modifier.height(if (status.budget.hasLimit) 9.dp else 10.dp))
            BudgetBar(
                label = kind.label,
                spentText = moneyText(kind.spent, currency),
                limitText = moneyText(kind.limit, currency),
                fraction = kind.usedFraction,
                over = kind.over,
                elapsed = elapsed,
                tail = if (kind.over) "超了 ${moneyText(kind.overBy, currency)}"
                else "${(kind.usedFraction * 100).roundToInt()}%"
            )
        }

        Spacer(Modifier.height(9.dp))
        val note = when {
            status.partialBase -> "概览还是上次刷新的那份，进度按近几天的流水算，多半偏少"
            !status.budget.kindsVisible && status.budget.limitedKinds.isNotEmpty() ->
                "分类额度只在「每月」档才有进度，换回每月就能看"
            status.anyOver -> "超支的那几类会在通知里点名，只提醒、不响"
            elapsed != null -> "条上的竖线标的是这一期过了多少 · ${status.elapsed?.label}"
            else -> "还没超，这里只显示进度"
        }
        Text(
            note,
            fontSize = 10.5.sp,
            color = if (status.partialBase || status.anyOver) MaterialTheme.colorScheme.error else t.textLight,
            lineHeight = 16.sp
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
    tail: String,
    elapsed: Float? = null
) {
    val t = LocalNoLine.current
    val accent = LocalAccent.current
    // 记完一笔条子从零涨过去，比直接跳到位好读；涨的过程也让人确认「就是刚才那笔」。
    val grown by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(durationMillis = 480))
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, fontSize = 11.5.sp, color = t.textSecondary, modifier = Modifier.width(64.dp))
            Text(
                spentText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                style = moneyStyle(),
                color = if (over) MaterialTheme.colorScheme.error else t.textStrong
            )
            Text(
                " / $limitText",
                fontSize = 11.5.sp,
                color = t.textSecondary,
                style = moneyStyle(),
                modifier = Modifier.padding(bottom = 1.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                tail,
                fontSize = 11.5.sp,
                fontWeight = if (over) FontWeight.Medium else FontWeight.Normal,
                style = moneyStyle(),
                color = if (over) MaterialTheme.colorScheme.error else t.textSecondary,
                textAlign = TextAlign.End
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(t.surfaceSunken)
        ) {
            // 超支不画「爆表」的长条：条子停在最右，靠颜色说话。
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(grown)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (over) MaterialTheme.colorScheme.error else accent.copy(alpha = 0.8f))
            )
            // 时间参考线：钱在线左边就是比时间慢着花，光看「用掉 60%」说明不了任何事。
            // 线落在已花的那段上就得用亮的，落在空的那段上用暗的，否则整条看不见。
            elapsed?.let {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(it.coerceIn(0.02f, 1f))
                        .fillMaxHeight()
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(
                                if (it <= grown) Color.White.copy(alpha = 0.9f)
                                else t.textStrong.copy(alpha = 0.4f)
                            )
                    )
                }
            }
        }
    }
}
