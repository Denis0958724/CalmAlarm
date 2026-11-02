package ua.calmalarm.app

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Кольори карти: вона завжди «нічна», як радар, і в світлій, і в темній темі. */
private object MapColors {
    val bgTop = Color(0xFF0B1430)
    val bgBottom = Color(0xFF060A18)
    val focus = Color(0xFF8FB0FF)
    val land = Color(0xFFFFFFFF)
    val drone = Color(0xFFFF9F43)
    val missile = Color(0xFFFF4D5E)
}

/** Що саме показуємо: межі видимої області карти в її координатах. */
private class MapView(val center: Offset, val spanX: Float, val spanY: Float)

private fun viewFor(geo: UaGeoData, region: Region): Pair<GeoShape, MapView>? {
    val focus = region.cityId?.let { geo.cities[it] }
        ?: region.districtId?.let { geo.districts[it] }
        ?: geo.oblasts[region.oblastId]
        ?: return null
    val local = region.cityId != null || region.districtId != null || region.oblastId == "31"
    val factor = if (local) 2.6f else 1.6f
    val b = focus.bounds
    val minSpan = if (local) 110f else 160f // ~50 і ~70 км
    return focus to MapView(b.center, max(b.width * factor, minSpan), max(b.height * factor, minSpan))
}

/**
 * Наближена карта вибраного місця: район (або область) чітко в центрі, сусіди навколо,
 * а до країв карта розмивається. Тривоги - з офіційної карти, загрози - з повідомлень Повітряних сил.
 */
@Composable
fun RegionMap(
    region: Region,
    alerts: Map<String, Level>,
    threats: List<Threat>,
    modifier: Modifier = Modifier,
    aspect: Float = 1.2f,
) {
    val ctx = LocalContext.current
    val geo = remember { UaGeo.get(ctx) } ?: return
    val fv = remember(region) { viewFor(geo, region) } ?: return
    val (focus, view) = fv
    val measurer = rememberTextMeasurer()
    val pulse = rememberInfiniteTransition(label = "pulse")
    val phase by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val shape = RoundedCornerShape(24.dp)

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(MapColors.bgTop, MapColors.bgBottom))),
    ) {
        // Розмитий шар під чітким: на краях видно саме його (Android 12+ уміє розмиття).
        if (Build.VERSION.SDK_INT >= 31) {
            Canvas(Modifier.matchParentSize().blur(7.dp)) {
                drawMapLayer(geo, focus, region, view, alerts, sharp = false)
            }
        }
        // Чіткий шар, який до країв стає прозорим.
        Canvas(
            Modifier
                .matchParentSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            drawMapLayer(geo, focus, region, view, alerts, sharp = true)
            drawRect(
                Brush.radialGradient(
                    0f to Color.Black,
                    0.42f to Color.Black,
                    1f to Color.Transparent,
                    center = center,
                    radius = max(size.width, size.height) * 0.62f,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        // Підписи і позначки загроз.
        Canvas(Modifier.matchParentSize()) {
            val t = transformFor(view)
            drawLabels(geo, focus, region, view, t, measurer)
            drawThreats(geo, threats, t, phase)
            // Легке затемнення по самому краю.
            drawRect(
                Brush.radialGradient(
                    0.72f to Color.Transparent,
                    1f to MapColors.bgBottom.copy(alpha = 0.85f),
                    center = center,
                    radius = max(size.width, size.height) * 0.72f,
                ),
            )
        }
    }
}

/** Перетворення з координат карти в пікселі. */
private class MapTransform(val scale: Float, val origin: Offset, val center: Offset, val screenCenter: Offset) {
    fun toScreen(p: Offset): Offset = Offset(
        screenCenter.x + (p.x - center.x) * scale,
        screenCenter.y + (p.y - center.y) * scale,
    )
}

private fun DrawScope.transformFor(view: MapView): MapTransform {
    val s = min(size.width / view.spanX, size.height / view.spanY)
    return MapTransform(s, Offset.Zero, view.center, Offset(size.width / 2, size.height / 2))
}

private fun DrawScope.visibleRect(view: MapView, t: MapTransform): Rect {
    val hw = size.width / 2 / t.scale
    val hh = size.height / 2 / t.scale
    return Rect(view.center.x - hw, view.center.y - hh, view.center.x + hw, view.center.y + hh)
}

private fun DrawScope.drawMapLayer(
    geo: UaGeoData,
    focus: GeoShape,
    region: Region,
    view: MapView,
    alerts: Map<String, Level>,
    sharp: Boolean,
) {
    val t = transformFor(view)
    val vis = visibleRect(view, t).inflate(20f)
    val px = 1f / t.scale // один піксель у координатах карти
    withTransform({
        translate(size.width / 2, size.height / 2)
        scale(t.scale, t.scale, pivot = Offset.Zero)
        translate(-view.center.x, -view.center.y)
    }) {
        val oblasts = geo.oblasts.values.filter { it.bounds.overlaps(vis) }
        val districts = geo.districts.values.filter { it.bounds.overlaps(vis) }

        // Суходіл.
        oblasts.forEach { drawPath(it.path, MapColors.land.copy(alpha = 0.035f), style = Fill) }

        // Тривоги з офіційної карти: область, район або місто.
        for ((rid, level) in alerts) {
            val s = geo.shapeFor(rid) ?: continue
            if (!s.bounds.overlaps(vis)) continue
            val c = levelColor(level)
            val isOblast = geo.oblasts.containsKey(rid)
            drawPath(s.path, c.copy(alpha = if (isOblast) 0.16f else 0.30f), style = Fill)
            if (!isOblast) drawPath(s.path, c.copy(alpha = 0.75f), style = Stroke(width = 1.3f * px, join = StrokeJoin.Round))
        }

        // Вибране місце.
        drawPath(
            focus.path,
            Brush.radialGradient(
                listOf(MapColors.focus.copy(alpha = 0.34f), MapColors.focus.copy(alpha = 0.12f)),
                center = focus.center,
                radius = max(focus.bounds.width, focus.bounds.height) * 0.7f,
            ),
            style = Fill,
        )

        // Межі районів і областей.
        districts.forEach {
            drawPath(it.path, MapColors.land.copy(alpha = 0.22f), style = Stroke(width = 1f * px, join = StrokeJoin.Round))
        }
        oblasts.forEach {
            drawPath(it.path, MapColors.land.copy(alpha = 0.55f), style = Stroke(width = 1.7f * px, join = StrokeJoin.Round))
        }

        // Контур вибраного місця зі світінням.
        if (sharp) {
            drawPath(focus.path, MapColors.focus.copy(alpha = 0.22f), style = Stroke(width = 10f * px, join = StrokeJoin.Round))
            drawPath(focus.path, MapColors.focus.copy(alpha = 0.35f), style = Stroke(width = 5f * px, join = StrokeJoin.Round))
        }
        drawPath(focus.path, MapColors.focus, style = Stroke(width = 2.6f * px, join = StrokeJoin.Round))
        if (focus.id != region.oblastId) {
            geo.oblasts[region.oblastId]?.let {
                drawPath(it.path, MapColors.focus.copy(alpha = 0.55f), style = Stroke(width = 1.6f * px, join = StrokeJoin.Round))
            }
        }
    }
}

private fun shortName(name: String): String =
    name.removeSuffix(" район").removeSuffix(" область").removePrefix("м. ")

private fun DrawScope.drawLabels(
    geo: UaGeoData,
    focus: GeoShape,
    region: Region,
    view: MapView,
    t: MapTransform,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val vis = visibleRect(view, t)
    val radius = max(size.width, size.height) * 0.5f
    val placed = mutableListOf<Offset>()
    val small = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color.White)
    val big = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

    fun label(text: String, at: Offset, style: TextStyle, alpha: Float) {
        if (alpha <= 0.05f) return
        if (placed.any { hypot(it.x - at.x, it.y - at.y) < 56.dp.toPx() * 0.7f }) return
        val layout = measurer.measure(text, style)
        val tl = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f)
        if (tl.x < 0 || tl.y < 0 || tl.x + layout.size.width > size.width || tl.y + layout.size.height > size.height) return
        drawText(layout, topLeft = tl, alpha = alpha)
        placed += at
    }

    val focusAt = t.toScreen(focus.center)
    label(shortName(focus.name.ifBlank { Regions.byId[region.oblastId]?.short ?: "" }), focusAt, big, 1f)

    val local = focus.id != region.oblastId || region.oblastId == "31"
    val others = (if (local) geo.districts.values else geo.oblasts.values)
        .filter { it.id != focus.id && vis.contains(it.center) }
        .sortedBy { hypot(it.center.x - focus.center.x, it.center.y - focus.center.y) }
        .take(10)
    for (s in others) {
        val at = t.toScreen(s.center)
        val d = hypot(at.x - size.width / 2, at.y - size.height / 2)
        val a = (1f - (d - radius * 0.35f) / (radius * 0.6f)).coerceIn(0f, 0.6f)
        label(shortName(s.name), at, small, a)
    }
}

/** Силует шахеда (дельтаподібне крило) в одиничних координатах. */
private val dronePath = Path().apply {
    moveTo(0f, -1f)
    lineTo(0.95f, 0.55f)
    lineTo(0.32f, 0.42f)
    lineTo(0.14f, 0.82f)
    lineTo(-0.14f, 0.82f)
    lineTo(-0.32f, 0.42f)
    lineTo(-0.95f, 0.55f)
    close()
}

/** Силует ракети в одиничних координатах. */
private val missilePath = Path().apply {
    moveTo(0f, -1f)
    lineTo(0.17f, -0.62f)
    lineTo(0.17f, 0.52f)
    lineTo(0.46f, 0.92f)
    lineTo(0.17f, 0.82f)
    lineTo(0.12f, 1f)
    lineTo(-0.12f, 1f)
    lineTo(-0.17f, 0.82f)
    lineTo(-0.46f, 0.92f)
    lineTo(-0.17f, 0.52f)
    lineTo(-0.17f, -0.62f)
    close()
}

fun DrawScope.drawThreatIcon(type: ThreatType, at: Offset, sizePx: Float) {
    val path = if (type == ThreatType.DRONE) dronePath else missilePath
    val color = if (type == ThreatType.DRONE) MapColors.drone else MapColors.missile
    rotate(if (type == ThreatType.MISSILE) 35f else 0f, pivot = at) {
        withTransform({
            translate(at.x, at.y)
            scale(sizePx / 2, sizePx / 2, pivot = Offset.Zero)
        }) {
            drawPath(path, color, style = Fill)
            drawPath(path, Color(0xFF1A0C05).copy(alpha = 0.6f), style = Stroke(width = 0.12f, join = StrokeJoin.Round))
        }
    }
}

private fun DrawScope.drawThreats(geo: UaGeoData, threats: List<Threat>, t: MapTransform, phase: Float) {
    if (threats.isEmpty()) return
    val radius = max(size.width, size.height) * 0.5f
    val groups = threats.groupBy { it.districtId?.takeIf { d -> geo.districts.containsKey(d) } ?: it.oblastId }
    for ((place, list) in groups) {
        val shape = geo.shapeFor(place) ?: continue
        val base = t.toScreen(shape.center)
        if (base.x < -20 || base.y < -20 || base.x > size.width + 20 || base.y > size.height + 20) continue
        val d = hypot(base.x - size.width / 2, base.y - size.height / 2)
        val alpha = (1f - (d - radius * 0.55f) / (radius * 0.55f)).coerceIn(0.25f, 1f)
        val types = list.map { it.type }.distinct()
        val icon = 15.dp.toPx()
        types.forEachIndexed { i, type ->
            val at = Offset(base.x + (i - (types.size - 1) / 2f) * icon * 1.6f, base.y - 14.dp.toPx())
            val color = if (type == ThreatType.DRONE) MapColors.drone else MapColors.missile
            // Пульсуюче коло.
            val p = (phase + i * 0.3f) % 1f
            drawCircle(color.copy(alpha = (1f - p) * 0.45f * alpha), radius = icon * (0.8f + p * 1.3f), center = at)
            drawCircle(Color(0xFF0A0F22).copy(alpha = 0.78f * alpha), radius = icon * 0.85f, center = at)
            drawCircle(color.copy(alpha = alpha), radius = icon * 0.85f, center = at, style = Stroke(width = 1.5.dp.toPx()))
            drawThreatIcon(type, at, icon * 1.1f)
        }
    }
}

@Composable
private fun LegendIcon(type: ThreatType) {
    Canvas(Modifier.size(16.dp)) { drawThreatIcon(type, center, size.minDimension * 0.9f) }
}

@Composable
private fun LegendSwatch(color: Color, fillAlpha: Float) {
    Box(
        Modifier
            .size(14.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = fillAlpha)),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapLegend(textColor: Color) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LegendItem({ LegendSwatch(MapColors.focus, 0.8f) }, "Ви тут", textColor)
        LegendItem({ LegendSwatch(Palette.Red, 0.8f) }, "Тривога", textColor)
        LegendItem({ LegendSwatch(Palette.Yellow, 0.8f) }, "Жовта", textColor)
        LegendItem({ LegendIcon(ThreatType.DRONE) }, "Шахеди", textColor)
        LegendItem({ LegendIcon(ThreatType.MISSILE) }, "Ракети", textColor)
    }
}

@Composable
private fun LegendItem(icon: @Composable () -> Unit, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** Скільки загроз видно на карті навколо вибраного місця. */
fun threatsNear(geo: UaGeoData?, region: Region, threats: List<Threat>): Pair<Int, Int> {
    geo ?: return 0 to 0
    val (focus, view) = viewFor(geo, region) ?: return 0 to 0
    val rect = Rect(
        focus.bounds.center.x - view.spanX / 2, focus.bounds.center.y - view.spanY / 2,
        focus.bounds.center.x + view.spanX / 2, focus.bounds.center.y + view.spanY / 2,
    )
    var drones = 0
    var missiles = 0
    threats.forEach { th ->
        val s = th.districtId?.let { geo.districts[it] } ?: geo.oblasts[th.oblastId] ?: return@forEach
        if (rect.contains(s.center)) {
            if (th.type == ThreatType.DRONE) drones++ else missiles++
        }
    }
    return drones to missiles
}

@Composable
fun MapAttribution(color: Color) {
    val ctx = LocalContext.current
    val text = remember { UaGeo.get(ctx)?.attribution ?: "" }
    if (text.isNotBlank()) {
        Text(text, color = color, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
    }
}
