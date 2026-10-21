package sa.nitaaq.mirror

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import sa.nitaaq.mirror.core.Quality
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
        // Create/unseal the TLS identity off the main thread (Keystore operations can take a moment).
        Thread { try { sa.nitaaq.mirror.net.Identity.get(this) } catch (_: Exception) {} }.start()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)!!
        val ctx = localized(this)
        nm.createNotificationChannel(
            NotificationChannel(CH_SHARING, ctx.getString(R.string.channel_sharing), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_APPROVAL, ctx.getString(R.string.channel_approval), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        const val CH_SHARING = "sharing"
        const val CH_APPROVAL = "approval"

        lateinit var instance: App
            private set

        val main = Handler(Looper.getMainLooper())

        fun localized(base: Context): Context {
            val lang = Prefs.of(base).language
            // Arabic text with Latin digits: IPs, ports and codes must look exactly like what the user types.
            val locale = Locale.forLanguageTag(if (lang == "ar") "ar-u-nu-latn" else lang)
            Locale.setDefault(locale)
            val cfg = Configuration(base.resources.configuration)
            cfg.setLocale(locale)
            cfg.setLayoutDirection(locale)
            return base.createConfigurationContext(cfg)
        }

        /** e.g. "motorola edge 60 · Android 15 (API 35)" — read from the device, never assumed. */
        fun osLine(): String = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

        fun deviceName(ctx: Context): String {
            val n = try { Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) } catch (_: Exception) { null }
            return (n?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}").take(48)
        }
    }
}

/** Simple, non-sensitive settings. (Pairing data lives in [sa.nitaaq.mirror.net.TrustedStore], encrypted.) */
class Prefs private constructor(private val sp: SharedPreferences) {
    var language: String
        get() = sp.getString("lang", "ar") ?: "ar"
        set(v) = sp.edit().putString("lang", v).apply()
    var quality: Quality
        get() = Quality.of(sp.getInt("quality", Quality.AUTO.code))
        set(v) = sp.edit().putInt("quality", v.code).apply()
    var fps: Int
        get() = sp.getInt("fps", 30)
        set(v) = sp.edit().putInt("fps", v).apply()
    var keepAwake: Boolean
        get() = sp.getBoolean("keep_awake", false)
        set(v) = sp.edit().putBoolean("keep_awake", v).apply()
    var autoRotate: Boolean
        get() = sp.getBoolean("auto_rotate", true)
        set(v) = sp.edit().putBoolean("auto_rotate", v).apply()
    var showDiagnostics: Boolean
        get() = sp.getBoolean("diagnostics", false)
        set(v) = sp.edit().putBoolean("diagnostics", v).apply()
    var lastRole: String
        get() = sp.getString("role", "") ?: ""
        set(v) = sp.edit().putString("role", v).apply()

    companion object {
        @Volatile private var inst: Prefs? = null
        fun of(ctx: Context): Prefs = inst ?: synchronized(this) {
            inst ?: Prefs(ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)).also { inst = it }
        }
    }
}
