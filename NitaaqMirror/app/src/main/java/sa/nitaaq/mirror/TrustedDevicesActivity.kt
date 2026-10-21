package sa.nitaaq.mirror

import android.app.AlertDialog
import android.os.Bundle
import android.text.format.DateUtils
import sa.nitaaq.mirror.core.Fingerprint
import sa.nitaaq.mirror.host.HostService
import sa.nitaaq.mirror.net.TrustedPeer
import sa.nitaaq.mirror.net.TrustedStore
import sa.nitaaq.mirror.ui.BaseActivity
import sa.nitaaq.mirror.ui.Ui

/** Screen 5: trusted devices, with "remove trust" (which also ends that device's session immediately). */
class TrustedDevicesActivity : BaseActivity() {

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        val store = TrustedStore.get(this)
        val c = Ui.column(this, 14)
        c.addView(Ui.title(this, getString(R.string.trusted_devices)))

        val controllers = store.list(TrustedStore.ROLE_CONTROLLER)
        val cc = Ui.card(this, Ui.title(this, getString(R.string.trusted_controllers), 17f), Ui.body(this, getString(R.string.trusted_controllers_desc), sizeSp = 13f))
        if (controllers.isEmpty()) cc.addView(Ui.body(this, getString(R.string.none_yet)))
        for (p in controllers) cc.addView(peerLine(p))
        c.addView(cc)

        val hosts = store.list(TrustedStore.ROLE_HOST)
        val hc = Ui.card(this, Ui.title(this, getString(R.string.saved_hosts), 17f), Ui.body(this, getString(R.string.saved_hosts_desc), sizeSp = 13f))
        if (hosts.isEmpty()) hc.addView(Ui.body(this, getString(R.string.none_yet)))
        for (p in hosts) hc.addView(peerLine(p))
        c.addView(hc)
        Ui.page(this, c)
    }

    private fun peerLine(p: TrustedPeer) = Ui.statusLine(
        this, p.name.ifBlank { "—" },
        getString(R.string.peer_details, Fingerprint.short(Fingerprint.fromHex(p.fingerprintHex) ?: ByteArray(4)),
            if (p.lastSeen > 0) DateUtils.getRelativeTimeSpanString(p.lastSeen).toString() else "—"),
        null, getString(R.string.remove_trust),
    ) {
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_trust)
            .setMessage(getString(R.string.remove_trust_confirm, p.name))
            .setPositiveButton(R.string.remove_trust) { _, _ ->
                TrustedStore.get(this).remove(p.role, p.fingerprintHex)
                if (p.role == TrustedStore.ROLE_CONTROLLER) HostService.running?.revoke(p.fingerprintHex)
                build()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
