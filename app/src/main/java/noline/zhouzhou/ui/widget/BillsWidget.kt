package noline.zhouzhou.ui.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import noline.zhouzhou.MainActivity
import noline.zhouzhou.data.sync.StatusNotifier
import noline.zhouzhou.TypechoApplication
import noline.zhouzhou.ui.navigation.AppLinks
import noline.zhouzhou.ui.theme.DarkNoLine
import noline.zhouzhou.ui.theme.LightNoLine

/**
 * 桌面上的买菜记账卡片：本月花了多少、今天花了几块、离预算还差多远，
 * 底下是「记一笔」和最近买过的品名——点一下直接进面板，品名已经替用户填好。
 *
 * 数据只读本地缓存（接口拉回来的那份），所以断网也能显示、进程冷启也不会转圈。
 * 真要把新的流水拉回来，靠卡片上那颗「刷新」或者 App 里记完一笔之后的一次 updateAll。
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
private val AccentColor = ColorProvider(LightNoLine.accent, DarkNoLine.accent)
private val AccentOnColor = ColorProvider(LightNoLine.onBrand, DarkNoLine.onBrand)
private val AccentSurface = ColorProvider(LightNoLine.surfaceSoft, DarkNoLine.surfaceSoft)
private val OverColor = ColorProvider(LightNoLine.healthObese, DarkNoLine.healthObese)
private val WarnColor = ColorProvider(LightNoLine.healthOver, DarkNoLine.healthOver)

/**
 * Glance 的每个容器最多放 10 个子元素，超出的会被**静默截断**（logcat 里只有一行
 * `Column container cannot have more than 10 elements`，卡片照样画得出来）。
 * 所以这里每一段都压成一个 Row / Column，外层 Column 最多 7 个子元素。
 */
@Composable
private fun BillsWidgetContent(data: BillsWidgetData) {
    val context = LocalContext.current

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(CardBackground)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            data.caption,
            style = TextStyle(color = SoftText, fontSize = 11.5.sp),
            modifier = GlanceModifier.clickable(openBillsAction(context))
        )

        if (data.hasData) {
            Text(
                data.monthAmount,
                style = TextStyle(color = StrongText, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(data.todayText, style = TextStyle(color = SoftText, fontSize = 12.sp))
                if (data.pendingText.isNotEmpty()) {
                    Spacer(GlanceModifier.width(8.dp))
                    Text(data.pendingText, style = TextStyle(color = WarnColor, fontSize = 11.sp))
                }
            }
            if (data.barText.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        data.barText,
                        style = TextStyle(
                            color = if (data.over) OverColor else AccentColor,
                            fontSize = 11.sp
                        )
                    )
                    Spacer(GlanceModifier.width(6.dp))
                    Text(data.percentText, style = TextStyle(color = SoftText, fontSize = 11.sp))
                }
            }
            Text(
                data.budgetText,
                style = TextStyle(
                    color = if (data.over) OverColor else SoftText,
                    fontSize = 11.5.sp
                )
            )
        } else {
            Text(
                if (data.configured) "还没记过账，或者本地没存上" else "先在 App 里填好接口密钥",
                style = TextStyle(color = SoftText, fontSize = 12.5.sp)
            )
        }

        Spacer(GlanceModifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                text = if (data.configured) "记一笔" else "去设置",
                onClick = if (data.configured)
                    openBillsAction(context, AppLinks.billsAdd())
                else openBillsAction(context),
                colors = ButtonDefaults.buttonColors(AccentColor, AccentOnColor),
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(GlanceModifier.width(8.dp))
            Button(
                text = "刷新",
                onClick = actionRunCallback<BillsWidgetRefreshCallback>(),
                colors = ButtonDefaults.buttonColors(AccentSurface, StrongText),
                style = TextStyle(fontSize = 13.sp)
            )
        }

        if (data.chips.isNotEmpty()) {
            Column {
                Spacer(GlanceModifier.height(8.dp))
                Text("最近买过", style = TextStyle(color = SoftText, fontSize = 10.5.sp))
                Spacer(GlanceModifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    data.chips.forEach { chip ->
                        Button(
                            text = chip.title.take(3),
                            onClick = openBillsAction(context, AppLinks.billsAdd(chip.title, chip.kind)),
                            colors = ButtonDefaults.buttonColors(AccentSurface, StrongText),
                            style = TextStyle(fontSize = 12.sp)
                        )
                        Spacer(GlanceModifier.width(6.dp))
                    }
                }
            }
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
