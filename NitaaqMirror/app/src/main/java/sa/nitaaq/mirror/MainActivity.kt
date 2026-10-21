package sa.nitaaq.mirror

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import sa.nitaaq.mirror.ctrl.ControllerActivity
import sa.nitaaq.mirror.host.HostActivity
import sa.nitaaq.mirror.host.HostState
import sa.nitaaq.mirror.ui.BaseActivity
import sa.nitaaq.mirror.ui.Ui

/** Screen 1: choose this device's role. */
class MainActivity : BaseActivity() {

    private var shownPhase: HostState.Phase? = null
    /** HostState changes every second while sharing (diagnostics); rebuild only when the phase changes. */
    private val refresh: () -> Unit = { if (HostState.phase != shownPhase) build() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HostState.observe(refresh)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    override fun onDestroy() {
        HostState.unobserve(refresh)
        super.onDestroy()
    }

    private fun build() {
        shownPhase = HostState.phase
        val c = Ui.column(this, 16)
        c.addView(Ui.title(this, getString(R.string.app_name_full), 26f))
        c.addView(Ui.body(this, getString(R.string.main_subtitle)))

        if (HostState.phase != HostState.Phase.STOPPED) {
            c.addView(Ui.card(this,
                Ui.body(this, getString(R.string.main_sharing_active), Ui.OK, 15f),
                Ui.secondary(this, getString(R.string.open_sharing)) { open(HostActivity::class.java) },
            ))
        }

        c.addView(roleCard(getString(R.string.role_controller), getString(R.string.role_controller_desc)) {
            Prefs.of(this).lastRole = "controller"; open(ControllerActivity::class.java)
        })
        c.addView(roleCard(getString(R.string.role_host), getString(R.string.role_host_desc)) {
            Prefs.of(this).lastRole = "host"; open(HostActivity::class.java)
        })

        val r = Ui.row(this, 12)
        r.addView(Ui.secondary(this, getString(R.string.trusted_devices)) { open(TrustedDevicesActivity::class.java) }, weight())
        r.addView(Ui.secondary(this, getString(R.string.settings)) { open(SettingsActivity::class.java) }, weight())
        c.addView(r)
        c.addView(Ui.body(this, getString(R.string.main_privacy_note), sizeSp = 13f))
        Ui.page(this, c)
    }

    private fun roleCard(title: String, desc: String, onClick: () -> Unit) = Ui.card(this,
        Ui.title(this, title, 20f),
        Ui.body(this, desc),
        Ui.primary(this, title, onClick),
    ).apply { gravity = Gravity.START }

    private fun weight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))
}
