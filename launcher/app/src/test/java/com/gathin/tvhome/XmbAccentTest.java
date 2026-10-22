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
}
