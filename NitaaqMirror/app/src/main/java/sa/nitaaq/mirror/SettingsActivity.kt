package sa.nitaaq.mirror

import android.os.Bundle
import android.widget.CompoundButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import sa.nitaaq.mirror.core.Quality
import sa.nitaaq.mirror.ctrl.RemoteSession
import sa.nitaaq.mirror.host.TouchControl
import sa.nitaaq.mirror.host.HostState
import sa.nitaaq.mirror.ui.BaseActivity
import sa.nitaaq.mirror.ui.Ui

/** Screen 6: a few simple settings. */
class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
    }

    private fun build() {
        val p = Prefs.of(this)
        val c = Ui.column(this, 14)
        c.addView(Ui.title(this, getString(R.string.settings)))

        c.addView(Ui.card(this,
            Ui.title(this, getString(R.string.s_language), 17f),
            radios(listOf("ar" to getString(R.string.lang_ar), "en" to getString(R.string.lang_en)), p.language) {
                p.language = it
                startActivity(android.content.Intent(this, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
            },
        ))

        c.addView(Ui.card(this,
            Ui.title(this, getString(R.string.s_quality), 17f),
            Ui.body(this, getString(R.string.s_quality_desc), sizeSp = 13f),
            radios(listOf(
                Quality.FAST.code.toString() to getString(R.string.q_fast_long),
                Quality.BALANCED.code.toString() to getString(R.string.q_balanced_long),
                Quality.AUTO.code.toString() to getString(R.string.q_auto_long),
            ), p.quality.code.toString()) { p.quality = Quality.of(it.toInt()); RemoteSession.sendQualityIfConnected() },
            Ui.title(this, getString(R.string.s_fps), 15f),
            radios(listOf("30" to "30 FPS", "60" to getString(R.string.fps60)), p.fps.toString()) { p.fps = it.toInt(); RemoteSession.sendQualityIfConnected() },
        ))

        c.addView(Ui.card(this,
            Ui.title(this, getString(R.string.s_controller), 17f),
            switch(getString(R.string.s_auto_rotate), p.autoRotate) { p.autoRotate = it },
            switch(getString(R.string.s_diagnostics), p.showDiagnostics) { p.showDiagnostics = it },
        ))

        c.addView(Ui.card(this,
            Ui.title(this, getString(R.string.s_host), 17f),
            switch(getString(R.string.s_keep_awake), p.keepAwake) {
                p.keepAwake = it
                if (HostState.controllerName != null) TouchControl.active?.setKeepAwake(it)
            },
            Ui.body(this, getString(R.string.s_keep_awake_desc), sizeSp = 13f),
        ))

        c.addView(Ui.body(this, getString(R.string.version_line, BuildInfo.VERSION_NAME), sizeSp = 12f))
        Ui.page(this, c)
    }

    private fun radios(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): RadioGroup {
        val g = RadioGroup(this)
        options.forEachIndexed { i, (key, label) ->
            val b = RadioButton(this).apply { id = 1000 + i; text = label; textSize = 15.5f; setTextColor(Ui.TEXT); isChecked = key == selected }
            g.addView(b)
        }
        g.setOnCheckedChangeListener { _, id -> options.getOrNull(id - 1000)?.let { if (it.first != selected) onPick(it.first) } }
        return g
    }

    @Suppress("DEPRECATION")
    private fun switch(label: String, value: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label; textSize = 15.5f; setTextColor(Ui.TEXT); isChecked = value
        minHeight = Ui.dp(this@SettingsActivity, 48)
        setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> onChange(v) }
    }
}

object BuildInfo {
    const val VERSION_NAME = "0.1.1"
}
