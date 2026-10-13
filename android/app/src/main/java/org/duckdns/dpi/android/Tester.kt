package org.duckdns.dpi.android

import android.content.Context
import android.content.Intent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** "Find a strategy": runs each preset on a temporary port and counts how many test sites answer over TLS. */
object Tester {
    private const val TEST_PORT = 1081
    val HOSTS = listOf(
        "discord.com", "gateway.discord.gg", "cdn.discordapp.com", "discord.media",
        "roblox.com", "www.roblox.com", "www.youtube.com", "i.ytimg.com"
    )

    class Result(val args: String, val score: Int)

    @Volatile var cancelled = false

    /** Blocking; call off the main thread. Stops at the first preset that passes every host. */
    fun run(ctx: Context, strategies: List<String>, onProgress: (done: Int, total: Int, best: Result?) -> Unit): Result? {
        cancelled = false
        val s = Settings(ctx)
        var best: Result? = null
        val log = VpnState.logFile(ctx.filesDir).absolutePath + ".test"
        for ((i, strat) in strategies.withIndex()) {
            if (cancelled) break
            val args = s.buildArgs(forcePort = TEST_PORT, overrideArgs = strat)
            val svc = Intent(ctx, ProxyService::class.java)
                .putExtra(ProxyService.EXTRA_ARGS, args.toTypedArray())
                .putExtra(ProxyService.EXTRA_LOG, log)
            ctx.startService(svc)
            var score = 0
            if (waitPort(TEST_PORT)) {
                val pool = Executors.newFixedThreadPool(HOSTS.size)
                val futures = HOSTS.map { h -> pool.submit<Boolean> { check(h) } }
                score = futures.count { f -> try { f.get(15, TimeUnit.SECONDS) } catch (_: Exception) { false } }
                pool.shutdownNow()
            }
            ctx.stopService(svc)
            Thread.sleep(400) // let the :dpi process die before the next one starts
            if (best == null || score > best.score) best = Result(strat, score)
            onProgress(i + 1, strategies.size, best)
            if (score == HOSTS.size) break
        }
        return best
    }

    private fun waitPort(p: Int): Boolean {
        val end = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < end) {
            try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", p), 300) }; return true }
            catch (_: Exception) { Thread.sleep(150) }
        }
        return false
    }

    private fun check(host: String): Boolean = try {
        val raw = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", TEST_PORT)))
        raw.soTimeout = 5000
        raw.connect(InetSocketAddress.createUnresolved(host, 443), 5000)
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
        ssl.soTimeout = 5000
        ssl.startHandshake()
        ssl.outputStream.write("HEAD / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
        ssl.outputStream.flush()
        val line = BufferedReader(InputStreamReader(ssl.inputStream)).readLine()
        ssl.close()
        line != null && line.startsWith("HTTP/")
    } catch (_: Exception) { false }
}
