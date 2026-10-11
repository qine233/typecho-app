package noline.zhouzhou.ui.screens.health

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import noline.zhouzhou.data.model.HealthRecord
import noline.zhouzhou.data.repository.PreflightError
import noline.zhouzhou.data.repository.RecordPreflight
import noline.zhouzhou.ui.theme.LocalAccent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 记一笔 / 改一笔。
 * 关键约束：接口是「按天整体覆盖」，所以编辑时必须把原有的体脂和备注一起带上，
 * 只发改体重这两个字段会把那天的 fat/note 抹掉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordSheet(
    existing: HealthRecord?,
    errors: List<PreflightError>,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (date: String, weight: String, fat: String, note: String) -> Boolean,
    onDelete: (String) -> Unit
) {
    var date by remember { mutableStateOf(existing?.date ?: RecordPreflight.today()) }
    var weight by remember { mutableStateOf(existing?.let { "%.1f".format(it.weight) } ?: "") }
    var fat by remember { mutableStateOf(existing?.fat?.let { "%.1f".format(it) } ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var showPicker by remember { mutableStateOf(false) }

    val accent = LocalAccent.current
    val blocking = errors.filter { it.blocking }
    val warnings = errors.filterNot { it.blocking }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                if (existing != null) "修改 ${existing.date}" else "记一笔体重",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(14.dp))

            DateRow(
                date = date,
                onSelect = { date = it },
                onPick = { showPicker = true }
            )

            Spacer(Modifier.height(18.dp))
            Text("体重（kg）", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Stepper(
                value = weight,
                onValueChange = { weight = it },
                onStep = { delta ->
                    weight = ((weight.toDoubleOrNull() ?: 0.0) + delta)
                        .coerceAtLeast(0.0)
                        .let { "%.1f".format(it) }
                },
                error = blocking.firstOrNull { it.field == "weight" }?.message
            )

            Spacer(Modifier.height(16.dp))
            Text("体脂（% ，可留空）", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            SheetField(
                value = fat,
                onValueChange = { fat = it },
                placeholder = "不填就不记录这一项",
                keyboardType = KeyboardType.Decimal,
                error = blocking.firstOrNull { it.field == "fat" }?.message
            )
            warnings.firstOrNull { it.field == "fat" }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it.message, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(16.dp))
            Text("备注（可留空）", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            SheetField(
                value = note,
                onValueChange = { if (it.length <= 240) note = it },
                placeholder = "今天状态怎么样",
                keyboardType = KeyboardType.Text,
                error = null,
                maxLines = 3
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    warnings.firstOrNull { it.field == "note" }?.message ?: " ",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    "${note.length}/200",
                    fontSize = 11.5.sp,
                    color = if (note.length > 200) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            blocking.firstOrNull { it.field == "date" }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (existing != null) {
                    OutlinedButton(onClick = { onDelete(existing.date) }) {
                        Text("删除这天", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Button(
                    onClick = { if (onSubmit(date, weight, fat, note)) onDismiss() },
                    enabled = !saving,
                    modifier = Modifier.height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = Color.White
                    )
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Text(if (existing == null) "记下来" else "保存修改", fontSize = 15.sp)
                    }
                }
            }
        }
    }

    if (showPicker) {
        // 接口不会拒绝未来的日期，所以选择器自己把明天及以后关掉。
        val tomorrowMillis = LocalDate.now().plusDays(1)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = RecordPreflight.parseDate(date)
                ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis < tomorrowMillis
            }
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                    }
                    showPicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun DateRow(date: String, onSelect: (String) -> Unit, onPick: () -> Unit) {
    val today = RecordPreflight.today()
    val yesterday = LocalDate.now().minusDays(1).toString()
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf("今天" to today, "昨天" to yesterday).forEach { (label, value) ->
            val selected = date == value
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (selected) LocalAccent.current else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Spacer(Modifier.width(2.dp))
        Text(
            date,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .clickable(onClick = onPick)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun Stepper(
    value: String,
    onValueChange: (String) -> Unit,
    onStep: (Double) -> Unit,
    error: String?
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(Icons.Default.Remove) { onStep(-0.1) }
        BasicTextField(
            value = value,
            onValueChange = { input -> if (input.length <= 6) onValueChange(input) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            cursorBrush = SolidColor(LocalAccent.current),
            textStyle = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(vertical = 12.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            "66.0",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    inner()
                }
            }
        )
        StepButton(Icons.Default.Add) { onStep(0.1) }
    }
    if (error != null) {
        Spacer(Modifier.height(6.dp))
        Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SheetField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    error: String?,
    maxLines: Int = 1
) {
    Column {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = maxLines == 1,
            maxLines = maxLines,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            cursorBrush = SolidColor(LocalAccent.current),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    inner()
                }
            }
        )
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}
