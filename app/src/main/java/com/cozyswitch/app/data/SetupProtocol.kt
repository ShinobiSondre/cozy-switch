package com.cozyswitch.app.data

import java.security.MessageDigest

/**
 * The Bluetooth setup protocol CozyLife plugs speak in setup mode, as the CozyLife app does it
 * (read from its code: SingleBleDevice.getBleToDeviceSendConfigNetInfoCmd1, BlePackage,
 * BleV5PkgParser, BleScanPkgParser, KeyUtils, CRC8Util).
 *
 * GATT service 0x1910; we write to 0x2b11 and the plug answers on 0x2b10.
 */
object SetupProtocol {
    val SERVICE = uuid16("1910")
    val WRITE = uuid16("2b11")
    val NOTIFY = uuid16("2b10")
    val CCCD = uuid16("2902")
    const val DOMAIN = "api-us" // first label of https://api-us.doiting.com

    private fun uuid16(s: String) = java.util.UUID.fromString("0000$s-0000-1000-8000-00805f9b34fb")

    /** What a plug in setup mode says in its Bluetooth advertisement. */
    data class Advert(
        val protocol: String,   // "01"/"04" = classic packets, "05" = v5 frames
        val netType: String,    // "00" = Wi-Fi + Bluetooth (plugs), "01" = Bluetooth only
        val productId: String,
        val deviceId: String,   // same id the plug uses on Wi-Fi and in the cloud
        val bound: Boolean,
    ) {
        val isV5 get() = protocol == "05" || protocol == "5"
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Parses the raw advertisement + scan response; null if it isn't a CozyLife device. */
    fun parseAdvert(raw: ByteArray): Advert? {
        val h = hex(raw)
        if (h.length <= 85) return null
        val board = h.substring(0, 50)
        val scan = h.substring(50).removePrefix("000000000000")
        if (scan.length < 34) return null
        val name = ascii(scan.substring(4, 8))
        if (name != "TT") return null
        return Advert(
            protocol = board.substring(22, 24),
            netType = board.substring(24, 26),
            productId = ascii(board.substring(26, 38)),
            deviceId = scan.substring(14, 34).lowercase(),
            bound = scan.substring(12, 14) == "01",
        )
    }

    private fun ascii(h: String) = String(unhex(h), Charsets.ISO_8859_1).trimEnd('\u0000')

    /** The 10-digit plug key the CozyLife app gives every plug of an account (from the user id). */
    fun deviceKey(userId: String): String {
        val md5 = MessageDigest.getInstance("MD5").digest(userId.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return md5.substring(0, 10).map { (it.code % 10).toString() }.joinToString("")
    }

    private fun padRight(hex: String, width: Int): String {
        require(hex.length <= width) { "too long" }
        return hex + "0".repeat(width - hex.length)
    }

    private fun strHex(s: String) = s.map { Integer.toHexString(it.code) }.joinToString("")

    /**
     * Command 1, "join this Wi-Fi": [01][len][ssid 32][password 64][bssid 6][key 10][token 33]
     * [lat 4][lng 4] and, in the newer form, [domain 10]. Returned as a hex string.
     */
    fun configCommand(
        token: String, ssid: String, password: String, bssid: String?,
        deviceKey: String, withDomain: Boolean,
    ): String {
        val ssidHex = if (ssid.isEmpty()) "02".repeat(32) else padRight(hex(ssid.toByteArray(Charsets.UTF_8)), 64)
        val pwdHex = padRight(hex(password.toByteArray(Charsets.UTF_8)), 128)
        val bssidHex = bssid?.replace(":", "")?.takeIf { it.length == 12 } ?: "000000000000"
        val keyHex = padRight(strHex(deviceKey), 20)
        val tokenHex = padRight(strHex(token), 66)
        val latLng = "00000000" + "00000000"
        val domain = if (withDomain) padRight(strHex(DOMAIN), 20) else ""
        val len = if (withDomain) "A3" else "99"
        return "01$len$ssidHex$pwdHex$bssidHex$keyHex$tokenHex$latLng$domain".uppercase()
    }

    /**
     * "Keep your Wi-Fi, but register again with this account" (SingleBleDevice
     * .getBleToDeviceSendConfigNetInfoCmd1Verify). CozyLife sends this every time it connects to a
     * plug over Bluetooth; it's what brings a plug that dropped off the cloud back online.
     * [key] is the last 10 characters of the plug's cloud key.
     */
    fun reconnectCommand(token: String, key: String, withDomain: Boolean): String {
        val keep = "01".repeat(32)
        val pwdHex = "0".repeat(128)
        val keyHex = padRight(strHex(key.takeLast(10)), 20)
        val tokenHex = padRight(strHex(token), 66)
        val domain = if (withDomain) padRight(strHex(DOMAIN), 20) else ""
        val len = if (withDomain) "A3" else "99"
        return "01$len$keep$pwdHex" + "000000000000" + "$keyHex$tokenHex" + "0000000000000000" + domain
    }

    /** Switch on/off over Bluetooth (SingleBleDevice.getBleToDeviceSetDevicePropertyCmd3 / BleV5PkgParser.buildCmd3FromJson). */
    fun setCommand(on: Boolean, v5: Boolean): String =
        if (v5) "03" + "03" + "0500" + "01" + (if (on) "FF000000" else "00000000")  // [type 3][len 5][dp 1][uint32 LE]
        else "0302" + "01" + (if (on) "FF" else "00")                               // [03][len 2][dp 1][value]

    /** Ask for datapoint 1 (getBleToDeviceSearchDeviceInfoCmd2 with attr [1]). */
    const val QUERY = "020101"

    /** On/off from a Bluetooth reply to command 2, 3 or 10 (datapoint 1), or null. */
    fun switchState(r: Reply, v5: Boolean): Boolean? {
        if (r.cmd !in listOf(2, 3, 10)) return null
        val d = r.data
        if (v5) {
            var i = 0
            while (i + 4 <= d.size) { // [type][len LE16 incl. dpid][dpid][value]
                val len = (d[i + 1].toInt() and 0xFF) or ((d[i + 2].toInt() and 0xFF) shl 8)
                if (len < 1 || i + 3 + len > d.size) break
                val dp = d[i + 3].toInt() and 0xFF
                if (dp == 1) {
                    var v = 0L
                    for (k in 0 until len - 1) v = v or ((d[i + 4 + k].toLong() and 0xFF) shl (8 * k))
                    return v > 0
                }
                i += 3 + len
            }
        } else {
            var i = 0
            while (i + 3 <= d.size) { // [cmd][len incl. dpid][dpid][value LE]
                val len = d[i + 1].toInt() and 0xFF
                if (len == 0 || i + 2 + len > d.size) break
                val dp = d[i + 2].toInt() and 0xFF
                if (dp == 1) {
                    var v = 0L
                    for (k in 0 until len - 1) v = v or ((d[i + 3 + k].toLong() and 0xFF) shl (8 * k))
                    return v > 0
                }
                i += 2 + len
            }
        }
        return null
    }

    fun crc8(b: ByteArray, from: Int = 0, to: Int = b.size): Int {
        var c = 0
        for (i in from until to) {
            c = (c xor b[i].toInt()) and 0xFF
            repeat(8) { c = if ((c and 0x80) != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
        }
        return c
    }

    /** Classic framing: 20-byte packets [version][index, last|0x80][17 data bytes][crc8]. */
    fun frameV4(cmdHex: String, version: String): List<ByteArray> {
        val chunk = 34
        val n = (cmdHex.length + chunk - 1) / chunk
        return (0 until n).map { i ->
            val sn = if (i == n - 1) i or 0x80 else i
            val data = cmdHex.substring(i * chunk, minOf((i + 1) * chunk, cmdHex.length)).padEnd(chunk, '0')
            val body = unhex(version + "%02X".format(sn) + data)
            body + crc8(body).toByte()
        }
    }

    /**
     * v5 framing: a message [cmd][flags][sn LE32][len LE16][payload] cut into frames
     * [05][seq LE16, last|0x8000][len LE16][data][crc8].
     */
    fun frameV5(cmdHex: String, dataLen: Int, sn: Int = System.currentTimeMillis().toInt()): List<ByteArray> {
        val cmd = unhex(cmdHex)
        val legacy = if (cmd.size > 2) cmd.copyOfRange(2, cmd.size) else ByteArray(0)
        val payload = when (cmd[0].toInt()) {
            1 -> if (legacy.size > 163) legacy.copyOfRange(0, 163) else legacy
            0 -> legacy.copyOf(4)
            4, 8 -> legacy
            else -> cmd.copyOfRange(1, cmd.size) // 2, 3 and others: everything after the command byte
        }
        val msg = ByteArray(8 + payload.size)
        msg[0] = cmd[0]; msg[1] = 0
        for (k in 0..3) msg[2 + k] = (sn ushr (8 * k)).toByte()
        msg[6] = payload.size.toByte(); msg[7] = (payload.size ushr 8).toByte()
        payload.copyInto(msg, 8)
        val len = dataLen.coerceIn(14, 508)
        val n = maxOf(1, (msg.size + len - 1) / len)
        return (0 until n).map { i ->
            val part = msg.copyOfRange(i * len, minOf((i + 1) * len, msg.size))
            val seq = if (i == n - 1) i or 0x8000 else i
            val f = ByteArray(part.size + 6)
            f[0] = 5
            f[1] = seq.toByte(); f[2] = (seq ushr 8).toByte()
            f[3] = part.size.toByte(); f[4] = (part.size ushr 8).toByte()
            part.copyInto(f, 5)
            f[f.size - 1] = crc8(f, 0, f.size - 1).toByte()
            f
        }
    }

    /** A reply from the plug: which command it answers, and its result (0 = OK). */
    data class Reply(val cmd: Int, val res: Int?, val data: ByteArray)

    /** Collects notification packets back into whole replies. */
    class Reassembler(private val v5: Boolean) {
        private var buf = ByteArray(0)

        /** Feed one notification; returns a reply when the last packet of one has arrived. */
        fun feed(p: ByteArray): Reply? {
            if (v5) {
                if (p.size < 6 || p[0].toInt() != 5) return null
                if (crc8(p, 0, p.size - 1) != (p[p.size - 1].toInt() and 0xFF)) return null
                val seq = (p[1].toInt() and 0xFF) or ((p[2].toInt() and 0xFF) shl 8)
                val len = (p[3].toInt() and 0xFF) or ((p[4].toInt() and 0xFF) shl 8)
                if (len > p.size - 6) return null
                if ((seq and 0x7FFF) == 0) buf = ByteArray(0)
                buf += p.copyOfRange(5, 5 + len)
                if ((seq and 0x8000) == 0) return null
                val m = buf; buf = ByteArray(0)
                if (m.size < 8) return null
                val plen = (m[6].toInt() and 0xFF) or ((m[7].toInt() and 0xFF) shl 8)
                val payload = m.copyOfRange(8, minOf(m.size, 8 + plen))
                return Reply(m[0].toInt() and 0xFF, payload.firstOrNull()?.toInt()?.and(0xFF), payload)
            } else {
                if (p.size != 20) return null
                if (crc8(p, 0, 19) != (p[19].toInt() and 0xFF)) return null
                val sn = p[1].toInt() and 0xFF
                if ((sn and 0x7F) == 0) buf = ByteArray(0)
                buf += p.copyOfRange(2, 19)
                if ((sn and 0x80) == 0) return null
                val m = buf; buf = ByteArray(0)
                val cmd = m[0].toInt() and 0xFF
                // Replies to commands 1, 4, 5 are [cmd][len][res]; 12 (Wi-Fi progress) is [cmd][len][status].
                return Reply(cmd, if (m.size > 2) m[2].toInt() and 0xFF else null, m)
            }
        }
    }
}
