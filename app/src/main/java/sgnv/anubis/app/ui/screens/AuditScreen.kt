package sgnv.anubis.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Intent
import androidx.lifecycle.viewmodel.compose.viewModel
import sgnv.anubis.app.audit.AppTrafficProbe
import sgnv.anubis.app.audit.AuditViewModel
import sgnv.anubis.app.audit.HoneypotDebug
import sgnv.anubis.app.audit.HoneypotListener
import sgnv.anubis.app.audit.PortState
import sgnv.anubis.app.audit.PortStatus
import sgnv.anubis.app.audit.model.AuditSuspect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AuditScreen(
    vpnActive: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    auditViewModel: AuditViewModel = viewModel(),
) {
    val suspects by auditViewModel.suspects.collectAsState()
    val portStatus by auditViewModel.portStatus.collectAsState()
    val running by auditViewModel.running.collectAsState()
    val debug by auditViewModel.debug.collectAsState()
    val decoyActive by auditViewModel.decoyActive.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Фоновый режим — читаем/пишем через AuditController напрямую (не через
    // MainViewModel). AuditScreen открывается из разных мест (Settings, Home),
    // MainViewModel шарится, но AuditViewModel свой — не хочется тянуть зависимость.
    var backgroundEnabled by remember {
        mutableStateOf(
            sgnv.anubis.app.service.AuditController.isBackgroundEnabled(context)
        )
    }
    var decoyWithBackground by remember {
        mutableStateOf(
            sgnv.anubis.app.service.AuditController.isDecoyWithBackgroundEnabled(context)
        )
    }

    // Launcher для системного диалога VPN-consent. Если user согласился —
    // реально поднимаем декой-tun0.
    val vpnConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            auditViewModel.startDecoyVpn()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("‹ Назад", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "Ловушки для сканеров",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Поднимаем фейковые SOCKS5/HTTP/Tor прокси на localhost и ловим тех, кто к ним ломится. " +
                "Это именно тот набор портов, которые методичка Минцифры советует сканить приложениям " +
                "для детекции VPN. Если видите кого-то в списке — он точно пытается вас спалить.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        // ── Фоновый режим (держит ловушки активными 24/7) ──────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (backgroundEnabled)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Круглосуточно",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (backgroundEnabled)
                                "Ловушки висят в фоне, уведомление в шторке, авто-перезапуск после ребута"
                            else
                                "Включите — и ловушки будут жить без открытого Anubis",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = backgroundEnabled,
                        onCheckedChange = { enabled ->
                            backgroundEnabled = enabled
                            if (enabled) {
                                sgnv.anubis.app.service.AuditController.start(
                                    context, persistPreference = true,
                                )
                            } else {
                                sgnv.anubis.app.service.AuditController.stop(
                                    context, persistPreference = true,
                                )
                            }
                        },
                    )
                }
                if (backgroundEnabled) {
                    androidx.compose.material3.HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "С приманкой VPN",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "soft-tun0 без блокировки трафика. Детекторы видят VPN и идут " +
                                    "сканить localhost. ~5% батареи в сутки.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = decoyWithBackground,
                            onCheckedChange = { enabled ->
                                decoyWithBackground = enabled
                                sgnv.anubis.app.service.AuditController.setDecoyWithBackground(
                                    context, enabled,
                                )
                            },
                        )
                    }
                    androidx.compose.material3.HorizontalDivider()
                    BatteryOnboardingChecklist()
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (vpnActive && !running) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "VPN включён",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "VLESS-клиент уже держит один из нужных нам портов — не все ловушки запустятся. " +
                            "Для честной проверки выключите VPN и размораживайте подозрительные приложения " +
                            "по одному перед запуском аудита.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!running) {
                Button(
                    onClick = { auditViewModel.start() },
                    modifier = Modifier.weight(1f),
                    // Одноразовый запуск — пока экран открыт. Для постоянного —
                    // тогл «Круглосуточно» сверху.
                ) { Text("Разово") }
            } else {
                FilledTonalButton(
                    onClick = {
                        if (backgroundEnabled) {
                            // Если активен фоновый режим — останавливаем через
                            // Controller (чтобы FGS тоже убился).
                            sgnv.anubis.app.service.AuditController.stop(
                                context, persistPreference = true,
                            )
                            backgroundEnabled = false
                        } else {
                            auditViewModel.stop()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Стоп") }
            }
            OutlinedButton(
                onClick = {
                    coroutineScope.launch {
                        val json = auditViewModel.exportAsJson()
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_TEXT, json)
                            putExtra(
                                Intent.EXTRA_SUBJECT,
                                "anubis-audit-${System.currentTimeMillis()}.json",
                            )
                        }
                        context.startActivity(
                            Intent.createChooser(share, "Экспорт аудита")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Экспорт") }
            OutlinedButton(
                onClick = { auditViewModel.clearHits() },
                modifier = Modifier.weight(1f),
            ) { Text("Очистить") }
        }

        Spacer(Modifier.height(16.dp))

        PortStatusRow(portStatus)

        Spacer(Modifier.height(12.dp))

        if (!vpnActive) {
            DecoyVpnCard(
                decoyActive = decoyActive,
                onToggle = {
                    if (decoyActive) {
                        auditViewModel.stopDecoyVpn()
                    } else {
                        val consent = auditViewModel.prepareDecoyVpnIntent()
                        if (consent == null) {
                            auditViewModel.startDecoyVpn()
                        } else {
                            vpnConsentLauncher.launch(consent)
                        }
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
        }

        DebugCard(debug, running)

        Spacer(Modifier.height(16.dp))

        // Heat-map: 24 столбца по часам. Показываем только когда хоть один хит
        // за сегодня есть — иначе просто пустой ряд серых точек, бессмысленно.
        val hitsByHour by auditViewModel.hitsByHourToday.collectAsState()
        if (hitsByHour.any { it > 0 }) {
            Spacer(Modifier.height(12.dp))
            HitsHeatMap(hitsByHour)
            Spacer(Modifier.height(12.dp))
        }

        // ── Активный срез трафика ─────────────────────────────────────
        Spacer(Modifier.height(12.dp))
        LaunchedEffect(Unit) { auditViewModel.refreshProbeCandidates() }
        TrafficProbeCard(auditViewModel)

        Spacer(Modifier.height(16.dp))

        if (suspects.isEmpty()) {
            Text(
                when {
                    running -> "Слушаем… пока никто не просканил. Запустите подозреваемые приложения " +
                        "или подождите фоновой активности."
                    else -> "Нажмите «Начать аудит», чтобы поднять ловушки."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "Пойманы (${suspects.size}):",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            // Column вместо LazyColumn: родительский Column уже verticalScroll,
            // nested LazyColumn даёт "infinite height constraints" краш.
            // Suspects — 0..~20, перерисовка всех на update не узкое место.
            for (suspect in suspects) {
                SuspectCard(
                    suspect = suspect,
                    labelFor = auditViewModel::labelFor,
                    onMarkLocal = { auditViewModel.markSuspectAsLocal(suspect) },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * Чек-лист что нужно сделать чтобы фоновый аудит реально жил на телефоне.
 * Notifications и battery whitelist детектируем программно (LaunchedEffect
 * перечитает при возврате с системного экрана через onResume). Honor
 * «Запуск приложений» детектировать нельзя — только флажком «я сделал».
 */
@Composable
private fun BatteryOnboardingChecklist() {
    val context = LocalContext.current
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle

    // Пересчитываем статусы когда Activity возвращается в RESUMED — пользователь
    // мог только что переключить setting на системном экране.
    var notificationsOk by remember {
        mutableStateOf(sgnv.anubis.app.service.BatteryOnboarding.areNotificationsEnabled(context))
    }
    var batteryOk by remember {
        mutableStateOf(sgnv.anubis.app.service.BatteryOnboarding.isIgnoringBatteryOptimizations(context))
    }
    var honorOk by remember {
        mutableStateOf(sgnv.anubis.app.service.BatteryOnboarding.isHonorStartupConfirmed(context))
    }
    val isHonor = remember { sgnv.anubis.app.service.BatteryOnboarding.isHonorOrHuawei() }

    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                notificationsOk =
                    sgnv.anubis.app.service.BatteryOnboarding.areNotificationsEnabled(context)
                batteryOk =
                    sgnv.anubis.app.service.BatteryOnboarding.isIgnoringBatteryOptimizations(context)
                honorOk = sgnv.anubis.app.service.BatteryOnboarding.isHonorStartupConfirmed(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Column(Modifier.padding(16.dp)) {
        Text(
            "Что нужно проверить:",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))

        ChecklistRow(
            done = notificationsOk,
            title = "Уведомления разрешены",
            subtitle = "Без них не увидите ловушек в шторке",
            actionLabel = "Открыть",
            onAction = { sgnv.anubis.app.service.BatteryOnboarding.openAppNotificationSettings(context) },
        )

        ChecklistRow(
            done = batteryOk,
            title = "Оптимизация батареи отключена",
            subtitle = "Иначе Doze mode усыпит ловушки ночью",
            actionLabel = "Отключить",
            onAction = { sgnv.anubis.app.service.BatteryOnboarding.requestBatteryWhitelist(context) },
        )

        if (isHonor) {
            ChecklistRow(
                done = honorOk,
                title = "Honor: «Запуск приложений» — ручное управление",
                subtitle = "MagicOS убивает сервис независимо от battery whitelist. " +
                    "Откройте экран, найдите Anubis, переключите все тумблеры ON, " +
                    "вернитесь сюда и отметьте что сделали.",
                actionLabel = "Открыть",
                onAction = { sgnv.anubis.app.service.BatteryOnboarding.openHonorStartupControl(context) },
                showConfirmButton = !honorOk,
                onConfirm = {
                    sgnv.anubis.app.service.BatteryOnboarding.setHonorStartupConfirmed(context, true)
                    honorOk = true
                },
            )
        }
    }
}

@Composable
private fun ChecklistRow(
    done: Boolean,
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
    showConfirmButton: Boolean = false,
    onConfirm: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            if (done) "✓ " else "○ ",
            style = MaterialTheme.typography.titleMedium,
            color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(end = 8.dp, top = 2.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!done) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onAction) { Text(actionLabel) }
                    if (showConfirmButton && onConfirm != null) {
                        TextButton(onClick = onConfirm) { Text("✓ Сделал") }
                    }
                }
            }
        }
    }
}

/**
 * Heat-map активности сканеров за сегодня. 24 столбца по часам 0..23.
 * Высота столбца пропорциональна max-бакету (нормализуется). Нулевые часы —
 * тонкая серая линия; активные — яркие primary. Цель: пользователь сразу
 * видит «банк просыпается в 23:15» или «все пики в 02:00-04:00».
 */
@Composable
private fun HitsHeatMap(hitsByHour: IntArray) {
    val max = hitsByHour.max().coerceAtLeast(1)
    val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Активность сканеров за сегодня",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                for (hour in 0..23) {
                    val count = hitsByHour[hour]
                    val heightFraction = if (count == 0) 0.04f else (count.toFloat() / max).coerceAtLeast(0.1f)
                    val isNow = hour == currentHour
                    val barColor = when {
                        count == 0 -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        isNow -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Column(
                        modifier = Modifier.weight(1f).padding(horizontal = 1.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((56 * heightFraction).dp)
                                .semantics {
                                    contentDescription = "час $hour, хитов $count"
                                },
                        ) {
                            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                drawRect(barColor)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            // Метки времени: 00, 06, 12, 18 — чтобы не лепить 24 числа.
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf(0, 6, 12, 18, 23).forEach { h ->
                    Text(
                        String.format("%02d", h),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun PortStatusRow(status: Map<Int, PortStatus>) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (port in HoneypotListener.PORTS) {
            val s = status[port] ?: PortStatus(port, PortState.STOPPED, null)
            val (bg, fg, label) = when (s.state) {
                PortState.LISTENING -> Triple(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                    "слушаю",
                )
                PortState.BUSY -> Triple(
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                    "занят",
                )
                PortState.STOPPED -> Triple(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    "—",
                )
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = bg),
                modifier = Modifier
                    .padding(0.dp)
                    .semantics(mergeDescendants = true) {
                        // Две Text'ы внутри Card (номер + статус) читались по отдельности —
                        // сливаем в одну accessible-нотацию: «порт 1080, слушаю».
                        contentDescription = "порт $port, $label"
                    },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        port.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = fg,
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = fg,
                    )
                }
            }
        }
    }
}

@Composable
private fun DecoyVpnCard(
    decoyActive: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (decoyActive) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (decoyActive) "Приманка VPN: ВКЛЮЧЕНА" else "Приманка VPN",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (decoyActive) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (decoyActive) {
                    "Поднят soft-tun0 без default-route — интернет на устройстве работает " +
                        "нормально. Детекторы видят TRANSPORT_VPN + интерфейс tun0 и должны " +
                        "триггернуть scan 127.0.0.1. Можно оставить фоном на часы/сутки — auto-stop 24ч."
                } else {
                    "Поднимет фейковый VPN (soft-tun0) без блокировки трафика. Интернет работает, " +
                        "детекторы видят VPN по TRANSPORT_VPN и tun0, идут сканить localhost. " +
                        "Безопасно оставлять надолго — auto-stop через 24ч, выключить можно из " +
                        "шторки, этой кнопкой, или в системном VPN-диалоге."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (decoyActive) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(8.dp))
            if (decoyActive) {
                FilledTonalButton(
                    onClick = onToggle,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Выключить приманку") }
            } else {
                Button(
                    onClick = onToggle,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Включить приманку VPN") }
            }
        }
    }
}

@Composable
private fun DebugCard(debug: HoneypotDebug, running: Boolean) {
    // Живой таймер: StateFlow debug не эмитит новых значений пока никто не
    // коннектится к honeypot, а значит без внешнего пинка recomposition не
    // случается и ageSec застынет на момент последней перерисовки (обычно
    // через пару секунд после старта). produceState с key = running — при
    // старте пускает корутину, которая раз в секунду обновляет value; при
    // stop() корутина отменяется, значение замирает на последнем тике.
    val now by produceState(initialValue = System.currentTimeMillis(), running) {
        while (running) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val ageSec = debug.startedAtMs?.let { (now - it) / 1000 } ?: 0
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                "Debug-срез",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("состояние: ")
                    append(if (running) "работает ${ageSec}с" else "остановлен")
                    append("\n")
                    append("портов слушается: ${debug.portsListening.size}/${HoneypotListener.PORTS.size}")
                    if (debug.portsFailed.isNotEmpty()) {
                        append(" · не удалось: ${debug.portsFailed.sorted().joinToString()}")
                    }
                    append("\n")
                    append("accept(): ${debug.accepts}")
                    debug.lastAcceptPort?.let { append(" · последний порт: $it") }
                    append("\n")
                    append("резолв uid: ${debug.resolvedUids} · резолв pkg: ${debug.resolvedPkgs}")
                    debug.lastError?.let {
                        append("\n⚠ ")
                        append(it)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SuspectCard(
    suspect: AuditSuspect,
    labelFor: (String) -> String,
    onMarkLocal: () -> Unit,
) {
    val label = remember(suspect.packageName) {
        suspect.packageName?.let(labelFor) ?: suspect.displayName
    }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            suspect.packageName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append("Подключений: ${suspect.hitCount} · порты: ${suspect.portsSeen.sorted().joinToString()}")
                    val protos = suspect.protocolsSeen.sorted()
                    if (protos.isNotEmpty() && protos != listOf("TCP")) {
                        append(" · протокол: ${protos.joinToString("/")}")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Последнее: ${timeFmt.format(Date(suspect.lastSeenMs))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            suspect.lastHandshakePreview?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Рукопожатие: $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (suspect.sniSeen.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "TLS SNI: ${suspect.sniSeen.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (suspect.packageName != null) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = onMarkLocal,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("→ в группу «Локальные»")
                }
            }
        }
    }
}

/**
 * Карточка «Срез трафика». Пассивный honeypot ловит только тех кто сам стучится
 * в localhost — а банки чаще делают пробы в момент запуска и не на loopback.
 * Здесь — активный срез: выбираем managed-пакет, через Shizuku force-stop +
 * am start, N секунд пасём /proc/net/tcp[6]+udp[6], фильтруем по UID пакета,
 * показываем уникальные remote endpoints. «Что Сбер делал в первые 15 секунд».
 */
@Composable
private fun TrafficProbeCard(vm: AuditViewModel) {
    val state by vm.probeState.collectAsState()
    val candidates by vm.probeCandidates.collectAsState()
    var showPicker by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Срез трафика приложения",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Запускает выбранное приложение и 15 секунд смотрит в /proc/net/tcp — " +
                    "показывает куда оно реально ходит при старте. Ловит активное " +
                    "сканирование и утечки мимо туннеля. Пассивные детекторы (спросил " +
                    "ConnectivityManager и всё) — увидеть не сможем.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            when (val s = state) {
                is AuditViewModel.ProbeState.Idle -> {
                    Button(
                        onClick = { showPicker = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = candidates.isNotEmpty(),
                    ) {
                        Text(
                            if (candidates.isEmpty()) "Нет managed-приложений"
                            else "Выбрать приложение для среза",
                        )
                    }
                    if (candidates.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Отметьте хотя бы одно приложение в AppList как LOCAL/VPN_ONLY/LAUNCH_VPN, чтобы выбрать его для среза.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is AuditViewModel.ProbeState.Running -> {
                    Text(
                        "Снимаю срез: ${s.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { s.elapsedSec.toFloat() / s.totalSec.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${s.elapsedSec}/${s.totalSec}с · найдено endpoint'ов: ${s.foundSoFar}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { vm.clearProbe() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Отменить") }
                }
                is AuditViewModel.ProbeState.Done -> {
                    Text(
                        "Срез: ${s.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Endpoint'ов найдено: ${s.endpoints.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (s.endpoints.isEmpty()) {
                        Text(
                            "Приложение не открыло ни одного коннекта за окно наблюдения. " +
                                "Либо Application.onCreate отложен (WorkManager/JobScheduler), " +
                                "либо всё через native NDK с sub-second handshake.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // Сначала loopback — это самые интересные, если есть хоть один
                        // → прямое доказательство что приложение пробует SOCKS5/Tor-порты.
                        val (loopback, external) = s.endpoints.partition {
                            it.remoteIp == "127.0.0.1" || it.remoteIp == "::1"
                        }
                        if (loopback.isNotEmpty()) {
                            Text(
                                "⚠ Коннекты на loopback (${loopback.size}):",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error,
                            )
                            for (ep in loopback) ProbeEndpointRow(ep, highlight = true)
                            Spacer(Modifier.height(6.dp))
                        }
                        Text(
                            "Внешние (${external.size}):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        for (ep in external.take(40)) ProbeEndpointRow(ep, highlight = false)
                        if (external.size > 40) {
                            Text(
                                "… и ещё ${external.size - 40}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.runProbe(s.packageName) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Ещё раз") }
                        OutlinedButton(
                            onClick = { vm.clearProbe() },
                            modifier = Modifier.weight(1f),
                        ) { Text("Закрыть") }
                    }
                }
                is AuditViewModel.ProbeState.Error -> {
                    Text(
                        "Ошибка среза: ${s.packageName}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        s.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { vm.clearProbe() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Закрыть") }
                }
            }
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Отмена") }
            },
            title = { Text("Выбрать приложение") },
            text = {
                // Высота ограничена, чтобы dialog не выпадал за экран.
                LazyColumn(modifier = Modifier.fillMaxWidth().height(380.dp)) {
                    items(candidates, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showPicker = false
                                    vm.runProbe(app.packageName)
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    app.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            app.group?.let {
                                Text(
                                    it.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            },
        )
    }
}

@Composable
private fun ProbeEndpointRow(ep: AppTrafficProbe.Endpoint, highlight: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${ep.protocol}/${ep.state}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            "${ep.remoteIp}:${ep.remotePort}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            color = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            "+${ep.firstSeenElapsedSec}с",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
