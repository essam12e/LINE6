package sa.nitaaq.mirror.host

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import sa.nitaaq.mirror.core.EncodeParams
import sa.nitaaq.mirror.core.QualityPolicy
import sa.nitaaq.mirror.core.Quality
import sa.nitaaq.mirror.proto.FrameFlags
import sa.nitaaq.mirror.proto.VideoConfig

/**
 * MediaProjection -> VirtualDisplay -> MediaCodec (H.264, hardware when available) -> [Sink].
 *
 * Zero CPU copies until the encoded bitstream: the VirtualDisplay renders straight into the encoder's input
 * Surface. The only copy is the encoded access unit (tens of KB) leaving the codec.
 *
 * Rotation / resolution changes reuse the *same* VirtualDisplay (resize + setSurface): on Android 14+ a
 * MediaProjection token may create only one VirtualDisplay, so it is never recreated.
 */
class ScreenEncoder(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val sink: Sink,
) {
    interface Sink {
        fun onVideoConfig(cfg: VideoConfig)
        fun onEncodedFrame(data: ByteArray, flags: Int, ptsUs: Long)
        fun onEncoderError(e: Throwable)
    }

    private val thread = HandlerThread("nm-encoder-ctl").apply { start() }
    private val handler = Handler(thread.looper)
    private val dm = ctx.getSystemService(DisplayManager::class.java)!!

    private var virtualDisplay: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drain: Thread? = null
    @Volatile private var running = false

    @Volatile var quality = Quality.AUTO
        private set
    @Volatile var fps = 30
        private set
    @Volatile var autoStep = 0
        private set
    @Volatile var current: EncodeParams? = null
        private set
    @Volatile var encoderName = ""
        private set
    @Volatile var hardware = false
        private set
    @Volatile var displayW = 0
        private set
    @Volatile var displayH = 0
        private set
    private var rotation = 0
    @Volatile var targetBitrate = 0
        private set
    @Volatile var framesOut = 0L
        private set

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            if (id != Display.DEFAULT_DISPLAY || !running) return
            val (w, h, _) = realDisplay()
            if (w != displayW || h != displayH) reconfigure()
        }
    }

    fun start(q: Quality, fps: Int) {
        handler.post {
            quality = q; this.fps = fps
            running = true
            try {
                configureCodec()
                dm.registerDisplayListener(displayListener, handler)
            } catch (e: Throwable) {
                running = false
                sink.onEncoderError(e)
            }
        }
    }

    fun setQuality(q: Quality, fps: Int) {
        handler.post {
            if (q == quality && fps == this.fps) return@post
            quality = q; this.fps = fps; autoStep = 0
            reconfigure()
        }
    }

    /** AUTO only: 0 = 720p, 1 = 540p. */
    fun setAutoStep(step: Int) {
        handler.post {
            if (quality != Quality.AUTO || step == autoStep) return@post
            autoStep = step
            reconfigure()
        }
    }

    fun setBitrate(bps: Int) {
        handler.post {
            val c = codec ?: return@post
            targetBitrate = bps
            try { c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) }) } catch (_: Exception) {}
        }
    }

    fun requestKeyFrame() {
        handler.post {
            try { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } catch (_: Exception) {}
        }
    }

    fun stop() {
        handler.post {
            running = false
            try { dm.unregisterDisplayListener(displayListener) } catch (_: Exception) {}
            releaseCodec()
            try { virtualDisplay?.release() } catch (_: Exception) {}
            virtualDisplay = null
            thread.quitSafely()
        }
    }

    private fun reconfigure() {
        if (!running) return
        try { configureCodec() } catch (e: Throwable) { sink.onEncoderError(e) }
    }

    @Suppress("DEPRECATION")
    private fun realDisplay(): Triple<Int, Int, Int> {
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        d.getRealMetrics(m)
        rotation = d.rotation
        return Triple(m.widthPixels, m.heightPixels, m.densityDpi)
    }

    private fun configureCodec() {
        val (w, h, dpi) = realDisplay()
        displayW = w; displayH = h
        var p = QualityPolicy.params(quality, fps, w, h, autoStep)
        val info = pickEncoder() ?: throw IllegalStateException("no H.264 encoder")
        p = fitToCapabilities(info, p)
        val newCodec = MediaCodec.createByCodecName(info.name)
        val format = buildFormat(p, full = true)
        try {
            newCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some encoders reject optional keys; retry with the essentials only.
            newCodec.reset()
            newCodec.configure(buildFormat(p, full = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val surface = newCodec.createInputSurface()
        newCodec.start()

        // Swap: point the display at the new encoder first, then drop the old one.
        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = projection.createVirtualDisplay(
                "NITAAQ Mirror", p.width, p.height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler,
            )
        } else {
            vd.resize(p.width, p.height, dpi)
            vd.surface = surface
        }
        releaseCodec()
        codec = newCodec
        inputSurface = surface
        current = p
        targetBitrate = p.bitrate
        encoderName = info.name
        hardware = isHardware(info)
        sink.onVideoConfig(VideoConfig(p.width, p.height, w, h, rotation, info.name, hardware))
        startDrain(newCodec)
    }

    private fun buildFormat(p: EncodeParams, full: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, p.width, p.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, p.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, p.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3)
            // Keep frames flowing on a static screen (lets the controller tell "frozen" from "nothing changed").
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            if (full) {
                setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
                setInteger(MediaFormat.KEY_LATENCY, 1)
                if (Build.VERSION.SDK_INT >= 29) {
                    // Displays run at 90-120 Hz; never feed the encoder more than the target rate.
                    setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, p.fps.toFloat())
                    setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
            }
        }

    private fun pickEncoder(): MediaCodecInfo? {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
        }
        return all.firstOrNull { isHardware(it) } ?: all.firstOrNull()
    }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated && !info.isSoftwareOnly
        else !(info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android."))

    /** Shrinks (keeping aspect) until the encoder accepts the size. */
    private fun fitToCapabilities(info: MediaCodecInfo, p: EncodeParams): EncodeParams {
        val vc = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities ?: return p
        var w = p.width; var h = p.height
        var guard = 0
        while (!vc.isSizeSupported(w, h) && guard++ < 12) {
            w = ((w * 0.9).toInt() / 16 * 16).coerceAtLeast(16)
            h = ((h * 0.9).toInt() / 16 * 16).coerceAtLeast(16)
        }
        val br = vc.bitrateRange.clamp(p.bitrate)
        return p.copy(width = w, height = h, bitrate = br)
    }

    private fun startDrain(c: MediaCodec) {
        val t = Thread({
            val info = MediaCodec.BufferInfo()
            try {
                while (running && codec === c) {
                    val idx = c.dequeueOutputBuffer(info, 50_000)
                    if (idx < 0) continue
                    try {
                        if (info.size > 0) {
                            val buf = c.getOutputBuffer(idx)
                            if (buf != null) {
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                val data = ByteArray(info.size)
                                buf.get(data)
                                var flags = 0
                                if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) flags = flags or FrameFlags.KEY
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) flags = flags or FrameFlags.CONFIG
                                framesOut++
                                sink.onEncodedFrame(data, flags, info.presentationTimeUs)
                            }
                        }
                    } finally {
                        c.releaseOutputBuffer(idx, false)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            } catch (e: IllegalStateException) {
                // Codec released during reconfiguration: expected.
            } catch (e: Throwable) {
                if (running && codec === c) sink.onEncoderError(e)
            }
        }, "nm-encoder-drain")
        t.priority = Thread.MAX_PRIORITY
        drain = t
        t.start()
    }

    private fun releaseCodec() {
        val c = codec ?: return
        codec = null
        try { c.stop() } catch (_: Exception) {}
        try { c.release() } catch (_: Exception) {}
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
        drain = null
    }
}
