package com.cozyswitch.app.data

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A standing connection to CozyLife's relay, held the way the CozyLife app holds it (NetTcpWriteClient
 * + ReadTcpClient + TcpHeartChecker): one socket per relay, subscribed to every plug on it, a
 * "cmd=keep" every 40 seconds, and the plugs' own reports pushed down it as they happen. Commands
 * go over the same socket, so there's no connect-and-subscribe delay per tap.
 */
object LiveLink {
    private class Relay(val host: String, val port: Int) {
        @Volatile var retryAt = 0L
        @Volatile var backoff = 5_000L
        @Volatile var socket: Socket? = null
        val subscribed = ConcurrentHashMap<String, String>() // did -> key
        val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
        val acks = ConcurrentLinkedQueue<CompletableDeferred<Int>>()
        @Volatile var lastRx = 0L
        val alive get() = socket?.let { !it.isClosed && it.isConnected } == true
    }

    private val relays = ConcurrentHashMap<String, Relay>()
    @Volatile private var running = false
    @Volatile private var keeper: Thread? = null
    @Volatile private var appCtx: Context? = null
    @Volatile private var stopAt = 0L

    /** Opens (or keeps) the links for every signed-in plug. Called when the app comes to the front. */
    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        stopAt = 0L
        if (running) return
        running = true
        keeper = Thread { loop() }.apply { isDaemon = true; name = "cozy-live-link"; start() }
    }

    /** Keeps the links a few minutes after the app goes to the back, then closes them. */
    fun stopSoon() { stopAt = System.currentTimeMillis() + 3 * 60_000 }

    val connected get() = relays.values.any { it.alive }

    private fun loop() {
        var lastKeep = 0L
        while (running) {
            if (stopAt != 0L && System.currentTimeMillis() > stopAt) break
            try {
                sync()
                val now = System.currentTimeMillis()
                if (now - lastKeep > 40_000) {
                    lastKeep = now
                    relays.values.filter { it.alive }.forEach { write(it, "cmd=keep") }
                }
                // No sign of life for 2 minutes: the connection is dead even if it looks open.
                relays.values.filter { it.alive && now - it.lastRx > 120_000 }.forEach { close(it, "silent for 2 min") }
            } catch (_: Exception) { }
            try { Thread.sleep(5_000) } catch (_: InterruptedException) { break }
        }
        running = false
        relays.values.forEach { close(it, "app in background") }
        relays.clear()
    }

    /** Makes the set of links match the plugs: right relay, subscribed to each plug. */
    @Synchronized private fun sync() {
        val ctx = appCtx ?: return
        val plugs = Plugs.load(ctx).filter { it.hasCloud }
        val want = plugs.groupBy { "${it.relayHost}:${it.relayPort}" }
        // Relays no plug uses any more (a plug moved to another relay): drop them.
        for (k in relays.keys.toList()) if (k !in want) relays.remove(k)?.let { close(it, "no plugs on it") }
        for ((k, list) in want) {
            val r = relays.getOrPut(k) { Relay(list[0].relayHost, list[0].relayPort) }
            if (!r.alive) {
                if (System.currentTimeMillis() < r.retryAt) continue
                if (open(r) == null) {
                    r.retryAt = System.currentTimeMillis() + r.backoff
                    r.backoff = (r.backoff * 2).coerceAtMost(120_000)
                    continue
                }
                r.backoff = 5_000
            }
            for (p in list) if (r.subscribed[p.did] != p.key) {
                write(r, "cmd=subscribe&topic=device_${p.did}&from=control&device_id=${p.did}&device_key=${p.key}")
                r.subscribed[p.did] = p.key
            }
        }
    }

    private fun open(r: Relay): Unit? = try {
        val s = Socket()
        s.connect(InetSocketAddress(r.host, r.port), 6_000)
        s.soTimeout = 0
        s.keepAlive = true
        r.socket = s
        r.subscribed.clear()
        r.lastRx = System.currentTimeMillis()
        Thread { read(r, s) }.apply { isDaemon = true; start() }
        ConnLog.add(appCtx, "cloud link: connected to ${r.host}:${r.port}")
        Unit
    } catch (e: Exception) {
        ConnLog.add(appCtx, "cloud link: can't reach ${r.host}:${r.port} (${e.javaClass.simpleName})")
        null
    }

    private fun close(r: Relay, why: String) {
        val s = r.socket ?: return
        r.socket = null
        try { s.close() } catch (_: Exception) { }
        r.subscribed.clear()
        r.acks.forEach { it.complete(-1) }; r.acks.clear()
        ConnLog.add(appCtx, "cloud link: closed ${r.host}:${r.port} ($why)")
    }

    private fun write(r: Relay, line: String) {
        val s = r.socket ?: return
        try { synchronized(r) { s.getOutputStream().apply { write((line + "\r\n").toByteArray()); flush() } } }
        catch (_: Exception) { close(r, "write failed") }
    }

    private fun read(r: Relay, s: Socket) {
        val sb = StringBuilder()
        try {
            val input: InputStream = s.getInputStream()
            while (true) {
                val b = input.read()
                if (b < 0) break
                if (b == '\n'.code) {
                    val line = sb.toString().trimEnd('\r'); sb.setLength(0)
                    if (line.isNotEmpty()) handle(r, line)
                } else sb.append(b.toChar())
            }
        } catch (_: Exception) { }
        if (r.socket === s) close(r, "relay hung up")
    }

    private fun handle(r: Relay, line: String) {
        r.lastRx = System.currentTimeMillis()
        val f = CozyRelay.parseLine(line)
        if (f["cmd"] == "publish" && f["topic"] == null && f["num"] != null) {
            r.acks.poll()?.complete(f["num"]?.toIntOrNull() ?: -1)
            return
        }
        val topic = f["topic"] ?: return
        if (!topic.startsWith("device_")) return
        val did = topic.removePrefix("device_")
        val raw = f["message"] ?: return
        val msg = try { JSONObject(URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")) } catch (_: Exception) { return }
        r.pending.remove(msg.optString("sn"))?.complete(msg)
        appCtx?.let { c -> Plugs.load(c).firstOrNull { it.did == did }?.let { ConnLog.add(c, "${it.name}: plug reported ${msg.toString().take(160)}") } }
        // Any report carrying datapoint 1 is the plug telling us its state: keep the app in step.
        val data = msg.optJSONObject("msg")?.optJSONObject("data")
        if (data != null && data.has("1")) {
            val on = data.optInt("1") > 0
            appCtx?.let { Plugs.pushed(it, did, on) }
        }
    }

    /**
     * Sends a command to a plug over the standing link. Returns its new state, null if it
     * didn't answer in time, or throws [CloudError] when the relay says the plug isn't there.
     * Returns null straight away if there's no live link (callers then use a one-off connection).
     */
    suspend fun send(p: CloudPlug, on: Boolean?): Boolean? {
        val r = relays["${p.relayHost}:${p.relayPort}"]?.takeIf { it.alive && it.subscribed[p.did] == p.key } ?: return null
        val sn = CozyClient.nextSn()
        val msg = if (on == null) JSONObject().put("attr", JSONArray().put(0))
        else JSONObject().put("attr", JSONArray().put(1)).put("data", JSONObject().put("1", if (on) 255 else 0))
        val frame = JSONObject().put("pv", 0).put("cmd", if (on == null) 2 else 3).put("sn", sn).put("msg", msg).toString()
        val reply = CompletableDeferred<JSONObject>()
        val ack = CompletableDeferred<Int>()
        r.pending[sn] = reply
        r.acks.add(ack)
        write(r, "cmd=publish&topic=control_${p.did}&device_id=${p.did}&device_key=${p.key}&message=" +
            URLEncoder.encode(frame, "UTF-8").replace("+", "%20"))
        try {
            val n = withTimeoutOrNull(4_000) { ack.await() }
            if (n == 0) throw CloudError("The plug isn't connected to CozyLife right now.")
            val m = withTimeoutOrNull(5_000) { reply.await() } ?: return null
            val d = m.optJSONObject("msg")?.optJSONObject("data")
            return if (d != null && d.has("1")) d.optInt("1") > 0 else on
        } finally {
            r.pending.remove(sn)
        }
    }
}
