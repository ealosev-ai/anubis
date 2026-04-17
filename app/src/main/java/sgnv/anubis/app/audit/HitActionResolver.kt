package sgnv.anubis.app.audit

import android.util.Log
import sgnv.anubis.app.audit.model.AuditHit
import sgnv.anubis.app.shizuku.FreezeActions

/**
 * Чистая логика принятия решения по honeypot-хиту — без Android-нотификаций.
 *
 * HitNotifier берёт [decide] и по его результату рендерит (или не рендерит)
 * Notification. Вынесено отдельно чтобы юнит-тесты могли гонять все ветви
 * (off/ask/auto, skip-self, not-installed, freeze-failed, already-frozen)
 * без NotificationManager.
 */
class HitActionResolver(
    private val freeze: FreezeActions,
    private val selfPackage: String,
    private val modeProvider: () -> HitNotifier.Mode,
) {
    sealed interface Action {
        data object Skip : Action
        data class ShowAsk(val pkg: String, val hit: AuditHit) : Action
        /** freezeSkipped = true если пакет уже был заморожен ранее и freeze() не вызывался. */
        data class ShowAuto(val pkg: String, val hit: AuditHit, val freezeSkipped: Boolean) : Action
        data class FreezeFailed(val pkg: String, val hit: AuditHit, val error: Throwable?) : Action
    }

    suspend fun decide(hit: AuditHit): Action {
        val mode = modeProvider()
        if (mode == HitNotifier.Mode.OFF) return Action.Skip
        val pkg = hit.packageName ?: return Action.Skip
        if (pkg == selfPackage) return Action.Skip
        if (!freeze.isAppInstalled(pkg)) return Action.Skip

        return when (mode) {
            HitNotifier.Mode.ASK -> Action.ShowAsk(pkg, hit)
            HitNotifier.Mode.AUTO -> {
                if (freeze.isAppFrozen(pkg)) {
                    // Уже заморожен — нотификация всё равно нужна (пользователь узнаёт что поймали),
                    // но freezeApp не вызываем.
                    Action.ShowAuto(pkg, hit, freezeSkipped = true)
                } else {
                    val r = freeze.freezeApp(pkg)
                    if (r.isFailure) {
                        Log.w("HitActionResolver", "auto-freeze: не удалось заморозить $pkg", r.exceptionOrNull())
                        Action.FreezeFailed(pkg, hit, r.exceptionOrNull())
                    } else {
                        Action.ShowAuto(pkg, hit, freezeSkipped = false)
                    }
                }
            }
            HitNotifier.Mode.OFF -> Action.Skip
        }
    }
}
