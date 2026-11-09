package com.cozyswitch.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.cozyswitch.app.widget.PlugWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import javax.net.SocketFactory

/** One plug. [on] is null when the plug didn't answer last time. */
data class Plug(
    val did: String,
    val name: String,
    val ip: String,
    val pid: String = "",
    val on: Boolean? = null,
)

/** The saved plug list, plus everything that talks to the plugs. Used by both the app and the widget. */
object Plugs {
    private const val PREFS = "plugs"
    private const val KEY = "list"

    @Synchronized
    fun load(ctx: Context): List<Plug> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Plug(
                    did = o.getString("did"),
                    name = o.optString("name"),
                    ip = o.optString("ip"),
                    pid = o.optString("pid"),
                    on = if (o.has("on")) o.optBoolean("on") else null,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(ctx: Context, list: List<Plug>) {
        val a = JSONArray()
        list.forEach { p ->
            val o = JSONObject().put("did", p.did).put("name", p.name).put("ip", p.ip).put("pid", p.pid)
            if (p.on != null) o.put("on", p.on)
            a.put(o)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
        listeners.forEach { it() }
    }

    /** The app screen listens here so it redraws when the widget changes something. */
    val listeners = mutableListOf<() -> Unit>()

    @Synchronized
    private fun edit(ctx: Context, change: (List<Plug>) -> List<Plug>) = save(ctx, change(load(ctx)))

    private fun replace(ctx: Context, did: String, change: (Plug) -> Plug) =
        edit(ctx) { l -> l.map { if (it.did == did) change(it) else it } }

    fun rename(ctx: Context, did: String, name: String) {
        replace(ctx, did) { it.copy(name = name.trim().ifEmpty { it.name }) }
        PlugWidget.updateAll(ctx)
    }

    fun remove(ctx: Context, did: String) {
        edit(ctx) { l -> l.filterNot { it.did == did } }
        PlugWidget.updateAll(ctx)
    }

    fun move(ctx: Context, did: String, by: Int) {
        edit(ctx) { l ->
            val i = l.indexOfFirst { it.did == did }
            val j = i + by
            if (i < 0 || j !in l.indices) l else l.toMutableList().apply { add(j, removeAt(i)) }
        }
        PlugWidget.updateAll(ctx)
    }

    // ---- Network ----

    /** The Wi-Fi network, so plug traffic never goes out over mobile data. */
    private fun wifi(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    fun onWifi(ctx: Context) = wifi(ctx) != null

    private fun client(ctx: Context, connectMs: Int = 1500): CozyClient =
        CozyClient(wifi(ctx)?.socketFactory ?: SocketFactory.getDefault(), connectMs = connectMs)

    /** Every address on the phone's Wi-Fi (at most a /24 around the phone). */
    private fun subnetHosts(ctx: Context): List<String> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val net = wifi(ctx) ?: return emptyList()
        val la = cm.getLinkProperties(net)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address } ?: return emptyList()
        val b = la.address.address
        val me = ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or
            ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
        val prefix = la.prefixLength.coerceIn(24, 30)
        val mask = -1 shl (32 - prefix)
        val base = me and mask
        val count = (1 shl (32 - prefix)) - 2
        return (1..count).map { base + it }.filter { it != me }.map { a ->
            "${(a ushr 24) and 255}.${(a ushr 16) and 255}.${(a ushr 8) and 255}.${a and 255}"
        }
    }

    data class ScanResult(val found: Int, val added: Int)

    /**
     * Looks through the whole Wi-Fi for CozyLife plugs. New ones are added; known ones get
     * their address updated if the router gave them a new one.
     */
    suspend fun scan(ctx: Context, progress: (Float) -> Unit = {}): ScanResult = withContext(Dispatchers.IO) {
        val hosts = subnetHosts(ctx)
        if (hosts.isEmpty()) return@withContext ScanResult(0, 0)
        val quick = client(ctx, connectMs = 700)
        val c = client(ctx)
        val gate = Semaphore(48)
        var done = 0
        val found = coroutineScope {
            hosts.map { ip ->
                async {
                    gate.withPermit {
                        val info = if (quick.portOpen(ip, 700)) c.info(ip) else null
                        synchronized(this@Plugs) { done++; progress(done.toFloat() / hosts.size) }
                        info?.let { ip to it }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        var added = 0
        edit(ctx) { old ->
            val l = old.toMutableList()
            for ((ip, info) in found) {
                val i = l.indexOfFirst { it.did == info.did }
                if (i >= 0) l[i] = l[i].copy(ip = ip, pid = info.pid)
                else { added++; l += Plug(info.did, "Plug ${l.size + 1}", ip, info.pid) }
            }
            l
        }
        refresh(ctx)
        ScanResult(found.size, added)
    }

    /** Adds a plug by its address. Returns false if nothing answered there. */
    suspend fun addByIp(ctx: Context, ip: String): Boolean = withContext(Dispatchers.IO) {
        val info = client(ctx).info(ip.trim()) ?: return@withContext false
        edit(ctx) { l ->
            if (l.any { it.did == info.did }) l.map { if (it.did == info.did) it.copy(ip = ip.trim()) else it }
            else l + Plug(info.did, "Plug ${l.size + 1}", ip.trim(), info.pid)
        }
        refresh(ctx)
        true
    }

    /** Asks every plug whether it's on. */
    suspend fun refresh(ctx: Context) = withContext(Dispatchers.IO) {
        val list = load(ctx)
        if (list.isEmpty()) { PlugWidget.updateAll(ctx); return@withContext }
        val c = client(ctx)
        val states = coroutineScope { list.map { p -> async { p.did to c.query(p.ip) } }.awaitAll() }.toMap()
        edit(ctx) { l -> l.map { if (states.containsKey(it.did)) it.copy(on = states[it.did]) else it } }
        PlugWidget.updateAll(ctx)
    }

    /**
     * Turns a plug on or off. If it doesn't answer at its old address, looks for it on the
     * Wi-Fi once (the router may have moved it) and tries again.
     */
    suspend fun switch(ctx: Context, did: String, on: Boolean): Boolean? = withContext(Dispatchers.IO) {
        val p = load(ctx).firstOrNull { it.did == did } ?: return@withContext null
        var result = client(ctx).set(p.ip, on)
        if (result == null && onWifi(ctx)) {
            scan(ctx)
            val moved = load(ctx).firstOrNull { it.did == did }
            if (moved != null && moved.ip != p.ip) result = client(ctx).set(moved.ip, on)
        }
        replace(ctx, did) { it.copy(on = result) }
        PlugWidget.updateAll(ctx)
        result
    }
}
