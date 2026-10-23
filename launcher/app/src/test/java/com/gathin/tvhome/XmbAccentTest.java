package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class XmbAccentTest {
    @Test public void timeOfDayFactor() {
        assertEquals(1.08f, XmbAccent.timeFactor(7), 0f);
        assertEquals(1.08f, XmbAccent.timeFactor(16), 0f);
        assertEquals(0.96f, XmbAccent.timeFactor(17), 0f);
        assertEquals(0.96f, XmbAccent.timeFactor(6), 0f);
        assertEquals(0.82f, XmbAccent.timeFactor(22), 0f);
        assertEquals(0.82f, XmbAccent.timeFactor(0), 0f);
    }

    @Test public void octoberEveningIsTheMonthColourAtNinetySixPercent() {
        // #d6517a x 0.96 = (205, 78, 117)
        assertEquals(0xCD4E75, XmbAccent.colorFor(9, 19));
    }

    @Test public void brightChannelsClampAt255() {
        int c = XmbAccent.colorFor(5, 12);   // #e0b53e x 1.08
        assertEquals(242, (c >> 16) & 0xFF);
        assertTrue(((c >> 8) & 0xFF) <= 255);
    }

    @Test public void everyMonthAndHourClearsTheLuminanceFloor() {
        for (int m = 0; m < 12; m++) {
            for (int h = 0; h < 24; h++) {
                assertTrue(m + "/" + h, XmbAccent.luminance(XmbAccent.floor(XmbAccent.colorFor(m, h))) >= 0.25);
            }
        }
    }

    @Test public void aBrightColourIsLeftAlone() {
        assertEquals(0xE0B53E, XmbAccent.floor(0xE0B53E));
    }

    @Test public void colourSettings() {
        assertEquals(0xD6517A, XmbAccent.base("month", 9));
        assertEquals(0x7FB4E0, XmbAccent.base("month1", 9));
        assertEquals(0x5F6FE0, XmbAccent.base("month12", 3));
        assertEquals(0x234EBA, XmbAccent.base("#234EBA", 3));
        assertEquals(0xD6517A, XmbAccent.base("month13", 9));   // unreadable: this month's
        assertEquals(0xD6517A, XmbAccent.base("#12345", 9));
        assertEquals(-1, XmbAccent.fixedColor("month"));
        assertEquals("#0A0B0C", XmbAccent.hex(0x0A0B0C));
    }

    @Test public void theOwnersColourDimsAtNightToo() {
        assertEquals(XmbAccent.colorFor("month10", 0, 23), XmbAccent.colorFor(9, 23));
    }

    @Test public void pickerRoundTrip() {
        int c = XmbAccent.fromHueBrightness(0.62f, 0.7f);
        float[] hb = XmbAccent.hueBrightness(c);
        assertEquals(0.62f, hb[0], 0.01f);
        assertEquals(0.7f, hb[1], 0.02f);
        assertEquals(XmbAccent.fromHueBrightness(0f, 0.5f), XmbAccent.fromHueBrightness(1f, 0.5f));
    }
}
