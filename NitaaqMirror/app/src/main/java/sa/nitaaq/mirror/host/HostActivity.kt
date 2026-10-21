package sa.nitaaq.mirror.host

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import sa.nitaaq.mirror.App
import sa.nitaaq.mirror.BuildConfig
import sa.nitaaq.mirror.R
import sa.nitaaq.mirror.TrustedDevicesActivity
import sa.nitaaq.mirror.core.PairInvite
import sa.nitaaq.mirror.net.Identity
import sa.nitaaq.mirror.net.LocalNet
import sa.nitaaq.mirror.net.TrustedStore
import sa.nitaaq.mirror.ui.BaseActivity
import sa.nitaaq.mirror.ui.Ui

/** Screens 2 + 3 on the host: permission checklist, start/stop sharing, pairing QR, approvals. */
class HostActivity : BaseActivity() {

    private var approvalDialog: AlertDialog? = null
    private var shownApproval: HostState.Approval? = null
    private var diagView: TextView? = null
    private var lastSig = ""
    /** Rebuild only when something the user acts on changed; diagnostics just update their text (1 Hz). */
    private val refresh: () -> Unit = { if (signature() != lastSig) build() else { diagView?.text = diagText() } }

    private fun signature() = listOf(
        HostState.phase, HostState.controllerName, HostState.pairingCode, HostState.pendingApproval,
        HostState.lastError, TouchControl.active != null, HostState.diagnostics.isEmpty(),
    ).joinToString("|")
    private val ticker = object : Runnable {
        override fun run() {
            if (HostState.pairingCode != null) build()
            App.main.postDelayed(this, 15_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HostState.observe(refresh)
    }

    override fun onResume() {
        super.onResume()
        build()
        App.main.postDelayed(ticker, 15_000)
    }

    override fun onPause() {
        App.main.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        HostState.unobserve(refresh)
        approvalDialog?.dismiss()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ state checks

    private fun notificationsOk() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun accessibilityOk(): Boolean {
        if (TouchControl.active != null) return true
        val am = getSystemService(AccessibilityManager::class.java)!!
        if (!BuildConfig.TOUCH_CONTROL) return false
        val me = ComponentName(packageName, TouchControl.SERVICE_CLASS)
        return am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.let { si -> ComponentName(si.packageName, si.name) == me } == true }
    }

    private fun batteryOk() = getSystemService(PowerManager::class.java)!!.isIgnoringBatteryOptimizations(packageName)

    // ------------------------------------------------------------------ UI

    private fun build() {
        lastSig = signature()
        diagView = null
        val c = Ui.column(this, 14)
        c.addView(Ui.title(this, getString(R.string.role_host)))
        val running = HostState.phase != HostState.Phase.STOPPED
        val net = LocalNet.info(this)

        HostState.lastError?.let { c.addView(Ui.card(this, Ui.body(this, it, Ui.DANGER))) }

        // --- Sharing status / controls
        val share = Ui.card(this)
        when (HostState.phase) {
            HostState.Phase.STOPPED -> {
                share.addView(Ui.body(this, getString(R.string.host_not_sharing), Ui.TEXT, 16f))
                share.addView(Ui.body(this, getString(R.string.host_entire_screen_hint), sizeSp = 13.5f))
                share.addView(Ui.primary(this, getString(R.string.start_sharing)) { requestCapture() })
            }
            HostState.Phase.RUNNING -> {
                val who = HostState.controllerName
                share.addView(Ui.body(this, if (who != null) getString(R.string.host_controlled_by, who) else getString(R.string.host_waiting), Ui.OK, 16f))
                share.addView(Ui.body(this, getString(R.string.host_address, net.ipv4 ?: "—", HostState.port), sizeSp = 13.5f))
                share.addView(Ui.danger(this, getString(R.string.stop_sharing_now)) { HostService.send(this, HostService.ACTION_STOP) })
            }
            HostState.Phase.PROJECTION_STOPPED -> {
                share.addView(Ui.body(this, getString(R.string.host_projection_stopped), Ui.WARN, 16f))
                share.addView(Ui.primary(this, getString(R.string.resume_sharing)) { requestCapture() })
                share.addView(Ui.danger(this, getString(R.string.stop_sharing_now)) { HostService.send(this, HostService.ACTION_STOP) })
            }
        }
        c.addView(share)

        // --- Pairing
        if (running) {
            val code = HostState.pairingCode
            if (code != null && net.ipv4 != null) {
                c.addView(pairingCard(code, net.ipv4))
            } else {
                c.addView(Ui.secondary(this, getString(R.string.pair_new_device)) { HostService.send(this, HostService.ACTION_OPEN_PAIRING) })
            }
        }

        // --- Permissions checklist
        val perms = Ui.card(this, Ui.title(this, getString(R.string.host_setup_title), 18f),
            Ui.body(this, getString(R.string.this_device, App.osLine()), sizeSp = 13f))
        if (!BuildConfig.TOUCH_CONTROL) {
            perms.addView(Ui.statusLine(this, getString(R.string.perm_accessibility), getString(R.string.touch_not_in_edition), false, null, null))
        } else perms.addView(Ui.statusLine(this, getString(R.string.perm_accessibility), getString(R.string.perm_accessibility_desc), accessibilityOk(),
            if (accessibilityOk()) null else getString(R.string.open_settings)) { openAccessibility() })
        if (BuildConfig.TOUCH_CONTROL && !accessibilityOk() && Build.VERSION.SDK_INT >= 33) {
            perms.addView(Ui.statusLine(this, getString(R.string.perm_restricted), getString(R.string.perm_restricted_desc), null,
                getString(R.string.app_info)) { openAppInfo() })
        }
        perms.addView(Ui.statusLine(this, getString(R.string.perm_notifications), getString(R.string.perm_notifications_desc), notificationsOk(),
            if (notificationsOk()) null else getString(R.string.allow)) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        })
        perms.addView(Ui.statusLine(this, getString(R.string.perm_battery), getString(R.string.perm_battery_desc), batteryOk(),
            if (batteryOk()) null else getString(R.string.allow)) { requestBattery() })
        perms.addView(Ui.statusLine(this, getString(R.string.perm_wifi),
            if (net.onWifi) getString(R.string.perm_wifi_ok, net.ipv4 ?: "—") else getString(R.string.perm_wifi_missing), net.onWifi, null, null))
        perms.addView(Ui.statusLine(this, getString(R.string.perm_capture), getString(R.string.perm_capture_desc), if (HostState.phase == HostState.Phase.RUNNING) true else null, null, null))
        c.addView(perms)

        // --- Diagnostics (host)
        if (running && HostState.diagnostics.isNotEmpty()) {
            val tv = Ui.body(this, diagText(), sizeSp = 13f)
            diagView = tv
            c.addView(Ui.card(this, Ui.title(this, getString(R.string.diagnostics), 16f), tv))
        }

        c.addView(Ui.secondary(this, getString(R.string.trusted_devices)) { startActivity(Intent(this, TrustedDevicesActivity::class.java)) })
        c.addView(Ui.body(this, getString(R.string.host_limits_note), sizeSp = 12.5f))
        Ui.page(this, c)
        showApprovalIfNeeded()
    }

    private fun diagText(): String {
        val d = HostState.diagnostics
        return buildString {
            d["res"]?.let { append(getString(R.string.diag_res)).append(": ").append(it).append('\n') }
            d["fps"]?.let { append("FPS: ").append(it).append('\n') }
            d["br"]?.toIntOrNull()?.let { append(getString(R.string.diag_bitrate)).append(": ").append("%.1f Mbps".format(java.util.Locale.US, it / 1e6)).append('\n') }
            d["enc"]?.let { append(getString(R.string.diag_encoder)).append(": ").append(it).append(if (d["hw"] == "1") " (HW)" else " (SW)").append('\n') }
            d["qd"]?.let { append(getString(R.string.diag_queue)).append(": ").append(it).append(" ms\n") }
            d["temp"]?.let { append(getString(R.string.diag_temp)).append(": ").append(it).append(" °C\n") }
            d["mem"]?.let { append(getString(R.string.diag_mem)).append(": ").append(it).append(" MB") }
        }
    }

    private fun pairingCard(code: String, ip: String): LinearLayout {
        val id = Identity.get(this)
        val invite = PairInvite(ip, HostState.port, id.fingerprintHex, code, App.deviceName(this))
        val img = ImageView(this).apply {
            setImageBitmap(qr(invite.toUri(), Ui.dp(this@HostActivity, 240)))
            layoutParams = LinearLayout.LayoutParams(Ui.dp(this@HostActivity, 240), Ui.dp(this@HostActivity, 240)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        val left = ((HostState.pairingExpiresAt - SystemClock.elapsedRealtime()) / 60_000).coerceAtLeast(0) + 1
        return Ui.card(this,
            Ui.title(this, getString(R.string.pair_title), 18f),
            Ui.body(this, getString(R.string.pair_instructions)),
            img,
            TextView(this).apply {
                text = getString(R.string.pair_manual, ip, HostState.port, code.substring(0, 4) + " " + code.substring(4))
                textSize = 18f; setTextColor(Ui.TEXT); gravity = Gravity.CENTER
                setTextIsSelectable(true)
            },
            Ui.body(this, getString(R.string.pair_expires, left), sizeSp = 13f),
            Ui.secondary(this, getString(R.string.pair_cancel)) { HostService.send(this, HostService.ACTION_CLOSE_PAIRING) },
        )
    }

    private fun qr(text: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun showApprovalIfNeeded() {
        val a = HostState.pendingApproval
        if (a == null) { approvalDialog?.dismiss(); approvalDialog = null; shownApproval = null; return }
        if (a == shownApproval && approvalDialog?.isShowing == true) return
        approvalDialog?.dismiss()
        shownApproval = a
        approvalDialog = AlertDialog.Builder(this)
            .setTitle(R.string.approve_title)
            .setMessage(getString(R.string.approve_dialog, a.name, a.digits, a.fpShort))
            .setCancelable(false)
            .setPositiveButton(R.string.allow) { _, _ -> HostService.send(this, HostService.ACTION_APPROVE, a.id) }
            .setNegativeButton(R.string.deny) { _, _ -> HostService.send(this, HostService.ACTION_DENY, a.id) }
            .show()
    }

    // ------------------------------------------------------------------ actions

    private fun requestCapture() {
        if (!notificationsOk() && Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
            return
        }
        HostState.lastError = null
        val mpm = getSystemService(MediaProjectionManager::class.java)!!
        // Android 14+: offer only "entire screen" (no single-app option), we need to move between apps.
        val intent = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_CAPTURE)
    }

    @Deprecated("Activity result API from the platform; no AndroidX here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            HostState.lastError = getString(R.string.err_projection_denied)
            build()
            return
        }
        val i = Intent(this, HostService::class.java)
            .setAction(HostService.ACTION_START)
            .putExtra(HostService.EXTRA_CODE, resultCode)
            .putExtra(HostService.EXTRA_DATA, data)
        startForegroundService(i)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 3 && notificationsOk()) requestCapture() else build()
    }

    private fun openAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    /** Opens the system screen (no special permission needed); the user picks the app and "Don't optimise". */
    private fun requestBattery() {
        try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        catch (_: Exception) { openAppInfo() }
    }

    companion object {
        private const val REQ_CAPTURE = 1
    }
}
