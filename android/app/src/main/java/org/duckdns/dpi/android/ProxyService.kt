package org.duckdns.dpi.android

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process

/**
 * Hosts ByeDPI in its own process (android:process=":dpi"). ByeDPI is written as a program: it keeps global
 * state and may call exit(), so it gets a process it can freely die in. Stopping = killing that process.
 */
class ProxyService : Service() {
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder = Binder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val args = intent?.getStringArrayExtra(EXTRA_ARGS)
        val log = intent?.getStringExtra(EXTRA_LOG)
        if (args != null && worker == null) {
            worker = Thread({
                NativeProxy.run(args, log)
                stopSelf()
            }, "ciadpi").also { it.start() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // the native main loop cannot be interrupted politely
        Process.killProcess(Process.myPid())
    }

    companion object {
        const val EXTRA_ARGS = "args"
        const val EXTRA_LOG = "log"
    }
}
