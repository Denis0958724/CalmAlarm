package ua.calmalarm.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

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

    val muted = MaterialTheme.colorScheme.onSurfaceVariant
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

        // ----- Шкала -----
        GlassCard(Modifier.fillMaxWidth(), tint = color) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Gauge(
                    fraction = r.percent / 100f,
                    alert = now != null,
                    color = color,
                    center = "${r.percent}%",
                    caption = if (now != null) "Тривога вже триває" else chanceLabel(r.percent),
                )
                Text(
                    if (now != null) {
                        buildString {
                            append(if (now.level == Level.YELLOW) "Жовта тривога" else "Червона тривога")
                            now.since?.let { append(" з ${hm(it)}") }
                        }
                    } else {
                        "Шанс, що тривогу оголосять у найближчі 30 хвилин"
                    },
                    modifier = Modifier.padding(horizontal = 20.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (now == null) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .background(muted.copy(alpha = 0.12f), RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    ) {
                        Text("Зазвичай для вашого місця: ${r.usualPercent}%", style = MaterialTheme.typography.labelLarge, color = muted)
                    }
                }
            }
        }

        // ----- Карта навколо -----
        Spacer(Modifier.height(14.dp))
        MapCard(region, r)

        // ----- Чому так -----
        if (r.reasons.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Чому так", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    r.reasons.forEach { reason ->
                        Row(verticalAlignment = Alignment.Top) {
                            Box(
                                Modifier
                                    .padding(top = 6.dp)
                                    .size(8.dp)
                                    .background(color, RoundedCornerShape(50)),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(reason, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        if (r.posts.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
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
                        GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
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

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (loading) "Оновлюю…" else "Оновлено о ${hm(r.time)}",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { refresh++ }, enabled = !loading) {
                Icon(Icons.Filled.Refresh, contentDescription = "Оновити")
            }
        }
        Text(
            "Оцінку розраховано за історією офіційних тривог по районах, поточною картою тривог " +
                "і повідомленнями Повітряних сил. Це прогноз, а не тривога - завжди дійте за офіційним сигналом.",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
private fun MapCard(region: Region, r: ChanceResult) {
    val ctx = LocalContext.current
    val geo = remember { UaGeo.get(ctx) }
    val near = remember(r, region) { threatsNear(geo, region, r.threats) }
    val drones = near.first
    val missiles = near.second
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Навколо вас",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                val parts = mutableListOf<String>()
                if (drones > 0) parts += "шахеди: $drones"
                if (missiles > 0) parts += "ракети: $missiles"
                val chipText = if (parts.isEmpty()) "Загроз поруч не видно" else parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
                val chipColor = if (parts.isEmpty()) Palette.Green else Palette.Orange
                Box(
                    Modifier
                        .background(chipColor.copy(alpha = 0.16f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(chipText, color = chipColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            RegionMap(region = region, alerts = r.alerts, threats = r.threats)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.padding(horizontal = 4.dp)) { MapLegend(MaterialTheme.colorScheme.onSurface) }
            Spacer(Modifier.height(8.dp))
            Text(
                "Тривоги - з офіційної карти. Шахеди й ракети - за повідомленнями Повітряних сил " +
                    "за останні 30 хв, місце приблизне (район або область).",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Box(Modifier.padding(horizontal = 4.dp)) { MapAttribution(muted.copy(alpha = 0.7f)) }
        }
    }
}

@Composable
private fun Gauge(fraction: Float, alert: Boolean, color: Color, center: String, caption: String) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val animated by animateFloatAsState(
        targetValue = if (alert) 1f else fraction.coerceIn(0.015f, 1f),
        animationSpec = tween(1000),
        label = "gauge",
    )
    Box(Modifier.size(232.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(18.dp),
        ) {
            val w = 20.dp.toPx()
            val arcSize = Size(size.width, size.height)
            val sweep = if (alert) {
                Brush.sweepGradient(listOf(color, color))
            } else {
                Brush.sweepGradient(
                    0f to Palette.Green,
                    0.28f to Palette.Yellow,
                    0.5f to Palette.Orange,
                    0.75f to Palette.Red,
                    1f to Palette.Red,
                )
            }
            rotate(135f) {
                drawArc(track, 0f, 270f, false, size = arcSize, style = Stroke(w, cap = StrokeCap.Round))
                // Світіння під дугою.
                drawArc(sweep, 0f, 270f * animated, false, size = arcSize, alpha = 0.25f, style = Stroke(w * 1.9f, cap = StrokeCap.Round))
                drawArc(sweep, 0f, 270f * animated, false, size = arcSize, style = Stroke(w, cap = StrokeCap.Round))
            }
            // Біла «кулька» на кінці дуги.
            val ang = Math.toRadians(135.0 + 270.0 * animated)
            val rr = size.width / 2
            val tip = Offset(this.center.x + (rr * cos(ang)).toFloat(), this.center.y + (rr * sin(ang)).toFloat())
            drawCircle(Color.White, radius = w * 0.32f, center = tip)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(center, fontSize = 52.sp, fontWeight = FontWeight.Bold, color = color, letterSpacing = (-1).sp)
            Text(caption, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}
