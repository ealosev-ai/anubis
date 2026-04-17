package sgnv.anubis.app.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import sgnv.anubis.app.update.UpdateChecker
import sgnv.anubis.app.update.UpdateInfo

/**
 * Всё что касается self-update: checking, enable/disable, source selection,
 * skip, dismiss. Жизненный цикл прикручен к scope ViewModel — он передаётся
 * снаружи, это упрощает тестирование (без Robolectric/AndroidViewModel).
 */
class UpdateController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo

    private val _updateCheckEnabled = MutableStateFlow(UpdateChecker.isEnabled(context))
    val updateCheckEnabled: StateFlow<Boolean> = _updateCheckEnabled

    private val _updateCheckInProgress = MutableStateFlow(false)
    val updateCheckInProgress: StateFlow<Boolean> = _updateCheckInProgress

    private val _updateSource = MutableStateFlow(UpdateChecker.getSource(context))
    val updateSource: StateFlow<String> = _updateSource

    /** Вызывается из MainViewModel.init после старта. */
    fun scheduleAutoCheck() {
        if (!UpdateChecker.isEnabled(context)) return
        scope.launch {
            delay(5000)
            val info = UpdateChecker.check(context, force = false) ?: return@launch
            if (UpdateChecker.shouldNotify(context, info)) {
                _updateInfo.value = info
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        _updateCheckEnabled.value = enabled
        UpdateChecker.setEnabled(context, enabled)
    }

    fun setSource(source: String) {
        UpdateChecker.setSource(context, source)
        _updateSource.value = UpdateChecker.getSource(context)
    }

    fun checkNow() {
        scope.launch {
            _updateCheckInProgress.value = true
            val info = UpdateChecker.check(context, force = true)
            _updateCheckInProgress.value = false
            _updateInfo.value = info
        }
    }

    fun dismiss() { _updateInfo.value = null }

    fun skipCurrent() {
        _updateInfo.value?.let { UpdateChecker.skipVersion(context, it.latestVersion) }
        _updateInfo.value = null
    }
}
