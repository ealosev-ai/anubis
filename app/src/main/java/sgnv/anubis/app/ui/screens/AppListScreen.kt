package sgnv.anubis.app.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sgnv.anubis.app.data.DefaultRestrictedApps
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.model.InstalledAppInfo
import sgnv.anubis.app.ui.MainViewModel

private val grayscaleFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
fun AppListScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val allApps by viewModel.installedApps.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var showAutoWarning by remember { mutableStateOf(false) }
    var pendingFirstAdd by remember { mutableStateOf<String?>(null) }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val userApps = allApps.filter { !it.isSystem }
    val systemApps = allApps.filter { it.isSystem }
    val currentList = if (selectedTab == 0) userApps else systemApps

    val noVpnCount = allApps.count { it.group == AppGroup.LOCAL }
    val vpnOnlyCount = allApps.count { it.group == AppGroup.VPN_ONLY }
    val launchCount = allApps.count { it.group == AppGroup.LAUNCH_VPN }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        Text(
            "Без VPN: $noVpnCount | Только VPN: $vpnOnlyCount | С VPN: $launchCount",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )

        // Плашка ошибок freeze/unfreeze — без неё молча остаётся enabled=3
        // и иконка пропадает с лаунчера без видимой причины.
        lastError?.let { error ->
            Spacer(Modifier.height(8.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Row(
                    Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        error,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(onClick = { viewModel.clearError() }) { Text("OK") }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { showAutoWarning = true },
                modifier = Modifier.weight(1f)
            ) {
                Text("Авто-выбор")
            }
            OutlinedButton(
                onClick = { viewModel.loadInstalledApps() },
            ) {
                Text("Обновить")
            }
        }

        Spacer(Modifier.height(8.dp))

        // Legend
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            GroupBadge("Без VPN", MaterialTheme.colorScheme.error)
            GroupBadge("Только VPN", MaterialTheme.colorScheme.tertiary)
            GroupBadge("С VPN", MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(8.dp))

        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Пользовательские (${userApps.size})") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Системные (${systemApps.size})") }
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            items(currentList, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    isKnownRestricted = DefaultRestrictedApps.isKnownRestricted(app.packageName),
                    onCycleGroup = {
                        if (app.group == null && !prefs.getBoolean("seen_first_add_warning", false)) {
                            pendingFirstAdd = app.packageName
                        } else {
                            viewModel.cycleAppGroup(app.packageName)
                        }
                    }
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    pendingFirstAdd?.let { pkg ->
        AlertDialog(
            onDismissRequest = { pendingFirstAdd = null },
            title = { Text("Добавить в группу?") },
            text = {
                Text(
                    "Приложение будет заморожено — после этого его ярлык исчезнет с рабочего стола. " +
                    "Восстановить можно в любой момент через долгое нажатие на Главной → «Разморозить» → «Убрать из группы». " +
                    "Для массовой отмены — раздел «Восстановление» в настройках."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putBoolean("seen_first_add_warning", true).apply()
                    viewModel.cycleAppGroup(pkg)
                    pendingFirstAdd = null
                }) { Text("Добавить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingFirstAdd = null }) { Text("Отмена") }
            }
        )
    }

    if (showAutoWarning) {
        val list = viewModel.restrictedListProvider.current()
        AutoSelectCategoriesDialog(
            list = list,
            lastSyncMs = viewModel.restrictedListProvider.lastSyncMs(),
            onSync = { viewModel.syncRestrictedList() },
            onDismiss = { showAutoWarning = false },
            onApply = { restrictedPkgs, restrictedPrefs, vpnOnlyPkgs ->
                prefs.edit().putBoolean("seen_auto_warning", true).apply()
                showAutoWarning = false
                viewModel.autoSelectRestricted(restrictedPkgs, restrictedPrefs, vpnOnlyPkgs)
            },
        )
    }
}

/**
 * Диалог выбора категорий для «Авто-выбор». По дефолту ВСЁ отмечено —
 * мы не знаем точно какие RU-приложения палят VPN, а какие нет; методичка
 * Минцифры универсальная, давят на всех, и что-то что сегодня не сливает —
 * завтра может начать после обновления. Поэтому дефолт = максимум защиты,
 * пользователь снимает галки только с того что ему явно неудобно морозить.
 */
@Composable
private fun AutoSelectCategoriesDialog(
    list: sgnv.anubis.app.data.RestrictedList,
    lastSyncMs: Long,
    onSync: () -> Unit,
    onDismiss: () -> Unit,
    onApply: (restrictedPkgs: Set<String>, restrictedPrefixes: List<String>, vpnOnlyPkgs: Set<String>) -> Unit,
) {
    val restrictedChecks = remember(list) {
        mutableStateOf(list.restricted.associate { it.id to true }.toMutableMap())
    }
    val vpnOnlyChecks = remember(list) {
        mutableStateOf(list.vpnOnly.associate { it.id to true }.toMutableMap())
    }
    val includePrefixes: Boolean = restrictedChecks.value["yandex"] == true
    val syncLabel = if (lastSyncMs == 0L) "список встроенный"
        else "обновлён ${relativeTime(lastSyncMs)}"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Что автоматически заморозить") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.heightIn(max = 480.dp),
            ) {
                item {
                    Text(
                        "Дефолт — максимум защиты. Ручные добавления не трогаем, " +
                            "клавиатуры не морозим никогда.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            syncLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onSync) { Text("Обновить") }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            restrictedChecks.value = restrictedChecks.value.mapValues { true }.toMutableMap()
                            vpnOnlyChecks.value = vpnOnlyChecks.value.mapValues { true }.toMutableMap()
                        }) { Text("Отметить всё") }
                        TextButton(onClick = {
                            restrictedChecks.value = restrictedChecks.value.mapValues { false }.toMutableMap()
                            vpnOnlyChecks.value = vpnOnlyChecks.value.mapValues { false }.toMutableMap()
                        }) { Text("Снять всё") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Без VPN (палят туннель — freeze при VPN on)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                items(list.restricted) { cat ->
                    CategoryCheckbox(
                        label = cat.label,
                        count = cat.packages.size,
                        checked = restrictedChecks.value[cat.id] == true,
                        onCheckedChange = { checked ->
                            val m = restrictedChecks.value.toMutableMap()
                            m[cat.id] = checked
                            restrictedChecks.value = m
                        },
                    )
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Только VPN (требуют туннель — freeze при VPN off)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                items(list.vpnOnly) { cat ->
                    CategoryCheckbox(
                        label = cat.label,
                        count = cat.packages.size,
                        checked = vpnOnlyChecks.value[cat.id] == true,
                        onCheckedChange = { checked ->
                            val m = vpnOnlyChecks.value.toMutableMap()
                            m[cat.id] = checked
                            vpnOnlyChecks.value = m
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val restrictedPkgs = list.restricted
                    .filter { restrictedChecks.value[it.id] == true }
                    .flatMap { it.packages }
                    .toSet()
                val restrictedPrefs = if (includePrefixes) list.prefixPatterns else emptyList()
                val vpnOnlyPkgs = list.vpnOnly
                    .filter { vpnOnlyChecks.value[it.id] == true }
                    .flatMap { it.packages }
                    .toSet()
                onApply(restrictedPkgs, restrictedPrefs, vpnOnlyPkgs)
            }) { Text("Применить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

private fun relativeTime(ms: Long): String {
    val delta = System.currentTimeMillis() - ms
    val minutes = delta / 60_000L
    val hours = delta / 3_600_000L
    val days = delta / (24 * 3_600_000L)
    return when {
        delta < 60_000L -> "только что"
        minutes < 60 -> "$minutes мин назад"
        hours < 24 -> "$hours ч назад"
        days < 30 -> "$days дн назад"
        else -> "давно"
    }
}

@Composable
private fun CategoryCheckbox(
    label: String,
    count: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "$count",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GroupBadge(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Card(
            modifier = Modifier.size(12.dp),
            colors = CardDefaults.cardColors(containerColor = color)
        ) {}
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AppRow(app: InstalledAppInfo, isKnownRestricted: Boolean, onCycleGroup: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager

    val iconBitmap = remember(app.packageName) {
        try {
            val drawable = pm.getApplicationIcon(app.packageName)
            val bmp = Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    val containerColor = when (app.group) {
        AppGroup.LOCAL -> MaterialTheme.colorScheme.errorContainer
        AppGroup.VPN_ONLY -> MaterialTheme.colorScheme.tertiaryContainer
        AppGroup.LAUNCH_VPN -> MaterialTheme.colorScheme.primaryContainer
        null -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCycleGroup() },
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App icon
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = if (app.isDisabled) "${app.label}, заморожено" else app.label,
                    modifier = Modifier.size(40.dp),
                    colorFilter = if (app.isDisabled) grayscaleFilter else null
                )
                Spacer(Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        app.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (app.isDisabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.onSurface
                    )
                    if (isKnownRestricted) {
                        Text(
                            " *",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Group badge
            Text(
                when (app.group) {
                    AppGroup.LOCAL -> "Без VPN"
                    AppGroup.VPN_ONLY -> "VPN"
                    AppGroup.LAUNCH_VPN -> "С VPN"
                    null -> "—"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = when (app.group) {
                    AppGroup.LOCAL -> MaterialTheme.colorScheme.error
                    AppGroup.VPN_ONLY -> MaterialTheme.colorScheme.tertiary
                    AppGroup.LAUNCH_VPN -> MaterialTheme.colorScheme.primary
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
