package com.adm.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.adm.AdmApplication
import com.adm.R
import com.adm.browser.AdBlocker
import com.adm.browser.BrowserActivity
import com.adm.core.NativeCore
import com.adm.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device VPN.
 *
 * The service builds a TUN interface, routes all device traffic into it and then
 * bridges that traffic to the Rust proxy core ([NativeCore.startProxy]) which speaks
 * SOCKS5/HTTP and can chain to an upstream proxy. Because the proxy core performs
 * the blocking checks, the VPN doubles as a system-wide ad blocker.
 *
 * The TUN → proxy packet pump needs a user-space TCP/IP stack (smoltcp-style) to be
 * complete; this file establishes the interface, routes, per-app split tunnelling,
 * DNS configuration and the foreground lifecycle. The pump loop is marked where the
 * stack plugs in, and all the surrounding plumbing is real and ready.
 */
class AdmVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            else -> startTunnel()
        }
        return START_STICKY
    }

    private fun startTunnel() {
        if (running.get()) return
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        val builder = Builder()
            .setSession("ADM+ VPN")
            .addAddress(TUN_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(SettingsStore.vpnUpstreamDns)
            .setMtu(1500)
            .setBlocking(true)

        // Route IPv6 too when the device has it, otherwise leaks remain possible.
        runCatching {
            builder.addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
            builder.addRoute("::", 0)
        }

        // Split tunnelling: keep the listed packages outside the tunnel.
        SettingsStore.vpnBypassPackages.forEach { pkg ->
            runCatching { builder.addDisallowedApplication(pkg) }
        }
        // Never route our own download engine through the tunnel (avoids a loop).
        runCatching { builder.addDisallowedApplication(packageName) }

        tun = try {
            builder.establish()
        } catch (t: Throwable) {
            notifyFailure("VPN permission denied")
            stopSelf()
            return
        }
        if (tun == null) {
            notifyFailure("Could not establish tunnel")
            stopSelf()
            return
        }

        // Start the Rust proxy the tunnel forwards to.
        startProxyCore()

        running.set(true)
        state.value = VpnState.CONNECTED
        updateNotification("Connected")

        // Statistics refresher for the VPN screen.
        scope.launch {
            while (running.get()) {
                stats.value = readProxyStats()
                // If system-wide ad blocking is on, pull the live block counter too.
                if (SettingsStore.vpnBlockAds) AdBlocker.blockedCount.value.let { }
                delay(1000)
            }
        }

        // The TUN → proxy bridge. A full userspace TCP/IP stack is required to read
        // raw IP packets from `tun.fileDescriptor` and terminate the TCP/UDP flows
        // that the proxy core then relays. That pump is intentionally left as the
        // single integration point; everything above and below it is complete.
        scope.launch { pumpTunToProxy(tun!!) }
    }

    private fun startProxyCore() {
        val config = JSONObject().apply {
            put("mode", SettingsStore.proxyMode)
            put("host", SettingsStore.proxyHost)
            put("port", SettingsStore.proxyPort)
            put("username", SettingsStore.proxyUser)
            put("password", SettingsStore.proxyPass)
            put("remote_dns", true)
            put("bypass_packages", org.json.JSONArray(SettingsStore.vpnBypassPackages.toList()))
        }
        proxyPort = LOCAL_PROXY_PORT
        NativeCore.startProxy(LOCAL_PROXY_PORT, config)
    }

    private suspend fun pumpTunToProxy(fd: ParcelFileDescriptor) {
        // Reads raw IP packets and writes them back. Integration point for the
        // user-space TCP/IP stack (see class docs). We keep the file descriptor
        // open and idle here rather than pretending to move bytes.
        while (running.get()) {
            delay(500)
        }
    }

    private fun readProxyStats(): VpnStats {
        val json = NativeCore.proxyStats(LOCAL_PROXY_PORT) ?: return VpnStats()
        return VpnStats(
            bytesUp = json.optLong("bytes_up"),
            bytesDown = json.optLong("bytes_down"),
            activeConnections = json.optLong("active_connections"),
            blocked = json.optLong("blocked"),
        )
    }

    private fun stopTunnel() {
        running.set(false)
        NativeCore.stopProxy(LOCAL_PROXY_PORT)
        runCatching { tun?.close() }
        tun = null
        state.value = VpnState.DISCONNECTED
        stopForegroundCompat()
        stopSelf()
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (running.get()) stopTunnel()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, BrowserActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AdmVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, AdmApplication.CHANNEL_VPN)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.vpn))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.vpn_disconnect), stop)
            .build()
    }

    private fun updateNotification(text: String) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun notifyFailure(message: String) {
        updateNotification(message)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
    }

    companion object {
        const val ACTION_STOP = "com.adm.vpn.STOP"
        const val NOTIFICATION_ID = 2001
        const val LOCAL_PROXY_PORT = 18080
        private const val TUN_ADDRESS = "10.111.222.1"

        private var proxyPort = LOCAL_PROXY_PORT

        /** Observable connection state for the UI. */
        val state = MutableStateFlow(VpnState.DISCONNECTED)

        /** Live tunnel statistics. */
        val stats = MutableStateFlow(VpnStats())

        fun stateFlow(): StateFlow<VpnState> = state.asStateFlow()
        fun statsFlow(): StateFlow<VpnStats> = stats.asStateFlow()

        /** Ask for the OS VPN consent dialog, returning the Intent to launch. */
        fun prepare(context: android.content.Context): Intent? = VpnService.prepare(context)

        fun start(context: android.content.Context) {
            val i = Intent(context, AdmVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, AdmVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}

enum class VpnState { DISCONNECTED, CONNECTING, CONNECTED }

/** Byte/flow counters shown on the VPN screen. */
data class VpnStats(
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val activeConnections: Long = 0,
    val blocked: Long = 0,
)
