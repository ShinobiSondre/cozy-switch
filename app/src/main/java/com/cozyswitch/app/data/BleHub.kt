package com.cozyswitch.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * What the CozyLife app does every time you open it (IndexFragment → refreshByApp → connectWifiBle
 * → updateDeviceData): connect to every plug in range over Bluetooth and stay connected, tell
 * each one once to keep its Wi-Fi and register with the account again (that revives plugs whose
 * Wi-Fi or cloud link froze), read its state, and then send taps over Bluetooth too.
 */
object BleHub {
    private val links = ConcurrentHashMap<String, PlugSetup.Link>()   // did -> open link
    private val revived = ConcurrentHashMap.newKeySet<String>()        // re-registered this session
    private val opLock = ConcurrentHashMap<String, Mutex>()
    private var job: Job? = null
    @Volatile private var stopAt = 0L

    fun isLinked(did: String) = links[did]?.isUp == true

    fun start(ctx: Context) {
        stopAt = 0L
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        revived.clear() // like CozyLife: re-register once per app session
        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val setup = PlugSetup(app)
            while (isActive) {
                if (stopAt != 0L && System.currentTimeMillis() > stopAt) break
                if (setup.hasPermission() && setup.bluetoothOn()) {
                    try { round(app, setup) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
                }
                delay(30_000)
            }
            links.values.forEach { it.close() }; links.clear()
        }
    }

    /** Keep the links a minute after the app goes to the back (a widget tap may follow), then close. */
    fun stopSoon() { stopAt = System.currentTimeMillis() + 60_000 }

    private suspend fun round(ctx: Context, setup: PlugSetup) {
        val plugs = Plugs.load(ctx)
        val missing = plugs.filter { !isLinked(it.did) }
        if (missing.isEmpty()) return
        // Like CozyLife: connect straight to each plug's known Bluetooth address; scan only for the rest.
        val needScan = missing.filter { setup.direct(it.did) == null }
        val heard = if (needScan.isEmpty()) emptyList()
        else setup.scan(8_000, stopWhen = { f -> needScan.all { m -> m.did == f.advert.deviceId || isLinked(m.did) } })
        for (p in missing) {
            val f = setup.direct(p.did) ?: heard.firstOrNull { it.advert.deviceId == p.did } ?: continue
            val link = setup.Link(f)
            try {
                link.open()
            } catch (e: Exception) {
                ConnLog.add(ctx, "${p.name}: Bluetooth connect failed (${e.message})"); link.close(); continue
            }
            links[p.did] = link
            ConnLog.add(ctx, "${p.name}: Bluetooth connected")
            val v5 = f.advert.isV5
            link.onDrop = { links.remove(p.did, link); ConnLog.add(ctx, "${p.name}: Bluetooth disconnected") }
            // The plug's own reports (state changes) keep the app in step.
            link.listener = { r -> SetupProtocol.switchState(r, v5)?.let { Plugs.pushed(ctx, p.did, it, "bluetooth") } }
            lockFor(p.did).withLock {
                // Re-register once per session, as CozyLife's updateDeviceData does.
                val token = Plugs.tokenOf(ctx)
                if (token != null && p.key.isNotEmpty() && revived.add(p.did)) {
                    link.drain()
                    link.send(SetupProtocol.reconnectCommand(token, p.key, v5))
                    val ok = link.awaitReply(1, 4_000) { }
                    ConnLog.add(ctx, "${p.name}: told to rejoin CozyLife over Bluetooth → ${ok?.res?.let { if (it == 0) "OK" else "code $it" } ?: "no reply"}")
                }
                link.drain()
                link.send(SetupProtocol.QUERY)
                link.awaitState(v5, 3_000)?.let { Plugs.pushed(ctx, p.did, it, "bluetooth") }
            }
        }
    }

    private fun lockFor(did: String) = opLock.getOrPut(did) { Mutex() }

    /** Switches over the standing Bluetooth link, or returns null if there isn't one. */
    suspend fun send(did: String, on: Boolean): Boolean? {
        val link = links[did]?.takeIf { it.isUp } ?: return null
        return lockFor(did).withLock {
            try {
                val v5 = link.plug.advert.isV5
                link.drain()
                link.send(SetupProtocol.setCommand(on, v5))
                link.awaitState(v5, 3_000) ?: run {
                    link.send(SetupProtocol.QUERY)
                    link.awaitState(v5, 3_000)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) {
                link.close(); links.remove(did, link); null
            }
        }
    }
}
