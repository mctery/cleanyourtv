package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class XmbStatusTest {
    @Test public void ratesReadAsTheRowShowsThem() {
        assertEquals("0.0 Mb/s", XmbStatus.rate(0f));
        assertEquals("0.0 Mb/s", XmbStatus.rate(-1f));
        assertEquals("4.5 Mb/s", XmbStatus.rate(4.49f));
        assertEquals("9.9 Mb/s", XmbStatus.rate(9.94f));
        assertEquals("10 Mb/s", XmbStatus.rate(9.96f));
        assertEquals("482 Mb/s", XmbStatus.rate(482.4f));
        assertEquals("1.2 Gb/s", XmbStatus.rate(1180f));
    }

    @Test public void batteryInFourBlocks() {
        assertEquals(4, XmbStatus.batteryBlocks(100));
        assertEquals(4, XmbStatus.batteryBlocks(76));
        assertEquals(3, XmbStatus.batteryBlocks(75));
        assertEquals(2, XmbStatus.batteryBlocks(50));
        assertEquals(1, XmbStatus.batteryBlocks(25));
        assertEquals(1, XmbStatus.batteryBlocks(6));
        assertEquals(0, XmbStatus.batteryBlocks(5));
    }

    @Test public void pingFromTheEdgesOwnRoundTrip() {
        java.util.List<String> cf = java.util.Arrays.asList("cfSpeedEdge;dur=3, cfSpeedWorker;dur=67",
                "cfL4;desc=\"?proto=TCP&rtt=10254&min_rtt=10190&rtt_var=3951&sent=5\"");
        assertEquals(10, XmbSpeedTest.roundTrip(cf, 95));
        // Without the edge's figure: the time taken less what the server says it spent.
        assertEquals(25, XmbSpeedTest.roundTrip(java.util.Collections.singletonList("cfSpeedEdge;dur=3, cfSpeedWorker;dur=67"), 95));
        assertEquals(95, XmbSpeedTest.roundTrip(java.util.Collections.emptyList(), 95));
    }

    @Test public void speedFromBytesAndTime() {
        assertEquals(8f, XmbSpeedTest.mbps(1_000_000, 1000), 1e-4);    // a megabyte a second is 8 Mb/s
        assertEquals(0f, XmbSpeedTest.mbps(1_000_000, 0), 0f);
    }
}
