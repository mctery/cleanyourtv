package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class XmbStatusPrefsTest {
    @Test public void whatComesWhenNeededGoesInAtTheLeftAndMovesNothing() {
        List<String> always = Arrays.asList("weather", "network");
        List<String> passing = new ArrayList<>();
        assertEquals(Arrays.asList("adb", "weather", "network"),
                XmbStatusPrefs.rowOrder(Arrays.asList("adb", "weather", "network"), always, passing));
        // A download: in at the far left, ADB and the weather stay where they are.
        assertEquals(Arrays.asList("download", "adb", "weather", "network"),
                XmbStatusPrefs.rowOrder(Arrays.asList("adb", "download", "weather", "network"), always, passing));
        // Then the speed, left of the download, although set between the weather and the network.
        assertEquals(Arrays.asList("speed", "download", "adb", "weather", "network"),
                XmbStatusPrefs.rowOrder(Arrays.asList("adb", "download", "weather", "speed", "network"), always, passing));
        // The download done: only the speed, left of it, closes up.
        assertEquals(Arrays.asList("speed", "adb", "weather", "network"),
                XmbStatusPrefs.rowOrder(Arrays.asList("adb", "weather", "speed", "network"), always, passing));
        assertEquals(Arrays.asList("adb", "weather", "network"),
                XmbStatusPrefs.rowOrder(Arrays.asList("adb", "weather", "network"), always, passing));
    }

    @Test public void severalAtOnceKeepTheOrderSetAndAlwaysWinsOverPassing() {
        List<String> passing = new ArrayList<>();
        assertEquals(Arrays.asList("battery", "storage", "weather"),
                XmbStatusPrefs.rowOrder(Arrays.asList("battery", "storage", "weather"), Collections.singletonList("weather"), passing));
        // The speed set to always: it joins the clock's side in the order set, and leaves the passing ones.
        passing.add("speed");
        assertEquals(Arrays.asList("weather", "speed", "network"),
                XmbStatusPrefs.rowOrder(Arrays.asList("weather", "speed", "network"),
                        Arrays.asList("weather", "speed", "network"), passing));
        assertTrue(passing.isEmpty());
    }

    @Test public void standardIsTheShippedRow() {
        XmbStatusPrefs p = XmbStatusPrefs.fromConfig(Collections.emptyList());
        assertEquals(XmbStatusPrefs.STANDARD, p.preset);
        assertEquals(XmbStatusPrefs.AUTO, p.mode("adb"));
        assertEquals(XmbStatusPrefs.ALWAYS, p.mode("weather"));
        assertEquals(XmbStatusPrefs.HIDDEN, p.mode("temperature"));
        assertEquals(XmbStatusPrefs.defaultOrder(), p.order);
    }

    @Test public void presetsGoRoundAndSetModes() {
        XmbStatusPrefs p = XmbStatusPrefs.preset(XmbStatusPrefs.STANDARD, XmbStatusPrefs.defaultOrder(), false);
        XmbStatusPrefs simple = p.nextPreset();
        assertEquals(XmbStatusPrefs.SIMPLE, simple.preset);
        assertEquals(XmbStatusPrefs.HIDDEN, simple.mode("weather"));
        assertEquals(XmbStatusPrefs.ALWAYS, simple.mode("network"));
        XmbStatusPrefs full = simple.nextPreset();
        assertEquals(XmbStatusPrefs.ALWAYS, full.mode("battery"));
        assertEquals(XmbStatusPrefs.ALWAYS, full.mode("temperature"));
        XmbStatusPrefs clock = full.nextPreset();
        assertEquals(XmbStatusPrefs.HIDDEN, clock.mode("network"));
        assertEquals(XmbStatusPrefs.AUTO, clock.mode("recording"));   // a recording always says so
        assertEquals(XmbStatusPrefs.STANDARD, clock.nextPreset().preset);
    }

    @Test public void changingAModeMakesItCustomUnlessItMatchesAPreset() {
        XmbStatusPrefs p = XmbStatusPrefs.preset(XmbStatusPrefs.STANDARD, XmbStatusPrefs.defaultOrder(), false);
        XmbStatusPrefs q = p.nextMode("weather");
        assertEquals(XmbStatusPrefs.HIDDEN, q.mode("weather"));
        assertEquals(XmbStatusPrefs.CUSTOM, q.preset);
        assertEquals(XmbStatusPrefs.STANDARD, q.nextMode("weather").preset);
        assertEquals(p.modes, p.nextMode("recording").modes);   // one mode only: nothing to change
    }

    @Test public void moveAndRoundTrip() {
        XmbStatusPrefs p = XmbStatusPrefs.preset(XmbStatusPrefs.FULL, XmbStatusPrefs.defaultOrder(), true)
                .moved("palette", -1);
        assertEquals("palette", p.order.get(p.order.size() - 2));
        XmbStatusPrefs back = XmbStatusPrefs.decode(p.encode());
        assertEquals(p.order, back.order);
        assertEquals(p.modes, back.modes);
        assertTrue(back.keepOnIdle);
        assertEquals(XmbStatusPrefs.FULL, back.preset);
        assertNull(XmbStatusPrefs.decode("garbage"));
    }

    @Test public void themeEntriesKeepTheirOrderAndUnknownOnesDrop() {
        XmbStatusPrefs p = XmbStatusPrefs.fromConfig(Arrays.asList("network=always", "nonsense=always", "weather=hidden",
                "battery=sideways"));
        // the listed ones in the order given; the rest keep their default places around them
        assertEquals(p.order.indexOf("network") + 1, p.order.indexOf("weather"));
        assertEquals("recording", p.order.get(0));
        assertFalse(p.order.contains("nonsense"));
        assertEquals(XmbStatusPrefs.ITEMS.size(), p.order.size());
        assertEquals(XmbStatusPrefs.HIDDEN, p.mode("weather"));
        assertEquals(XmbStatusPrefs.AUTO, p.mode("battery"));      // not a mode it has: its default
        assertEquals(XmbStatusPrefs.CUSTOM, p.preset);
    }

    @Test public void dustAndSpeedSitBetweenTheWeatherAndTheNetwork() {
        XmbStatusPrefs p = XmbStatusPrefs.fromConfig(Collections.emptyList());
        int weather = p.order.indexOf("weather");
        assertEquals("air", p.order.get(weather + 1));
        assertEquals("speed", p.order.get(weather + 2));
        assertEquals("network", p.order.get(weather + 3));
        assertEquals(XmbStatusPrefs.ALWAYS, p.mode("air"));
        assertEquals(XmbStatusPrefs.AUTO, p.mode("speed"));      // only while something downloads
        // A setup saved on the TV before they existed gets them in their places, with their defaults.
        XmbStatusPrefs old = XmbStatusPrefs.decode("v1;custom;0;weather=hidden,network=always,palette=always");
        assertEquals(old.order.indexOf("weather") + 1, old.order.indexOf("air"));
        assertEquals(XmbStatusPrefs.AUTO, old.mode("speed"));
    }

    @Test public void movedPastSkipsWhatIsInBetween() {
        XmbStatusPrefs p = XmbStatusPrefs.fromConfig(Collections.emptyList());
        // Carried down past the weather with the temperature (left out on a TV that cannot read it) in between.
        XmbStatusPrefs q = p.movedPast("audio", "weather", 1);
        assertEquals(q.order.indexOf("weather") + 1, q.order.indexOf("audio"));
        XmbStatusPrefs r = q.movedPast("audio", "usb", -1);
        assertEquals(r.order.indexOf("usb") - 1, r.order.indexOf("audio"));
        assertEquals(p.order.size(), r.order.size());
    }

    @Test public void missingKeysGoBackNearTheirDefaultNeighbours() {
        assertEquals(XmbStatusPrefs.defaultOrder(), XmbStatusPrefs.complete(Arrays.asList("network")));
    }
}
