package com.gathin.tvhome;

import android.content.pm.ApplicationInfo;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class XmbCatalogTest {
    private static final XmbCatalog.Known KNOWN = ShippedConfig.known();

    @Test public void knownStreamingAppsAreVideo() {
        assertEquals(XmbCatalog.VIDEO, XmbCatalog.categoryFor("com.netflix.ninja", -1, Collections.emptyList(), KNOWN));
        assertEquals(XmbCatalog.VIDEO, XmbCatalog.categoryFor("in.startv.hotstar.dplus.tv", -1, Collections.emptyList(), KNOWN));
        assertEquals(XmbCatalog.VIDEO, XmbCatalog.categoryFor("com.wbd.stream", -1, Collections.emptyList(), KNOWN));
    }

    @Test public void knownMusicAppsAreMusic() {
        assertEquals(XmbCatalog.MUSIC, XmbCatalog.categoryFor("com.google.android.youtube.tvmusic", -1, Collections.emptyList(), KNOWN));
    }

    @Test public void theAppsOwnCategoryCountsNext() {
        assertEquals(XmbCatalog.VIDEO, XmbCatalog.categoryFor("com.example.tv", ApplicationInfo.CATEGORY_VIDEO, Collections.emptyList(), KNOWN));
        assertEquals(XmbCatalog.MUSIC, XmbCatalog.categoryFor("com.example.radio", ApplicationInfo.CATEGORY_AUDIO, Collections.emptyList(), KNOWN));
        assertEquals(XmbCatalog.APPS, XmbCatalog.categoryFor("com.android.vending", -1, Collections.emptyList(), KNOWN));
    }

    @Test public void theOwnersChoiceWinsAndTheLastEntryCounts() {
        assertEquals(XmbCatalog.APPS, XmbCatalog.categoryFor("com.netflix.ninja", -1,
                Collections.singletonList("com.netflix.ninja=apps"), KNOWN));
        assertEquals(XmbCatalog.MUSIC, XmbCatalog.categoryFor("com.netflix.ninja", -1,
                Arrays.asList("com.netflix.ninja=apps", " com.netflix.ninja = music"), KNOWN));
    }

    @Test public void badOverridesAreIgnored() {
        assertEquals(XmbCatalog.VIDEO, XmbCatalog.categoryFor("com.netflix.ninja", -1,
                Arrays.asList("com.netflix.ninja=settings", "=apps", "com.netflix.ninja", "com.netflix=apps"), KNOWN));
    }

    @Test public void aNameEndingInADotTakesThePackagesUnderIt() {
        assertEquals(true, XmbCatalog.Known.matches("com.hbo.", "com.hbo.max.tv"));
        assertEquals(false, XmbCatalog.Known.matches("com.hbo.", "com.hbox"));
        assertEquals(false, XmbCatalog.Known.matches("com.dazn", "com.dazn.tv"));   // no dot: that name only
        assertEquals(false, XmbCatalog.Known.matches("  ", "com.dazn"));
    }

    @Test public void aThemesListReplacesTheShippedOne() {
        XmbCatalog.Known theirs = new XmbCatalog.Known(Collections.singletonList("com.example.player"),
                Collections.<String>emptyList(), Collections.singletonList("tools:com.example.files"));
        assertEquals(XmbCatalog.VIDEO, XmbCatalog.categoryFor("com.example.player", -1, Collections.<String>emptyList(), theirs));
        assertEquals(XmbCatalog.APPS, XmbCatalog.categoryFor("com.netflix.ninja", -1, Collections.<String>emptyList(), theirs));
        assertEquals("tools", XmbCatalog.categoryFor("com.example.files", -1, false, Collections.<String>emptyList(),
                Collections.singletonList("tools"), theirs));
    }
}
