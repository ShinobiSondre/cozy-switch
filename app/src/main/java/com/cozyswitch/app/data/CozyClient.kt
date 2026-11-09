package com.cozyswitch.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

/** What a plug says about itself. */
data class PlugInfo(val did: String, val pid: String, val mac: String)

/**
 * Talks to CozyLife Wi-Fi plugs over the home network. After the CozyLife app has put a plug
 * on Wi-Fi (that's what the Bluetooth is for), the plug listens on TCP port 5555 for short
 * JSON messages, one per line:
 *
 *   info:  {"pv":0,"cmd":0,"sn":"…","msg":{}}
 *   query: {"pv":0,"cmd":2,"sn":"…","msg":{"attr":[0]}}
 *   set:   {"pv":0,"cmd":3,"sn":"…","msg":{"attr":[1],"data":{"1":255}}} (255 = on like the CozyLife app, 0 = off)
 *
 * The plug answers with the same "sn", and may also send unrequested status messages (cmd 10).
 * Same protocol as the Home Assistant CozyLife integration; no cloud account is involved.
 */
class CozyClient(
    private val factory: SocketFactory = SocketFactory.getDefault(),
    private val port: Int = PORT,
    private val connectMs: Int = 1500,
    private val replyMs: Int = 2500,
) {
    companion object {
        const val PORT = 5555
        const val CMD_INFO = 0
        const val CMD_QUERY = 2
        const val CMD_SET = 3
        private val sn = AtomicLong(System.currentTimeMillis())
        fun nextSn(): String = sn.incrementAndGet().toString()

        fun packet(cmd: Int, sn: String, msg: JSONObject): ByteArray =
            (JSONObject().put("pv", 0).put("cmd", cmd).put("sn", sn).put("msg", msg).toString() + "\r\n")
                .toByteArray(Charsets.UTF_8)

        /** Reads whole JSON objects from a stream, however the plug splits or joins them. */
        internal class ObjectReader(private val input: InputStream) {
            private val sb = StringBuilder()
            private var depth = 0
            private var inString = false
            private var escape = false

            /** Next complete object, or null when the stream ends. Throws on timeout. */
            fun next(): JSONObject? {
                while (true) {
                    val b = input.read()
                    if (b < 0) return null
                    val c = b.toChar()
                    if (depth == 0) {
                        if (c == '{') { sb.setLength(0); sb.append(c); depth = 1 }
                        continue
                    }
                    sb.append(c)
                    if (inString) {
                        when {
                            escape -> escape = false
                            c == '\\' -> escape = true
                            c == '"' -> inString = false
                        }
                        continue
                    }
                    when (c) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                try { return JSONObject(sb.toString()) } catch (_: Exception) { /* skip junk */ }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun open(ip: String, ms: Int = connectMs): Socket {
        val s = factory.createSocket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(ip, port), ms)
            s.soTimeout = replyMs
            return s
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) { }
            throw e
        }
    }

    /** Sends one message and waits for the answer with the same sn. */
    private fun request(s: Socket, reader: ObjectReader, cmd: Int, msg: JSONObject): JSONObject? {
        val id = nextSn()
        s.getOutputStream().apply { write(packet(cmd, id, msg)); flush() }
        val deadline = System.currentTimeMillis() + replyMs
        while (System.currentTimeMillis() < deadline) {
            val o = try { reader.next() } catch (_: SocketTimeoutException) { return null } ?: return null
            if (o.optString("sn") == id) return o
        }
        return null
    }

    private fun <T> withPlug(ip: String, block: (Socket, ObjectReader) -> T): T? = try {
        open(ip).use { s -> block(s, ObjectReader(s.getInputStream())) }
    } catch (_: Exception) {
        null
    }

    /** True if something answers on the plug port at all (used when scanning). */
    fun portOpen(ip: String, ms: Int): Boolean = try {
        open(ip, ms).close(); true
    } catch (_: Exception) {
        false
    }

    fun info(ip: String): PlugInfo? = withPlug(ip) { s, r ->
        val m = request(s, r, CMD_INFO, JSONObject())?.optJSONObject("msg") ?: return@withPlug null
        val did = m.optString("did")
        if (did.isEmpty()) null else PlugInfo(did, m.optString("pid"), m.optString("mac"))
    }

    /** Whether the plug is on, or null if it didn't answer. */
    fun query(ip: String): Boolean? = withPlug(ip) { s, r ->
        stateOf(request(s, r, CMD_QUERY, JSONObject().put("attr", JSONArray().put(0))))
    }

    /** Switches the plug and returns its new state, or null if it didn't answer. */
    fun set(ip: String, on: Boolean): Boolean? = withPlug(ip) { s, r ->
        val msg = JSONObject()
            .put("attr", JSONArray().put(1))
            .put("data", JSONObject().put("1", if (on) 255 else 0))
        // Most plugs echo the change back; if one doesn't, ask it directly.
        stateOf(request(s, r, CMD_SET, msg))
            ?: stateOf(request(s, r, CMD_QUERY, JSONObject().put("attr", JSONArray().put(0))))
    }

    private fun stateOf(reply: JSONObject?): Boolean? {
        val data = reply?.optJSONObject("msg")?.optJSONObject("data") ?: return null
        if (!data.has("1")) return null
        return data.optInt("1", 0) > 0
    }
}
