package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class XmbWaveTest {
    private static int h(float f) {
        return XmbWave.half(f) & 0xFFFF;
    }

    @Test public void halvesAsIeee() {
        assertEquals(0x3C00, h(1f));
        assertEquals(0xC000, h(-2f));
        assertEquals(0x3800, h(0.5f));
        assertEquals(0x2E66, h(0.1f));
        assertEquals(0xB4CD, h(-0.3f));
        assertEquals(0x7BFF, h(65504f));
        assertEquals(0x0000, h(0f));
        assertEquals(0x8000, h(-0f));
    }

    @Test public void roundsToNearestEven() {
        assertEquals(0x3C00, h(1f + 1f / 2048));          // halfway, the even one stays
        assertEquals(0x3C02, h(1f + 3f / 2048));          // halfway, up to the even one
        assertEquals(0x3C01, h(1f + 1f / 2048 + 1e-7f));  // just past halfway: up
    }

    @Test public void tooSmallIsZeroTooLargeIsTheLargest() {
        assertEquals(0x0000, h(1e-6f));
        assertEquals(0x8000, h(-1e-6f));
        assertEquals(0x7BFF, h(1e6f));
        assertEquals(0xFBFF, h(-70000f));
    }
}
