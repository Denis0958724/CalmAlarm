package ua.calmalarm.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifs.ensureChannels(this)
        Scheduler.scheduleAll(this)
        setContent {
            CalmAlarmTheme {
                AppRoot()
            }
        }
    }
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    var region by remember { mutableStateOf(Store.region(ctx)) }
    var tab by rememberSaveable { mutableIntStateOf(if (Store.region(ctx) == null) 2 else 0) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !Store.flag(ctx, "asked_notif")) {
            Store.setFlag(ctx, "asked_notif")
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    AppBackground {
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            when (tab) {
                0 -> AlarmTab(region, onPickRegion = { tab = 2 })
                1 -> ChanceTab(region, onPickRegion = { tab = 2 })
                else -> RegionTab(region, onSaved = {
                    region = it
                    tab = 0
                })
            }
        }
        FloatingNavBar(
            tab = tab,
            onTab = { tab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        )
    }
}

/** Плаваюча нижня панель вкладок. */
@Composable
private fun FloatingNavBar(tab: Int, onTab: (Int) -> Unit, modifier: Modifier = Modifier) {
    val dark = LocalDark.current
    val items = listOf(
        Triple("Будильник", painterResource(R.drawable.ic_tab_alarm), 0),
        Triple("Ймовірність", painterResource(R.drawable.ic_tab_chance), 1),
        Triple("Регіон", rememberVectorPainter(Icons.Outlined.LocationOn), 2),
    )
    Row(
        modifier
            .fillMaxWidth()
            .shadow(18.dp, RoundedCornerShape(30.dp), ambientColor = Color.Black, spotColor = Color.Black)
            .clip(RoundedCornerShape(30.dp))
            .background(if (dark) Color(0xFF141D31).copy(alpha = 0.96f) else Color.White.copy(alpha = 0.97f))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { (label, icon, index) ->
            NavItem(label, icon, tab == index, Modifier.weight(1f)) { onTab(index) }
        }
    }
}

@Composable
private fun NavItem(label: String, icon: Painter, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fg = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .then(if (selected) Modifier.background(Palette.accentBrush) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = fg, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

// ======================= Вкладка «Будильник» =======================

@Composable
fun AlarmTab(region: Region?, onPickRegion: () -> Unit) {
    val ctx = LocalContext.current
    var alarms by remember { mutableStateOf(Store.alarms(ctx)) }
    var waitings by remember { mutableStateOf(Store.waitings(ctx)) }
    var editing by remember { mutableStateOf<Alarm?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    var showSupport by remember { mutableStateOf(false) }
    val ringing by RingService.ringing.collectAsState()

    DisposableEffect(Unit) {
        val p = Store.prefs(ctx)
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            alarms = Store.alarms(ctx)
            waitings = Store.waitings(ctx)
        }
        p.registerOnSharedPreferenceChangeListener(l)
        onDispose { p.unregisterOnSharedPreferenceChangeListener(l) }
    }

    val owner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                resumeTick++
                alarms = Store.alarms(ctx)
                waitings = Store.waitings(ctx)
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val issues = remember(resumeTick) { Perms.issues(ctx) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick++ }

    var status by remember(region) { mutableStateOf<RegionStatus?>(null) }
    var statusFailed by remember(region) { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(region, refresh) {
        val r = region ?: return@LaunchedEffect
        while (true) {
            val s = withContext(Dispatchers.IO) {
                runCatching { AlertApi.statusFor(ctx, AlertApi.fetch(7000), r) }.getOrNull()
            }
            if (s != null) {
                status = s
                statusFailed = false
            } else {
                statusFailed = true
            }
            delay(60_000)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 190.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScreenHeader(
                        "Будильник",
                        "Не будить під час тривоги - дзвонить після відбою",
                        modifier = Modifier.weight(1f),
                    )
                    if (BuildConfig.SHOW_SUPPORT) {
                        Spacer(Modifier.width(8.dp))
                        SupportButton(onClick = { showSupport = true })
                    }
                }
            }
            if (ringing) {
                item {
                    RingingBanner(onStop = {
                        ctx.startService(Intent(ctx, RingService::class.java).setAction(RingService.ACTION_STOP))
                    })
                }
            }
            item {
                RegionStatusCard(region, status, statusFailed, onRefresh = { refresh++ }, onPick = onPickRegion)
            }
            val activeWaits = waitings.values.filter { it.level != null }.sortedBy { it.since }
            items(activeWaits, key = { "w${it.alarmId}" }) { w ->
                WaitingCard(w, alarms.firstOrNull { it.id == w.alarmId }, onCancel = {
                    AlarmEngine.cancelWait(ctx, w.alarmId)
                })
            }
            if (issues.isNotEmpty()) {
                item {
                    PermissionsCard(issues, onFix = { issue ->
                        if (issue == Perms.Issue.NOTIFICATIONS && Build.VERSION.SDK_INT >= 33 &&
                            !Store.flag(ctx, "asked_notif_2")
                        ) {
                            Store.setFlag(ctx, "asked_notif_2")
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            Perms.open(ctx, issue)
                        }
                    })
                }
            }
            if (alarms.isNotEmpty()) {
                item {
                    Text(
                        "Мої будильники",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
            }
            if (alarms.isEmpty()) {
                item { EmptyAlarms() }
            }
            items(alarms, key = { it.id }) { a ->
                AlarmCard(
                    a,
                    onClick = {
                        editing = a
                        editingIsNew = false
                    },
                    onToggle = { on -> toggleAlarm(ctx, a, on) },
                )
            }
        }
        AddButton(
            onClick = {
                editing = Alarm(id = -1, hour = 7, minute = 0, days = WEEKDAYS)
                editingIsNew = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 22.dp, bottom = 100.dp),
        )
    }

    if (showSupport) {
        SupportSheet(onDismiss = { showSupport = false })
    }

    editing?.let { e ->
        AlarmEditor(
            initial = e,
            isNew = editingIsNew,
            onSave = { a ->
                val saved = if (editingIsNew) a.copy(id = Store.newId(ctx)) else a
                Store.saveAlarm(ctx, saved)
                AlarmEngine.cancelWait(ctx, saved.id)
                val at = Scheduler.schedule(ctx, saved)
                if (at != null) {
                    Toast.makeText(ctx, "Задзвонить через ${untilText(at - System.currentTimeMillis())}", Toast.LENGTH_SHORT).show()
                }
                editing = null
            },
            onDelete = {
                Scheduler.cancelAll(ctx, e.id)
                AlarmEngine.cancelWait(ctx, e.id)
                Store.deleteAlarm(ctx, e.id)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun AddButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(62.dp)
            .shadow(16.dp, CircleShape, ambientColor = Palette.Violet, spotColor = Palette.Violet)
            .clip(CircleShape)
            .background(Palette.accentBrush)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Add, contentDescription = "Додати будильник", tint = Color.White, modifier = Modifier.size(30.dp))
    }
}

private fun toggleAlarm(ctx: Context, a: Alarm, on: Boolean) {
    val u = a.copy(enabled = on)
    Store.saveAlarm(ctx, u)
    if (!on) {
        Scheduler.cancelAll(ctx, a.id)
        AlarmEngine.cancelWait(ctx, a.id)
    } else {
        Scheduler.schedule(ctx, u)?.let { at ->
            Toast.makeText(ctx, "Задзвонить через ${untilText(at - System.currentTimeMillis())}", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun RingingBanner(onStop: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Palette.accentBrush),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Будильник дзвонить",
                modifier = Modifier.weight(1f),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Palette.Night),
            ) { Text("Вимкнути") }
        }
    }
}

/** Пульсуюча крапка стану (пульсує під час тривоги). */
@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        if (pulsing) {
            val tr = rememberInfiniteTransition(label = "dot")
            val s by tr.animateFloat(1f, 2.2f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "s")
            val a by tr.animateFloat(0.5f, 0f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "a")
            Box(
                Modifier
                    .size(12.dp)
                    .scale(s)
                    .background(color.copy(alpha = a), CircleShape),
            )
        } else {
            Box(Modifier.size(24.dp).background(color.copy(alpha = 0.18f), CircleShape))
        }
        Dot(color, 12.dp)
    }
}

@Composable
fun RegionStatusCard(
    region: Region?,
    status: RegionStatus?,
    failed: Boolean,
    onRefresh: () -> Unit,
    onPick: () -> Unit,
) {
    if (region == null) {
        GlassCard(Modifier.fillMaxWidth(), tint = Palette.Sky) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "Спершу виберіть свій регіон",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Тоді будильник знатиме, чи є тривога саме у вас.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                GradientButton("Вибрати регіон", onPick)
            }
        }
        return
    }
    val grey = MaterialTheme.colorScheme.outline
    val (color, text) = when {
        status == null && failed -> grey to "Немає зв'язку з картою тривог"
        status == null -> grey to "Перевіряю тривогу…"
        status.active -> levelColor(status.level) to buildString {
            append(if (status.level == Level.YELLOW) "Жовта тривога" else "Червона тривога")
            status.since?.let { append(" з ${hm(it)}") }
        }
        status.partial -> Palette.Orange to "Тривога в частині області"
        else -> Palette.Green to "Тривоги немає"
    }
    GlassCard(Modifier.fillMaxWidth(), tint = color) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color, pulsing = status?.active == true)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    region.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Оновити", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Велика кнопка з фірмовим градієнтом. */
@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.accentBrush)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WaitingCard(w: Waiting, alarm: Alarm?, onCancel: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), tint = Palette.Yellow) {
        Column(Modifier.padding(start = 18.dp, end = 8.dp, top = 16.dp, bottom = 4.dp)) {
            Text(
                "⏳ Будильник ${alarm?.timeText ?: ""} чекає відбою",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Зараз ${w.level.title()} тривога. Задзвонить одразу після відбою, але не пізніше ${hm(w.deadline)}. " +
                    "Якщо тривога триватиме довше - сьогодні не дзвонитиме.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 10.dp),
            )
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Не чекати") }
        }
    }
}

@Composable
private fun PermissionsCard(issues: List<Perms.Issue>, onFix: (Perms.Issue) -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), tint = Palette.Yellow) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.Yellow)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Щоб будильник точно спрацював",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(6.dp))
            issues.forEach { issue ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(issue.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            issue.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { onFix(issue) }) { Text("Дозволити") }
                }
            }
        }
    }
}

@Composable
private fun EmptyAlarms() {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .background(
                        Brush.linearGradient(listOf(Palette.Sky.copy(alpha = 0.25f), Palette.Violet.copy(alpha = 0.25f))),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_tab_alarm),
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text("Ще немає будильників", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Натисніть «+», щоб додати перший",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AlarmCard(a: Alarm, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val dim = if (a.enabled) 1f else 0.42f
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    a.timeText,
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = (-1).sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                )
                Text(
                    daysText(a.days),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim),
                )
                Spacer(Modifier.height(8.dp))
                val chipColor = if (a.waitsForAlerts) Palette.Sky else MaterialTheme.colorScheme.outline
                Row(
                    Modifier
                        .background(chipColor.copy(alpha = 0.12f * dim), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (a.waitsForAlerts) {
                        if (a.skipRed) {
                            Dot(Palette.Red.copy(alpha = dim), 8.dp)
                            Spacer(Modifier.width(4.dp))
                        }
                        if (a.skipYellow) {
                            Dot(Palette.Yellow.copy(alpha = dim), 8.dp)
                            Spacer(Modifier.width(4.dp))
                        }
                        Spacer(Modifier.width(2.dp))
                        Text(
                            "Чекає відбою · до ${waitLabel(a.maxWaitMin)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f * dim),
                        )
                    } else {
                        Text(
                            "Дзвонить навіть під час тривоги",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f * dim),
                        )
                    }
                }
            }
            Switch(
                checked = a.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Palette.Blue,
                    checkedThumbColor = Color.White,
                    checkedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}
