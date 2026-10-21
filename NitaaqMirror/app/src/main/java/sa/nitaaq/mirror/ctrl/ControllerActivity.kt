package sa.nitaaq.mirror.ctrl

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import sa.nitaaq.mirror.App
import sa.nitaaq.mirror.R
import sa.nitaaq.mirror.core.LinkState
import sa.nitaaq.mirror.core.PairInvite
import sa.nitaaq.mirror.core.PairingCrypto
import sa.nitaaq.mirror.net.LocalNet
import sa.nitaaq.mirror.net.TrustedPeer
import sa.nitaaq.mirror.net.TrustedStore
import sa.nitaaq.mirror.ui.BaseActivity
import sa.nitaaq.mirror.ui.Ui

/** Screen 3 on the controller: scan QR / manual pairing, and the list of saved hosts. */
class ControllerActivity : BaseActivity() {

    private val online = HashMap<String, LocalNet.Found>()
    @Volatile private var searching = false

    private val listener = object : RemoteSession.Listener {
        override fun onState(state: LinkState) { build() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RemoteSession.addListener(listener)
    }

    override fun onResume() {
        super.onResume()
        build()
        search()
    }

    override fun onDestroy() {
        RemoteSession.removeListener(listener)
        super.onDestroy()
    }

    private fun build() {
        val c = Ui.column(this, 14)
        c.addView(Ui.title(this, getString(R.string.role_controller)))
        c.addView(Ui.body(this, getString(R.string.this_device, App.osLine()), sizeSp = 13f))
        val net = LocalNet.info(this)
        if (!net.onWifi) c.addView(Ui.card(this, Ui.body(this, getString(R.string.ctrl_not_on_wifi), Ui.WARN)))

        // Active session shortcut
        val t = RemoteSession.target
        val st = RemoteSession.state
        if (t != null && (st is LinkState.Connected || st is LinkState.Reconnecting || st is LinkState.HostNeedsApproval || st is LinkState.Connecting || st is LinkState.Pairing)) {
            c.addView(Ui.card(this,
                Ui.body(this, getString(R.string.ctrl_active_session, RemoteSession.hostName.ifBlank { t.host }), Ui.OK, 16f),
                Ui.primary(this, getString(R.string.back_to_screen)) { openRemote() },
                Ui.secondary(this, getString(R.string.disconnect)) { RemoteSession.disconnect(); build() },
            ))
        }

        val hosts = TrustedStore.get(this).list(TrustedStore.ROLE_HOST)
        if (hosts.isNotEmpty()) {
            val card = Ui.card(this, Ui.title(this, getString(R.string.saved_hosts), 18f))
            for (h in hosts) card.addView(hostRow(h))
            card.addView(Ui.body(this, if (searching) getString(R.string.searching_network) else getString(R.string.search_done_hint), sizeSp = 12.5f))
            c.addView(card)
        }

        c.addView(Ui.card(this,
            Ui.title(this, getString(R.string.pair_new_host), 18f),
            Ui.body(this, getString(R.string.pair_new_host_desc)),
            Ui.primary(this, getString(R.string.scan_qr)) {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(this, ScanActivity::class.java), REQ_SCAN)
            },
            Ui.secondary(this, getString(R.string.manual_entry)) { manualDialog() },
        ))

        // Hosts that answer on the LAN but are not paired.
        val unknown = online.values.filter { f -> hosts.none { f.hostIdPrefix.isNotEmpty() && it.fingerprintHex.startsWith(f.hostIdPrefix) } }
        if (unknown.isNotEmpty()) {
            c.addView(Ui.card(this, Ui.body(this, getString(R.string.unpaired_found, unknown.joinToString("، ") { it.name }), sizeSp = 13.5f)))
        }
        c.addView(Ui.body(this, getString(R.string.network_help), sizeSp = 12.5f))
        Ui.page(this, c)
    }

    private fun hostRow(h: TrustedPeer): LinearLayout {
        val found = online.values.firstOrNull { it.hostIdPrefix.isNotEmpty() && h.fingerprintHex.startsWith(it.hostIdPrefix) }
        val sub = when {
            found != null -> getString(R.string.host_online, found.address)
            h.lastAddress.isNotBlank() -> getString(R.string.host_last_address, h.lastAddress)
            else -> ""
        }
        val r = Ui.column(this, 6)
        r.addView(Ui.statusLine(this, h.name, sub, if (found != null) true else null, getString(R.string.connect)) {
            val addr = found?.address ?: h.lastAddress
            val port = found?.port ?: h.lastPort.takeIf { it > 0 } ?: LocalNet.DEFAULT_PORT
            if (addr.isBlank()) { Toast.makeText(this, R.string.host_not_found, Toast.LENGTH_LONG).show(); return@statusLine }
            RemoteSession.connect(this, HostTarget(h.name, addr, port, h.fingerprintHex, null))
            openRemote()
        })
        return r
    }

    private fun search() {
        if (searching) return
        searching = true
        Thread {
            val found = try { LocalNet.discover(this, 3000) } catch (_: Exception) { emptyList() }
            App.main.post {
                searching = false
                online.clear()
                for (f in found) online[f.hostIdPrefix + f.address] = f
                if (!isFinishing) build()
            }
        }.start()
    }

    private fun manualDialog() {
        val col = Ui.column(this, 8)
        val p = Ui.dp(this, 20)
        col.setPadding(p, p / 2, p, 0)
        val ip = EditText(this).apply { hint = getString(R.string.manual_ip_hint); inputType = InputType.TYPE_CLASS_PHONE }
        val port = EditText(this).apply { setText(LocalNet.DEFAULT_PORT.toString()); inputType = InputType.TYPE_CLASS_NUMBER }
        val code = EditText(this).apply { hint = getString(R.string.manual_code_hint); inputType = InputType.TYPE_CLASS_NUMBER }
        col.addView(Ui.body(this, getString(R.string.manual_desc), sizeSp = 13.5f))
        col.addView(ip); col.addView(port); col.addView(code)
        AlertDialog.Builder(this)
            .setTitle(R.string.manual_entry)
            .setView(col)
            .setPositiveButton(R.string.connect) { _, _ ->
                val host = toLatinDigits(ip.text.toString().trim().replace('٫', '.'))
                val portN = PairingCrypto.normalizeCode(port.text.toString()).toIntOrNull() ?: LocalNet.DEFAULT_PORT
                val codeN = PairingCrypto.normalizeCode(code.text.toString())
                if (host.isBlank() || codeN.length != 8) {
                    Toast.makeText(this, R.string.manual_invalid, Toast.LENGTH_LONG).show()
                } else {
                    RemoteSession.connect(this, HostTarget("", host, portN, null, codeN))
                    openRemote()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toLatinDigits(s: String) = s.map { if (it in '٠'..'٩') ('0' + (it - '٠')) else it }.joinToString("")

    @Deprecated("Platform activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SCAN || resultCode != RESULT_OK) return
        val inv = data?.getStringExtra(ScanActivity.EXTRA_TEXT)?.let { PairInvite.parse(it) } ?: return
        RemoteSession.connect(this, HostTarget(inv.name, inv.host, inv.port, inv.fingerprintHex, inv.code))
        openRemote()
    }

    private fun openRemote() = startActivity(Intent(this, RemoteActivity::class.java))

    companion object {
        private const val REQ_SCAN = 7
    }
}
