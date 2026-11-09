package com.cozyswitch.app.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.concurrent.thread

private const val DID = "aaaabbbbccccdddd0001"
private const val KEY = "test-key-not-a-real-one"

/** A pretend CozyLife relay with one plug behind it, answering like the real one. */
class FakeRelay(var on: Boolean = false, val subscribers: Int = 1, val answer: Boolean = true) {
    val server = ServerSocket(0)
    val received = mutableListOf<Map<String, String>>()

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { serve(s) }
            }
        }
    }

    private fun serve(s: Socket) = s.use {
        val r = s.getInputStream().bufferedReader()
        val out = s.getOutputStream()
        var subscribed = false
        while (true) {
            val line = r.readLine() ?: break
            val f = CozyRelay.parseLine(line)
            synchronized(received) { received += f }
            when (f["cmd"]) {
                "subscribe" -> { subscribed = true; out.write("cmd=subscribe&res=1\r\n".toByteArray()) }
                "publish" -> {
                    out.write("cmd=publish&res=1&num=$subscribers\r\n".toByteArray())
                    if (subscribers > 0 && answer && subscribed) {
                        val req = JSONObject(URLDecoder.decode(f["message"]!!, "UTF-8"))
                        if (req.getInt("cmd") == 3) on = req.getJSONObject("msg").getJSONObject("data").getInt("1") > 0
                        val rep = JSONObject().put("pv", 0).put("cmd", req.getInt("cmd")).put("sn", req.getString("sn")).put("res", 0)
                            .put("msg", JSONObject().put("attr", listOf(1)).put("data", JSONObject().put("1", if (on) 1 else 0)))
                        val enc = URLEncoder.encode(rep.toString(), "UTF-8").replace("+", "%20")
                        // another device's report first, to be ignored
                        out.write("cmd=publish&topic=device_other&message=%7B%7D\r\n".toByteArray())
                        out.write("cmd=publish&topic=device_$DID&device_id=$DID&message=$enc\r\n".toByteArray())
                    }
                }
            }
            out.flush()
        }
    }

    fun plug() = CloudPlug(DID, KEY, "Test", "127.0.0.1", server.localPort, null, true)
    fun close() = server.close()
}

class CozyCloudTest {
    private val relays = mutableListOf<FakeRelay>()
    private fun relay(r: FakeRelay) = r.also { relays += it }
    @After fun tearDown() = relays.forEach { it.close() }

    @Test fun switchesThroughTheRelay() {
        val r = relay(FakeRelay(on = false))
        val c = CozyRelay(timeoutMs = 3000)
        assertEquals(false, c.query(r.plug()))
        assertEquals(true, c.set(r.plug(), true))
        assertTrue(r.on)
        assertEquals(false, c.set(r.plug(), false))
        assertFalse(r.on)
    }

    @Test fun subscribesBeforePublishingWithKeyOnEveryLine() {
        val r = relay(FakeRelay())
        CozyRelay(3000).query(r.plug())
        val got = synchronized(r.received) { r.received.toList() }
        assertEquals(listOf("subscribe", "publish"), got.map { it["cmd"] })
        assertEquals("device_$DID", got[0]["topic"])
        assertEquals("control_$DID", got[1]["topic"])
        got.forEach { assertEquals(DID, it["device_id"]); assertEquals(KEY, it["device_key"]) }
    }

    @Test fun offlinePlugGivesAClearError() {
        val r = relay(FakeRelay(subscribers = 0))
        try { CozyRelay(3000).set(r.plug(), true); fail("expected CloudError") } catch (e: CloudError) {
            assertTrue(e.message!!.contains("isn't connected"))
        }
    }

    @Test fun silentPlugTimesOutWithNull() {
        val r = relay(FakeRelay(answer = false))
        val t = System.currentTimeMillis()
        assertNull(CozyRelay(1500).query(r.plug()))
        assertTrue(System.currentTimeMillis() - t < 4000)
    }

    @Test fun readsTheAccountDeviceList() {
        val body = """{"ret":"1","desc":"Success","info":{
            "device_bind":{"count":2,"list":[
              {"device_id":"$DID","device_key":"k1","device_name":"Lamp","ip":"203.0.113.10","port":8898,"is_online":1,"local_ip":"192.168.1.40"},
              {"device_id":"nokey"}]},
            "device_share":{"count":1,"list":[{"device_id":"shared1","device_key":"k2","device_name":"Heater","ip":"203.0.113.11","port":8899,"is_online":0}]}}}"""
        val l = CozyAccount.parseDevices(body)
        assertEquals(2, l.size)
        assertEquals(CloudPlug(DID, "k1", "Lamp", "203.0.113.10", 8898, "192.168.1.40", true), l[0])
        assertEquals("Heater", l[1].name); assertEquals(8899, l[1].relayPort); assertFalse(l[1].online)
    }

    @Test fun accountErrorsReadLikeWords() {
        try { CozyAccount.parseDevices("""{"ret":"1001","desc":"x"}"""); fail() } catch (e: CloudError) {
            assertEquals("Wrong password.", e.message)
        }
    }

    @Test fun lineParsingKeepsPlusSigns() {
        assertEquals("a+b", CozyRelay.parseLine("cmd=publish&message=a+b")["message"])
    }
}
