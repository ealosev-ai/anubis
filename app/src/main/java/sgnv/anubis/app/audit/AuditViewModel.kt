package sgnv.anubis.app.audit

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.audit.model.AuditSuspect
import sgnv.anubis.app.service.StealthVpnService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

    override fun onCleared() {
        statusJob?.cancel()
        // НЕ вызываем listener.shutdown() — инстанс живёт в AnubisApp и должен
        // пережить закрытие Activity. Останавливаем только если пользователь
        // явно нажал «Остановить» (через stop()).
        super.onCleared()
    }
}
