package com.gathin.tvhome;

import android.content.pm.ApplicationInfo;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class XmbCatalogTest {
    @Test public void knownStreamingAppsAreVideo() {
        assertEquals(XmbCatalog.VIDEO, XmbCatalog.categoryFor("com.netflix.ninja", -1, Collections.emptyList()));
        assertEquals(XmbCatalog.VIDEO, XmbCatalog.categoryFor("in.startv.hotstar.dplus.tv", -1, Collections.emptyList()));
        assertEquals(XmbCatalog.VIDEO, XmbCatalog.categoryFor("com.wbd.stream", -1, Collections.emptyList()));
    }

    @Test public void knownMusicAppsAreMusic() {
        assertEquals(XmbCatalog.MUSIC, XmbCatalog.categoryFor("com.google.android.youtube.tvmusic", -1, Collections.emptyList()));
    }

    @Test public void theAppsOwnCategoryCountsNext() {
        assertEquals(XmbCatalog.VIDEO, XmbCatalog.categoryFor("com.example.tv", ApplicationInfo.CATEGORY_VIDEO, Collections.emptyList()));
        assertEquals(XmbCatalog.MUSIC, XmbCatalog.categoryFor("com.example.radio", ApplicationInfo.CATEGORY_AUDIO, Collections.emptyList()));
        assertEquals(XmbCatalog.APPS, XmbCatalog.categoryFor("com.android.vending", -1, Collections.emptyList()));
    }

    @Test public void theOwnersChoiceWinsAndTheLastEntryCounts() {
        assertEquals(XmbCatalog.APPS, XmbCatalog.categoryFor("com.netflix.ninja", -1,
                Collections.singletonList("com.netflix.ninja=apps")));
        assertEquals(XmbCatalog.MUSIC, XmbCatalog.categoryFor("com.netflix.ninja", -1,
                Arrays.asList("com.netflix.ninja=apps", " com.netflix.ninja = music")));
    }

    @Test public void badOverridesAreIgnored() {
        assertEquals(XmbCatalog.VIDEO, XmbCatalog.categoryFor("com.netflix.ninja", -1,
                Arrays.asList("com.netflix.ninja=settings", "=apps", "com.netflix.ninja", "com.netflix=apps")));
    }
}
