package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class XmbStampedTest {
    @Test public void roundTripsValuesAndTime() {
        XmbStamped s = XmbStamped.decode(new XmbStamped(1234567890L, 31, "x", -1).encode(), 3);
        assertEquals(1234567890L, s.time);
        assertEquals(31, s.number(0, 0));
        assertEquals("x", s.values[1]);
        assertEquals(-1, s.number(2, 0));
        assertEquals(7, s.number(1, 7));   // not a number: the fallback
    }

    @Test public void onlyTheExpectedShapeIsRead() {
        assertNull(XmbStamped.decode(null, 1));
        assertNull(XmbStamped.decode("80|1|2", 1));          // an older build's longer form
        assertNull(XmbStamped.decode("80", 1));              // no time
        assertNull(XmbStamped.decode("80|soon", 1));         // a time that is not one
        assertEquals(80, XmbStamped.decode("80|5", 1).number(0, -1));   // the remote battery's form
    }

    @Test public void freshEitherWayRound() {
        XmbStamped s = new XmbStamped(10_000L, 1);
        assertTrue(s.fresh(10_500L, 1000));
        assertTrue(s.fresh(9_500L, 1000));      // the clock went back a little
        assertFalse(s.fresh(12_000L, 1000));
        assertFalse(s.fresh(1_000L, 1000));     // the clock went back a lot
    }
}
