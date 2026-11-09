package com.cozyswitch.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.cozyswitch.app.widget.PlugWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import javax.net.SocketFactory

/**
 * One plug. [on] is the last known state (null only if never known); [online] says whether
 * it answered recently. A single missed answer doesn't wipe the state: CozyLife doesn't either.
 */
data class Plug(
    val did: String,
    val name: String,
    val ip: String,
    val pid: String = "",
    val on: Boolean? = null,
    // From the CozyLife account, for switching away from home. Empty when not signed in.
    val key: String = "",
    val relayHost: String = "",
    val relayPort: Int = 0,
    // How the plug was last reached ("wifi", "cloud" or "" when not at all), and the
    // count of failed home-Wi-Fi tries. After 3 in a row the plug's own listener is
    // probably stuck (a known firmware fault), so Wi-Fi is skipped until [probeAt].
    val via: String = "",
    val fails: Int = 0,
    val probeAt: Long = 0,
    val online: Boolean = true,
    val misses: Int = 0,
    val seenAt: Long = 0,
) {
    /** Its home-Wi-Fi connection has stopped answering; only unplugging it fixes that. */
    val wifiStuck get() = fails >= STUCK_AFTER
    val hasCloud get() = key.isNotEmpty() && relayHost.isNotEmpty() && relayPort > 0
    fun cloud() = CloudPlug(did, key, name, relayHost, relayPort, null, true)
}

const val STUCK_AFTER = 3
const val PROBE_EVERY_MS = 10 * 60 * 1000L

/** What one attempt to reach a plug ended with. */
internal data class Outcome(val on: Boolean?, val via: String, val fails: Int, val probeAt: Long)

/**
 * Wi-Fi first, cloud second, skipping a stuck Wi-Fi connection (and checking it again every
 * 10 minutes, so it's used again once the plug has been unplugged and plugged back in).
 */
internal fun reach(
    p: Plug, home: Boolean, now: Long,
    local: (String) -> Boolean?,
    cloud: (CloudPlug) -> Boolean?,
): Outcome {
    var fails = p.fails
    var probeAt = p.probeAt
    val tryLocal = home && p.ip.isNotEmpty() && (!p.hasCloud || fails < STUCK_AFTER || now >= probeAt)
    if (tryLocal) {
        local(p.ip)?.let { return Outcome(it, "wifi", 0, 0) }
        fails++
        if (fails >= STUCK_AFTER) probeAt = now + PROBE_EVERY_MS
    }
    if (p.hasCloud) cloud(p.cloud())?.let { return Outcome(it, "cloud", fails, probeAt) }
    return Outcome(null, "", fails, probeAt)
}

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
                    key = o.optString("key"),
                    relayHost = o.optString("relayHost"),
                    relayPort = o.optInt("relayPort", 0),
                    via = o.optString("via"),
                    fails = o.optInt("fails", 0),
                    probeAt = o.optLong("probeAt", 0),
                    online = o.optBoolean("online", true),
                    misses = o.optInt("misses", 0),
                    seenAt = o.optLong("seenAt", 0),
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
            o.put("via", p.via).put("fails", p.fails).put("probeAt", p.probeAt)
                .put("online", p.online).put("misses", p.misses).put("seenAt", p.seenAt)
            if (p.hasCloud) o.put("key", p.key).put("relayHost", p.relayHost).put("relayPort", p.relayPort)
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

    /** Folds an answer (or a miss) into a plug. Two misses in a row before calling it offline. */
    private fun Plug.withOutcome(o: Outcome, offlineNow: Boolean = false): Plug =
        if (o.on != null) copy(on = o.on, via = o.via, fails = o.fails, probeAt = o.probeAt,
            online = true, misses = 0, seenAt = System.currentTimeMillis())
        else copy(fails = o.fails, probeAt = o.probeAt, misses = misses + 1,
            online = !offlineNow && misses + 1 < 2)

    /** One connection at a time per plug: they queue or drop a second one (seen on real plugs). */
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private fun <T> oneAtATime(did: String, block: () -> T): T = synchronized(locks.getOrPut(did) { Any() }) { block() }

    fun rename(ctx: Context, did: String, name: String) {
        replace(ctx, did) { it.copy(name = name.trim().ifEmpty { it.name }) }
        PlugWidget.updateAll(ctx)
    }

    fun remove(ctx: Context, did: String) {
        edit(ctx) { l -> l.filterNot { it.did == did } }
        Automations.forgetPlug(ctx, did)
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
        val udp = Discovery.all(ctx)
        val hosts = subnetHosts(ctx).filterNot { h -> udp.any { it.second == h } }
        if (hosts.isEmpty() && udp.isEmpty()) return@withContext ScanResult(0, 0)
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
        } + udp.map { (did, ip) -> ip to PlugInfo(did, "", "") }
        var added = 0
        edit(ctx) { old ->
            val l = old.toMutableList()
            for ((ip, info) in found) {
                val i = l.indexOfFirst { it.did == info.did }
                if (i >= 0) l[i] = l[i].copy(ip = ip, pid = info.pid.ifEmpty { l[i].pid })
                else { added++; l += Plug(info.did, "Plug ${l.size + 1}", ip, info.pid) }
            }
            l
        }
        refresh(ctx, force = true)
        ScanResult(found.distinctBy { it.second.did }.size, added)
    }

    /** Adds a plug by its address. Returns false if nothing answered there. */
    suspend fun addByIp(ctx: Context, ip: String): Boolean = withContext(Dispatchers.IO) {
        val info = client(ctx).info(ip.trim()) ?: return@withContext false
        edit(ctx) { l ->
            if (l.any { it.did == info.did }) l.map { if (it.did == info.did) it.copy(ip = ip.trim()) else it }
            else l + Plug(info.did, "Plug ${l.size + 1}", ip.trim(), info.pid)
        }
        refresh(ctx, force = true)
        true
    }

    @Volatile private var lastRefresh = 0L
    @Volatile private var lastSummary = ""

    /**
     * Updates every plug's state. Like the CozyLife app, it reads from CozyLife's cloud when signed
     * in (one request, and the plugs aren't disturbed); plugs are only asked directly when the
     * cloud doesn't know them. Skipped if it ran in the last 20 seconds, unless [force].
     */
    suspend fun refresh(ctx: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < 20_000) { PlugWidget.updateAll(ctx); return@withContext }
        lastRefresh = now
        if (load(ctx).isEmpty()) { PlugWidget.updateAll(ctx); return@withContext }

        // 1. The cloud: fresh relay addresses + online flags, then on/off for all plugs at once.
        val token = token(ctx)
        val cloudOnline = mutableMapOf<String, Boolean>()
        var cloudStates: Map<String, Boolean?> = emptyMap()
        if (token != null) {
            try {
                val devices = CozyAccount().devices(token)
                cloudProblem = null
                applyCloud(ctx, devices)
                devices.forEach { cloudOnline[it.did] = it.online }
                cloudStates = CozyAccount().states(token, devices.filter { it.online }.map { it.did })
            } catch (e: CloudError) {
                // CozyLife answered but refused: usually an expired sign-in. (Network trouble isn't this.)
                if (e.message?.startsWith("Couldn't reach") != true) cloudProblem = e.message
            } catch (_: Exception) { }
        }

        val list = load(ctx)
        val home = onWifi(ctx)
        val c = client(ctx)
        val relay = CozyRelay()
        val results = coroutineScope {
            list.map { p ->
                async { try {
                    val fromCloud = cloudStates[p.did]
                    when {
                        fromCloud != null -> p.did to (Outcome(fromCloud, if (p.via == "wifi") "wifi" else "cloud", p.fails, p.probeAt) to false)
                        cloudOnline[p.did] == false -> p.did to (Outcome(null, "", p.fails, p.probeAt) to true)
                        else -> {
                            // 2. Not in the cloud answer: ask the plug (Wi-Fi, then relay).
                            var o = reach(p, home, System.currentTimeMillis(), { ip -> oneAtATime(p.did) { c.query(ip) } },
                                { cp -> try { kotlinx.coroutines.runBlocking { LiveLink.send(cp, null) } ?: relay.query(cp) } catch (_: CloudError) { null } })
                            if (o.on == null && home && !p.hasCloud) {
                                // 3. It may have a new address: ask the Wi-Fi who's there (quick UDP call).
                                val ip = Discovery.find(ctx, p.did)
                                if (ip != null && ip != p.ip) {
                                    replace(ctx, p.did) { it.copy(ip = ip) }
                                    oneAtATime(p.did) { c.query(ip) }?.let { o = Outcome(it, "wifi", 0, 0) }
                                }
                            }
                            p.did to (o to false)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (_: Exception) { p.did to (Outcome(null, "", p.fails, p.probeAt) to false) } }
            }.awaitAll()
        }.toMap()
        edit(ctx) { l -> l.map { p -> results[p.did]?.let { (o, off) -> p.withOutcome(o, off) } ?: p } }
        PlugWidget.updateAll(ctx)
        val summary = load(ctx).joinToString { p ->
            "${p.name}=" + (if (!p.online) "offline" else when (p.on) { true -> "on"; false -> "off"; null -> "?" }) +
                (if (cloudStates.containsKey(p.did)) "(cloud)" else "")
        } + if (token != null && cloudStates.isEmpty()) "  [cloud status unavailable]" else ""
        if (summary != lastSummary) { lastSummary = summary; ConnLog.add(ctx, "status: $summary") }

        // Plugs that dropped off: reconnect them over Bluetooth if they're near, like CozyLife.
        val now2 = System.currentTimeMillis()
        load(ctx).filter { !it.online && now2 - (lastBleTry[it.did] ?: 0L) > 5 * 60_000 }.take(2).forEach { p ->
            try { reconnect(ctx, p.did) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    /**
     * Turns a plug on or off. If it doesn't answer at its old address, looks for it on the
     * Wi-Fi once (the router may have moved it) and tries again.
     */
    suspend fun switch(ctx: Context, did: String, on: Boolean): Boolean? = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var p = load(ctx).firstOrNull { it.did == did } ?: return@withContext null
        val home = onWifi(ctx)
        lastCloudError = null
        val c = client(ctx)
        val now = System.currentTimeMillis()
        val tryLocal = home && p.ip.isNotEmpty() && (!p.hasCloud || p.fails < STUCK_AFTER || now >= p.probeAt)
        var fails = p.fails
        var probeAt = p.probeAt

        suspend fun cloudOnce(cp: CloudPlug): Boolean? = try {
            // The standing link if it's up (no setup delay), otherwise a one-off connection.
            LiveLink.send(cp, on) ?: CozyRelay().set(cp, on)
        } catch (e: CloudError) { lastCloudError = e.message; null }

        // 1. Like the CozyLife app: send over Wi-Fi and the cloud at the same time; first answer wins.
        val winner = kotlinx.coroutines.channels.Channel<Pair<Boolean, String>?>(4)
        var won: Pair<Boolean, String>? = null
        // Detached, so a slow path never holds up the answer from a fast one.
        run {
            val race = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
            val jobs = mutableListOf<kotlinx.coroutines.Job>()
            if (tryLocal) jobs += race.launch {
                val r = oneAtATime(did) { c.set(p.ip, on) }
                ConnLog.add(ctx, "${p.name}: Wi-Fi ${p.ip} → ${r ?: "no answer"} (${System.currentTimeMillis() - start} ms)")
                if (r == null) { fails++; if (fails >= STUCK_AFTER) probeAt = System.currentTimeMillis() + PROBE_EVERY_MS } else fails = 0
                winner.send(r?.let { it to "wifi" })
            }
            if (p.hasCloud) jobs += race.launch {
                var r = cloudOnce(p.cloud())
                if (r == null) {
                    // The plug may have moved to another relay: fetch the address and try once more.
                    syncCloud(ctx)
                    val fresh = load(ctx).firstOrNull { it.did == did }
                    if (fresh != null && fresh.hasCloud && (fresh.relayHost != p.relayHost || fresh.relayPort != p.relayPort)) {
                        r = cloudOnce(fresh.cloud())
                    }
                }
                ConnLog.add(ctx, "${p.name}: cloud ${p.relayHost}:${p.relayPort} → ${r ?: (lastCloudError ?: "no answer")} (${System.currentTimeMillis() - start} ms)")
                winner.send(r?.let { it to "cloud" })
            }
            var left = jobs.size
            kotlinx.coroutines.withTimeoutOrNull(15_000) {
                while (left > 0) {
                    val r = winner.receive(); left--
                    if (r != null) { won = r; break }
                }
            }
            jobs.forEach { it.cancel() }
        }
        // 2. At home and no answer: the router may have moved the plug. A quick UDP call finds it.
        if (won == null && home && p.ip.isNotEmpty()) {
            val ip = Discovery.find(ctx, did)
            if (ip != null && ip != p.ip) {
                replace(ctx, did) { it.copy(ip = ip) }
                val r = oneAtATime(did) { c.set(ip, on) }
                ConnLog.add(ctx, "${p.name}: moved to $ip → ${r ?: "no answer"}")
                if (r != null) { won = r to "wifi"; fails = 0 }
            }
        }
        // 3. Bluetooth, like the CozyLife app, which also re-registers the plug with the cloud.
        if (won == null) {
            lastBleTry[did] = System.currentTimeMillis()
            p = load(ctx).firstOrNull { it.did == did } ?: p
            val r = PlugSetup(ctx).bleSet(did, on, reregisterFor(ctx, p))
            ConnLog.add(ctx, "${p.name}: Bluetooth → ${r ?: "not found / no answer"}")
            if (r != null) won = r to "bluetooth"
        }
        val o = Outcome(won?.first, won?.second ?: "", fails, probeAt)
        replace(ctx, did) { it.withOutcome(o) }
        ConnLog.add(ctx, "${p.name}: turn ${if (on) "on" else "off"} → ${won?.let { "OK via ${it.second}" } ?: "FAILED"} in ${System.currentTimeMillis() - start} ms")
        PlugWidget.updateAll(ctx)
        o.on
    }

    /** A plug reported its state by itself (over the standing cloud link). */
    fun pushed(ctx: Context, did: String, on: Boolean) {
        val p = load(ctx).firstOrNull { it.did == did } ?: return
        if (p.on == on && p.online) return
        replace(ctx, did) { it.copy(on = on, online = true, misses = 0, seenAt = System.currentTimeMillis()) }
        PlugWidget.updateAll(ctx)
    }

    /** Set when CozyLife refuses the saved sign-in; the screen asks to sign in again. */
    @Volatile var cloudProblem: String? = null

    /** Why the last cloud switch failed, in words, if it did. */
    @Volatile var lastCloudError: String? = null

    // ---- CozyLife account (away mode) ----

    private const val ACCOUNT = "account"

    private fun token(ctx: Context): String? =
        ctx.getSharedPreferences(ACCOUNT, Context.MODE_PRIVATE).getString("token", null)

    fun accountEmail(ctx: Context): String? =
        ctx.getSharedPreferences(ACCOUNT, Context.MODE_PRIVATE).getString("email", null)

    /**
     * Signs in to CozyLife, then copies each plug's cloud key and relay address (and its
     * CozyLife name, if you haven't renamed it). Plugs only in the account are added too.
     * The password isn't kept; only the session.
     */
    suspend fun signIn(ctx: Context, email: String, password: String): Int = withContext(Dispatchers.IO) {
        val acc = CozyAccount()
        val session = acc.login(email, password)
        val token = session.token
        ctx.getSharedPreferences(ACCOUNT, Context.MODE_PRIVATE).edit()
            .putString("email", email.trim()).putString("token", token).putString("uid", session.uid).apply()
        val n = applyCloud(ctx, acc.devices(token))
        refresh(ctx, force = true)
        n
    }

    /** Re-reads the account's device list (relay addresses can change). Quietly does nothing when signed out. */
    suspend fun syncCloud(ctx: Context) = withContext(Dispatchers.IO) {
        val token = ctx.getSharedPreferences(ACCOUNT, Context.MODE_PRIVATE).getString("token", null)
            ?: return@withContext
        try { applyCloud(ctx, CozyAccount().devices(token)) } catch (_: Exception) { }
    }

    /** Token and user id, needed to set up a plug; null if not signed in (or signed in before setup existed). */
    fun session(ctx: Context): CozyAccount.Session? {
        val p = ctx.getSharedPreferences(ACCOUNT, Context.MODE_PRIVATE)
        val t = p.getString("token", null) ?: return null
        val u = p.getString("uid", null)?.ifEmpty { null } ?: return null
        return CozyAccount.Session(t, u)
    }

    /** Like [session], but fetches the user id from CozyLife if this sign-in didn't keep it. */
    suspend fun ensureSession(ctx: Context): CozyAccount.Session? = withContext(Dispatchers.IO) {
        session(ctx)?.let { return@withContext it }
        val t = token(ctx) ?: return@withContext null
        val uid = try { CozyAccount().uid(t) } catch (_: Exception) { null } ?: return@withContext null
        ctx.getSharedPreferences(ACCOUNT, Context.MODE_PRIVATE).edit().putString("uid", uid).apply()
        CozyAccount.Session(t, uid)
    }

    /** What the reconnect message needs: token and the plug's key. */
    private fun reregisterFor(ctx: Context, p: Plug): Pair<String, String>? {
        val t = token(ctx) ?: return null
        return if (p.key.isNotEmpty()) t to p.key else null
    }

    /**
     * Brings a plug back the way the CozyLife app does: connect over Bluetooth, read its state,
     * and tell it to register with the account again (keeping its Wi-Fi). Returns true if it answered.
     */
    suspend fun reconnect(ctx: Context, did: String): Boolean = withContext(Dispatchers.IO) {
        val p = load(ctx).firstOrNull { it.did == did } ?: return@withContext false
        lastBleTry[did] = System.currentTimeMillis()
        val state = PlugSetup(ctx).bleSet(did, null, reregisterFor(ctx, p))
        ConnLog.add(ctx, "${p.name}: reconnect over Bluetooth → ${if (state != null) "answered, told to rejoin CozyLife" else "not found / no answer"}")
        if (state != null) replace(ctx, did) { it.withOutcome(Outcome(state, "bluetooth", it.fails, it.probeAt)) }
        PlugWidget.updateAll(ctx)
        state != null
    }

    private val lastBleTry = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Waits for a plug to show up online in the account after setup, then adds it here. */
    suspend fun waitForCloudPlug(ctx: Context, did: String, timeoutMs: Long = 120_000): Boolean = withContext(Dispatchers.IO) {
        val s = session(ctx) ?: return@withContext false
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val found = try { CozyAccount().devices(s.token).firstOrNull { it.did == did } } catch (_: Exception) { null }
            if (found != null && found.online) {
                applyCloud(ctx, listOf(found))
                if (onWifi(ctx)) Discovery.find(ctx, did)?.let { ip -> replace(ctx, did) { it.copy(ip = ip) } }
                refresh(ctx, force = true)
                return@withContext true
            }
            kotlinx.coroutines.delay(4000)
        }
        false
    }

    fun signOut(ctx: Context) {
        ctx.getSharedPreferences(ACCOUNT, Context.MODE_PRIVATE).edit().clear().apply()
        edit(ctx) { l -> l.map { it.copy(key = "", relayHost = "", relayPort = 0) } }
        PlugWidget.updateAll(ctx)
    }

    private fun isDefaultName(n: String) = Regex("Plug \\d+").matches(n)

    private fun applyCloud(ctx: Context, cloud: List<CloudPlug>): Int {
        var count = 0
        edit(ctx) { old ->
            val l = old.toMutableList()
            for (c in cloud) {
                count++
                val i = l.indexOfFirst { it.did == c.did }
                if (i >= 0) {
                    val p = l[i]
                    l[i] = p.copy(
                        key = c.key, relayHost = c.relayHost, relayPort = c.relayPort,
                        name = if (isDefaultName(p.name)) c.name else p.name,
                        ip = p.ip.ifEmpty { c.localIp.orEmpty() },
                    )
                } else {
                    l += Plug(c.did, c.name, c.localIp.orEmpty(), "", null, c.key, c.relayHost, c.relayPort)
                }
            }
            l
        }
        return count
    }
}
