package sgnv.anubis.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.model.InstalledAppInfo
import sgnv.anubis.app.data.model.ManagedApp
import sgnv.anubis.app.data.repository.AppRepository
import sgnv.anubis.app.service.ShortcutActivity
import sgnv.anubis.app.shizuku.ShizukuManager

/**
 * Управление списком установленных приложений, раскладкой по группам
 * и созданием home-screen shortcuts. Извлечено из MainViewModel чтобы он
 * не разрастался и чтобы логика apps была независимой от остального.
 */
class AppListController(
    private val context: Context,
    private val repository: AppRepository,
    private val shizuku: ShizukuManager,
    private val scope: CoroutineScope,
) {
    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps

    private val _localApps = MutableStateFlow<List<ManagedApp>>(emptyList())
    val localApps: StateFlow<List<ManagedApp>> = _localApps

    private val _vpnOnlyApps = MutableStateFlow<List<ManagedApp>>(emptyList())
    val vpnOnlyApps: StateFlow<List<ManagedApp>> = _vpnOnlyApps

    private val _launchVpnApps = MutableStateFlow<List<ManagedApp>>(emptyList())
    val launchVpnApps: StateFlow<List<ManagedApp>> = _launchVpnApps

    fun isAppFrozen(packageName: String): Boolean = shizuku.isAppFrozen(packageName)

    fun loadInstalledApps() {
        scope.launch {
            _installedApps.value = repository.getInstalledApps()
        }
    }

    fun loadGroupedApps() {
        scope.launch {
            _localApps.value = repository.getAppsByGroup(AppGroup.LOCAL)
            _vpnOnlyApps.value = repository.getAppsByGroup(AppGroup.VPN_ONLY)
            _launchVpnApps.value = repository.getAppsByGroup(AppGroup.LAUNCH_VPN)
        }
    }

    fun cycleAppGroup(packageName: String) {
        scope.launch {
            repository.cycleGroup(packageName)
            _installedApps.value = repository.getInstalledApps()
            _localApps.value = repository.getAppsByGroup(AppGroup.LOCAL)
            _vpnOnlyApps.value = repository.getAppsByGroup(AppGroup.VPN_ONLY)
            _launchVpnApps.value = repository.getAppsByGroup(AppGroup.LAUNCH_VPN)
        }
    }

    fun removeFromGroup(packageName: String) {
        scope.launch {
            repository.removeApp(packageName)
            loadInstalledApps()
            loadGroupedApps()
        }
    }

    fun autoSelectRestricted() {
        scope.launch {
            repository.autoSelectRestricted()
            loadInstalledApps()
            loadGroupedApps()
        }
    }

    /**
     * Создать pinned shortcut на экране. Иконка рендерится в Bitmap поверх
     * app-icon; если что-то упало — fallback на системный sym_def_app_icon.
     */
    fun createShortcut(packageName: String) {
        scope.launch {
            val pm = context.packageManager
            val sm = context.getSystemService(ShortcutManager::class.java) ?: return@launch
            if (!sm.isRequestPinShortcutSupported) return@launch

            val group = repository.getAppGroup(packageName)
            val label = try {
                pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
            } catch (_: Exception) { packageName }

            val icon = try {
                val drawable = pm.getApplicationIcon(packageName)
                val bmp = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                Icon.createWithBitmap(bmp)
            } catch (_: Exception) {
                Icon.createWithResource(context, android.R.drawable.sym_def_app_icon)
            }

            val intent = Intent(context, ShortcutActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("package", packageName)
                putExtra("group", group?.name ?: AppGroup.LAUNCH_VPN.name)
            }

            val shortcutInfo = ShortcutInfo.Builder(context, "stealth_$packageName")
                .setShortLabel(label)
                .setLongLabel("$label (Stealth)")
                .setIcon(icon)
                .setIntent(intent)
                .build()

            sm.requestPinShortcut(shortcutInfo, null)
        }
    }
}
