package ua.vidbij.alarm

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private fun chanceColor(p: Int): Color = when {
    p < 15 -> Palette.Green
    p < 40 -> Palette.Yellow
    p < 70 -> Palette.Orange
    else -> Palette.Red
}

private fun chanceLabel(p: Int): String = when {
    p < 15 -> "Низька"
    p < 40 -> "Помірна"
    p < 70 -> "Висока"
    else -> "Дуже висока"
}

@Composable
fun ChanceTab(region: Region?, onPickRegion: () -> Unit) {
    if (region == null) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            ScreenHeader("Ймовірність тривоги")
            RegionStatusCard(null, null, false, onRefresh = {}, onPick = onPickRegion)
        }
        return
    }
    val ctx = LocalContext.current
    var result by remember(region) { mutableStateOf<ChanceResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var showPosts by remember { mutableStateOf(false) }

    LaunchedEffect(region, refresh) {
        while (true) {
            loading = true
            val r = try {
                Chance.compute(ctx, region)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            loading = false
            if (r != null) {
                result = r
                failed = false
            } else {
                failed = true
            }
            delay(120_000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        ScreenHeader("Ймовірність тривоги", region.label)
        val r = result
        if (r == null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 64.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (failed && !loading) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Не вдалося отримати дані.\nПеревірте інтернет.",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { refresh++ }) { Text("Спробувати ще") }
                    }
                } else {
                    CircularProgressIndicator()
                }
            }
            return@Column
        }

        val now = r.alertNow
        val color = if (now != null) levelColor(now.level) else chanceColor(r.percent)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Gauge(
                fraction = r.percent / 100f,
                color = color,
                center = "${r.percent}%",
                caption = if (now != null) "Тривога вже триває" else chanceLabel(r.percent),
            )
        }
        Text(
            if (now != null) {
                buildString {
                    append(if (now.level == Level.YELLOW) "Жовта тривога" else "Червона тривога")
                    now.since?.let { append(" з ${hm(it)}") }
                }
            } else {
                "Шанс, що тривогу оголосять найближчі 30 хвилин"
            },
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (r.reasons.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Чому так", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    r.reasons.forEach { reason ->
                        Row {
                            Text("•  ", color = color, fontWeight = FontWeight.Bold)
                            Text(reason, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        if (r.posts.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { showPosts = !showPosts }) {
                Text(if (showPosts) "Сховати повідомлення моніторів" else "Що пишуть монітори")
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (showPosts) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                )
            }
            if (showPosts) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.posts.forEach { p ->
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    "${p.channel} · ${hm(p.time)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    p.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (loading) "Оновлюю…" else "Оновлено о ${hm(r.time)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { refresh++ }, enabled = !loading) {
                Icon(Icons.Filled.Refresh, contentDescription = "Оновити")
            }
        }
        Text(
            "Це приблизна оцінка за картою тривог і каналами моніторів (Повітряні сили, monitor, єРадар, Де Ракета). " +
                "Вона не замінює офіційну тривогу — завжди дійте за сигналом тривоги.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Gauge(fraction: Float, color: Color, center: String, caption: String) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(900),
        label = "gauge",
    )
    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(14.dp),
        ) {
            val stroke = Stroke(width = 22.dp.toPx(), cap = StrokeCap.Round)
            drawArc(color = track, startAngle = 135f, sweepAngle = 270f, useCenter = false, style = stroke)
            drawArc(color = color, startAngle = 135f, sweepAngle = 270f * animated, useCenter = false, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(center, fontSize = 56.sp, fontWeight = FontWeight.SemiBold, color = color)
            Text(caption, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        }
    }
}
