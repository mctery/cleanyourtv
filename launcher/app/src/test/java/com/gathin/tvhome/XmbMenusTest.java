package com.gathin.tvhome;

import android.content.pm.ApplicationInfo;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class XmbMenusTest {
    private static final XmbCatalog.Known KNOWN = ShippedConfig.known();

    private static String keys(List<XmbMenus.Menu> menus) {
        StringBuilder sb = new StringBuilder();
        for (XmbMenus.Menu m : menus) sb.append(sb.length() > 0 ? "," : "").append(m.key);
        return sb.toString();
    }

    @Test public void parsesKeyLabelAndIcon() {
        XmbMenus.Menu m = XmbMenus.parse("games:เกม:sports_esports");
        assertEquals("games", m.key);
        assertEquals("เกม", m.label);
        assertEquals("sports_esports", m.icon);
        XmbMenus.Menu bare = XmbMenus.parse("tools");
        assertNull(bare.label);
        assertNull(bare.icon);
        assertNull(XmbMenus.parse("tools::no_such_icon").icon);
        assertNull(XmbMenus.parse("Bad Key:x"));
        assertNull(XmbMenus.parse(""));
    }

    @Test public void themeMenusGoBeforeNetwork() {
        List<XmbMenus.Menu> menus = XmbMenus.merge(null, Arrays.asList("games:Games", "tools"), Collections.emptySet());
        assertEquals("settings,video,music,apps,games,tools,network", keys(menus));
        assertEquals("handyman", menus.get(5).icon);   // a preset brings its icon
    }

    @Test public void theTvListWinsAndKeepsEveryBuiltIn() {
        List<XmbMenus.Menu> saved = Arrays.asList(new XmbMenus.Menu("games", "G", null),
                new XmbMenus.Menu("settings", null, null), new XmbMenus.Menu("network", null, null));
        List<XmbMenus.Menu> menus = XmbMenus.merge(saved, Arrays.asList("social", "tools"),
                new HashSet<>(Collections.singletonList("tools")));
        assertEquals("games,settings,social,video,music,apps,network", keys(menus));
    }

    @Test public void newKeys() {
        List<XmbMenus.Menu> menus = XmbMenus.merge(null, Collections.singletonList("menu1"), Collections.emptySet());
        assertEquals("games", XmbMenus.newKey(menus, "games"));
        assertEquals("menu2", XmbMenus.newKey(menus, null));
        assertEquals("menu2", XmbMenus.newKey(menus, "video"));
    }

    @Test public void builtInsHoldAppsExceptSettingsAndNetwork() {
        assertFalse(new XmbMenus.Menu("settings", null, null).holdsApps());
        assertFalse(new XmbMenus.Menu("network", null, null).holdsApps());
        assertTrue(new XmbMenus.Menu("video", null, null).holdsApps());
        assertTrue(new XmbMenus.Menu("games", null, null).holdsApps());
    }

    @Test public void presetsCollectTheirApps() {
        List<String> mine = Arrays.asList("games", "tools", "social");
        List<String> none = Collections.emptyList();
        assertEquals("games", XmbCatalog.categoryFor("com.yodo1.crossyroad", ApplicationInfo.CATEGORY_GAME, false, none, mine, KNOWN));
        assertEquals("games", XmbCatalog.categoryFor("com.frogmind.badland", -1, true, none, mine, KNOWN));
        assertEquals("tools", XmbCatalog.categoryFor("com.anydesk.anydeskandroid", -1, false, none, mine, KNOWN));
        assertEquals("tools", XmbCatalog.categoryFor("com.android.vending", -1, false, none, mine, KNOWN));
        assertEquals("social", XmbCatalog.categoryFor("com.tiktok.tv", -1, false, none, mine, KNOWN));
        // Known video apps stay in Video, and without the menu a game is an app as before.
        assertEquals(XmbCatalog.VIDEO, XmbCatalog.categoryFor("com.netflix.ninja", -1, false, none, mine, KNOWN));
        assertEquals(XmbCatalog.APPS, XmbCatalog.categoryFor("com.frogmind.badland", -1, true, none, none, KNOWN));
    }

    @Test public void theOwnersMoveWinsButOnlyToAMenuThatExists() {
        List<String> mine = Collections.singletonList("tools");
        assertEquals("tools", XmbCatalog.categoryFor("com.netflix.ninja", -1, false,
                Collections.singletonList("com.netflix.ninja=tools"), mine, KNOWN));
        assertEquals(XmbCatalog.VIDEO, XmbCatalog.categoryFor("com.netflix.ninja", -1, false,
                Collections.singletonList("com.netflix.ninja=games"), mine, KNOWN));
    }
}
