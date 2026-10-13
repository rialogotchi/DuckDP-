package org.duckdns.dpi.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private lateinit var s: Settings
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var args: EditText
    private lateinit var sni: EditText
    private lateinit var port: EditText
    private lateinit var dns: EditText
    private lateinit var ipv6: Switch
    private lateinit var preset: Spinner
    private lateinit var find: Button
    private lateinit var findStatus: TextView
    private lateinit var logView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var strategies: List<String> = emptyList()
    private var finding = false

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) startVpn()
        else Toast.makeText(this, "VPN izni verilmedi", Toast.LENGTH_SHORT).show()
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestVpn() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        s = Settings(this)
        status = findViewById(R.id.status); toggle = findViewById(R.id.toggle)
        args = findViewById(R.id.args); sni = findViewById(R.id.sni); port = findViewById(R.id.port)
        dns = findViewById(R.id.dns); ipv6 = findViewById(R.id.ipv6); preset = findViewById(R.id.preset)
        find = findViewById(R.id.find); findStatus = findViewById(R.id.findStatus); logView = findViewById(R.id.log)

        strategies = assets.open("strategies.list").bufferedReader().readLines().filter { it.isNotBlank() }
        val names = listOf("Elle / varsayılan") + strategies.mapIndexed { i, st -> "${i + 1}. ${st.take(40)}" }
        preset.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        preset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos > 0) args.setText(strategies[pos - 1])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        args.setText(s.args); sni.setText(s.sni); port.setText(s.port.toString()); dns.setText(s.dns); ipv6.isChecked = s.ipv6

        toggle.setOnClickListener {
            when (VpnState.state) {
                VpnState.S.RUNNING, VpnState.S.STARTING ->
                    startService(Intent(this, DpiVpnService::class.java).setAction(DpiVpnService.ACTION_STOP))
                else -> if (save()) {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else requestVpn()
                }
            }
        }
        find.setOnClickListener { if (finding) Tester.cancelled = true else findStrategy() }
        VpnState.listener = { ui.post { render() } }
    }

    private fun save(): Boolean {
        val a = args.text.toString().trim()
        Settings.checkArgs(a)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show(); return false }
        val p = port.text.toString().toIntOrNull()
        if (p == null || p !in 1..65535) { Toast.makeText(this, "Port 1-65535 olmalı", Toast.LENGTH_SHORT).show(); return false }
        s.args = a; s.sni = sni.text.toString().trim().ifEmpty { "google.com" }; s.port = p
        s.dns = dns.text.toString().trim().ifEmpty { "1.1.1.1" }; s.ipv6 = ipv6.isChecked
        return true
    }

    private fun requestVpn() {
        val i = VpnService.prepare(this)
        if (i != null) vpnPermission.launch(i) else startVpn()
    }

    private fun startVpn() = startForegroundService(Intent(this, DpiVpnService::class.java))

    private fun findStrategy() {
        if (VpnState.state != VpnState.S.STOPPED && VpnState.state != VpnState.S.FAILED) {
            Toast.makeText(this, "Önce VPN'i kapatın", Toast.LENGTH_SHORT).show(); return
        }
        if (!save()) return
        finding = true; find.text = "İptal"
        Thread {
            val best = Tester.run(this, strategies) { done, total, b ->
                ui.post { findStatus.text = "Deneniyor $done/$total — en iyi: ${b?.score ?: 0}/${Tester.HOSTS.size}" }
            }
            ui.post {
                finding = false; find.text = "En iyi stratejiyi bul (Discord, Roblox, YouTube)"
                if (best != null && best.score > 0) {
                    args.setText(best.args)
                    findStatus.text = "Seçildi: ${best.score}/${Tester.HOSTS.size} site açıldı. Bağlan'a basın."
                } else findStatus.text = "Hiçbir strateji çalışmadı. DNS'i değiştirmeyi / Özel DNS'i açmayı deneyin."
            }
        }.start()
    }

    private fun render() {
        when (VpnState.state) {
            VpnState.S.STOPPED -> { status.text = "Kapalı"; toggle.text = "Bağlan" }
            VpnState.S.STARTING -> { status.text = "Başlıyor…"; toggle.text = "İptal" }
            VpnState.S.RUNNING -> { status.text = "Bağlı — tüm trafik ByeDPI üzerinden"; toggle.text = "Bağlantıyı kes" }
            VpnState.S.FAILED -> { status.text = "Hata: ${VpnState.message}"; toggle.text = "Tekrar dene" }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val f = VpnState.logFile(filesDir)
            logView.text = if (f.exists()) f.readLines().takeLast(40).joinToString("\n") else ""
            ui.postDelayed(this, 1500)
        }
    }

    override fun onResume() { super.onResume(); render(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }
    override fun onDestroy() { super.onDestroy(); VpnState.listener = null }
}
