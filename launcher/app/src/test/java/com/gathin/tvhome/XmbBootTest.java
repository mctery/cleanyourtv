package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class XmbBootTest {
    @Test public void bezierMatchesKnownCurves() {
        assertEquals(0.5f, XmbBoot.bezier(0f, 0f, 1f, 1f, 0.5f), 1e-4f);        // linear
        assertEquals(0.8024f, XmbBoot.bezier(0.25f, 0.1f, 0.25f, 1f, 0.5f), 2e-3f);   // CSS "ease" halfway
        assertEquals(0f, XmbBoot.bezier(0.42f, 0f, 0.18f, 1f, -1f), 0f);
        assertEquals(1f, XmbBoot.bezier(0.42f, 0f, 0.18f, 1f, 2f), 0f);
        float last = 0f;
        for (int i = 1; i <= 100; i++) {   // always forward
            float y = XmbBoot.bezier(0.42f, 0f, 0.18f, 1f, i / 100f);
            assertTrue(y >= last);
            last = y;
        }
    }

    @Test public void startsBlackAndEndsAsTheWaveAlwaysIs() {
        XmbBoot b = new XmbBoot().at(0f);
        assertEquals(0f, b.expose, 0f);                  // no colour yet
        assertTrue(b.fast);                              // every frame while the lines sweep...
        assertEquals(0f, b.rows2, 0f);                   // ...drawn with every other row
        assertTrue(b.reveal < -1.5f);                    // no line yet
        assertFalse(b.done);
        b.at(XmbBoot.length());
        assertTrue(b.done);
        assertFalse(b.fast);
        assertEquals(1f, b.rows2, 1e-4f);               // every row again
        assertEquals(1f, b.boost, 1e-4f);
        assertEquals(1f, b.spark, 1e-4f);
        assertEquals(0f, b.tip, 1e-4f);
        assertEquals(0f, b.trail, 1e-4f);
        assertEquals(0f, b.haze, 1e-4f);
        assertEquals(1f, b.expose, 1e-4f);
        assertTrue(b.bloom >= 1.95f);                   // the colour everywhere
        assertTrue(b.reveal - b.spread > 1f);            // every line drawn to the right edge
    }

    @Test public void theLinesCrossLeftToRightAndTheColourComesAfterThem() {
        XmbBoot b = new XmbBoot();
        float last = -10f;
        for (int ms = (int) XmbStyle.BOOT_LINES_AT_MS; ms <= XmbStyle.BOOT_LINES_AT_MS + XmbStyle.BOOT_LINES_MS; ms += 50) {
            b.at(ms);
            assertTrue(b.reveal >= last);
            last = b.reveal;
        }
        b.at(XmbStyle.BOOT_LINES_AT_MS + 1000);
        assertEquals(0f, b.expose, 0f);                  // still black behind the lines
        assertTrue(b.tip > 1f && b.trail > 0.5f);        // the heads lit
        assertTrue(XmbStyle.BOOT_COLOUR_AT_MS < XmbStyle.BOOT_LINES_AT_MS + XmbStyle.BOOT_LINES_MS);   // overlapping
        assertTrue(XmbStyle.BOOT_MENU_AT_MS < XmbBoot.length());
    }
}
