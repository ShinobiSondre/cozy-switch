package com.cozyswitch.app.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private val bleLock = kotlinx.coroutines.sync.Mutex()

/** Last time each plug (by device id) was heard over Bluetooth. */
private val seenMap = java.util.concurrent.ConcurrentHashMap<String, Pair<FoundPlug, Long>>()
private val seen = object {
    operator fun get(did: String) = seenMap[did]
    operator fun set(did: String, f: FoundPlug) { seenMap[did] = f to System.currentTimeMillis() }
}

/** A CozyLife plug heard over Bluetooth (in setup mode, or already set up). */
data class FoundPlug(val device: BluetoothDevice, val advert: SetupProtocol.Advert, val rssi: Int)

class SetupError(msg: String) : Exception(msg)

/** The Wi-Fi used for the last successful setup, so the next one is a single tap. Stored only on this phone. */
object SavedWifi {
    private const val PREFS = "setup_wifi"
    fun get(ctx: Context): Pair<String, String>? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ssid = p.getString("ssid", null) ?: return null
        return ssid to (p.getString("pw", "") ?: "")
    }
    /** The saved password for one network (Android doesn't let apps read the phone's own), or null. */
    fun pwFor(ctx: Context, ssid: String): String? = try {
        val m = org.json.JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("nets", "{}") ?: "{}")
        if (m.has(ssid)) m.getString(ssid) else get(ctx)?.takeIf { it.first == ssid }?.second
    } catch (_: Exception) { null }

    fun put(ctx: Context, ssid: String, pw: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val m = try { org.json.JSONObject(p.getString("nets", "{}") ?: "{}") } catch (_: Exception) { org.json.JSONObject() }
        m.put(ssid, pw)
        p.edit().putString("ssid", ssid).putString("pw", pw).putString("nets", m.toString()).apply()
    }
    fun forget(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
}

/**
 * Puts a CozyLife plug on Wi-Fi over Bluetooth, the way the CozyLife app does: connect, send
 * command 1 (Wi-Fi name and password, the account's plug key and login token), wait for "OK",
 * then wait for the plug to appear online in the account.
 */
@SuppressLint("MissingPermission") // the screen asks for Bluetooth permission before calling in here
class PlugSetup(private val ctx: Context) {
    private val adapter get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    fun bluetoothOn() = adapter?.isEnabled == true

    fun hasPermission() = listOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        .all { ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

    /**
     * Listens in a loop (20 s on, 10 s off) and reports plugs waiting to be added: in setup mode
     * and not tied to an account, exactly what makes the CozyLife app pop up "device to be added".
     * Runs until cancelled.
     */
    suspend fun watch(onWaiting: (List<FoundPlug>) -> Unit) {
        while (true) {
            if (hasPermission() && bluetoothOn()) {
                try {
                    val waiting = scan(20_000).filter { !it.advert.bound }
                    onWaiting(waiting)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
            }
            delay(10_000)
        }
    }

    /** Listens for plugs in setup mode for [ms] milliseconds. */
    suspend fun scan(ms: Long = 12_000, stopWhen: (FoundPlug) -> Boolean = { false },
                     onFound: (List<FoundPlug>) -> Unit = {}): List<FoundPlug> {
        val scanner = adapter?.bluetoothLeScanner ?: throw SetupError("Bluetooth is off.")
        val found = linkedMapOf<String, FoundPlug>()
        val done = CompletableDeferred<Unit>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val raw = r.scanRecord?.bytes ?: return
                val a = SetupProtocol.parseAdvert(raw) ?: return
                if (a.netType == "01") return // Bluetooth-only gadgets, not Wi-Fi plugs
                val f = FoundPlug(r.device, a, r.rssi)
                seen[a.deviceId] = f
                synchronized(found) {
                    found[r.device.address] = f
                    onFound(found.values.sortedByDescending { it.rssi })
                }
                if (stopWhen(f)) done.complete(Unit)
            }
        }
        // CozyLife plugs call themselves "TT" over Bluetooth (BleScanListenerManage).
        val filters = listOf(android.bluetooth.le.ScanFilter.Builder().setDeviceName("TT").build())
        scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        try { withTimeoutOrNull(ms) { done.await() } } finally { try { scanner.stopScan(cb) } catch (_: Exception) { } }
        return synchronized(found) { found.values.sortedByDescending { it.rssi } }
    }

    // ---- Switching and reconnecting over Bluetooth, like the CozyLife app ----

    /**
     * The plug at its known Bluetooth address (bt_mac from the account), without scanning, the way
     * CozyLife connects: set-up plugs may not advertise, but they still accept a connection.
     */
    fun direct(did: String): FoundPlug? {
        val p = Plugs.load(ctx).firstOrNull { it.did == did } ?: return null
        if (p.btMac.length != 17) return null
        val dev = try { adapter?.getRemoteDevice(p.btMac) } catch (_: Exception) { null } ?: return null
        val proto = p.bleProtocol.ifEmpty { "01" }.padStart(2, '0')
        return FoundPlug(dev, SetupProtocol.Advert(proto, "00", p.pid, did, true), 0)
    }

    /** Finds a set-up plug nearby by its device id (plugs keep advertising after setup). */
    suspend fun near(did: String, ms: Long = 8_000): FoundPlug? {
        seen[did]?.let { if (System.currentTimeMillis() - it.second < 60_000) return it.first }
        direct(did)?.let { return it }
        return scan(ms, stopWhen = { it.advert.deviceId == did }).firstOrNull { it.advert.deviceId == did }
    }

    /**
     * Switches a plug over Bluetooth. When [reregister] holds token+key, it also sends the
     * "keep your Wi-Fi, register again" message CozyLife sends on every Bluetooth connection,
     * which brings a plug that dropped off the cloud back. Returns the new state, or null.
     */
    suspend fun bleSet(did: String, on: Boolean?, reregister: Pair<String, String>?): Boolean? = bleLock.withLock {
        if (!hasPermission() || !bluetoothOn()) return@withLock null
        val plug = near(did) ?: return@withLock null
        val v5 = plug.advert.isV5
        val link = Link(plug)
        try {
            link.open()
            var state: Boolean? = null
            if (on != null) {
                link.send(SetupProtocol.setCommand(on, v5))
                state = link.awaitState(v5, 4_000)
            }
            if (state == null) {
                link.send(SetupProtocol.QUERY)
                state = link.awaitState(v5, 4_000)
            }
            if (reregister != null) {
                val (token, key) = reregister
                link.send(SetupProtocol.reconnectCommand(token, key, v5))
                link.awaitReply(1, 4_000) { }
            }
            state
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (_: Exception) { null
        } finally { link.close() }
    }

    /** Runs the whole setup. [step] reports progress in words. */
    suspend fun setUp(
        plug: FoundPlug, ssid: String, password: String,
        session: CozyAccount.Session, step: (String) -> Unit,
    ) {
        if (ssid.toByteArray().size > 32) throw SetupError("The Wi-Fi name is too long (max 32 characters).")
        if (password.toByteArray().size > 64) throw SetupError("The Wi-Fi password is too long (max 64 characters).")
        val key = SetupProtocol.deviceKey(session.uid)
        step("Connecting to the plug over Bluetooth…")
        val link = Link(plug)
        try {
            link.open()
            step("Sending your Wi-Fi details…")
            // The CozyLife app sends the newer form (with the server name) first; older firmware wants the short form.
            var reply: SetupProtocol.Reply? = null
            for (withDomain in listOf(true, false)) {
                val cmd = SetupProtocol.configCommand(session.token, ssid, password, null, key, withDomain)
                link.send(cmd)
                reply = link.awaitReply(1, 6_000) { s -> step(s) }
                if (reply != null) break
            }
            when {
                reply == null -> throw SetupError("The plug didn't confirm. Put it back in setup mode and try again.")
                reply.res != 0 -> throw SetupError("The plug refused the details (code ${reply.res}).")
            }
            step("The plug got the details. It's joining your Wi-Fi now…")
            // Progress messages (command 12) may follow; listen briefly, they're optional.
            link.awaitReply(-1, 4_000) { s -> step(s) }
        } finally {
            link.close()
        }
        step("Waiting for the plug to come online in your account (up to 2 minutes)…")
        if (!Plugs.waitForCloudPlug(ctx, plug.advert.deviceId)) {
            throw SetupError("The plug got the details but didn't come online. Check the Wi-Fi password, " +
                "and that it's a 2.4 GHz network, then try again.")
        }
    }

    /** One Bluetooth connection to a plug, with the waits turned into suspend calls. */
    inner class Link(val plug: FoundPlug) {
        /** Called for every reply, including ones the plug sends by itself. */
        @Volatile var listener: ((SetupProtocol.Reply) -> Unit)? = null
        /** Called when the Bluetooth connection drops. */
        @Volatile var onDrop: (() -> Unit)? = null
        @Volatile var isUp = false
        private var gatt: BluetoothGatt? = null
        private var write: BluetoothGattCharacteristic? = null
        private var connected = CompletableDeferred<Boolean>()
        private var services = CompletableDeferred<Boolean>()
        private var mtu = CompletableDeferred<Int>()
        private var descriptor = CompletableDeferred<Boolean>()
        private var written = CompletableDeferred<Boolean>()
        private val replies = Channel<SetupProtocol.Reply>(Channel.UNLIMITED)
        private val reassembler = SetupProtocol.Reassembler(plug.advert.isV5)
        private var dataLen = 14

        private val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
                if (state == BluetoothProfile.STATE_CONNECTED) connected.complete(true)
                else {
                    connected.complete(false); services.complete(false); written.complete(false); descriptor.complete(false)
                    if (isUp) { isUp = false; onDrop?.invoke() }
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { services.complete(status == BluetoothGatt.GATT_SUCCESS) }
            override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) { mtu.complete(if (status == BluetoothGatt.GATT_SUCCESS) m else 23) }
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { descriptor.complete(status == BluetoothGatt.GATT_SUCCESS) }
            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { written.complete(status == BluetoothGatt.GATT_SUCCESS) }
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { heard(value) }
            @Deprecated("Android 12")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < 33) @Suppress("DEPRECATION") heard(c.value ?: return)
            }
        }

        private fun heard(v: ByteArray) {
            reassembler.feed(v)?.let { r -> replies.trySend(r); try { listener?.invoke(r) } catch (_: Exception) { } }
        }

        /** Throws away replies that arrived earlier, so the next wait only sees fresh ones. */
        fun drain() { while (replies.tryReceive().isSuccess) { } }

        suspend fun open() {
            repeat(2) { attempt ->
                connected = CompletableDeferred(); services = CompletableDeferred()
                gatt = plug.device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
                val ok = withTimeoutOrNull(15_000) { connected.await() } == true
                if (ok) {
                    if (plug.advert.isV5) {
                        mtu = CompletableDeferred()
                        gatt?.requestMtu(517)
                        val m = withTimeoutOrNull(3_000) { mtu.await() } ?: 23
                        dataLen = (m - 9).coerceIn(14, 508)
                    }
                    delay(300)
                    gatt?.discoverServices()
                    if (withTimeoutOrNull(10_000) { services.await() } == true) {
                        val svc = gatt?.getService(SetupProtocol.SERVICE) ?: throw SetupError("This doesn't look like a CozyLife plug.")
                        write = svc.getCharacteristic(SetupProtocol.WRITE)
                        val notify = svc.getCharacteristic(SetupProtocol.NOTIFY)
                        if (write == null || notify == null) throw SetupError("This doesn't look like a CozyLife plug.")
                        gatt!!.setCharacteristicNotification(notify, true)
                        val d = notify.getDescriptor(SetupProtocol.CCCD)
                        if (d != null) {
                            descriptor = CompletableDeferred()
                            writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            withTimeoutOrNull(3_000) { descriptor.await() }
                        }
                        isUp = true
                        return
                    }
                }
                close()
                if (attempt == 0) delay(1000)
            }
            throw SetupError("Couldn't connect to the plug over Bluetooth. Keep the phone close and try again.")
        }

        @Suppress("DEPRECATION")
        private fun writeDescriptor(d: BluetoothGattDescriptor, v: ByteArray) {
            if (Build.VERSION.SDK_INT >= 33) gatt?.writeDescriptor(d, v)
            else { d.value = v; gatt?.writeDescriptor(d) }
        }

        @Suppress("DEPRECATION")
        private fun writeChar(v: ByteArray): Boolean {
            val c = write ?: return false
            return if (Build.VERSION.SDK_INT >= 33) {
                gatt?.writeCharacteristic(c, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = v
                gatt?.writeCharacteristic(c) == true
            }
        }

        suspend fun send(cmdHex: String) {
            val packets = if (plug.advert.isV5) SetupProtocol.frameV5(cmdHex, dataLen)
            else SetupProtocol.frameV4(cmdHex, plug.advert.protocol.ifEmpty { "01" })
            for (p in packets) {
                var ok = false
                for (tryNo in 0 until 5) { // the CozyLife app retries a failed packet up to 5 times
                    written = CompletableDeferred()
                    if (writeChar(p) && withTimeoutOrNull(3_000) { written.await() } == true) { ok = true; break }
                    delay(100)
                }
                if (!ok) throw SetupError("Lost the Bluetooth connection while sending. Try again.")
            }
        }

        /** Waits for a reply to [cmd] (or, with -1, just passes on progress), reporting Wi-Fi progress. */
        suspend fun awaitReply(cmd: Int, ms: Long, progress: (String) -> Unit): SetupProtocol.Reply? =
            withTimeoutOrNull(ms) {
                while (true) {
                    val r = replies.receive()
                    if (r.cmd == 12) progress(when (r.res) {
                        1 -> "The plug is looking for your Wi-Fi…"
                        2 -> "The plug is connecting to your Wi-Fi…"
                        3 -> "The plug is on your Wi-Fi, connecting to CozyLife…"
                        else -> "The plug is working on it (step ${r.res})…"
                    })
                    if (r.cmd == cmd) return@withTimeoutOrNull r
                }
                @Suppress("UNREACHABLE_CODE") null
            }

        /** Waits for a status reply carrying datapoint 1. */
        suspend fun awaitState(v5: Boolean, ms: Long): Boolean? = withTimeoutOrNull(ms) {
            while (true) {
                val r = replies.receive()
                SetupProtocol.switchState(r, v5)?.let { return@withTimeoutOrNull it }
            }
            @Suppress("UNREACHABLE_CODE") null
        }

        fun close() {
            isUp = false
            try { gatt?.disconnect() } catch (_: Exception) { }
            try { gatt?.close() } catch (_: Exception) { }
            gatt = null
        }
    }
}
