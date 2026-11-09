package com.cozyswitch.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Finds plugs on the home Wi-Fi the way the CozyLife app does: one UDP "who's there?" to
 * port 6095. Plugs answer this even when their TCP control port has frozen, so it's also how a
 * plug that got a new address from the router is found again, quickly.
 */
object Discovery {
    private const val PORT = 6095

    /** Every plug that answers: (device id, address). */
    fun all(ctx: Context, timeoutMs: Int = 2500): List<Pair<String, String>> = listen(ctx, null, timeoutMs)

    /** The current address of one plug, or null if it's quiet. Returns as soon as it answers. */
    fun find(ctx: Context, did: String, timeoutMs: Int = 2500): String? = listen(ctx, did, timeoutMs).firstOrNull()?.second

    private fun listen(ctx: Context, want: String?, timeoutMs: Int): List<Pair<String, String>> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return emptyList()
        val out = linkedMapOf<String, String>()
        try {
            DatagramSocket().use { s ->
                wifi.bindSocket(s)
                s.broadcast = true
                val probe = probe().toByteArray()
                val targets = mutableListOf(InetAddress.getByName("255.255.255.255"))
                cm.getLinkProperties(wifi)?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.let { la ->
                    val b = la.address.address
                    val bits = 32 - la.prefixLength.coerceIn(8, 30)
                    val ip = ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or
                        ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
                    val bc = ip or ((1 shl bits) - 1)
                    targets += InetAddress.getByAddress(byteArrayOf((bc ushr 24).toByte(), (bc ushr 16).toByte(), (bc ushr 8).toByte(), bc.toByte()))
                }
                for (t in targets) try { s.send(DatagramPacket(probe, probe.size, t, PORT)) } catch (_: Exception) { }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(2048)
                while (true) {
                    val left = (end - System.currentTimeMillis()).toInt()
                    if (left <= 0) break
                    s.soTimeout = left
                    val pkt = DatagramPacket(buf, buf.size)
                    try { s.receive(pkt) } catch (_: SocketTimeoutException) { break }
                    val did = parse(String(pkt.data, 0, pkt.length)) ?: continue
                    out[did] = pkt.address.hostAddress ?: continue
                    if (did == want) break
                }
            }
        } catch (_: Exception) { }
        return out.entries.map { it.key to it.value }.filter { want == null || it.first == want }
    }

    internal fun probe() = JSONObject().put("cmd", 0).put("pv", 0).put("sn", CozyClient.nextSn()).put("msg", JSONObject()).toString()

    /** The device id from a discovery answer, or null. */
    internal fun parse(s: String): String? = try {
        JSONObject(s.trim()).optJSONObject("msg")?.optString("did")?.ifEmpty { null }
    } catch (_: Exception) { null }
}
