package org.duckdns.dpi.android

import java.io.File

/** Process-local state shared by the VPN service and the activity (both live in the main process). */
object VpnState {
    enum class S { STOPPED, STARTING, RUNNING, FAILED }

    @Volatile var state = S.STOPPED
    @Volatile var message = ""
    @Volatile var listener: (() -> Unit)? = null

    fun set(s: S, msg: String = "") {
        state = s; message = msg
        listener?.invoke()
    }

    fun logFile(dir: File) = File(dir, "byedpi.log")
}
