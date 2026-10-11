package noline.zhouzhou.ui.screens.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import noline.zhouzhou.data.model.HealthProfile
import noline.zhouzhou.data.model.HealthRecord
import noline.zhouzhou.data.repository.RecordPreflight
import noline.zhouzhou.ui.theme.LocalAccent

/**
 * 体重折线。画法对齐网页版 noline_health_trend()：
 * x 轴按真实日期线性分布（断档会拉开，不伪装等距），y 轴上下界贴着数据极值，
 * 极差小于 2kg 时按中值 ±1 撑开，否则一条水平线看着像坏了。
 */
@Composable
fun WeightTrendChart(
    series: List<HealthRecord>,
    modifier: Modifier = Modifier,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val accent = LocalAccent.current

    when {
        series.isEmpty() -> EmptyChartHint(labelColor)
        series.size == 1 -> SinglePointChart(series.first(), accent, labelColor)
        else -> {
            val points = rememberPoints(series)
            val days = points.map { it.day }
            val weights = points.map { it.weight }
            val minDay = days.min().toDouble()
            val maxDay = days.max().toDouble()
            var low = weights.min()
            var high = weights.max()
            if (high - low < 2.0) {
                val mid = (high + low) / 2
                low = mid - 1
                high = mid + 1
            }

            Column(modifier) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(168.dp)
                ) {
                    val padTop = 14.dp.toPx()
                    val padBottom = 26.dp.toPx()
                    val padSide = 8.dp.toPx()
                    val plotH = size.height - padTop - padBottom
                    val plotW = size.width - padSide * 2
                    val spanDay = (maxDay - minDay).coerceAtLeast(1.0)

                    fun xOf(day: Long) = padSide + ((day - minDay) / spanDay * plotW).toFloat()
                    fun yOf(weight: Double) =
                        padTop + (1f - ((weight - low) / (high - low)).toFloat()) * plotH

                    // 上下界参考线
                    drawLine(
                        color = labelColor.copy(alpha = 0.18f),
                        start = Offset(padSide, padTop),
                        end = Offset(padSide + plotW, padTop),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
                    )
                    drawLine(
                        color = labelColor.copy(alpha = 0.18f),
                        start = Offset(padSide, padTop + plotH),
                        end = Offset(padSide + plotW, padTop + plotH),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
                    )

                    val line = Path()
                    points.forEachIndexed { i, p ->
                        val x = xOf(p.day)
                        val y = yOf(p.weight)
                        if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
                    }
                    val area = Path().apply {
                        addPath(line)
                        lineTo(xOf(points.last().day), padTop + plotH)
                        lineTo(xOf(points.first().day), padTop + plotH)
                        close()
                    }
                    drawPath(
                        area,
                        Brush.verticalGradient(
                            listOf(accent.copy(alpha = 0.20f), accent.copy(alpha = 0.02f))
                        )
                    )
                    drawPath(
                        line,
                        accent,
                        style = Stroke(width = 2.2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    )

                    if (points.size <= 60) {
                        points.forEach { p ->
                            drawCircle(accent, radius = 2.4.dp.toPx(), center = Offset(xOf(p.day), yOf(p.weight)))
                        }
                    }
                    val last = points.last()
                    drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(xOf(last.day), yOf(last.weight)))
                    drawCircle(accent, radius = 3.4.dp.toPx(), center = Offset(xOf(last.day), yOf(last.weight)))
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${series.first().date.drop(5)} · ${"%.1f".format(weights.min())}kg",
                        fontSize = 11.sp,
                        color = labelColor
                    )
                    Text(
                        "${series.last().date.drop(5)} · ${"%.1f".format(weights.max())}kg",
                        fontSize = 11.sp,
                        color = labelColor
                    )
                }
            }
        }
    }
}

private data class ChartPoint(val day: Long, val weight: Double)

/** 点数过多时按序号等距抽样，但最后一点一定保留——那才是「现在」。 */
@Composable
private fun rememberPoints(series: List<HealthRecord>): List<ChartPoint> =
    androidx.compose.runtime.remember(series) {
        val raw = series.mapNotNull { rec ->
            val day = RecordPreflight.parseDate(rec.date)?.toEpochDay() ?: return@mapNotNull null
            ChartPoint(day, rec.weight)
        }
        if (raw.size <= 180) raw
        else {
            val step = raw.size / 180 + 1
            raw.filterIndexed { i, _ -> i % step == 0 } + raw.last()
        }
    }

@Composable
private fun EmptyChartHint(labelColor: Color) {
    Box(
        modifier = Modifier.fillMaxWidth().height(168.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("还没有记录，画出曲线的第一步从今早的体重开始", fontSize = 12.5.sp, color = labelColor)
    }
}

@Composable
private fun SinglePointChart(record: HealthRecord, accent: Color, labelColor: Color) {
    Column(
        modifier = Modifier.fillMaxWidth().height(168.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(modifier = Modifier.size(46.dp)) {
            drawCircle(accent.copy(alpha = 0.14f), radius = size.minDimension / 2)
            drawCircle(accent, radius = 5.dp.toPx())
        }
        Spacer(Modifier.height(8.dp))
        Text("${"%.1f".format(record.weight)} kg · ${record.date}", fontSize = 12.5.sp, color = labelColor)
        Spacer(Modifier.height(2.dp))
        Text("再记一条就能看到趋势", fontSize = 11.5.sp, color = labelColor.copy(alpha = 0.7f))
    }
}

/** 概览页顶部那张大卡：当前体重 + 涨跌 + BMI 分档。 */
@Composable
fun WeightHeroCard(record: HealthRecord?, profile: HealthProfile) {
    val accent = LocalAccent.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                record?.let { "%.1f".format(it.weight) } ?: "—",
                fontSize = 46.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "kg",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 9.dp)
            )
            Spacer(Modifier.width(12.dp))
            record?.let {
                Text(
                    it.deltaLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (it.delta != null && it.delta < 0) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 9.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                record?.bmi == null -> Text(
                    "先在博客后台填身高，才能算 BMI",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    Text(
                        "BMI ${"%.1f".format(record.bmi)}",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    val label = record.categoryLabel.ifBlank { "—" }
                    Text(
                        label,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier
                            .padding(horizontal = 0.dp)
                            .background(
                                categoryColor(record.category, accent),
                                androidx.compose.foundation.shape.RoundedCornerShape(999.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

private fun categoryColor(category: String, accent: Color): Color = when (category) {
    "thin" -> Color(0xFF7BAFD4)
    "normal" -> Color(0xFF6FA871)
    "over" -> Color(0xFFD9A441)
    "obese" -> Color(0xFFCC6B5B)
    else -> accent
}
