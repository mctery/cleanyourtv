package com.gathin.tvhome;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class XmbRunningTest {
    /** Shaped like dumpsys activity processes on the Mi Box S (Android 11), cut short. */
    private static final String DUMP = String.join("\n",
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)",
            "  All known processes:",
            "  *PERS* UID 1000 ProcessRecord{71e2003 1409:com.example.partner/1000}",
            "    packageList={com.example.partner}",
            "    lastPss=8.7MB lastSwapPss=2.1MB lastCachedPss=0.00 lastCachedSwapPss=0.00 lastRss=97MB",
            "    oom adj: max=-800 curRaw=-800 setRaw=-800 cur=-800 set=-800",
            "  *APP* UID 10099 ProcessRecord{39bda9 5135:com.example.game/u0a99}",
            "    user #0 uid=10099 gids={3003, 50099, 20099, 9997}",
            "    packageList={com.example.game}",
            "    lastPss=542MB lastSwapPss=44MB lastCachedPss=542MB lastCachedSwapPss=44MB lastRss=617MB",
            "    oom adj: max=1001 curRaw=700 setRaw=700 cur=700 set=700",
            "    Activities:",
            "      - ActivityRecord{17a9d30 u0 com.example.game/.Main t5061}",
            "  *APP* UID 10057 ProcessRecord{a1 1949:com.example.store/u0a57}",
            "    packageList={com.example.store}",
            "    lastPss=55MB lastSwapPss=0.00",
            "    oom adj: max=1001 curRaw=700 setRaw=700 cur=700 set=700",
            "  *APP* UID 10057 ProcessRecord{a2 5926:com.example.store:background/u0a57}",
            "    packageList={com.example.store}",
            "    lastPss=42MB lastSwapPss=0.00",
            "    oom adj: max=1001 curRaw=945 setRaw=945 cur=945 set=945",
            "  *APP* UID 10085 ProcessRecord{a3 3594:com.example.music/u0a85}",
            "    packageList={com.example.music}",
            "    lastPss=41MB lastSwapPss=0.00",
            "    oom adj: max=1001 curRaw=200 setRaw=200 cur=200 set=200",
            "  *APP* UID 10070 ProcessRecord{a4 6000:com.example.gone/u0a70}",
            "    packageList={com.example.gone}",
            "    lastPss=30MB lastSwapPss=0.00",
            "    oom adj: max=1001 curRaw=900 setRaw=900 cur=900 set=900",
            "    killed=false killedByAm=true waitingToKill=null",
            "  *APP* UID 10055 ProcessRecord{a5 1122:com.example.shared/u0a55}",
            "    packageList={com.example.shared, com.example.shared.two}",
            "    lastPss=1,2GB lastSwapPss=0.00",
            "    oom adj: max=1001 curRaw=100 setRaw=100 cur=100 set=100",
            "",
            "  OOM levels:",
            "    packageList={com.example.not.a.process}",
            "    lastPss=99MB");

    @Test public void readsEachAppsProcesses() {
        Map<String, XmbRunning.App> apps = XmbRunning.parse(DUMP);
        XmbRunning.App game = apps.get("com.example.game");
        assertEquals(542 * 1024, game.kb);
        assertEquals(700, game.adj);
        assertTrue(game.closable());
        // Two processes of one app: their memory together, the more important one decides.
        XmbRunning.App store = apps.get("com.example.store");
        assertEquals((55 + 42) * 1024, store.kb);
        assertEquals(700, store.adj);
        // A foreground service (music playing) is kept by Android: listed, not closable.
        assertFalse(apps.get("com.example.music").closable());
        // One process for two packages (a shared user id): both listed; a comma for the decimal point is read.
        assertEquals(Math.round(1.2 * 1024 * 1024), apps.get("com.example.shared.two").kb);
    }

    @Test public void leavesOutTheSystemsDyingProcessesAndOtherSections() {
        Map<String, XmbRunning.App> apps = XmbRunning.parse(DUMP);
        assertNull(apps.get("com.example.partner"));       // *PERS*
        assertNull(apps.get("com.example.gone"));          // being killed
        assertNull(apps.get("com.example.not.a.process")); // past the end of the processes
        assertEquals(5, apps.size());
    }

    @Test public void sizesAsAndroidWritesThemAndAsPeopleReadThem() {
        assertEquals(8909, XmbRunning.kilobytes("8.7MB"));
        assertEquals(0, XmbRunning.kilobytes("0.00"));
        assertEquals(512, XmbRunning.kilobytes("512KB"));
        assertEquals(0, XmbRunning.kilobytes("n/a"));
        assertEquals("542 MB", XmbRunning.size(542 * 1024));
        assertEquals("1 MB", XmbRunning.size(100));
        assertEquals("1.8 GB", XmbRunning.size(1_887_436));
        assertEquals("999 MB", XmbRunning.size(999 * 1024));
    }
}
