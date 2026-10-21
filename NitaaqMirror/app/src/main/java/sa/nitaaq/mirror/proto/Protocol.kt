package sa.nitaaq.mirror.proto

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Wire protocol. Everything travels inside one mutually-authenticated TLS connection:
 *   frame = [type:u8][length:u32][payload]
 * Video goes host -> controller, control goes controller -> host, so the two never queue behind
 * each other in the same direction.
 */
object Msg {
    const val PROTOCOL_VERSION = 1
    const val MAX_FRAME = 8 * 1024 * 1024

    // controller -> host
    const val HELLO = 1
    const val PAIR_PROOF = 2
    const val TOUCH = 3
    const val NAV = 4
    const val TEXT = 5
    const val PING = 6
    const val VIDEO_CTRL = 7

    // host -> controller
    const val WELCOME = 20
    const val PAIR_NONCE = 21
    const val PAIR_RESULT = 22
    const val VIDEO_CONFIG = 23
    const val VIDEO_FRAME = 24
    const val STATUS = 25
    const val PONG = 26
    const val TEXT_RESULT = 27
    const val BYE = 28
}

class Frame(val type: Int, val payload: ByteArray)

object FrameIO {
    @Throws(IOException::class)
    fun read(input: DataInputStream): Frame {
        val type = input.read()
        if (type < 0) throw EOFException()
        val len = input.readInt()
        if (len < 0 || len > Msg.MAX_FRAME) throw IOException("bad frame length")
        val data = ByteArray(len)
        input.readFully(data)
        return Frame(type, data)
    }

    @Throws(IOException::class)
    fun write(out: OutputStream, type: Int, payload: ByteArray, off: Int = 0, len: Int = payload.size) {
        val header = byteArrayOf(
            type.toByte(),
            (len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte(),
        )
        out.write(header)
        out.write(payload, off, len)
    }
}

/** Small helper to build payloads. */
inline fun payload(block: DataOutputStream.() -> Unit): ByteArray {
    val bos = ByteArrayOutputStream(64)
    DataOutputStream(bos).use { it.block() }
    return bos.toByteArray()
}

fun ByteArray.reader(): DataInputStream = DataInputStream(this.inputStream())

// ---------------------------------------------------------------- hello / pairing

enum class HelloMode(val code: Int) { TRUSTED(0), PAIR(1) }

class Hello(val version: Int, val deviceName: String, val mode: HelloMode, val nonce: ByteArray) {
    fun encode() = payload {
        writeInt(version); writeUTF(deviceName); writeByte(mode.code); write(nonce)
    }

    companion object {
        fun decode(b: ByteArray): Hello = b.reader().run {
            val v = readInt(); val name = readUTF().take(64)
            val mode = if (readUnsignedByte() == 1) HelloMode.PAIR else HelloMode.TRUSTED
            val nonce = ByteArray(16); readFully(nonce)
            Hello(v, name, mode, nonce)
        }
    }
}

class Welcome(val hostName: String, val hostId: String, val sessionGen: Int) {
    fun encode() = payload { writeUTF(hostName); writeUTF(hostId); writeInt(sessionGen) }

    companion object {
        fun decode(b: ByteArray) = b.reader().run { Welcome(readUTF(), readUTF(), readInt()) }
    }
}

object PairResult {
    const val OK = 0
    const val WRONG_CODE = 1
    const val REJECTED = 2
    const val WINDOW_CLOSED = 3
    const val TOO_MANY_ATTEMPTS = 4
    const val TIMEOUT = 5
}

object ByeReason {
    const val HOST_STOPPED = 1
    const val REPLACED = 2
    const val NOT_TRUSTED = 3
    const val PROJECTION_ENDED = 4
    const val PROTOCOL = 5
    const val TRUST_REVOKED = 6
}

// ---------------------------------------------------------------- input

object TouchAction {
    const val DOWN = 0
    const val MOVE = 1
    const val UP = 2
    const val CANCEL = 3
}

/** Normalised pointer (0..1 of the host frame). */
data class Pointer(val id: Int, val x: Float, val y: Float)

data class TouchMsg(
    val action: Int,
    val changedId: Int,
    val seq: Int,
    val timeMs: Long,
    val pointers: List<Pointer>,
) {
    fun encode() = payload {
        writeByte(action); writeByte(changedId); writeInt(seq); writeLong(timeMs)
        writeByte(pointers.size)
        for (p in pointers) { writeByte(p.id); writeFloat(p.x); writeFloat(p.y) }
    }

    companion object {
        const val MAX_POINTERS = 10

        fun decode(b: ByteArray): TouchMsg = b.reader().run {
            val action = readUnsignedByte(); val changed = readUnsignedByte(); val seq = readInt(); val t = readLong()
            val n = readUnsignedByte().coerceAtMost(MAX_POINTERS)
            val list = ArrayList<Pointer>(n)
            repeat(n) {
                val id = readUnsignedByte(); val x = readFloat(); val y = readFloat()
                if (x.isFinite() && y.isFinite()) list.add(Pointer(id, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)))
            }
            TouchMsg(action, changed, seq, t, list)
        }
    }
}

object Nav {
    const val BACK = 1
    const val HOME = 2
    const val RECENTS = 3
    const val NOTIFICATIONS = 4
    const val QUICK_SETTINGS = 5
    const val WAKE_SCREEN = 7
}

object TextOp {
    const val INSERT = 1
    const val REPLACE_ALL = 2
    const val BACKSPACE = 3
    const val ENTER = 4
    /** Focus the input field on the host so the host's own keyboard opens. */
    const val SHOW_KEYBOARD = 5
}

object TextResult {
    const val OK = 0
    const val NO_FOCUS = 1
    const val NOT_EDITABLE = 2
    const val PASSWORD = 3
    const val UNSUPPORTED = 4
    const val NO_ACCESSIBILITY = 5
    const val FAILED = 6
}

object VideoCtrl {
    const val PAUSE = 1
    const val RESUME = 2
    const val KEYFRAME = 3
    const val QUALITY = 4 // + quality:u8 + fps:u8
}

// ---------------------------------------------------------------- video

object FrameFlags {
    const val KEY = 1
    const val CONFIG = 2
}

class VideoConfig(
    val width: Int, val height: Int,
    val displayWidth: Int, val displayHeight: Int,
    val rotation: Int, val encoderName: String, val hardware: Boolean,
) {
    fun encode() = payload {
        writeInt(width); writeInt(height); writeInt(displayWidth); writeInt(displayHeight)
        writeInt(rotation); writeUTF(encoderName); writeBoolean(hardware)
    }

    companion object {
        fun decode(b: ByteArray) = b.reader().run {
            VideoConfig(readInt(), readInt(), readInt(), readInt(), readInt(), readUTF(), readBoolean())
        }
    }
}

/** Video frame header: flags:u8, ptsUs:i64, hostSendNs:i64 (host monotonic clock), then the H.264 access unit. */
object VideoFrameHeader {
    const val SIZE = 17
}

/** Status: a flat list of key/value strings, sent about once a second. */
object StatusCodec {
    fun encode(map: Map<String, String>) = payload {
        writeShort(map.size)
        for ((k, v) in map) { writeUTF(k); writeUTF(v) }
    }

    fun decode(b: ByteArray): Map<String, String> = b.reader().run {
        val n = readUnsignedShort()
        val m = LinkedHashMap<String, String>(n)
        repeat(n) { m[readUTF()] = readUTF() }
        m
    }
}

@Throws(IOException::class)
fun InputStream.readExactly(n: Int): ByteArray {
    val b = ByteArray(n)
    DataInputStream(this).readFully(b)
    return b
}
