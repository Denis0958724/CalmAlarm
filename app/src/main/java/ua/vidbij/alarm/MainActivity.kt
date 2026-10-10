package ua.vidbij.alarm

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
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
            VidbijTheme {
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(painterResource(R.drawable.ic_tab_alarm), contentDescription = null) },
                    label = { Text("Будильник") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(painterResource(R.drawable.ic_tab_chance), contentDescription = null) },
                    label = { Text("Ймовірність") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Outlined.LocationOn, contentDescription = null) },
                    label = { Text("Регіон") },
                )
            }
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ScreenHeader("Будильник", "Не будить під час тривоги — дзвонить після відбою") }
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
        FloatingActionButton(
            onClick = {
                editing = Alarm(id = -1, hour = 7, minute = 0, days = WEEKDAYS)
                editingIsNew = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Додати будильник")
        }
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
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.Blue),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Будильник дзвонить",
                modifier = Modifier.weight(1f),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Palette.Night),
            ) { Text("Вимкнути") }
        }
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
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "Спершу виберіть свій регіон",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Тоді будильник знатиме, чи є тривога саме у вас.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onPick) { Text("Вибрати регіон") }
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
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.13f)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(color, 12.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    region.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Оновити")
            }
        }
    }
}

@Composable
private fun WaitingCard(w: Waiting, alarm: Alarm?, onCancel: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(start = 18.dp, end = 8.dp, top = 16.dp, bottom = 4.dp)) {
            Text(
                "⏳ Будильник ${alarm?.timeText ?: ""} чекає відбою",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Зараз ${w.level.title()} тривога. Задзвонить одразу після відбою, але не пізніше ${hm(w.deadline)}. " +
                    "Якщо тривога триватиме довше — сьогодні не дзвонитиме.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(end = 10.dp),
            )
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Не чекати") }
        }
    }
}

@Composable
private fun PermissionsCard(issues: List<Perms.Issue>, onFix: (Perms.Issue) -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.Yellow)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Щоб будильник точно спрацював",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
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
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(R.drawable.ic_tab_alarm),
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(12.dp))
        Text("Ще немає будильників", style = MaterialTheme.typography.titleMedium)
        Text(
            "Натисніть «+», щоб додати перший",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AlarmCard(a: Alarm, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val dim = if (a.enabled) 1f else 0.45f
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    a.timeText,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                )
                Text(
                    daysText(a.days),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim),
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim),
                        )
                    } else {
                        Text(
                            "Дзвонить навіть під час тривоги",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim),
                        )
                    }
                }
            }
            Switch(checked = a.enabled, onCheckedChange = onToggle)
        }
    }
}
