package com.cozyswitch.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTest {
    @Test fun readsCloudStatesLikeTheCozyLifeApp() {
        val body = """{"ret":"1","info":[
            {"device_id":"a","state":{"1":"255","online":true}},
            {"device_id":"b","state":{"1":0,"online":1}},
            {"device_id":"c","state":{"1":255,"online":false}},
            {"device_id":"d","state":{"online":true}},
            {"device_id":"e","state":{"1":"1"}}]}"""
        val m = CozyAccount.parseStates(body)
        assertEquals(true, m["a"]); assertEquals(false, m["b"])
        assertFalse(m.containsKey("c")) // offline: no fresh state
        assertFalse(m.containsKey("d")) // no switch value
        assertEquals(true, m["e"])
    }

    @Test fun readsADiscoveryAnswer() {
        assertEquals("629168597cb94c4c1d8f", Discovery.parse("""{"cmd":0,"pv":0,"sn":"1","msg":{"did":"629168597cb94c4c1d8f","ip":"192.168.1.40"},"res":0}"""))
        assertNull(Discovery.parse("garbage"))
    }

    @Test fun discoveryProbeIsTheReadOnlyInfoCommand() {
        val p = org.json.JSONObject(Discovery.probe())
        assertEquals(0, p.getInt("cmd"))
    }

    @Test fun sequenceNumbersFollowTheClockLikeCozyLife() {
        val a = CozyClient.nextSn().toLong()
        Thread.sleep(30)
        val b = CozyClient.nextSn().toLong()
        val now = System.currentTimeMillis()
        assertTrue(b > a)
        assertTrue(now - b < 1000) // current time, not a count from when the app started
    }
}
