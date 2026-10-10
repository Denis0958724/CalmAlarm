package ua.calmalarm.app

import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun RegionTab(region: Region?, onSaved: (Region) -> Unit) {
    val ctx = LocalContext.current
    // 0 - підсумок, 1 - вибір області, 2 - вибір району
    var step by remember { mutableIntStateOf(if (region == null) 1 else 0) }
    var picked by remember { mutableStateOf<Oblast?>(null) }

    fun save(r: Region) {
        Store.saveRegion(ctx, r)
        Toast.makeText(ctx, "Регіон збережено: ${r.label}", Toast.LENGTH_SHORT).show()
        onSaved(r)
    }

    val current = picked
    when {
        step == 0 && region != null -> RegionSummary(region, onChange = { step = 1 })
        step == 2 && current != null -> DistrictPicker(
            oblast = current,
            selectedId = region?.cityId ?: region?.districtId,
            onBack = { step = 1 },
            onPick = { d, city ->
                save(Region(current.id, current.name, d?.id, d?.name, city?.id, city?.short))
            },
        )
        else -> OblastPicker(
            selectedId = region?.oblastId,
            canGoBack = region != null,
            onBack = { step = 0 },
            onPick = { ob ->
                if (ob.hasDistricts) {
                    picked = ob
                    step = 2
                } else {
                    save(Region(ob.id, ob.name))
                }
            },
        )
    }
}

@Composable
private fun RegionSummary(region: Region, onChange: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        ScreenHeader("Регіон", "Будильник зважає на тривогу саме тут")
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        region.oblastName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        region.subtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        val ob = Regions.byId[region.oblastId]
        if (region.districtId == null && ob?.hasDistricts == true) {
            Spacer(Modifier.height(12.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Row(Modifier.padding(16.dp)) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Зараз будильник чекає, якщо тривога хоча б в одному районі області. " +
                            "Виберіть свій район або місто - тоді він зважатиме лише на тривогу саме у вас.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onChange, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Змінити регіон")
        }
    }
}

@Composable
private fun PickerHeader(title: String, subtitle: String, onBack: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
        }
        ScreenHeader(title, subtitle)
    }
}

@Composable
private fun PickRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = "Вибрано", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun OblastPicker(
    selectedId: String?,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onPick: (Oblast) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp),
    ) {
        item {
            Box(Modifier.padding(horizontal = 8.dp)) {
                PickerHeader("Виберіть область", "Крок 1 з 2", if (canGoBack) onBack else null)
            }
        }
        items(Regions.oblasts, key = { it.id }) { ob ->
            PickRow(ob.name, null, ob.id == selectedId) { onPick(ob) }
        }
    }
}

@Composable
private fun DistrictPicker(
    oblast: Oblast,
    selectedId: String?,
    onBack: () -> Unit,
    onPick: (District?, City?) -> Unit,
) {
    val ctx = LocalContext.current
    var districts by remember(oblast.id) { mutableStateOf<List<District>?>(null) }
    var failed by remember(oblast.id) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(oblast.id, attempt) {
        failed = false
        val list = withContext(Dispatchers.IO) {
            runCatching { Regions.loadDistricts(ctx)[oblast.id].orEmpty() }.getOrNull()
        }
        if (list == null) failed = true else districts = list
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            Box(Modifier.padding(horizontal = 8.dp)) {
                PickerHeader("Виберіть район", "${oblast.name} · Крок 2 з 2", onBack)
            }
        }
        item {
            PickRow(
                "Вся область",
                "Будильник чекатиме, якщо тривога хоча б в одному районі області",
                selectedId == null,
            ) { onPick(null, null) }
        }
        val list = districts
        when {
            list != null -> {
                val cities = list.flatMap { d -> d.cities.map { d to it } }
                items(cities, key = { "c" + it.second.id }) { (d, c) ->
                    PickRow(c.short, "Місто з окремими тривогами · ${d.name}", c.id == selectedId) { onPick(d, c) }
                }
                items(list, key = { it.id }) { d ->
                    PickRow(d.name, null, d.id == selectedId) { onPick(d, null) }
                }
            }
            failed -> item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Не вдалося завантажити список районів. Перевірте інтернет.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { attempt++ }) { Text("Спробувати ще") }
                }
            }
            else -> item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
        }
    }
}
