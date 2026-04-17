package sgnv.anubis.app.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sgnv.anubis.app.shizuku.FreezeMode
import sgnv.anubis.app.shizuku.ShizukuStatus
import sgnv.anubis.app.ui.MainViewModel
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.launch
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientControls
import sgnv.anubis.app.vpn.VpnClientType
import sgnv.anubis.app.vpn.VpnControlMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onOpenRecovery: () -> Unit = {},
    onOpenAudit: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val selectedClient by viewModel.selectedVpnClient.collectAsState()
    val installedClients by viewModel.installedVpnClients.collectAsState()
    val shizukuStatus by viewModel.shizukuStatus.collectAsState()
    var showAppPicker by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Spacer(Modifier.height(24.dp))

        Text("VPN-клиент", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Выберите VPN-клиент для управления",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        // Known clients
        VpnClientType.entries.forEach { client ->
            val isInstalled = installedClients.contains(client)
            val isSelected = selectedClient.packageName == client.packageName
            val isFrozen = isInstalled && !viewModel.isVpnClientEnabled(client.packageName)
            val control = VpnClientControls.getControl(client)

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable(enabled = isInstalled) {
                        viewModel.selectVpnClient(SelectedVpnClient.fromKnown(client))
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            client.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = if (isInstalled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                        val modeText = when {
                            !isInstalled -> "Не установлен"
                            isFrozen -> "Заморожен!"
                            control.mode == VpnControlMode.SEPARATE -> "Полное управление"
                            control.mode == VpnControlMode.TOGGLE -> "Авто вкл. / принудит. выкл."
                            else -> "Ручной режим"
                        }
                        Text(
                            modeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                isFrozen -> MaterialTheme.colorScheme.error
                                !isInstalled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                control.mode == VpnControlMode.SEPARATE -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    RadioButton(selected = isSelected, onClick = {
                        if (isInstalled) viewModel.selectVpnClient(SelectedVpnClient.fromKnown(client))
                    }, enabled = isInstalled)
                }
            }
        }

        // Custom client option
        val isCustomSelected = selectedClient.knownType == null
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { showAppPicker = true },
            colors = CardDefaults.cardColors(
                containerColor = if (isCustomSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isCustomSelected) "Другой: ${selectedClient.displayName}" else "Другой клиент...",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text("Выбрать любое приложение (ручной режим)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RadioButton(selected = isCustomSelected, onClick = { showAppPicker = true })
            }
        }

        Spacer(Modifier.height(24.dp))

        // Freeze mode
        Text("Режим заморозки", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "disable-user стабильнее, но на Honor/MagicOS ломает иконки в папках " +
                "лаунчера (шлёт PACKAGE_REMOVED). Режим suspend показывает системный " +
                "диалог «приостановлено» вместо запуска — иконки на месте.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        val freezeMode by viewModel.freezeMode.collectAsState()
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setFreezeMode(FreezeMode.DISABLE_USER) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("pm disable-user", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            "Классика. Приложение отключается полностью.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    RadioButton(
                        selected = freezeMode == FreezeMode.DISABLE_USER,
                        onClick = { viewModel.setFreezeMode(FreezeMode.DISABLE_USER) },
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setFreezeMode(FreezeMode.SUSPEND) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("pm suspend", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            "Android 7+. При тапе — диалог «приостановлено».",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    RadioButton(
                        selected = freezeMode == FreezeMode.SUSPEND,
                        onClick = { viewModel.setFreezeMode(FreezeMode.SUSPEND) },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Hit action mode (audit → freeze)
        Text("При обнаружении сканера", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Что делать когда honeypot ловит приложение, которое сканит " +
                "ваши VPN-порты. Действия обратимы — через RecoveryScreen или " +
                "экран «Приложения».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        val hitMode by viewModel.hitActionMode.collectAsState()
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                HitActionRow(
                    title = "Ничего",
                    subtitle = "только показываем в списке «Пойманные»",
                    selected = hitMode == "off",
                ) { viewModel.setHitActionMode("off") }
                HitActionRow(
                    title = "Спрашивать",
                    subtitle = "нотификация с кнопками «Заморозить» / «Отклонить»",
                    selected = hitMode == "ask",
                ) { viewModel.setHitActionMode("ask") }
                HitActionRow(
                    title = "Замораживать сразу",
                    subtitle = "добавить в LOCAL и заморозить; нотификация с кнопкой «Разморозить»",
                    selected = hitMode == "auto",
                ) { viewModel.setHitActionMode("auto") }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Background monitoring
        Text("Мониторинг", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        val bgMonitoring by viewModel.backgroundMonitoring.collectAsState()

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Фоновый мониторинг VPN", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Автозаморозка при изменении VPN вне Anubis",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = bgMonitoring,
                    onCheckedChange = { viewModel.setBackgroundMonitoring(it) },
                    modifier = Modifier.semantics {
                        stateDescription = if (bgMonitoring) "включено" else "выключено"
                    },
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // Shizuku status
        Text("Shizuku", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Статус", style = MaterialTheme.typography.bodyMedium)
                Text(
                    when (shizukuStatus) {
                        ShizukuStatus.READY -> "Подключён"
                        ShizukuStatus.NO_PERMISSION -> "Нет разрешения"
                        ShizukuStatus.UNAVAILABLE -> "Не доступен"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (shizukuStatus == ShizukuStatus.READY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // Backup / Restore groups
        BackupRestoreSection(viewModel = viewModel)

        Spacer(Modifier.height(12.dp))

        // Recovery entry
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenRecovery() }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Восстановление", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "Разморозить приложения, очистить группы",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Audit entry — honeypot VPN detectors
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenAudit() }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Аудит детекторов VPN", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "Honeypot: поймать приложения, которые сканят SOCKS5/HTTP/Tor на localhost",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(24.dp))

        // About
        Text("О приложении", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Anubis v${sgnv.anubis.app.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Управляет группами приложений и VPN-подключением. Изолирует приложения по группам для контроля сетевого доступа.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                val updateCheckEnabled by viewModel.updateCheckEnabled.collectAsState()
                val updateCheckInProgress by viewModel.updateCheckInProgress.collectAsState()
                val updateInfo by viewModel.updateInfo.collectAsState()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Проверять обновления", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Автоматическая проверка при запуске",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = updateCheckEnabled,
                        onCheckedChange = { viewModel.setUpdateCheckEnabled(it) },
                        modifier = Modifier.semantics {
                            stateDescription = if (updateCheckEnabled) "включено" else "выключено"
                        },
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Источник обновлений — по умолчанию свой форк,
                // тянуть upstream автоматически нельзя (форк сильно разошёлся).
                val updateSource by viewModel.updateSource.collectAsState()
                Text(
                    "Источник обновлений",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        "fork" to "Мой форк",
                        "upstream" to "Upstream",
                        "off" to "Выкл.",
                    ).forEach { (key, label) ->
                        Row(
                            modifier = Modifier
                                .clickable { viewModel.setUpdateSource(key) }
                                .padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = updateSource == key,
                                onClick = { viewModel.setUpdateSource(key) },
                            )
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { viewModel.checkForUpdatesNow() },
                        enabled = !updateCheckInProgress
                    ) {
                        Text(
                            if (updateCheckInProgress) "Проверка..." else "Проверить сейчас",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    updateInfo?.let { info ->
                        if (!info.isUpdateAvailable) {
                            Text(
                                "У вас последняя версия",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                val context = LocalContext.current
                TextButton(onClick = {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/sogonov/anubis")
                    )
                    context.startActivity(intent)
                }) {
                    Text("GitHub", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    // App picker bottom sheet
    if (showAppPicker) {
        val allApps by viewModel.installedApps.collectAsState()
        val context = LocalContext.current
        val pm = context.packageManager

        // Filter to apps that have INTERNET permission (likely VPN clients)
        val vpnCandidates = remember(allApps) {
            allApps.filter { !it.isSystem && !it.isDisabled }
                .sortedBy { it.label.lowercase() }
        }

        ModalBottomSheet(
            onDismissRequest = { showAppPicker = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Выберите VPN-клиент", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(400.dp)) {
                    items(vpnCandidates, key = { it.packageName }) { app ->
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
                            } catch (e: Exception) { null }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.selectVpnClient(SelectedVpnClient.fromPackage(app.packageName))
                                    showAppPicker = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (iconBitmap != null) {
                                Image(bitmap = iconBitmap, contentDescription = app.label, modifier = Modifier.size(36.dp))
                                Spacer(Modifier.width(12.dp))
                            }
                            Column {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

}

@Composable
private fun BackupRestoreSection(viewModel: MainViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val json = try {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (_: Exception) { null }
            if (json == null) {
                status = "Не удалось прочитать файл"
                return@launch
            }
            val n = viewModel.importGroupsJson(json, replaceAll = false)
            status = if (n < 0) "Битый JSON" else "Импортировано записей: $n"
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Бекап групп", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                "Экспортировать списки LOCAL/VPN_ONLY/LAUNCH_VPN в JSON, " +
                    "или восстановить из ранее сохранённого файла.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(onClick = {
                    coroutineScope.launch {
                        val json = viewModel.exportGroupsJson()
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_TEXT, json)
                            putExtra(Intent.EXTRA_SUBJECT, "anubis-groups-${System.currentTimeMillis()}.json")
                        }
                        context.startActivity(
                            Intent.createChooser(share, "Экспорт групп")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text("Экспорт") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                }) { Text("Импорт…") }
            }
            status?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun HitActionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}
