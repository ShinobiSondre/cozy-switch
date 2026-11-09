package com.cozyswitch.app.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** A pretend CozyLife plug on a local port, answering the way real ones do. */
class FakePlug(
    var on: Boolean = false,
    /** Send an unrequested status message before each reply, like real plugs sometimes do. */
    val chatty: Boolean = false,
    /** Don't echo set commands (some firmware only answers queries). */
    val silentSet: Boolean = false,
    /** Split replies into small chunks with no newline. */
    val chunky: Boolean = false,
) {
    val server = ServerSocket(0)
    val port get() = server.localPort
    var received = mutableListOf<JSONObject>()

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { serve(s) }
            }
        }
    }

    private fun serve(s: Socket) = s.use {
        val reader = s.getInputStream().bufferedReader()
        val out = s.getOutputStream()
        fun send(o: JSONObject) {
            val bytes = (o.toString() + if (chunky) "" else "\r\n").toByteArray()
            if (chunky) bytes.toList().chunked(7).forEach { out.write(it.toByteArray()); out.flush(); Thread.sleep(2) }
            else { out.write(bytes); out.flush() }
        }
        fun state() = JSONObject().put("attr", listOf(1)).put("data", JSONObject().put("1", if (on) 1 else 0))
        while (true) {
            val line = reader.readLine() ?: break
            val m = JSONObject(line)
            synchronized(received) { received += m }
            val sn = m.getString("sn")
            if (chatty) send(JSONObject().put("cmd", 10).put("pv", 0).put("sn", "999").put("res", 0).put("msg", state()))
            when (m.getInt("cmd")) {
                0 -> send(JSONObject().put("cmd", 0).put("pv", 0).put("sn", sn).put("res", 0).put("msg",
                    JSONObject().put("did", "629168597cb94c4c1d8f").put("pid", "e2s64v").put("mac", "7cb94c4c1d8f")))
                2 -> send(JSONObject().put("cmd", 2).put("pv", 0).put("sn", sn).put("res", 0).put("msg", state()))
                3 -> {
                    on = m.getJSONObject("msg").getJSONObject("data").getInt("1") > 0
                    if (!silentSet) send(JSONObject().put("cmd", 3).put("pv", 0).put("sn", sn).put("res", 0).put("msg", state()))
                }
            }
        }
    }

    fun close() = server.close()
}

class CozyClientTest {
    private val plugs = mutableListOf<FakePlug>()
    private fun plug(p: FakePlug) = p.also { plugs += it }
    private fun client(p: FakePlug) = CozyClient(port = p.port, connectMs = 1000, replyMs = 1500)

    @After fun tearDown() = plugs.forEach { it.close() }

    @Test fun readsDeviceInfo() {
        val p = plug(FakePlug())
        val info = client(p).info("127.0.0.1")!!
        assertEquals("629168597cb94c4c1d8f", info.did)
        assertEquals("e2s64v", info.pid)
    }

    @Test fun queryAndSwitch() {
        val p = plug(FakePlug(on = false))
        val c = client(p)
        assertEquals(false, c.query("127.0.0.1"))
        assertEquals(true, c.set("127.0.0.1", true))
        assertTrue(p.on)
        assertEquals(true, c.query("127.0.0.1"))
        assertEquals(false, c.set("127.0.0.1", false))
        assertFalse(p.on)
    }

    @Test fun sendsTheSameMessagesAsTheOfficialProtocol() {
        val p = plug(FakePlug())
        client(p).set("127.0.0.1", true)
        val m = synchronized(p.received) { p.received.first() }
        assertEquals(3, m.getInt("cmd"))
        assertEquals(0, m.getInt("pv"))
        assertEquals(1, m.getJSONObject("msg").getJSONArray("attr").getInt(0))
        assertEquals(255, m.getJSONObject("msg").getJSONObject("data").getInt("1"))
    }

    @Test fun ignoresUnrequestedStatusMessages() {
        val p = plug(FakePlug(on = true, chatty = true))
        val c = client(p)
        assertEquals(true, c.query("127.0.0.1"))
        assertEquals(false, c.set("127.0.0.1", false))
        assertEquals("629168597cb94c4c1d8f", c.info("127.0.0.1")?.did)
    }

    @Test fun handlesRepliesSplitIntoPieces() {
        val p = plug(FakePlug(on = true, chunky = true))
        assertEquals(true, client(p).query("127.0.0.1"))
        assertEquals(false, client(p).set("127.0.0.1", false))
    }

    @Test fun asksAgainIfThePlugDoesNotEchoTheSwitch() {
        val p = plug(FakePlug(on = false, silentSet = true))
        assertEquals(true, client(p).set("127.0.0.1", true))
    }

    @Test fun unreachablePlugGivesNullQuickly() {
        val p = plug(FakePlug()); val port = p.port; p.close()
        val c = CozyClient(port = port, connectMs = 500, replyMs = 500)
        val t = System.currentTimeMillis()
        assertNull(c.query("127.0.0.1"))
        assertNull(c.set("127.0.0.1", true))
        assertTrue(System.currentTimeMillis() - t < 3000)
    }

    @Test fun objectReaderCopesWithBracesInsideText() {
        val raw = "junk{\"a\":\"}{\\\"\",\"b\":{\"c\":1}}\r\n{\"sn\":\"2\"}"
        val r = CozyClient.Companion.ObjectReader(ByteArrayInputStream(raw.toByteArray()))
        assertEquals("}{\"", r.next()!!.getString("a"))
        assertEquals("2", r.next()!!.getString("sn"))
        assertNull(r.next())
    }
}
