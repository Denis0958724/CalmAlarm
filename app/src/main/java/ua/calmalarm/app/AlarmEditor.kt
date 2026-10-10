package ua.calmalarm.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AlarmEditor(
    initial: Alarm,
    isNew: Boolean,
    onSave: (Alarm) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val timeState = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
        var days by remember { mutableStateOf(initial.days) }
        var tone by remember { mutableIntStateOf(initial.tone) }
        var red by remember { mutableStateOf(initial.skipRed) }
        var yellow by remember { mutableStateOf(initial.skipYellow) }
        var wait by remember { mutableIntStateOf(initial.maxWaitMin.coerceIn(WAIT_MIN, WAIT_MAX)) }
        var snooze by remember { mutableIntStateOf(initial.snoozeMin.coerceIn(SNOOZE_LOW, SNOOZE_HIGH)) }

        DisposableEffect(Unit) {
            onDispose { Tones.stopPreview() }
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Закрити") }
                    Text(
                        if (isNew) "Новий будильник" else "Будильник",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        onSave(
                            initial.copy(
                                hour = timeState.hour,
                                minute = timeState.minute,
                                days = days,
                                tone = tone,
                                skipRed = red,
                                skipYellow = yellow,
                                maxWaitMin = wait,
                                snoozeMin = snooze,
                                enabled = true,
                            ),
                        )
                    }) {
                        Text("Зберегти", style = MaterialTheme.typography.titleMedium)
                    }
                }

                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                ) {
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        TimePicker(state = timeState)
                    }

                    // ----- Дні -----
                    SectionTitle("Дні")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (d in 1..7) {
                            val selected = d in days
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    )
                                    .clickable { days = if (selected) days - d else days + d },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    DAY_SHORT[d - 1],
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = days == WEEKDAYS, onClick = { days = WEEKDAYS }, label = { Text("Будні") })
                        FilterChip(selected = days == ALL_DAYS, onClick = { days = ALL_DAYS }, label = { Text("Щодня") })
                        FilterChip(selected = days.isEmpty(), onClick = { days = emptySet() }, label = { Text("Один раз") })
                    }
                    Text(
                        if (days.isEmpty()) "Спрацює один раз, у найближчий такий час" else "Повторюється: ${daysText(days)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // ----- Мелодія -----
                    SectionTitle("Мелодія")
                    Tones.names.forEachIndexed { i, name ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    tone = i
                                    Tones.playPreview(ctx, i)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = tone == i, onClick = {
                                tone = i
                                Tones.playPreview(ctx, i)
                            })
                            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { Tones.playPreview(ctx, i) }) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = "Прослухати",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    // ----- Тривога -----
                    SectionTitle("Під час тривоги")
                    Text(
                        "Не будити, поки триває:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    LevelRow(red, { red = it }, Palette.Red, "Червона тривога", "ракети, масована атака")
                    LevelRow(yellow, { yellow = it }, Palette.Yellow, "Жовта тривога", "дрони")
                    Text(
                        if (red || yellow) {
                            "Будильник задзвонить одразу після відбою. Якщо рівень тривоги не вказано — вважаємо її червоною."
                        } else {
                            "Будильник дзвонитиме як звичайний, навіть під час тривоги."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // ----- Ліміт очікування -----
                    SliderSetting(
                        title = "Якщо тривога затягнеться",
                        hint = "Не дзвонити зовсім, якщо тривога триває довше ніж",
                        valueText = waitLabel(wait),
                        value = wait,
                        range = WAIT_MIN..WAIT_MAX,
                        step = WAIT_STEP,
                        enabled = red || yellow,
                        onChange = { wait = it },
                    )

                    // ----- Відкладання -----
                    SliderSetting(
                        title = "Кнопка «Ще трохи»",
                        hint = "На скільки відкласти будильник, коли він дзвонить",
                        valueText = "$snooze хв",
                        value = snooze,
                        range = SNOOZE_LOW..SNOOZE_HIGH,
                        step = 1,
                        enabled = true,
                        onChange = { snooze = it },
                    )

                    if (!isNew) {
                        Spacer(Modifier.height(28.dp))
                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("Видалити будильник") }
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}

@Composable
private fun SliderSetting(
    title: String,
    hint: String,
    valueText: String,
    value: Int,
    range: IntRange,
    step: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.Bottom) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            valueText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        hint,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
    Slider(
        value = value.toFloat(),
        onValueChange = { v ->
            val snapped = (Math.round((v - range.first) / step) * step + range.first).coerceIn(range.first, range.last)
            if (snapped != value) onChange(snapped)
        },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        enabled = enabled,
    )
    Row(Modifier.fillMaxWidth()) {
        Text(
            if (range.first < 60) "${range.first} хв" else waitLabel(range.first),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (range.last <= 60) "${range.last} хв" else waitLabel(range.last),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LevelRow(checked: Boolean, onChange: (Boolean) -> Unit, color: Color, title: String, sub: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Dot(color, 12.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
