package com.cozyswitch.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import kotlinx.coroutines.delay

/** A Wi-Fi network near the phone, as offered when setting up a plug. */
data class WifiNet(val ssid: String, val has24: Boolean, val level: Int, val connected: Boolean, val open: Boolean)

/** Lists nearby Wi-Fi (Android only shares this with apps that have Location permission). */
object WifiNetworks {
    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The network the phone is on now, or null if unknown. */
    @Suppress("DEPRECATION")
    fun current(ctx: Context): String? = try {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        wm.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    } catch (_: Exception) { null }

    /** Nearby networks: the phone's own first, then those the plugs can use (2.4 GHz), strongest first. */
    @Suppress("DEPRECATION")
    suspend fun scan(ctx: Context): List<WifiNet> {
        if (!hasPermission(ctx)) return emptyList()
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return emptyList()
        try { wm.startScan() } catch (_: Exception) { } // Android may throttle this; recent results are used then
        delay(2_500)
        val now = current(ctx)
        val results = try { wm.scanResults } catch (_: SecurityException) { emptyList() }
        return results.filter { !it.SSID.isNullOrBlank() }.groupBy { it.SSID }.map { (ssid, aps) ->
            WifiNet(
                ssid = ssid,
                has24 = aps.any { it.frequency in 2400..2500 },
                level = aps.maxOf { it.level },
                connected = ssid == now,
                open = aps.all { ap -> listOf("WPA", "WEP", "SAE", "PSK", "EAP").none { ap.capabilities.contains(it) } },
            )
        }.sortedWith(compareByDescending<WifiNet> { it.connected }.thenByDescending { it.has24 }.thenByDescending { it.level })
    }
}
