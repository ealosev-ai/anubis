package sgnv.anubis.app.audit

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.audit.model.AuditSuspect
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.model.InstalledAppInfo
import sgnv.anubis.app.service.StealthVpnService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * VM — тонкая обёртка над application-scoped audit-компонентами в [AnubisApp].
 * Listener и repository намеренно НЕ создаются здесь: они должны переживать
 * закрытие Activity, иначе фоновый аудит на ночь невозможен. Декой-VPN —
 * foreground service, держит процесс → listener в AnubisApp живёт вместе с ним.
 *
 * При recreate Activity новая VM подхватит те же инстансы и увидит уже
 * накопленных suspects / running-флаг / debug-счётчики.
 */
class AuditViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AnubisApp
    private val listener = app.auditListener
    private val repository = app.auditRepository

    val suspects: StateFlow<List<AuditSuspect>> = repository.suspects

    private val _portStatus = MutableStateFlow(
        HoneypotListener.PORTS.associateWith { PortStatus(it, PortState.STOPPED, null) }
    )
    val portStatus: StateFlow<Map<Int, PortStatus>> = _portStatus

    val debug: StateFlow<HoneypotDebug> = listener.debug

    /** running берётся из listener'а, а не держится локально — при recreate UI подхватит. */
    val running: StateFlow<Boolean> = listener.running

    /** decoyActive — StateFlow сервиса, так что UI синхронизирован с реальностью. */
    val decoyActive: StateFlow<Boolean> = StealthVpnService.decoyActive

    /**
     * Heat-map: 24 числа — кол-во хитов в часе 0..23 за сегодня.
     * Пересчитывается из сырых timestamp'ов (реактивный dao-flow).
     * Для heat-map хватает агрегации на view-side — 24 бакета это копейки.
     */
    val hitsByHourToday: StateFlow<IntArray> =
        app.auditRepository.timestampsSinceFlow(startOfTodayMs())
            .map { timestamps ->
                val buckets = IntArray(24)
                val cal = Calendar.getInstance()
                for (ts in timestamps) {
                    cal.timeInMillis = ts
                    val hour = cal.get(Calendar.HOUR_OF_DAY)
                    buckets[hour]++
                }
                buckets
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IntArray(24))

    private fun startOfTodayMs(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private var statusJob: Job? = null

    init {
        statusJob = viewModelScope.launch {
            listener.portStatus.collect { ps ->
                _portStatus.value = _portStatus.value + (ps.port to ps)
            }
        }
    }

    fun start() = listener.start()

    fun stop() = listener.stop()

    fun clearHits() {
        viewModelScope.launch { repository.clear() }
    }

    /** Возвращает JSON-дамп всех хитов для ShareSheet. */
    suspend fun exportAsJson(): String = repository.exportAsJson()

    fun markSuspectAsLocal(suspect: AuditSuspect) {
        val pkg = suspect.packageName ?: return
        viewModelScope.launch { repository.markAsLocal(pkg) }
    }

    /**
     * Проверить VPN-consent. Если `null` — можно сразу звать `startDecoyVpn()`.
     * Если Intent — его надо показать пользователю через `ActivityResultLauncher`.
     */
    fun prepareDecoyVpnIntent(): Intent? =
        StealthVpnService.prepareVpn(getApplication())

    /** Поднять soft-tun0 (после получения consent). */
    fun startDecoyVpn() {
        StealthVpnService.startDecoy(getApplication())
    }

    /** Опустить soft-tun0. */
    fun stopDecoyVpn() {
        StealthVpnService.stopDecoy(getApplication())
    }

    fun labelFor(packageName: String): String {
        val pm = getApplication<Application>().packageManager
        return try {
            pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    // ── Активный срез трафика ───────────────────────────────────────────

    private val probe = AppTrafficProbe(app.shizukuManager)

    /** Состояние среза — для UI. */
    sealed class ProbeState {
        data object Idle : ProbeState()
        data class Running(
            val packageName: String,
            val label: String,
            val elapsedSec: Int,
            val totalSec: Int,
            val foundSoFar: Int,
        ) : ProbeState()
        data class Done(
            val packageName: String,
            val label: String,
            val endpoints: List<AppTrafficProbe.Endpoint>,
        ) : ProbeState()
        data class Error(val packageName: String, val message: String) : ProbeState()
    }

    private val _probeState = MutableStateFlow<ProbeState>(ProbeState.Idle)
    val probeState: StateFlow<ProbeState> = _probeState

    /** Кандидаты для среза — managed apps. Пустой список если ничего не выбрано. */
    private val _probeCandidates = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val probeCandidates: StateFlow<List<InstalledAppInfo>> = _probeCandidates

    /** Обновить список кандидатов (вызывается при открытии секции). */
    fun refreshProbeCandidates() {
        viewModelScope.launch {
            val all = app.appRepository.getInstalledApps()
            // Показываем только managed — их пользователь уже отобрал как «интересные».
            // Остальные 300 системных пакетов в dropdown'е бесполезны.
            val managed = all.filter { it.group != null }.sortedBy { it.label.lowercase() }
            _probeCandidates.value = managed
        }
    }

    private var probeJob: Job? = null

    fun runProbe(packageName: String, durationSec: Int = 15) {
        // Отмена предыдущего среза — если пользователь кликнул по второму пакету
        // не дожидаясь окончания первого. По идее UI не даст, но на всякий.
        probeJob?.cancel()
        val label = labelFor(packageName)
        _probeState.value = ProbeState.Running(packageName, label, 0, durationSec, 0)
        probeJob = viewModelScope.launch {
            val result = probe.run(
                context = getApplication(),
                packageName = packageName,
                durationSec = durationSec,
                onTick = { elapsed, found ->
                    _probeState.value = ProbeState.Running(packageName, label, elapsed, durationSec, found)
                },
            )
            _probeState.value = result.fold(
                onSuccess = { endpoints -> ProbeState.Done(packageName, label, endpoints) },
                onFailure = { err -> ProbeState.Error(packageName, err.message ?: "сбой среза") },
            )
        }
    }

    fun clearProbe() {
        probeJob?.cancel()
        _probeState.value = ProbeState.Idle
    }

    override fun onCleared() {
        statusJob?.cancel()
        // НЕ вызываем listener.shutdown() — инстанс живёт в AnubisApp и должен
        // пережить закрытие Activity. Останавливаем только если пользователь
        // явно нажал «Остановить» (через stop()).
        super.onCleared()
    }
}
