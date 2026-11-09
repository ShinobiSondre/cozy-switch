package com.cozyswitch.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReachTest {
    private val withCloud = Plug("d", "Lamp", "192.168.1.40", key = "k", relayHost = "203.0.113.1", relayPort = 8898)
    private var localCalls = 0
    private val deadLocal: (String) -> Boolean? = { localCalls++; null }
    private val okLocal: (String) -> Boolean? = { localCalls++; true }
    private val okCloud: (CloudPlug) -> Boolean? = { true }
    private val deadCloud: (CloudPlug) -> Boolean? = { null }

    private fun Plug.after(o: Outcome) = copy(on = o.on, via = o.via, fails = o.fails, probeAt = o.probeAt)

    @Test fun wifiFirstWhenItWorks() {
        val o = reach(withCloud, home = true, now = 0, okLocal, okCloud)
        assertEquals("wifi", o.via); assertEquals(0, o.fails)
    }

    @Test fun fallsBackToCloudAndStopsWaitingForAStuckPlug() {
        var p = withCloud
        repeat(3) { p = p.after(reach(p, true, 1000, deadLocal, okCloud)) }
        assertEquals(3, localCalls)
        assertEquals("cloud", p.via)
        assertTrue(p.wifiStuck)
        // Now skipped: straight to the cloud.
        p = p.after(reach(p, true, 2000, deadLocal, okCloud))
        assertEquals(3, localCalls)
        assertEquals("cloud", p.via)
    }

    @Test fun checksWifiAgainAfterTenMinutesAndRecovers() {
        var p = withCloud
        repeat(3) { p = p.after(reach(p, true, 0, deadLocal, okCloud)) }
        p = p.after(reach(p, true, PROBE_EVERY_MS + 1, okLocal, okCloud))
        assertEquals("wifi", p.via); assertEquals(0, p.fails)
    }

    @Test fun withoutCloudWifiIsAlwaysTried() {
        var p = withCloud.copy(key = "", relayHost = "", relayPort = 0)
        repeat(5) { p = p.after(reach(p, true, 0, deadLocal, okCloud)) }
        assertEquals(5, localCalls)
        assertNull(p.on)
    }

    @Test fun awayGoesStraightToCloudWithoutCountingWifiFailures() {
        val o = reach(withCloud, home = false, now = 0, deadLocal, okCloud)
        assertEquals(0, localCalls); assertEquals("cloud", o.via); assertEquals(0, o.fails)
    }

    @Test fun bothDeadMeansNotAnswering() {
        val o = reach(withCloud, true, 0, deadLocal, deadCloud)
        assertNull(o.on); assertEquals("", o.via)
    }
}
