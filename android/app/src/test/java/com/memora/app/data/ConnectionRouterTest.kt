package com.memora.app.data

import com.memora.app.data.MemoraException.Kind
import com.memora.app.local.OperatingMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRouterTest {
    private val lan = ServerConfig("192.168.1.20", 8010, "ABCDE-FGH23")
    private val ts = TailscaleConfig(true, "100.101.102.103", 8010)
    private val code = "ABCDE-FGH23"

    /** A fake network: which hosts answer, and how the others fail. Records every host contacted. */
    private class Net(val answers: Map<String, Kind?>) {
        val contacted = mutableListOf<String>()
        val probe: suspend (ServerConfig) -> ServerInfo = { c ->
            contacted += c.host
            val failure = answers[c.host] ?: throw MemoraException("No route to ${c.host}", Kind.UNREACHABLE)
            throw MemoraException("fail ${c.host}", failure)
        }
        val okProbe: (Set<String>) -> suspend (ServerConfig) -> ServerInfo = { up ->
            { c ->
                contacted += c.host
                if (c.host in up) ServerInfo("laptop", "0.4.0")
                else throw MemoraException("down ${c.host}", answers[c.host] ?: Kind.UNREACHABLE)
            }
        }
    }

    private fun router(up: Set<String>, net: Net = Net(emptyMap())) = net to ConnectionRouter(net.okProbe(up))

    @Test fun lanIsPreferredAndTailscaleIsNotTouchedWhenLanWorks() = runBlocking {
        val (net, r) = router(setOf(lan.host, ts.host))
        val o = r.connect(OperatingMode.AUTO, lan, ts, code) as RouteOutcome.Connected
        assertEquals(LaptopRoute.LAN, o.route)
        assertEquals(listOf(lan.host), net.contacted)
    }

    @Test fun automaticFallsBackToTailscaleWhenLanIsUnreachable() = runBlocking {
        val (net, r) = router(setOf(ts.host))
        val o = r.connect(OperatingMode.AUTO, lan, ts, code) as RouteOutcome.Connected
        assertEquals(LaptopRoute.TAILSCALE, o.route) // the route reported is the one that answered
        assertEquals(ts.host, o.config.host)
        assertEquals(code, o.config.code)
        assertEquals(listOf(lan.host, ts.host), net.contacted)
        assertEquals(1, o.skipped.size)
    }

    @Test fun laptopModeUsesTheLaptopRoutesAndReportsFailureInsteadOfFallingBackToPhone() = runBlocking {
        val (_, r) = router(emptySet())
        val o = r.connect(OperatingMode.LAPTOP, lan, ts, code)
        assertTrue(o is RouteOutcome.Failed)
        assertEquals(2, (o as RouteOutcome.Failed).attempts.size)
        assertTrue(o.message.contains("LAN") && o.message.contains("Tailscale"))
    }

    @Test fun phoneOnlyNeverContactsAnyLaptopAddress() = runBlocking {
        val (net, r) = router(setOf(lan.host, ts.host))
        val o = r.connect(OperatingMode.PHONE_ONLY, lan, ts, code)
        assertTrue(o is RouteOutcome.NotAttempted)
        assertTrue(net.contacted.isEmpty())
    }

    @Test fun wrongPairingCodeStopsInsteadOfTryingAnotherRoute() = runBlocking {
        val net = Net(mapOf(lan.host to Kind.UNAUTHORIZED))
        val r = ConnectionRouter(net.okProbe(setOf(ts.host)))
        val o = r.connect(OperatingMode.AUTO, lan, ts, code) as RouteOutcome.Failed
        assertEquals(Kind.UNAUTHORIZED, o.kind)
        assertEquals(listOf(lan.host), net.contacted)
    }

    @Test fun serverErrorsAreNotTreatedAsNetworkOutages() = runBlocking {
        for (kind in listOf(Kind.SERVER, Kind.NOT_MEMORA, Kind.REJECTED, Kind.AI_UNAVAILABLE)) {
            val net = Net(mapOf(lan.host to kind))
            val o = ConnectionRouter(net.okProbe(setOf(ts.host))).connect(OperatingMode.AUTO, lan, ts, code) as RouteOutcome.Failed
            assertEquals(kind, o.kind)
            assertEquals(1, net.contacted.size)
        }
    }

    @Test fun timeoutOnLanAlsoTriesTailscale() = runBlocking {
        val net = Net(mapOf(lan.host to Kind.TIMEOUT))
        val o = ConnectionRouter(net.okProbe(setOf(ts.host))).connect(OperatingMode.AUTO, lan, ts, code) as RouteOutcome.Connected
        assertEquals(LaptopRoute.TAILSCALE, o.route)
    }

    @Test fun disabledOrCodelessTailscaleIsNeverProbed() = runBlocking {
        val (net, r) = router(setOf(ts.host))
        assertTrue(r.connect(OperatingMode.AUTO, lan, ts.copy(enabled = false), code) is RouteOutcome.Failed)
        assertEquals(listOf(lan.host), net.contacted)
        net.contacted.clear()
        assertTrue(r.connect(OperatingMode.AUTO, null, ts, "") is RouteOutcome.NotAttempted)
        assertTrue(net.contacted.isEmpty())
    }

    @Test fun tailscaleOnlyWorksWithoutAnyLanSetup() = runBlocking {
        val (_, r) = router(setOf(ts.host))
        val o = r.connect(OperatingMode.LAPTOP, null, ts, code) as RouteOutcome.Connected
        assertEquals(LaptopRoute.TAILSCALE, o.route)
    }

    @Test fun overlappingChecksDoNotStackUp() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val r = ConnectionRouter { calls++; release.await(); ServerInfo("laptop", "0.4.0") }
        val first = async { r.connect(OperatingMode.AUTO, lan, ts, code) }
        yield()
        assertEquals(RouteOutcome.Busy, r.connect(OperatingMode.AUTO, lan, ts, code))
        assertEquals(RouteOutcome.Busy, r.test(LaptopRoute.TAILSCALE, ServerConfig(ts.host, 8010, code)))
        release.complete(Unit)
        assertTrue(first.await() is RouteOutcome.Connected)
        assertEquals(1, calls)
    }

    @Test fun testChecksExactlyOneRouteWithoutFallback() = runBlocking {
        val (net, r) = router(setOf(lan.host))
        val o = r.test(LaptopRoute.TAILSCALE, ServerConfig(ts.host, 8010, code))
        assertTrue(o is RouteOutcome.Failed)
        assertEquals(listOf(ts.host), net.contacted)
    }

    // Address validation

    @Test fun acceptsTailscaleAddresses() {
        for (h in listOf("100.101.102.103", "100.64.0.1", "100.127.255.254", "laptop", "my-laptop.tail1234.ts.net", "MY-LAPTOP.TAIL1234.TS.NET")) {
            assertNull(h, TailscaleAddress.validate(h, "8010"))
        }
    }

    @Test fun rejectsMalformedAndUnexpectedAddresses() {
        val bad = listOf(
            "", "  ", "http://100.101.102.103", "https://laptop.ts.net", "100.101.102.103/", "100.101.102.103:8010",
            "100.63.255.255", "100.128.0.1", "192.168.1.20", "8.8.8.8", "100.101.102", "100.101.102.999", "localhost", "127.0.0.1",
            "example.com", "laptop.example.org", "bad_host", "-bad.ts.net", "a b.ts.net", "user@laptop", "laptop?x=1", "laptop#x",
        )
        for (h in bad) assertNotNull("should reject '$h'", TailscaleAddress.validate(h, "8010"))
    }

    @Test fun rejectsBadPorts() {
        for (p in listOf("", "0", "65536", "abc", "-1")) assertNotNull(TailscaleAddress.validate("100.101.102.103", p))
        assertNull(TailscaleAddress.validate("100.101.102.103", "65535"))
    }

    @Test fun errorMessagesNeverContainThePairingCode() = runBlocking {
        val (_, r) = router(emptySet())
        val o = r.connect(OperatingMode.AUTO, lan, ts, code) as RouteOutcome.Failed
        assertTrue(!o.message.contains(code))
    }
}
