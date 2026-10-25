package com.gathin.tvhome;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Settings > Open apps: the apps left running behind Home, the memory each one holds, and closing them.
 *
 * Android shows an app its own processes only, so the list is what the activity manager itself reports (dumpsys
 * activity processes, read as text, about 0.1 s): every process with its packages, the memory Android last measured
 * for it (PSS) and how much Android wants to keep it (its oom adj). Reading that takes DUMP and PACKAGE_USAGE_STATS,
 * which only ADB can grant (pm grant; mytv/install.sh does): without them the list stays empty and the item says so,
 * the free memory still shows.
 *
 * Closing is what Android does by itself when memory runs short (ActivityManager.killBackgroundProcesses): the app's
 * processes end, its place in Recents stays, and opening it again starts it afresh. An app Android keeps (playing
 * music in the background, a cast) is not ended that way; the list says it is in use.
 */
final class XmbRunning {
    private static final String TAG = "TvHome.Running";
    /** ProcessList.SERVICE_ADJ: killBackgroundProcesses ends processes this important or less (higher numbers). */
    static final int CLOSABLE_ADJ = 500;
    /** dumpsys's own timeout in seconds, and the most of its text read. */
    private static final String DUMP_TIMEOUT_S = "4";
    private static final int MAX_DUMP = 4 << 20;
    /** After closing, Android needs a moment to take the processes off its list. */
    private static final long SETTLE_MS = 500;

    /** An app with processes running. */
    static final class App {
        final String pkg;
        /** Its processes' memory in kB, as Android last measured it. */
        long kb;
        /** The most important of its processes' oom adj (the lowest number). */
        int adj = Integer.MAX_VALUE;
        /** The app's own icon, loaded with the reading (off the main thread); null when it has none. */
        android.graphics.drawable.Drawable icon;

        App(String pkg) {
            this.pkg = pkg;
        }

        /** Closing ends it: none of its processes is one Android keeps (playing, a foreground service, on screen). */
        boolean closable() {
            return adj >= CLOSABLE_ADJ;
        }
    }

    /** A reading: the apps Home shows that have processes running, the most memory first, and the memory free. */
    static final class Snapshot {
        /** Null when Android would not tell (DUMP or PACKAGE_USAGE_STATS not granted). */
        final List<App> apps;
        final long freeKb, totalKb;

        Snapshot(List<App> apps, long freeKb, long totalKb) {
            this.apps = apps;
            this.freeKb = freeKb;
            this.totalKb = totalKb;
        }

        App find(String pkg) {
            if (apps != null) for (App a : apps) if (a.pkg.equals(pkg)) return a;
            return null;
        }
    }

    /** Told on the main thread with the new reading. */
    interface Listener {
        void read(Snapshot s);
    }

    private static volatile Snapshot last;
    /** One reading or closing at a time, in order. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> new Thread(r, "xmb-running"));

    private XmbRunning() {
    }

    /** The last reading, or null before the first. */
    static Snapshot last() {
        return last;
    }

    /** DUMP and PACKAGE_USAGE_STATS were granted over ADB: the list can be read. */
    static boolean allowed(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
                && ctx.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Reads on the worker thread, keeping the apps among shown (the packages Home lists). */
    static void refresh(Context ctx, Set<String> shown, Listener listener) {
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        WORKER.execute(() -> {
            Snapshot s = read(app, shown);
            main.post(() -> listener.read(s));
        });
    }

    /** Closes the apps and reads again once Android has let them go. */
    static void close(Context ctx, Collection<String> pkgs, Set<String> shown, Listener listener) {
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        WORKER.execute(() -> {
            ActivityManager am = app.getSystemService(ActivityManager.class);
            for (String pkg : pkgs) {
                try {
                    am.killBackgroundProcesses(pkg);
                } catch (RuntimeException e) {
                    Log.w(TAG, "could not close " + pkg + ": " + e);
                }
            }
            SystemClock.sleep(SETTLE_MS);
            Snapshot s = read(app, shown);
            main.post(() -> listener.read(s));
        });
    }

    private static Snapshot read(Context ctx, Set<String> shown) {
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        ctx.getSystemService(ActivityManager.class).getMemoryInfo(mem);
        List<App> apps = null;
        String dump = allowed(ctx) ? dump() : null;
        if (dump != null && !dump.contains("Permission Denial")) {
            apps = new ArrayList<>();
            for (App a : parse(dump).values()) {
                if (shown.contains(a.pkg) && !a.pkg.equals(ctx.getPackageName())) apps.add(a);
            }
            apps.sort((a, b) -> Long.compare(b.kb, a.kb));
            PackageManager pm = ctx.getPackageManager();
            for (App a : apps) {
                try {
                    a.icon = pm.getApplicationIcon(a.pkg);
                } catch (PackageManager.NameNotFoundException ignored) {
                    // uninstalled since: the list's glyph stands in
                }
            }
        }
        Snapshot s = new Snapshot(apps, mem.availMem / 1024, mem.totalMem / 1024);
        last = s;
        return s;
    }

    private static String dump() {
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/dumpsys", "-t", DUMP_TIMEOUT_S, "activity", "processes")
                    .redirectErrorStream(true).start();
            p.getOutputStream().close();
            ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (out.size() < MAX_DUMP) out.write(buf, 0, n);
                }
            }
            p.waitFor(1, TimeUnit.SECONDS);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException | InterruptedException e) {
            Log.w(TAG, "could not read the processes: " + e);
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static final Pattern ADJ = Pattern.compile("\\bset=(-?\\d+)");

    /**
     * By package, from the text of dumpsys activity processes: each "*APP*" process's packages (packageList), the
     * memory last measured (lastPss) and its oom adj (set=), until the next process or the next section. Processes
     * being killed and the system's own (*PERS*) are left out. Pure, tested on the JVM.
     */
    static Map<String, App> parse(String dump) {
        Map<String, App> out = new LinkedHashMap<>();
        List<String> pkgs = null;
        long kb = 0;
        int adj = Integer.MAX_VALUE;
        boolean in = false, dead = false;
        for (String line : dump.split("\n")) {
            boolean start = line.startsWith("  *APP*") || line.startsWith("  *PERS*");
            if (in && (start || indent(line) < 4)) {
                if (!dead && pkgs != null) add(out, pkgs, kb, adj);
                in = false;
            }
            if (start) {
                in = line.startsWith("  *APP*");
                pkgs = null;
                kb = 0;
                adj = Integer.MAX_VALUE;
                dead = false;
                continue;
            }
            if (!in) continue;
            String t = line.trim();
            if (t.startsWith("packageList={") && t.endsWith("}")) {
                pkgs = new ArrayList<>();
                for (String p : t.substring(13, t.length() - 1).split(",")) {
                    if (!p.trim().isEmpty()) pkgs.add(p.trim());
                }
            } else if (t.startsWith("lastPss=")) {
                int end = t.indexOf(' ');
                kb = kilobytes(t.substring(8, end < 0 ? t.length() : end));
            } else if (t.startsWith("oom adj:")) {
                Matcher m = ADJ.matcher(t);
                if (m.find()) adj = Integer.parseInt(m.group(1));
            } else if (t.startsWith("killed=true") || t.contains("killedByAm=true")) {
                dead = true;
            }
        }
        if (in && !dead && pkgs != null) add(out, pkgs, kb, adj);
        return out;
    }

    private static void add(Map<String, App> out, List<String> pkgs, long kb, int adj) {
        for (String pkg : pkgs) {
            App a = out.get(pkg);
            if (a == null) out.put(pkg, a = new App(pkg));
            a.kb += kb;
            a.adj = Math.min(a.adj, adj);
        }
    }

    private static int indent(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        return i == line.length() ? 0 : i;   // a blank line ends a block too
    }

    /** Android's own way of writing a size ("542MB", "8.7MB", "0.00", "1,2GB" in some languages) in kB. */
    static long kilobytes(String s) {
        int i = 0;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == ',')) i++;
        double v;
        try {
            v = Double.parseDouble(s.substring(0, i).replace(',', '.'));
        } catch (NumberFormatException e) {
            return 0;
        }
        String unit = s.substring(i).trim().toUpperCase(Locale.ROOT);
        switch (unit) {
            case "KB": return Math.round(v);
            case "MB": return Math.round(v * 1024);
            case "GB": return Math.round(v * 1024 * 1024);
            case "TB": return Math.round(v * 1024 * 1024 * 1024);
            default: return Math.round(v / 1024);   // bytes
        }
    }

    /** A size as people read it, counted as Android's dump counts (1024): "542 MB", "1.8 GB". */
    static String size(long kb) {
        double mb = kb / 1024.0;
        if (mb >= 1000) return String.format(Locale.ROOT, "%.1f GB", mb / 1024);
        return String.format(Locale.ROOT, "%d MB", Math.max(1, Math.round(mb)));
    }

    /** The Settings item's line: how many apps run and the memory free, "3 apps running, 1.1 GB of 1.8 GB memory free". */
    static String line(Context ctx, Snapshot s) {
        if (s == null) {
            ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
            ctx.getSystemService(ActivityManager.class).getMemoryInfo(mem);
            return ctx.getString(R.string.xmb_running_ram, size(mem.availMem / 1024), size(mem.totalMem / 1024));
        }
        String ram = ctx.getString(R.string.xmb_running_ram, size(s.freeKb), size(s.totalKb));
        if (s.apps == null) return XmbCatalog.join(ctx, ram, ctx.getString(R.string.xmb_running_no_access));
        String apps = s.apps.isEmpty() ? ctx.getString(R.string.xmb_running_none)
                : ctx.getResources().getQuantityString(R.plurals.xmb_running_count, s.apps.size(), s.apps.size());
        return XmbCatalog.join(ctx, apps, ram);
    }
}
