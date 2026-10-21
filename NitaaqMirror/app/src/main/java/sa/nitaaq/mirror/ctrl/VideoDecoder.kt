package sa.nitaaq.mirror.ctrl

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import sa.nitaaq.mirror.core.RollingStats
import sa.nitaaq.mirror.proto.FrameFlags

/**
 * H.264 decoder rendering straight to the SurfaceView's Surface (no CPU copies of pixels).
 * Low-latency rules: request the platform low-latency mode when supported, never wait for a frame to "be due",
 * and when several decoded frames are ready at once, show only the newest one.
 */
class VideoDecoder(private val onNeedKeyFrame: () -> Unit) {

    private val lock = Object()
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var needKey = true
    /** Last SPS/PPS seen; re-fed whenever the codec is (re)created so a key frame alone is enough. */
    private var csd: ByteArray? = null
    @Volatile private var outThread: Thread? = null

    @Volatile var decoderName = ""
        private set
    @Volatile var hardware = false
        private set
    @Volatile var lowLatency = false
        private set
    @Volatile var renderedFrames = 0L
        private set
    @Volatile var droppedFrames = 0L
        private set
    val decodeMs = RollingStats(120)
    private val arrivalByPts = LinkedHashMap<Long, Long>()
    /** Consecutive decoder failures; after 2 we switch to the platform software decoder for this session. */
    private var failures = 0
    @Volatile private var preferSoftware = false

    fun setSurface(s: Surface?) {
        synchronized(lock) {
            if (s === surface) return
            surface = s
            release()
            needKey = true
        }
        if (s != null) onNeedKeyFrame()
    }

    fun configure(w: Int, h: Int) {
        synchronized(lock) {
            if (w == width && h == height && codec != null) return
            width = w; height = h
            release()
            csd = null // new encoder -> new SPS/PPS follows
            needKey = true
        }
    }

    /** Called from the network thread. */
    fun feed(data: ByteArray, offset: Int, len: Int, flags: Int, ptsUs: Long, arrivalNs: Long) {
        synchronized(lock) {
            val isConfig = flags and FrameFlags.CONFIG != 0
            val isKey = flags and FrameFlags.KEY != 0
            if (isConfig) csd = data.copyOfRange(offset, offset + len)
            if (surface == null || width == 0) return
            if (codec == null) {
                if (!isConfig && !isKey) { droppedFrames++; return }
                if (!start()) return
                if (!isConfig) csd?.let { queueConfig(it) }
            }
            if (needKey && !isConfig && !isKey) { droppedFrames++; return }
            val c = codec ?: return
            try {
                val idx = c.dequeueInputBuffer(20_000)
                if (idx < 0) {
                    // Decoder is backed up: skip and resynchronise on the next key frame rather than lag.
                    droppedFrames++; needKey = true; onNeedKeyFrame(); return
                }
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(data, offset, len)
                c.queueInputBuffer(idx, 0, len, ptsUs, if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
                if (isKey) needKey = false
                if (!isConfig) {
                    synchronized(arrivalByPts) {
                        arrivalByPts[ptsUs] = arrivalNs
                        if (arrivalByPts.size > 120) arrivalByPts.remove(arrivalByPts.keys.first())
                    }
                }
            } catch (e: Exception) {
                // CodecException / IllegalState: rebuild on the next key frame (software decoder after 2 failures).
                onFailure(e)
                release()
                needKey = true
                onNeedKeyFrame()
            }
        }
    }

    private fun queueConfig(b: ByteArray) {
        val c = codec ?: return
        try {
            val idx = c.dequeueInputBuffer(20_000)
            if (idx < 0) return
            c.getInputBuffer(idx)?.apply { clear(); put(b) }
            c.queueInputBuffer(idx, 0, b.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
        } catch (_: Exception) {}
    }

    private fun onFailure(e: Exception) {
        failures++
        if (failures >= 2 && !preferSoftware) preferSoftware = true
        android.util.Log.w("NitaaqMirror", "decoder failure #$failures ($decoderName): ${e.javaClass.simpleName}; software=$preferSoftware")
    }

    private fun isSoftware(info: MediaCodecInfo) =
        if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly else info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")

    private fun start(): Boolean {
        val s = surface ?: return false
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            val name = pickDecoder(format)
            val c = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val info = c.codecInfo
            decoderName = info.name
            hardware = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            lowLatency = false
            if (Build.VERSION.SDK_INT >= 30) {
                val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                if (caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    lowLatency = true
                }
            }
            try {
                c.configure(format, s, null, 0)
                c.start()
            } catch (e: Exception) {
                c.release(); throw e
            }
            codec = c
            startOutput(c)
            true
        } catch (e: Exception) {
            codec = null
            onFailure(e)
            false
        }
    }

    private fun pickDecoder(format: MediaFormat): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
        }
        if (preferSoftware) list.firstOrNull { isSoftware(it) }?.let { return it.name }
        val hw = list.filter { if (Build.VERSION.SDK_INT >= 29) it.isHardwareAccelerated && !it.isAlias else !it.name.startsWith("OMX.google.") && !it.name.startsWith("c2.android.") }
        // Prefer a hardware decoder that advertises low latency.
        if (Build.VERSION.SDK_INT >= 30) {
            hw.firstOrNull { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency) }
                ?.let { return it.name }
        }
        return hw.firstOrNull()?.name ?: list.firstOrNull()?.name
    }

    private fun startOutput(c: MediaCodec) {
        val t = Thread({
            val info = MediaCodec.BufferInfo()
            try {
                while (codec === c) {
                    var idx = c.dequeueOutputBuffer(info, 20_000)
                    if (idx < 0) continue
                    // Newer frame already waiting? Drop the older one: freshness over completeness.
                    while (true) {
                        val pts = info.presentationTimeUs
                        val nextInfo = MediaCodec.BufferInfo()
                        val next = c.dequeueOutputBuffer(nextInfo, 0)
                        if (next < 0) break
                        c.releaseOutputBuffer(idx, false)
                        droppedFrames++
                        forget(pts)
                        idx = next
                        info.set(nextInfo.offset, nextInfo.size, nextInfo.presentationTimeUs, nextInfo.flags)
                    }
                    c.releaseOutputBuffer(idx, true)
                    renderedFrames++
                    val arrival = synchronized(arrivalByPts) { arrivalByPts.remove(info.presentationTimeUs) }
                    if (arrival != null) decodeMs.add((System.nanoTime() - arrival) / 1_000_000.0)
                }
            } catch (e: Exception) {
                // Normal when released; a real error while still current counts as a failure.
                var failed = false
                if (codec === c) synchronized(lock) { if (codec === c) { onFailure(e); release(); needKey = true; failed = true } }
                if (failed) onNeedKeyFrame()
            }
        }, "nm-decoder-out")
        t.priority = Thread.MAX_PRIORITY
        outThread = t
        t.start()
    }

    private fun forget(pts: Long) {
        synchronized(arrivalByPts) { arrivalByPts.remove(pts) }
    }

    fun release() {
        synchronized(lock) {
            val c = codec ?: return
            codec = null
            try { c.stop() } catch (_: Exception) {}
            try { c.release() } catch (_: Exception) {}
            outThread = null
        }
    }
}
