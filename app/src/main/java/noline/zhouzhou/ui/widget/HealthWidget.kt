package noline.zhouzhou.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import noline.zhouzhou.MainActivity
import noline.zhouzhou.R
import noline.zhouzhou.TypechoApplication
import noline.zhouzhou.ui.navigation.AppLinks
import noline.zhouzhou.ui.theme.DarkNoLine
import noline.zhouzhou.ui.theme.LightNoLine

/**
 * 桌面上的健康体重卡片：现在多重、比上次轻还是重、离目标还差多少，
 * 大卡底下还有 7/30/90 天各减了多少。点哪儿都是进 App——体重这件事没法代填，
 * 数字得用户自己站在秤上按出来。
 *
 * 和买菜卡一样只读本地缓存，断网也画得出来；规格由 manifest 里的 receiver 决定：
 * [HealthWidgetCompact] 2×2、[HealthWidgetMedium] 3×2、[HealthWidget] 3×3。
 */
class HealthWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) =
        provideHealth(context, id, WidgetSpec.Full)
}

class HealthWidgetMedium : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) =
        provideHealth(context, id, WidgetSpec.Medium)
}

class HealthWidgetCompact : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) =
        provideHealth(context, id, WidgetSpec.Compact)
}

class HealthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HealthWidget()
}

class HealthWidgetMediumReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HealthWidgetMedium()
}

class HealthWidgetCompactReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HealthWidgetCompact()
}

private val AllHealthWidgets: List<GlanceAppWidget> =
    listOf(HealthWidget(), HealthWidgetMedium(), HealthWidgetCompact())

/**
 * 三种规格吃的是同一份本地缓存，所以任何一次刷新都得把桌面上的卡片全部重画——
 * 只点一下当前那张，另一张会停在旧数字上，看上去像称错了。
 */
private suspend fun updateAllHealthWidgets(context: Context) {
    val app = context.applicationContext
    AllHealthWidgets.forEach { runCatching { it.updateAll(app) } }
}

/**
 * 卡片上那颗「刷新」。它只拉数据、不替用户把队列里的记录提交上去——
 * 离线攒下的那几条要等 App 里有网时自己补交，桌面按钮不该有这个权力。
 */
class HealthWidgetRefreshCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val app = context.applicationContext
        val repository = (app as? TypechoApplication)?.container?.healthRepository
        runCatching { withContext(Dispatchers.IO) { repository?.refresh(flushFirst = false) } }
        updateAllHealthWidgets(app)
    }
}

/** 记完一条体重、删完一条之后叫一声，让卡片当场跟上。 */
suspend fun refreshHealthWidget(context: Context) {
    updateAllHealthWidgets(context.applicationContext)
}

private suspend fun GlanceAppWidget.provideHealth(context: Context, id: GlanceId, spec: WidgetSpec) {
    val data = runCatching { loadHealthWidgetData(context, spec) }
        .getOrElse { HealthWidgetData.unavailable() }
    val glass = widgetGlassBackground(context, id)
    provideContent { HealthWidgetContent(spec, data, glass) }
}

private suspend fun loadHealthWidgetData(context: Context, spec: WidgetSpec): HealthWidgetData {
    val repository = (context.applicationContext as? TypechoApplication)?.container?.healthRepository
        ?: return HealthWidgetData.unavailable()
    return withContext(Dispatchers.IO) {
        buildHealthWidgetData(
            configured = repository.isConfigured(),
            overview = repository.cachedOverview(),
            pending = repository.pendingOps(),
            spec = spec
        )
    }
}

private val StrongText = ColorProvider(LightNoLine.textStrong, DarkNoLine.textStrong)
private val SoftText = ColorProvider(LightNoLine.textSecondary, DarkNoLine.textSecondary)
private val FaintText = ColorProvider(LightNoLine.textLight, DarkNoLine.textLight)
private val AccentColor = ColorProvider(LightNoLine.accent, DarkNoLine.accent)
private val AccentOnColor = ColorProvider(LightNoLine.onBrand, DarkNoLine.onBrand)
private val TrackColor = ColorProvider(Color(0x80C6D0EC), Color(0x4DFFFFFF))

/** 站点把涨跌直接交给 BMI 语义色：降绿升橙，概览页就是这么画的。 */
private val DownColor = ColorProvider(LightNoLine.healthNormal, DarkNoLine.healthNormal)
private val UpColor = ColorProvider(LightNoLine.healthOver, DarkNoLine.healthOver)
private val WarnColor = ColorProvider(LightNoLine.healthOver, DarkNoLine.healthOver)

private fun trendColor(trend: WidgetTrend) = when (trend) {
    WidgetTrend.Down -> DownColor
    WidgetTrend.Up -> UpColor
    else -> SoftText
}

/**
 * 排版吃的是 [BillsWidgetContent] 那一套：外层 Column 只有六段（Glance 每个容器最多
 * 十个子元素，超了静默截断），右对齐靠「fillMaxWidth + TextAlign.End」，
 * 明细行的首列是死宽。根背景和买菜卡同一套：能拿到壁纸就现糊一张毛玻璃
 * （[widgetGlassBackground]），拿不到退回 shape——Glance 的 cornerRadius 要 API 31，
 * minSdk 26 只能这么画圆角。根 View 上还压一层全透明背景，专门用来挡住桌面
 * 自作主张垫在卡片后面的那块底板（买菜卡那边有详细解释）。
 */
@Composable
private fun HealthWidgetContent(spec: WidgetSpec, data: HealthWidgetData, glass: Bitmap?) {
    val context = LocalContext.current
    val wide = spec.wide

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Color.Transparent, Color.Transparent))
            .background(if (glass != null) ImageProvider(glass) else ImageProvider(R.drawable.widget_card_bg))
            .padding(
                horizontal = if (wide) 18.dp else 15.dp,
                vertical = if (wide) 13.dp else 12.dp
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "健康体重",
                style = TextStyle(color = StrongText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            )
            Text(
                "刷新",
                style = TextStyle(
                    color = FaintText,
                    fontSize = 11.sp,
                    textAlign = TextAlign.End
                ),
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable(actionRunCallback<HealthWidgetRefreshCallback>())
            )
        }

        if (data.hasData) {
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        data.weightText,
                        style = TextStyle(
                            color = StrongText,
                            fontSize = spec.amountSp.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        "kg",
                        style = TextStyle(color = FaintText, fontSize = 13.sp),
                        modifier = GlanceModifier.padding(start = 5.dp, bottom = 6.dp)
                    )
                    // 窄卡那点宽度只够「68.5 kg」，涨跌那句话挪到下一行去
                    if (wide) {
                        Text(
                            data.deltaText,
                            style = TextStyle(
                                color = trendColor(data.trend),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.End
                            ),
                            modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (wide) data.dateText else data.deltaText,
                        style = TextStyle(color = SoftText, fontSize = 12.sp)
                    )
                    if (data.pendingText.isNotEmpty()) {
                        Text(
                            data.pendingText,
                            style = TextStyle(color = WarnColor, fontSize = 10.5.sp, textAlign = TextAlign.End),
                            modifier = GlanceModifier.fillMaxWidth()
                        )
                    }
                }
                // BMI 那行只有 3×3 放得下：两格高的宿主约 178dp，多一行会把底部按钮压扁
                if (spec.tall && data.bmiText.isNotEmpty()) {
                    Text(
                        data.bmiText,
                        style = TextStyle(color = FaintText, fontSize = 11.5.sp),
                        modifier = GlanceModifier.padding(top = 2.dp)
                    )
                }
            }
            Column(modifier = GlanceModifier.padding(top = if (data.hasGoalBar) 10.dp else 6.dp)) {
                if (data.hasGoalBar) {
                    LinearProgressIndicator(
                        progress = data.goalFraction,
                        modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                        color = if (data.goalDone) DownColor else AccentColor,
                        backgroundColor = TrackColor
                    )
                    Spacer(GlanceModifier.height(5.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        data.goalText,
                        style = TextStyle(
                            color = if (data.goalDone) DownColor else SoftText,
                            fontSize = 11.5.sp,
                            fontWeight = if (data.goalDone) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                    if (wide) {
                        Text(
                            data.percentText,
                            style = TextStyle(color = FaintText, fontSize = 11.sp, textAlign = TextAlign.End),
                            modifier = GlanceModifier.fillMaxWidth()
                        )
                    }
                }
            }
        } else {
            Text(
                if (data.configured) "还没称过，或者本地没存上" else "先在 App 里填好接口密钥",
                style = TextStyle(color = SoftText, fontSize = 12.5.sp),
                modifier = GlanceModifier.padding(top = 10.dp)
            )
        }

        // 桌面给的高度不一定刚好是排版要吃的那点，剩下的空隙全交给一根 defaultWeight 的弹簧。
        // 有明细时它顶在「变化」上面，让明细和「记体重」粘成底部那一组；
        // 没明细时（2×2 和 3×2）它顶在按钮上面，不然卡片底下会空出一条。
        if (data.rows.isNotEmpty()) {
            Spacer(GlanceModifier.defaultWeight())
            Column(modifier = GlanceModifier.padding(top = 11.dp)) {
                Text(
                    "变化",
                    style = TextStyle(color = FaintText, fontSize = 10.sp),
                    modifier = GlanceModifier.padding(bottom = 2.dp)
                )
                data.rows.forEach { row ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp)
                            .clickable(openHealthAction(context))
                    ) {
                        Text(
                            row.label,
                            style = TextStyle(color = StrongText, fontSize = 12.5.sp),
                            modifier = GlanceModifier.width(46.dp)
                        )
                        Text(row.meta, style = TextStyle(color = FaintText, fontSize = 10.5.sp))
                        Text(
                            row.value,
                            style = TextStyle(
                                color = trendColor(row.trend),
                                fontSize = 12.sp,
                                fontWeight = if (row.trend == WidgetTrend.Down) FontWeight.Bold else FontWeight.Normal,
                                textAlign = TextAlign.End
                            ),
                            modifier = GlanceModifier.fillMaxWidth()
                        )
                    }
                }
            }
            Spacer(GlanceModifier.height(10.dp))
        } else {
            Spacer(GlanceModifier.defaultWeight())
        }

        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(spec.buttonDp.dp)
                .background(ImageProvider(R.drawable.widget_primary_bg))
                .clickable(
                    if (data.configured) openHealthAction(context, AppLinks.HEALTH_ADD)
                    else openHealthAction(context)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (data.configured) "记体重" else "去设置",
                style = TextStyle(
                    color = AccentOnColor,
                    fontSize = if (wide) 14.sp else 13.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

private fun openHealthAction(context: Context, link: String = AppLinks.HEALTH) =
    actionStartActivity(openHealth(context, link))

/**
 * 桌面的一次点击 = 打开 App + 落到健康页（带 /add 时当场把录入面板拉开）。
 * Glance 只能发 Intent，所以落点写在深链里。
 */
private fun openHealth(context: Context, link: String = AppLinks.HEALTH): Intent =
    Intent(context, MainActivity::class.java)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .setData(Uri.parse(link))
