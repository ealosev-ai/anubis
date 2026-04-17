package sgnv.anubis.app.audit

import android.net.ConnectivityManager
import android.system.OsConstants
import android.util.Log
import java.net.InetSocketAddress

private const val TAG = "NativeUidResolver"

/**
 * Быстрый путь резолва UID без Shizuku — через Android API
 * `ConnectivityManager.getConnectionOwnerUid` (API 29+).
 *
 * Работает только для "connected" коннектов где известны оба endpoint'а:
 * - TCP с ESTABLISHED-записью в /proc/net/tcp
 * - UDP только если клиент сделал connect() (rare)
 *
 * Для unconnected UDP (sendto) возвращает null — там ConnectivityManager
 * не может определить owner'а, падём на Shizuku /proc/net/udp.
 */
interface NativeUidResolver {
    fun resolveTcp(remotePort: Int, localHoneypotPort: Int): Int?
    fun resolveUdp(srcPort: Int, localHoneypotPort: Int): Int?
}

class AndroidNativeUidResolver(
    private val connectivity: ConnectivityManager,
) : NativeUidResolver {

    override fun resolveTcp(remotePort: Int, localHoneypotPort: Int): Int? {
        val local = InetSocketAddress("127.0.0.1", remotePort)
        val remote = InetSocketAddress("127.0.0.1", localHoneypotPort)
        val uid = try {
            connectivity.getConnectionOwnerUid(OsConstants.IPPROTO_TCP, local, remote)
        } catch (e: Exception) {
            Log.w(TAG, "getConnectionOwnerUid TCP threw: ${e.message}")
            return null
        }
        Log.d(TAG, "TCP resolve local=:$remotePort remote=:$localHoneypotPort → uid=$uid")
        return uid.takeIf { it > 0 && it != android.os.Process.INVALID_UID }
    }

    override fun resolveUdp(srcPort: Int, localHoneypotPort: Int): Int? {
        val local = InetSocketAddress("127.0.0.1", srcPort)
        val remote = InetSocketAddress("127.0.0.1", localHoneypotPort)
        val uid = try {
            connectivity.getConnectionOwnerUid(OsConstants.IPPROTO_UDP, local, remote)
        } catch (e: Exception) {
            Log.w(TAG, "getConnectionOwnerUid UDP threw: ${e.message}")
            return null
        }
        Log.d(TAG, "UDP resolve local=:$srcPort remote=:$localHoneypotPort → uid=$uid")
        return uid.takeIf { it > 0 && it != android.os.Process.INVALID_UID }
    }
}
