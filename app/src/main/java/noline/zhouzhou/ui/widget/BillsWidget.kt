package noline.zhouzhou.ui.widget

import android.content.Context
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
import noline.zhouzhou.data.sync.StatusNotifier
import noline.zhouzhou.TypechoApplication
import noline.zhouzhou.ui.navigation.AppLinks
import noline.zhouzhou.ui.theme.DarkNoLine
import noline.zhouzhou.ui.theme.LightNoLine

/**
 * 桌面上的买菜记账卡片：本月花了多少、离预算还差多远，
 * 底下两行是最近买过的东西——点一行等于「再买一次」，品名和分类已经填好。
 *
 * 数据只读本地缓存（接口拉回来的那份），所以断网也能显示、进程冷启也不会转圈。
 * 真要把新的流水拉回来，靠标题行那颗「刷新」或者 App 里记完一笔之后的一次 updateAll。
 */
class BillsWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = runCatching { loadBillsWidgetData(context) }
            .getOrElse { BillsWidgetData.unavailable() }
        provideContent { BillsWidgetContent(data) }
    }
}

class BillsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BillsWidget()
}

/**
 * 卡片上那颗「刷新」。它只拉数据、不替用户提交队列里的流水——
 * 离线攒下的那几笔要等 App 里有网时自己补交，桌面按钮不该有这个权力。
 */
class BillsWidgetRefreshCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val repository = (context.applicationContext as? TypechoApplication)?.container?.billsRepository
        runCatching { withContext(Dispatchers.IO) { repository?.refresh(flushFirst = false) } }
        BillsWidget().update(context, glanceId)
    }
}

/** 记完一笔、改完预算之后叫一声，让卡片当场跟上。 */
suspend fun refreshBillsWidget(context: Context) {
    runCatching { BillsWidget().updateAll(context.applicationContext) }
}

private suspend fun loadBillsWidgetData(context: Context): BillsWidgetData {
    val repository = (context.applicationContext as? TypechoApplication)?.container?.billsRepository
        ?: return BillsWidgetData.unavailable()
    return withContext(Dispatchers.IO) {
        buildBillsWidgetData(
            configured = repository.isConfigured(),
            overview = repository.cachedOverview(),
            status = repository.currentBudgetStatus(),
            pending = repository.pendingOps(),
            hints = repository.titleHints()
        )
    }
}

private val CardBackground = ColorProvider(LightNoLine.cardBg, DarkNoLine.cardBg)
private val StrongText = ColorProvider(LightNoLine.textStrong, DarkNoLine.textStrong)
private val SoftText = ColorProvider(LightNoLine.textSecondary, DarkNoLine.textSecondary)
private val FaintText = ColorProvider(LightNoLine.textLight, DarkNoLine.textLight)
private val AccentColor = ColorProvider(LightNoLine.accent, DarkNoLine.accent)
private val AccentOnColor = ColorProvider(LightNoLine.onBrand, DarkNoLine.onBrand)
private val TrackColor = ColorProvider(Color(0xFFE9ECF5), Color(0xFF33353B))
private val HairlineColor = ColorProvider(LightNoLine.cardBorder, DarkNoLine.cardBorder)
private val OverColor = ColorProvider(LightNoLine.healthObese, DarkNoLine.healthObese)
private val WarnColor = ColorProvider(LightNoLine.healthOver, DarkNoLine.healthOver)

/**
 * Glance 的每个容器最多放 10 个子元素，超出的会被**静默截断**（logcat 里只有一行
 * `Column container cannot have more than 10 elements`，卡片照样画得出来）。
 * 所以外层 Column 一共九段，进度条和分隔线都只占一个位置。
 *
 * 也没有 weight：右对齐一律靠「fillMaxWidth + TextAlign.End」，
 * 流水行的品名列是死宽（[RowTitleMaxChars] 在算文案时就截好，Glance 不会自己打点）。
 */
@Composable
private fun BillsWidgetContent(data: BillsWidgetData) {
    val context = LocalContext.current

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(CardBackground)
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "买菜记账",
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
                    .clickable(actionRunCallback<BillsWidgetRefreshCallback>())
            )
        }

        if (data.hasData) {
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        data.monthAmount,
                        style = TextStyle(color = StrongText, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    )
                    Text(
                        data.monthMeta,
                        style = TextStyle(color = FaintText, fontSize = 10.5.sp),
                        modifier = GlanceModifier.padding(start = 8.dp, bottom = 5.dp)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(data.todayText, style = TextStyle(color = SoftText, fontSize = 12.sp))
                    if (data.pendingText.isNotEmpty()) {
                        Text(
                            data.pendingText,
                            style = TextStyle(color = WarnColor, fontSize = 10.5.sp, textAlign = TextAlign.End),
                            modifier = GlanceModifier.fillMaxWidth()
                        )
                    }
                }
            }
            Column(modifier = GlanceModifier.padding(top = if (data.hasBudgetBar) 10.dp else 4.dp)) {
                if (data.hasBudgetBar) {
                    LinearProgressIndicator(
                        progress = data.budgetFraction,
                        modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                        color = if (data.over) OverColor else AccentColor,
                        backgroundColor = TrackColor
                    )
                    Spacer(GlanceModifier.height(5.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        data.budgetText,
                        style = TextStyle(
                            color = if (data.over) OverColor else SoftText,
                            fontSize = 11.5.sp,
                            fontWeight = if (data.over) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                    Text(
                        data.percentText,
                        style = TextStyle(color = FaintText, fontSize = 11.sp, textAlign = TextAlign.End),
                        modifier = GlanceModifier.fillMaxWidth()
                    )
                }
            }
        } else {
            Text(
                if (data.configured) "还没记过账，或者本地没存上" else "先在 App 里填好接口密钥",
                style = TextStyle(color = SoftText, fontSize = 12.5.sp),
                modifier = GlanceModifier.padding(top = 10.dp)
            )
        }

        if (data.rows.isNotEmpty()) {
            Column(modifier = GlanceModifier.padding(top = 11.dp)) {
                Text(
                    "再买一次",
                    style = TextStyle(color = FaintText, fontSize = 10.sp),
                    modifier = GlanceModifier.padding(bottom = 2.dp)
                )
                data.rows.forEach { row ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp)
                            .clickable(openBillsAction(context, AppLinks.billsAdd(row.prefill.title, row.prefill.kind)))
                    ) {
                        Text(
                            row.title,
                            style = TextStyle(color = StrongText, fontSize = 12.5.sp),
                            modifier = GlanceModifier.width(84.dp)
                        )
                        Text(row.meta, style = TextStyle(color = FaintText, fontSize = 10.5.sp))
                        Text(
                            row.amountText,
                            style = TextStyle(
                                color = if (row.pending) WarnColor else SoftText,
                                fontSize = 12.sp,
                                textAlign = TextAlign.End
                            ),
                            modifier = GlanceModifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        Spacer(GlanceModifier.height(11.dp))
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(38.dp)
                .background(ImageProvider(R.drawable.widget_primary_bg))
                .clickable(
                    if (data.configured) openBillsAction(context, AppLinks.billsAdd())
                    else openBillsAction(context)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (data.configured) "记一笔" else "去设置",
                style = TextStyle(color = AccentOnColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            )
        }
    }
}

private fun openBillsAction(context: Context, link: String = StatusNotifier.DEEP_LINK_BILLS) =
    actionStartActivity(openBills(context, link))

/**
 * 把小组件的一次点击变成「打开 App + 落到记账页 + 面板里已经填好这半张表单」。
 * Glance 只能发 Intent，所以品名和分类走深链参数带过去。
 */
private fun openBills(context: Context, link: String = StatusNotifier.DEEP_LINK_BILLS): Intent =
    Intent(context, MainActivity::class.java)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .setData(Uri.parse(link))
