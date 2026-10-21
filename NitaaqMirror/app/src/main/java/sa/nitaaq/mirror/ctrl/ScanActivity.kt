package sa.nitaaq.mirror.ctrl

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import sa.nitaaq.mirror.App
import sa.nitaaq.mirror.R
import sa.nitaaq.mirror.core.PairInvite
import sa.nitaaq.mirror.ui.BaseActivity
import sa.nitaaq.mirror.ui.Ui
import java.util.concurrent.atomic.AtomicBoolean

/** Scans the pairing QR with Camera2 + ZXing core (no Play Services, no network). */
class ScanActivity : BaseActivity() {

    private lateinit var texture: TextureView
    private lateinit var hint: TextView
    private val thread = HandlerThread("nm-scan").apply { start() }
    private val handler = Handler(thread.looper)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private val busy = AtomicBoolean(false)
    private val done = AtomicBoolean(false)
    private val qr = QRCodeReader()
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        val p = Ui.dp(this, 20)
        root.setPadding(p, p, p, p)
        root.addView(Ui.title(this, getString(R.string.scan_qr)))
        hint = Ui.body(this, getString(R.string.scan_hint))
        root.addView(hint)
        val frame = FrameLayout(this)
        texture = TextureView(this)
        frame.addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = p; bottomMargin = p })
        root.addView(Ui.secondary(this, getString(R.string.manual_entry_instead)) { setResult(RESULT_CANCELED); finish() })
        Ui.applySystemBarPadding(root)
        setContentView(root)
        // Preview is 4:3 in portrait.
        frame.post {
            val w = frame.width
            texture.layoutParams = FrameLayout.LayoutParams(w, (w * 4) / 3, Gravity.CENTER)
        }
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        startWhenReady()
    }

    override fun onPause() {
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) hint.text = getString(R.string.camera_denied)
    }

    private fun startWhenReady() {
        if (texture.isAvailable) openCamera()
        else texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val cm = getSystemService(CameraManager::class.java)!!
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull() ?: run { hint.text = getString(R.string.camera_unavailable); return }
        val map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
        // ~720p 4:3 is plenty for a QR on a nearby phone screen.
        val size = sizes.filter { it.width * 3 == it.height * 4 && it.width <= 1280 }.maxByOrNull { it.width } ?: sizes.minByOrNull { Math.abs(it.width - 1280) } ?: Size(640, 480)
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cd: CameraDevice) { camera = cd; startSession(cd, size) }
                override fun onDisconnected(cd: CameraDevice) { cd.close() }
                override fun onError(cd: CameraDevice, error: Int) { cd.close(); App.main.post { hint.text = getString(R.string.camera_unavailable) } }
            }, handler)
        } catch (e: Exception) {
            hint.text = getString(R.string.camera_unavailable)
        }
    }

    @Suppress("DEPRECATION")
    private fun startSession(cd: CameraDevice, size: Size) {
        val st = texture.surfaceTexture ?: return
        st.setDefaultBufferSize(size.width, size.height)
        val preview = Surface(st)
        val ir = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        reader = ir
        ir.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (done.get() || !busy.compareAndSet(false, true)) { img.close(); return@setOnImageAvailableListener }
            try {
                val plane = img.planes[0]
                val w = img.width; val h = img.height
                val rowStride = plane.rowStride
                val buf = plane.buffer
                val y = ByteArray(w * h)
                if (rowStride == w) buf.get(y, 0, w * h)
                else for (row in 0 until h) { buf.position(row * rowStride); buf.get(y, row * w, w) }
                img.close()
                decode(y, w, h)
            } catch (_: Exception) {
                try { img.close() } catch (_: Exception) {}
            } finally {
                busy.set(false)
            }
        }, handler)
        cd.createCaptureSession(listOf(preview, ir.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val req = cd.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(preview); addTarget(ir.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
                try { s.setRepeatingRequest(req.build(), null, handler) } catch (_: Exception) {}
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { App.main.post { hint.text = getString(R.string.camera_unavailable) } }
        }, handler)
    }

    private fun decode(y: ByteArray, w: Int, h: Int) {
        val src = PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false)
        val text = try { qr.decode(BinaryBitmap(HybridBinarizer(src)), hints).text } catch (_: Exception) { null } finally { qr.reset() }
        if (text == null) return
        if (PairInvite.parse(text) == null) {
            App.main.post { hint.text = getString(R.string.scan_not_ours) }
            return
        }
        if (!done.compareAndSet(false, true)) return
        App.main.post {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
            finish()
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
    }

    companion object {
        const val EXTRA_TEXT = "text"
    }
}
