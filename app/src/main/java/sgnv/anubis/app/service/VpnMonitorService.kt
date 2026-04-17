package sgnv.anubis.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.R
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.ui.MainActivity

/**
 * Foreground service that monitors VPN state changes and auto-freezes app groups.
 *
 * When VPN turns ON:  freezes LOCAL group
 * When VPN turns OFF: freezes VPN_ONLY group
 *
 * This closes the gap where user toggles VPN outside of Anubis.
 */
class VpnMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                startVpnMonitoring()
            }
            ACTION_STOP -> {
                stopVpnMonitoring()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startVpnMonitoring() {
        if (networkCallback != null) return

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // VPN turned ON — freeze LOCAL group
                scope.launch { freezeGroup(AppGroup.LOCAL) }
            }

            override fun onLost(network: Network) {
                // VPN turned OFF — freeze VPN_ONLY group
                val stillActive = cm.allNetworks.any { n ->
                    cm.getNetworkCapabilities(n)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
                if (!stillActive) {
                    scope.launch { freezeGroup(AppGroup.VPN_ONLY) }
                }
            }
        }

        try {
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (_: Exception) {}
    }

    private fun stopVpnMonitoring() {
        networkCallback?.let {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
            networkCallback = null
        }
    }

    private suspend fun freezeGroup(group: AppGroup) {
        val app = applicationContext as AnubisApp
        val shizukuManager = app.shizukuManager
        if (!shizukuManager.awaitUserService()) return

        val packages = app.appRepository.getPackagesByGroup(group)
        for (pkg in packages) {
            if (shizukuManager.isAppInstalled(pkg) && !shizukuManager.isAppFrozen(pkg)) {
                shizukuManager.freezeApp(pkg)
            }
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AnubisApp.CHANNEL_ID)
            .setContentTitle("Anubis: мониторинг активен")
            .setContentText("Автозаморозка при изменении VPN")
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    override fun onDestroy() {
        stopVpnMonitoring()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "sgnv.anubis.app.START_MONITOR"
        const val ACTION_STOP = "sgnv.anubis.app.STOP_MONITOR"
        const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, VpnMonitorService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, VpnMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
