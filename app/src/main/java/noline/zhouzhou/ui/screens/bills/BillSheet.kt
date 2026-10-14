package noline.zhouzhou.ui.screens.bills

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import noline.zhouzhou.data.model.BillInput
import noline.zhouzhou.data.model.BillKind
import noline.zhouzhou.data.repository.BillPreflight
import noline.zhouzhou.data.repository.PreflightError
import noline.zhouzhou.data.repository.RecordPreflight
import noline.zhouzhou.data.repository.TitleHint
import noline.zhouzhou.ui.theme.LocalAccent
import noline.zhouzhou.ui.theme.LocalNoLine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 面板里正在填的那一条（服务器还没见过它）。 */
private data class Draft(
    val title: String = "",
    val amount: String = "",
    val date: String = RecordPreflight.today(),
    val kind: String = "",
    val shop: String = "",
    val note: String = ""
)

/**
 * 记一笔 / 改一笔。
 *
 * 新增走的是「一次买多样」：填好一样按「再加一样」，它被收进上面那排药丸，
 * 最后点「记下来」把所有条目合并成**一个**批量 POST——不是每样发一个请求。
 * 编辑只有这一条，PUT 需要服务器给的 id。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillSheet(
    existing: BillRowUi?,
    hints: List<TitleHint>,
    errors: List<PreflightError>,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (List<BillInput>) -> Unit,
    onDelete: (() -> Unit)?
) {
    val t = LocalNoLine.current
    val accent = LocalAccent.current
    var draft by remember {
        mutableStateOf(
            existing?.record?.let {
                Draft(
                    title = it.title,
                    amount = "%.2f".format(it.amount).removeSuffix(".00").let { text ->
                        // 66.50 保持两位，66 就写 66，别在输入框里塞一堆零
                        if (it.amount % 1.0 == 0.0) it.amount.toInt().toString() else text
                    },
                    date = it.date,
                    kind = it.kind,
                    shop = it.shop,
                    note = it.note
                )
            } ?: Draft()
        )
    }
    var staged by remember { mutableStateOf(listOf<BillInput>()) }
    var showPicker by remember { mutableStateOf(false) }
    var showExtras by remember { mutableStateOf(existing != null) }
    var localErrors by remember { mutableStateOf(listOf<PreflightError>()) }

    // 编辑时错误来自 ViewModel（state.fieldErrors），新增攒在面板自己的 localErrors 里。
    val shown = if (existing != null) errors else localErrors
    val blocking = shown.filter { it.blocking }
    val warnings = shown.filterNot { it.blocking }

    // 品名候选：正在打的那几个字能对上就排前面，最多给 5 个，多了挡屏幕。
    val suggestions = remember(draft.title, hints) {
        val head = hints.filter { it.title.contains(draft.title.trim(), ignoreCase = true) }
        (if (head.isNotEmpty()) head else hints).distinctBy { it.title }.take(5)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                if (existing != null) "修改 ${existing.record.title}" else "记一笔买菜",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = t.textStrong
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (existing != null) "只改这条，别的不动"
                else "一样一条，买齐了一起记",
                fontSize = 11.5.sp,
                color = t.textLight
            )

            if (existing == null && staged.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                StagedRow(
                    staged = staged,
                    onRemove = { index -> staged = staged.filterIndexed { i, _ -> i != index } }
                )
            }

            Spacer(Modifier.height(14.dp))
            Text("买了什么", fontSize = 12.5.sp, color = t.textSecondary)
            Spacer(Modifier.height(7.dp))
            BillField(
                value = draft.title,
                onValueChange = { if (it.length <= BillPreflight.MaxTitle + 8) draft = draft.copy(title = it) },
                placeholder = "番茄、排骨、挂面…",
                keyboardType = KeyboardType.Text,
                error = blocking.firstOrNull { it.field == "title" }?.message
                    ?: warnings.firstOrNull { it.field == "title" }?.message
            )
            if (existing == null && suggestions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    suggestions.forEach { hint ->
                        Text(
                            hint.title,
                            fontSize = 12.sp,
                            color = t.textSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(t.surfaceSunken)
                                .clickable {
                                    // 选中顺带填上次的分类和店铺，这是这套数据能提供的最省事的输入方式
                                    draft = draft.copy(
                                        title = hint.title,
                                        kind = draft.kind.ifBlank { hint.kind },
                                        shop = draft.shop.ifBlank { hint.shop }
                                    )
                                }
                                .padding(horizontal = 11.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("多少钱（元）", fontSize = 12.5.sp, color = t.textSecondary)
            Spacer(Modifier.height(7.dp))
            BillField(
                value = draft.amount,
                onValueChange = { input ->
                    if (input.length <= 9 && input.all { c -> c.isDigit() || c == '.' }) {
                        draft = draft.copy(amount = input)
                    }
                },
                placeholder = "12.50",
                keyboardType = KeyboardType.Decimal,
                headline = true,
                error = blocking.firstOrNull { it.field == "amount" }?.message
                    ?: warnings.firstOrNull { it.field == "amount" }?.message
            )

            Spacer(Modifier.height(16.dp))
            BillDateRow(
                date = draft.date,
                onSelect = { draft = draft.copy(date = it) },
                onPick = { showPicker = true },
                error = blocking.firstOrNull { it.field == "date" }?.message
            )

            Spacer(Modifier.height(16.dp))
            Text("分类", fontSize = 12.5.sp, color = t.textSecondary)
            Spacer(Modifier.height(2.dp))
            Text(
                "认不准就选「其他」；分类填错了服务器不会报错，会直接当成其他存进去",
                fontSize = 10.5.sp,
                color = t.textLight
            )
            Spacer(Modifier.height(8.dp))
            KindPicker(selected = draft.kind) { draft = draft.copy(kind = it) }

            Spacer(Modifier.height(12.dp))
            Text(
                if (showExtras) "在哪儿买的、备注（可留空）" else "在哪儿买的、备注",
                fontSize = 12.5.sp,
                color = t.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showExtras = !showExtras }
                    .padding(vertical = 2.dp)
            )
            if (showExtras) {
                Spacer(Modifier.height(8.dp))
                BillField(
                    value = draft.shop,
                    onValueChange = { if (it.length <= BillPreflight.MaxShop + 6) draft = draft.copy(shop = it) },
                    placeholder = "东门菜场（超过 40 字会被截断）",
                    keyboardType = KeyboardType.Text,
                    error = null
                )
                Spacer(Modifier.height(10.dp))
                BillField(
                    value = draft.note,
                    onValueChange = { if (it.length <= BillPreflight.MaxNote + 40) draft = draft.copy(note = it) },
                    placeholder = "两个、打折买的（超过 200 字会被截断）",
                    keyboardType = KeyboardType.Text,
                    singleLine = false,
                    error = null
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    OutlinedButton(onClick = onDelete) {
                        Text("删除这条", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.width(10.dp))
                }
                if (existing == null) {
                    Text(
                        "再加一样",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(enabled = !saving) {
                                localErrors = BillPreflight.validate(draft.title, draft.amount, draft.date)
                                if (!localErrors.any { it.blocking }) {
                                    staged = staged + toInput(draft)
                                    draft = Draft(kind = draft.kind)
                                    localErrors = emptyList()
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    // 提交中禁用：POST 永远新增，双击就是两笔，服务器不会兜这个。
                    onClick = {
                        localErrors = BillPreflight.validate(draft.title, draft.amount, draft.date)
                        if (localErrors.any { it.blocking }) return@Button
                        val all = if (existing == null) staged + toInput(draft) else listOf(toInput(draft))
                        onSubmit(all)
                    },
                    enabled = !saving,
                    modifier = Modifier.height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White)
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White
                        )
                    } else {
                        Text(
                            when {
                                existing != null -> "保存修改"
                                staged.isNotEmpty() -> "记下 ${staged.size + 1} 笔"
                                else -> "记下来"
                            },
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        // 接口不拒未来的日期，得由 App 自己把明天及以后关掉。
        val tomorrowMillis = LocalDate.now().plusDays(1)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = RecordPreflight.parseDate(draft.date)
                ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis < tomorrowMillis
            }
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        draft = draft.copy(
                            date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        )
                    }
                    showPicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("取消") } }
        ) { DatePicker(state = state) }
    }
}

/** 已经收进去的那几笔，点药丸上的叉就把它退回来重填。 */
@Composable
private fun StagedRow(staged: List<BillInput>, onRemove: (Int) -> Unit) {
    val t = LocalNoLine.current
    Column {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            staged.forEachIndexed { index, input ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(t.accentBg)
                        .padding(start = 11.dp, end = 5.dp, top = 5.dp, bottom = 5.dp)
                ) {
                    Text(
                        "${input.title} ${"%.2f".format(input.amount)}",
                        fontSize = 12.sp,
                        color = t.accent
                    )
                    Spacer(Modifier.width(5.dp))
                    Icon(
                        Icons.Default.Close, "撤掉这条",
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .clickable { onRemove(index) }
                            .padding(1.dp),
                        tint = t.accent
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "已收 ${staged.size} 笔 · 共 ${"%.2f".format(staged.sumOf { it.amount })} 元",
            fontSize = 10.5.sp,
            color = t.textLight
        )
    }
}

@Composable
private fun KindPicker(selected: String, onPick: (String) -> Unit) {
    val t = LocalNoLine.current
    val accent = LocalAccent.current
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        BillKind.Codes.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                row.forEach { code ->
                    val active = (selected.ifBlank { "other" } == code) && selected.isNotBlank() ||
                        (selected.isBlank() && code == "other")
                    Text(
                        BillKind.label(code),
                        fontSize = 12.5.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) Color.White else t.textSecondary,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) accent else t.surfaceSunken)
                            .clickable { onPick(code) }
                            .padding(vertical = 7.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BillDateRow(date: String, onSelect: (String) -> Unit, onPick: () -> Unit, error: String?) {
    val t = LocalNoLine.current
    val today = RecordPreflight.today()
    val yesterday = LocalDate.now().minusDays(1).toString()
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf("今天" to today, "昨天" to yesterday).forEach { (label, value) ->
            val selected = date == value
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Color.White else t.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (selected) LocalAccent.current else t.surfaceSunken)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 13.dp, vertical = 6.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            date,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            color = t.textStrong,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(t.surfaceSunken)
                .clickable(onClick = onPick)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
    if (error != null) {
        Spacer(Modifier.height(5.dp))
        Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun BillField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    error: String?,
    headline: Boolean = false,
    singleLine: Boolean = true
) {
    val t = LocalNoLine.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        cursorBrush = SolidColor(LocalAccent.current),
        textStyle = (if (headline) {
            MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
        } else MaterialTheme.typography.bodyLarge).copy(color = t.textStrong),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(t.surfaceSunken)
            .padding(horizontal = 14.dp, vertical = if (headline) 12.dp else 11.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = if (headline) MaterialTheme.typography.headlineMedium
                                else MaterialTheme.typography.bodyLarge,
                        color = t.textLight,
                        modifier = if (headline) Modifier.fillMaxWidth() else Modifier
                    )
                }
                inner()
            }
        }
    )
    if (error != null) {
        Spacer(Modifier.height(5.dp))
        Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
    }
}

/** 输入框里的那一条 → 接口要的形（金额取 2 位、可选空字段不发）。 */
private fun toInput(draft: Draft): BillInput = BillPreflight.toInput(
    date = draft.date,
    title = draft.title,
    amountText = draft.amount,
    kind = draft.kind.ifBlank { "other" },
    shop = draft.shop,
    note = draft.note
)
