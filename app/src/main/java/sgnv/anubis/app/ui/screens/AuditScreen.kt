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
                    Text(
                        "Honor / MagicOS 10+ (Android 16): Настройки → Приложения → Anubis → " +
                            "Батарея → «Ручное управление» → все тумблеры ON. Иначе система " +
                            "убивает сервис в фоне.\n\nЕсли уведомление не появилось в шторке — " +
                            "проверьте разрешение на уведомления в настройках приложения.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
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
