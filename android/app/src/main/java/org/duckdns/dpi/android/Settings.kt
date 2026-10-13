package org.duckdns.dpi.android

import android.content.Context

/** Same defaults as the web app (DpiSettings.java). */
class Settings(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    var args: String
        get() = sp.getString("args", DEFAULT_ARGS)!!
        set(v) = sp.edit().putString("args", v).apply()
    var sni: String
        get() = sp.getString("sni", "google.com")!!
        set(v) = sp.edit().putString("sni", v).apply()
    var port: Int
        get() = sp.getInt("port", 1080)
        set(v) = sp.edit().putInt("port", v).apply()
    var dns: String
        get() = sp.getString("dns", "1.1.1.1")!!
        set(v) = sp.edit().putString("dns", v).apply()
    var ipv6: Boolean
        get() = sp.getBoolean("ipv6", true)
        set(v) = sp.edit().putBoolean("ipv6", v).apply()

    /** ciadpi argv (after the program name): --ip/--port in front unless given, {sni} filled in. */
    fun buildArgs(forcePort: Int = 0, overrideArgs: String? = null): List<String> {
        var tokens = split(overrideArgs ?: args)
        if (forcePort > 0) tokens = dropOption(dropOption(tokens, "-p", "--port"), "-i", "--ip")
        val out = ArrayList<String>()
        if (!hasOption(tokens, "-i", "--ip")) { out += "--ip"; out += "127.0.0.1" }
        if (!hasOption(tokens, "-p", "--port")) { out += "--port"; out += (if (forcePort > 0) forcePort else port).toString() }
        tokens.forEach { out += it.replace("{sni}", sni) }
        return out
    }

    /** The port the proxy will really listen on (the args win, as in ByeByeDPI). */
    fun effectivePort(): Int {
        val t = split(args)
        for (i in t.indices) {
            val a = t[i]
            if (a == "--port" && i + 1 < t.size) return t[i + 1].toIntOrNull() ?: port
            if (a.startsWith("--port=")) return a.substring(7).toIntOrNull() ?: port
            if (a == "-p" && i + 1 < t.size) return t[i + 1].toIntOrNull() ?: port
            if (a.startsWith("-p") && !a.startsWith("--") && a.length > 2) return a.substring(2).toIntOrNull() ?: port
        }
        return port
    }

    companion object {
        const val DEFAULT_ARGS = "-c512 -b16384 -Kt,h -o1 -e97 -An -Ku -a1 -An"
        val FORBIDDEN = setOf("-h", "--help", "-v", "--version", "-D", "--daemon", "-w", "--pidfile")

        fun checkArgs(a: String): String? {
            for (t in split(a)) {
                val name = if (t.startsWith("--") && t.contains('=')) t.substringBefore('=') else t
                if (name in FORBIDDEN || (t.startsWith("-w") && !t.startsWith("--"))) return "$name kullanılamaz"
            }
            return null
        }

        fun split(text: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var quote = ' '; var quoting = false
            for (c in text) {
                when {
                    quoting && c == quote -> quoting = false
                    !quoting && (c == '\'' || c == '"') -> { quoting = true; quote = c }
                    !quoting && c.isWhitespace() -> if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) }
                    else -> cur.append(c)
                }
            }
            if (cur.isNotEmpty()) out += cur.toString()
            return out
        }

        private fun hasOption(t: List<String>, s: String, l: String) =
            t.any { it == l || it.startsWith("$l=") || it == s || (it.startsWith(s) && !it.startsWith("--")) }

        private fun dropOption(t: List<String>, s: String, l: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            while (i < t.size) {
                val a = t[i]
                val bare = a == l || a == s
                if (bare) i++ // its value is the next token
                else if (!(a.startsWith("$l=") || (a.startsWith(s) && !a.startsWith("--")))) out += a
                i++
            }
            return out
        }
    }
}
