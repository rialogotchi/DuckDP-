package org.duckdns.dpi.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import hev.htproxy.TProxyService
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The embedded local VPN. No root and no remote server: a TUN interface captures ALL traffic (TCP and UDP,
 * IPv4 and IPv6), hev-socks5-tunnel turns it into SOCKS5 (TCP CONNECT + UDP ASSOCIATE) and hands it to the
 * ByeDPI proxy running in the ":dpi" process, which desyncs it and sends it to the real network.
 * This app itself is excluded from the VPN so ByeDPI's own sockets do not loop back into the tunnel.
 */
class DpiVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var proxyIntent: Intent? = null
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {}
        override fun onServiceDisconnected(n: ComponentName?) {}
    }
    private var bound = false
    @Volatile private var generation = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAll()
            return START_NOT_STICKY
        }
        startAll()
        return START_NOT_STICKY
    }

    private fun startAll() {
        if (VpnState.state == VpnState.S.RUNNING || VpnState.state == VpnState.S.STARTING) return
        VpnState.set(VpnState.S.STARTING)
        startForegroundCompat()
        val gen = ++generation
        val s = Settings(this)
        val port = s.effectivePort()
        val log = VpnState.logFile(filesDir)

        val pi = Intent(this, ProxyService::class.java)
            .putExtra(ProxyService.EXTRA_ARGS, s.buildArgs().toTypedArray())
            .putExtra(ProxyService.EXTRA_LOG, log.absolutePath)
        proxyIntent = pi
        startService(pi)
        // BIND_IMPORTANT gives the :dpi process the importance of this foreground service
        bound = bindService(Intent(this, ProxyService::class.java), conn, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)

        Thread {
            if (!waitForPort(port, 6000)) {
                if (gen == generation) fail("ByeDPI başlamadı (port $port). Günlüğe bakın.")
                return@Thread
            }
            if (gen != generation) return@Thread
            try {
                establishTunnel(s, port)
                VpnState.set(VpnState.S.RUNNING)
            } catch (e: Exception) {
                fail("VPN kurulamadı: ${e.message}")
            }
        }.start()
    }

    private fun establishTunnel(s: Settings, port: Int) {
        val b = Builder()
            .setSession("Duck DPI")
            .setMtu(MTU)
            .addAddress("198.18.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(s.dns)
        if (s.ipv6) {
            b.addAddress("fc00::1", 128)
            b.addRoute("::", 0)
        }
        b.addDisallowedApplication(packageName) // our own (ByeDPI) sockets must reach the real network
        val fd = b.establish() ?: throw IllegalStateException("izin verilmedi")
        tun = fd

        val cfg = File(filesDir, "tproxy.yml")
        cfg.writeText(
            """
            tunnel:
              mtu: $MTU
              ipv4: 198.18.0.1
            ${if (s.ipv6) "  ipv6: 'fc00::1'" else ""}
            socks5:
              port: $port
              address: 127.0.0.1
              udp: 'udp'
            misc:
              task-stack-size: 81920
              connect-timeout: 5000
              read-write-timeout: 60000
              log-level: warn
            """.trimIndent() + "\n"
        )
        TProxyService.TProxyStartService(cfg.absolutePath, fd.fd)
    }

    private fun waitForPort(port: Int, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
                return true
            } catch (_: Exception) {
                Thread.sleep(150)
            }
        }
        return false
    }

    private fun fail(msg: String) {
        teardown()
        VpnState.set(VpnState.S.FAILED, msg)
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopAll() {
        generation++
        teardown()
        VpnState.set(VpnState.S.STOPPED)
        stopForegroundCompat()
        stopSelf()
    }

    private fun teardown() {
        try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        if (bound) { try { unbindService(conn) } catch (_: Exception) {}; bound = false }
        proxyIntent?.let { stopService(it) } // ProxyService.onDestroy kills the :dpi process
        proxyIntent = null
    }

    override fun onRevoke() { stopAll() } // user turned the VPN off in system settings

    override fun onDestroy() {
        if (VpnState.state != VpnState.S.FAILED) { teardown(); VpnState.set(VpnState.S.STOPPED) }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Duck DPI", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DpiVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Duck DPI çalışıyor")
            .setContentText("Yerel VPN: tüm trafik ByeDPI üzerinden")
            .setContentIntent(open)
            .addAction(0, "Durdur", stop)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 1, n, type)
    }

    private fun stopForegroundCompat() = ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

    companion object {
        const val ACTION_STOP = "org.duckdns.dpi.STOP"
        private const val CHANNEL = "vpn"
        private const val MTU = 1500
    }
}
