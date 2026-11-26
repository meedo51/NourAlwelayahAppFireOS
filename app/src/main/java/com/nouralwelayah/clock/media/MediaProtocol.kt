package com.nouralwelayah.clock.media

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Bluetooth LE link between the media remote (phone) and the media player (tablet).
 * The player is a GATT server with one service: the remote writes to RX, the player notifies on TX.
 * Both directions carry JSON objects, one per line ("\n"), split into pieces that fit the link.
 *
 * Remote -> player: {"c":"hello"} (send list + state), {"c":"toggle"|"next"|"prev"|"stop"},
 *   {"c":"file","i":n} (play file n), {"c":"yt","i":n} (play YouTube video n), {"c":"link","url":"..."},
 *   {"c":"vol","v":0..1}, {"c":"folders"}, {"c":"browse","folder":"..."}, {"c":"pick","i":n} (play file n of the
 *   folder browsed last, then the rest of that folder); "stop" stops and closes the player screen;
 *   {"c":"full","on":bool} (video full screen; a video started from the remote starts full screen)
 * Player -> remote also: {"t":"folders","list":[{"name","n"}]}, {"t":"browse","folder","files":[{"title","video"}]}
 * Player -> remote: {"t":"list","files":[{"title","video"}],"yt":[{"title"}]},
 *   {"t":"state","kind":"file"|"yt"|"none","i":n,"title":"...","playing":bool,"pos":ms,"dur":ms,"vol":0..1},
 *   {"t":"msg","text":"..."}
 * Own UUIDs, so the ESP32 clock picker (Nordic UART) does not list the tablet as a clock.
 */
object MediaProtocol {
    val SERVICE: UUID = UUID.fromString("8f1d0001-5c2e-4b7a-9a61-3e2c7d4a9b10")
    val RX: UUID = UUID.fromString("8f1d0002-5c2e-4b7a-9a61-3e2c7d4a9b10")      // remote writes
    val TX: UUID = UUID.fromString("8f1d0003-5c2e-4b7a-9a61-3e2c7d4a9b10")      // player notifies
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val MTU = 247
    const val MAX_PIECE = 512

    /**
     * Splits one message (a line) into pieces of at most [size] bytes (MTU - 3), never more than 512:
     * Android 14+ always negotiates MTU 517, but an attribute value may not be longer than 512 bytes.
     */
    fun pieces(line: String, size: Int): List<ByteArray> {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        val n = size.coerceIn(20, MAX_PIECE)
        return (bytes.indices step n).map { bytes.copyOfRange(it, minOf(it + n, bytes.size)) }
    }
}

/** Collects pieces into lines; bytes, not text, since a character can be split between pieces. */
class LineCollector(private val onLine: (String) -> Unit) {
    private val buf = ByteArrayOutputStream()

    fun add(data: ByteArray) {
        for (b in data) {
            if (b == '\n'.code.toByte()) {
                val line = String(buf.toByteArray(), Charsets.UTF_8).trim()
                buf.reset()
                if (line.isNotEmpty()) onLine(line)
            } else buf.write(b.toInt())
        }
        if (buf.size() > 64 * 1024) buf.reset()       // never a line this long: drop garbage
    }

    fun reset() = buf.reset()
}
