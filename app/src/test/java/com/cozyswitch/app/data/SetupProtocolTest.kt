package com.cozyswitch.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reference values were produced by running the CozyLife app's own (decompiled) helper code. */
class SetupProtocolTest {
    private val token = "l6n7l3v6i54g86aci1oyyfescspnyf"

    @Test fun plugKeyMatchesTheCozyLifeApp() = assertEquals("1987091727", SetupProtocol.deviceKey("123456"))

    @Test fun configCommandWithDomainMatchesTheCozyLifeApp() {
        val ref = "01A3486A656D6D65000000000000000000000000000000000000000000000000000068656D6D656C69673132330000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000313938373039313732376c366e376c337636693534673836616369316f79796665736373706e796600000000000000000000006170692d757300000000"
        assertEquals(ref.uppercase(), SetupProtocol.configCommand(token, "Hjemme", "hemmelig123", "", "1987091727", true))
        assertEquals(2 + 163 * 2 + 2, ref.length) // [01][len] + 163 bytes
    }

    @Test fun configCommandWithoutDomainMatchesTheCozyLifeApp() {
        val ref = "0199486A656D6D65000000000000000000000000000000000000000000000000000068656D6D656C69673132330000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000aabbccddeeff313938373039313732376c366e376c337636693534673836616369316f79796665736373706e79660000000000000000000000"
        assertEquals(ref.uppercase(), SetupProtocol.configCommand(token, "Hjemme", "hemmelig123", "aa:bb:cc:dd:ee:ff", "1987091727", false))
    }

    @Test fun crcMatchesTheCozyLifeApp() =
        assertEquals(0xD3, SetupProtocol.crc8(SetupProtocol.unhex("01800102030405060708090A0B0C0D0E0F1011")))

    @Test fun classicPacketsAre20BytesAndReassembleToTheCommand() {
        val cmd = SetupProtocol.configCommand(token, "Hjemme", "pw", null, "1987091727", false)
        val pk = SetupProtocol.frameV4(cmd, "01")
        assertEquals(10, pk.size) // 155 bytes / 17 per packet
        pk.forEach { assertEquals(20, it.size); assertEquals(1, it[0].toInt()) }
        assertEquals(0x89, pk.last()[1].toInt() and 0xFF) // index 9 | last
        val joined = pk.joinToString("") { SetupProtocol.hex(it.copyOfRange(2, 19)) }
        assertTrue(joined.startsWith(cmd))
        pk.forEach { assertEquals(SetupProtocol.crc8(it, 0, 19), it[19].toInt() and 0xFF) }
    }

    @Test fun v5FramesCarryTheMessage() {
        val cmd = SetupProtocol.configCommand(token, "Hjemme", "pw", null, "1987091727", true)
        val fr = SetupProtocol.frameV5(cmd, 100, sn = 0x01020304)
        assertEquals(2, fr.size) // 8 + 163 = 171 bytes
        assertEquals(0x80, fr.last()[2].toInt() and 0xFF)
        val msg = fr.flatMap { f -> f.copyOfRange(5, f.size - 1).toList() }.toByteArray()
        assertEquals(1, msg[0].toInt()); assertEquals(0, msg[1].toInt())
        assertArrayEquals(byteArrayOf(4, 3, 2, 1), msg.copyOfRange(2, 6))
        assertEquals(163, (msg[6].toInt() and 0xFF) or ((msg[7].toInt() and 0xFF) shl 8))
        assertEquals(SetupProtocol.hex(SetupProtocol.unhex(cmd).copyOfRange(2, 165)), SetupProtocol.hex(msg.copyOfRange(8, msg.size)))
    }

    @Test fun readsAClassicReply() {
        // Plug says: cmd 1, len 1, res 0
        val body = SetupProtocol.unhex("0180" + "010100" + "00".repeat(14))
        val pkt = body + SetupProtocol.crc8(body).toByte()
        val r = SetupProtocol.Reassembler(false).feed(pkt)!!
        assertEquals(1, r.cmd); assertEquals(0, r.res)
    }

    @Test fun ignoresAPacketWithABadChecksum() {
        val body = SetupProtocol.unhex("0180" + "010100" + "00".repeat(14))
        assertNull(SetupProtocol.Reassembler(false).feed(body + 0x00))
    }

    @Test fun readsAV5Reply() {
        val frames = SetupProtocol.frameV5("0101" + "00", 50) // cmd 1, payload [00]
        val r = SetupProtocol.Reassembler(true).feed(frames.single())!!
        assertEquals(1, r.cmd); assertEquals(0, r.res)
    }

    @Test fun recognisesAPlugInSetupMode() {
        // 25-byte broadcast part: … protocol 04, net type 00, product "dmaF7n", mac; then scan part with "TT", bind flag, device id.
        val board = "0201061AFF" + "000000000000" + "04" + "00" + SetupProtocol.hex("dmaF7n".toByteArray()) + "112233445566"
        val scan = "0000" + SetupProtocol.hex("TT".toByteArray()) + "0000" + "00" + "629168597cb94c4c1d8f" + "00".repeat(8)
        val a = SetupProtocol.parseAdvert(SetupProtocol.unhex(board + scan))!!
        assertEquals("04", a.protocol); assertEquals("00", a.netType); assertEquals("dmaF7n", a.productId)
        assertEquals("629168597cb94c4c1d8f", a.deviceId); assertFalse(a.bound); assertFalse(a.isV5)
    }

    @Test fun ignoresOtherBluetoothDevices() =
        assertNull(SetupProtocol.parseAdvert(SetupProtocol.unhex("0201060303AAFE" + "00".repeat(50))))

    @Test fun bluetoothOnOffMatchesTheCozyLifeApp() {
        assertEquals("030201FF", SetupProtocol.setCommand(true, false))
        assertEquals("03020100", SetupProtocol.setCommand(false, false))
        assertEquals("030305000001FF000000".substring(0, 2) + "03" + "0500" + "01" + "FF000000", SetupProtocol.setCommand(true, true))
    }

    @Test fun reconnectKeepsTheWifiAndCarriesKeyAndToken() {
        val c = SetupProtocol.reconnectCommand(token, "abcdef1987091727", false)
        assertEquals(2 + 153 * 2 + 2, c.length)
        assertTrue(c.startsWith("0199" + "01".repeat(32) + "0".repeat(128) + "000000000000"))
        assertTrue(c.contains(SetupProtocol.hex("1987091727".toByteArray()).lowercase()))
    }

    @Test fun readsSwitchStateFromClassicReply() {
        val r = SetupProtocol.Reply(3, null, SetupProtocol.unhex("030201FF" + "000000"))
        assertEquals(true, SetupProtocol.switchState(r, false))
        assertEquals(false, SetupProtocol.switchState(SetupProtocol.Reply(10, null, SetupProtocol.unhex("03020100")), false))
    }

    @Test fun readsSwitchStateFromV5Reply() {
        val r = SetupProtocol.Reply(3, null, SetupProtocol.unhex("03050001FF000000"))
        assertEquals(true, SetupProtocol.switchState(r, true))
    }

    @Test fun v5SetFrameCarriesTheRecord() {
        val f = SetupProtocol.frameV5(SetupProtocol.setCommand(true, true), 100, sn = 1).single()
        val msg = f.copyOfRange(5, f.size - 1)
        assertEquals(3, msg[0].toInt())
        assertEquals("03050001FF000000", SetupProtocol.hex(msg.copyOfRange(8, msg.size)))
    }
}
