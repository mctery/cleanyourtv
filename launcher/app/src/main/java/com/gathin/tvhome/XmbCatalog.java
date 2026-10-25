package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the XMB home shows: the categories (Settings, Video, Music, Apps, Network and the owner's own, {@link XmbMenus})
 * and their items. TV apps go in Video, Music, Apps or one of the owner's menus by {@link #categoryFor}; Settings and
 * Network hold built-in items. Built fresh on every reload; what it keeps is the owner's own moves
 * (SharedPreferences, {@link #PREF_MOVES}) and the decoded app art ({@link #ART}), so coming back to Home does not
 * decode every banner again.
 */
final class XmbCatalog {
    static final String SETTINGS = "settings", VIDEO = "video", MUSIC = "music", APPS = "apps", NETWORK = "network";
    /** The built-in categories an app can be moved to; the owner's own menus come on top. */
    static final List<String> APP_CATEGORIES = Collections.unmodifiableList(Arrays.asList(VIDEO, MUSIC, APPS));
    static final String PREF_MOVES = "xmb_moves";

    /** Streaming and video apps that do not declare android:appCategory="video" themselves. */
    private static final Set<String> VIDEO_PACKAGES = new HashSet<>(Arrays.asList(
            "com.netflix.ninja", "com.google.android.youtube.tv", "com.google.android.youtube.tvkids",
            "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus", "com.wbd.stream",
            "com.ais.mimo.aisplay.tv", "com.beci.thaitv3android", "com.google.android.videos",
            "com.xiaomi.mitv.mediaexplorer", "com.mitv.videoplayer", "com.apple.atve.androidtv.appletv",
            "com.plexapp.android", "org.xbmc.kodi", "com.mxtech.videoplayer.ad", "com.android.tv"));
    private static final String[] VIDEO_PREFIXES = {"in.startv.hotstar", "com.hbo.", "com.amazon.amazonvideo."};
    private static final Set<String> MUSIC_PACKAGES = new HashSet<>(Arrays.asList(
            "com.google.android.youtube.tvmusic", "com.spotify.tv.android", "com.xiaomi.mimusic2",
            "com.apple.android.music", "com.tencent.ibg.joox", "deezer.android.tv", "com.aspiro.tidal"));

    /** An app's decoded art, shared between reloads through the drawables' constant states. */
    private static final class Art {
        final Drawable.ConstantState banner, icon;

        Art(Drawable banner, Drawable icon) {
            this.banner = banner != null ? banner.getConstantState() : null;
            this.icon = icon != null ? icon.getConstantState() : null;
        }

        /** False when a drawable could not be shared and has to be loaded each time. */
        boolean complete(boolean hadBanner, boolean hadIcon) {
            return (!hadBanner || banner != null) && (!hadIcon || icon != null);
        }
    }

    /**
     * Art by package, activity and APK path. The path changes with every install or update, so an updated app's new
     * banner is picked up; entries for apps that are gone are dropped at the next load.
     */
    private static final Map<String, Art> ART = new HashMap<>();

    /** Memory is short and Home is in the background: the art is decoded again on the next load. */
    static void trim() {
        ART.clear();
    }

    static final class Item {
        /** Stable identity for remembering the selection: the component, or "sys:<name>" for a built-in item. */
        final String key;
        final String title;
        final String detail;
        /** The app's 16:9 banner, or null (a glyph or an icon is drawn instead). */
        final Drawable banner;
        /** A white glyph to tint, or the app's own icon when it has neither banner nor glyph. */
        final Drawable icon;
        final boolean glyph;
        /** The glyph's drawable resource (its glow is kept by it, XmbGlow); 0 for an app's own art. */
        final int iconRes;
        /** The app, for app items; null for built-in ones. */
        final String pkg;
        /** What OK opens; null when {@link #action} runs instead. */
        final Intent intent;
        final String action;

        Item(String key, String title, String detail, Drawable banner, Drawable icon, boolean glyph, String pkg,
             Intent intent, String action) {
            this(key, title, detail, banner, icon, 0, pkg, intent, action);
        }

        /** A built-in item drawn with a glyph. */
        Item(String key, String title, String detail, Context ctx, int glyph, Intent intent, String action) {
            this(key, title, detail, null, ctx.getDrawable(glyph), glyph, null, intent, action);
        }

        private Item(String key, String title, String detail, Drawable banner, Drawable icon, int iconRes, String pkg,
                     Intent intent, String action) {
            this.key = key;
            this.title = title;
            this.detail = detail;
            this.banner = banner;
            this.icon = icon;
            this.glyph = iconRes != 0;
            this.iconRes = iconRes;
            this.pkg = pkg;
            this.intent = intent;
            this.action = action;
        }
    }

    static final class Category {
        final String key;
        final String label;
        final Drawable icon;
        /** The icon's resource, so two loads can be compared ({@link #signature}). */
        final int iconRes;
        final List<Item> items;

        Category(String key, String label, Drawable icon, int iconRes, List<Item> items) {
            this.key = key;
            this.label = label;
            this.icon = icon;
            this.iconRes = iconRes;
            this.items = items;
        }
    }

    /**
     * Everything a load puts on screen, as text: two loads with the same signature look the same, so Home need not
     * rebuild (a package broadcast for an app it does not show, say, would otherwise redraw the cross-bar).
     */
    static String signature(List<Category> cats) {
        StringBuilder sb = new StringBuilder();
        for (Category c : cats) {
            sb.append(c.key).append('|').append(c.label).append('|').append(c.iconRes).append('\n');
            for (Item i : c.items) {
                // An app's detail ("last opened ...") changes by the minute and is updated in place; a built-in
                // item's detail is a setting's value and counts.
                boolean live = i.pkg != null || SPEEDTEST.equals(i.key);
                sb.append(' ').append(i.key).append('|').append(i.title).append('|').append(live ? "" : i.detail)
                        .append('|').append(i.banner != null).append('|').append(i.action).append('\n');
            }
        }
        return sb.toString();
    }

    /** Built-in actions run by XmbActivity. */
    static final String ACTION_TOGGLE_SOUNDS = "toggle_sounds", ACTION_MUSIC = "music", ACTION_COLOR = "color",
            ACTION_MENUS = "menus", ACTION_STATUS = "status", ACTION_DIAG = "diagnostics", ACTION_SPEEDTEST = "speedtest";
    /** The speed test's item: its line is updated in place while it measures, not part of the signature. */
    static final String SPEEDTEST = "sys:speedtest";

    /** What the Settings category says about the XMB's own settings. */
    static final class SettingsState {
        boolean soundsAvailable, soundsOn, musicAvailable;
        int musicLevel;
        String colorName;
    }

    private XmbCatalog() {
    }

    /** The category for the built-in menus only (no menus of the owner's). */
    static String categoryFor(String pkg, int appCategory, List<String> overrides) {
        return categoryFor(pkg, appCategory, false, overrides, Collections.emptyList());
    }

    /**
     * The category an app goes in: the owner's choice first ("package=category" entries, later ones win, to a menu
     * that exists), then a preset menu of the owner's that names the package, then the known video and music apps,
     * then a preset menu that takes the app's own android:appCategory (games, social...), then that category's
     * built-in match (video, audio), else Apps.
     */
    static String categoryFor(String pkg, int appCategory, boolean game, List<String> overrides, List<String> menus) {
        String chosen = null;
        for (String entry : overrides) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String cat = entry.substring(eq + 1).trim();
            if (entry.substring(0, eq).trim().equals(pkg) && (APP_CATEGORIES.contains(cat) || menus.contains(cat))) {
                chosen = cat;
            }
        }
        if (chosen != null) return chosen;
        for (String m : menus) {
            XmbMenus.Preset p = XmbMenus.preset(m);
            if (p != null && p.takesPackage(pkg)) return m;
        }
        if (VIDEO_PACKAGES.contains(pkg)) return VIDEO;
        for (String prefix : VIDEO_PREFIXES) if (pkg.startsWith(prefix)) return VIDEO;
        if (MUSIC_PACKAGES.contains(pkg)) return MUSIC;
        for (String m : menus) {
            XmbMenus.Preset p = XmbMenus.preset(m);
            if (p != null && p.takesCategory(appCategory, game)) return m;
        }
        if (appCategory == ApplicationInfo.CATEGORY_VIDEO) return VIDEO;
        if (appCategory == ApplicationInfo.CATEGORY_AUDIO) return MUSIC;
        return APPS;
    }

    /** The owner's moves from the options menu, as "package=category". */
    static List<String> moves(Context ctx) {
        return new ArrayList<>(Config.settings(ctx).getStringSet(PREF_MOVES, Collections.emptySet()));
    }

    static void move(Context ctx, String pkg, String category) {
        Set<String> next = new LinkedHashSet<>();
        for (String e : Config.settings(ctx).getStringSet(PREF_MOVES, Collections.emptySet())) {
            if (!e.startsWith(pkg + "=")) next.add(e);
        }
        next.add(pkg + "=" + category);
        Config.settings(ctx).edit().putStringSet(PREF_MOVES, next).apply();
    }


    static String label(Context ctx, String category) {
        switch (category) {
            case SETTINGS: return ctx.getString(R.string.xmb_cat_settings);
            case VIDEO: return ctx.getString(R.string.xmb_cat_video);
            case MUSIC: return ctx.getString(R.string.xmb_cat_music);
            case APPS: return ctx.getString(R.string.xmb_cat_apps);
            default: return ctx.getString(R.string.xmb_cat_network);
        }
    }

    /** A menu's label: the owner's, else the built-in one's. */
    static String label(Context ctx, XmbMenus.Menu m) {
        return m.label != null ? m.label : m.builtIn() ? label(ctx, m.key) : m.key;
    }

    /** A menu's icon: the one chosen for it, else the built-in one's, else the preset's, else a star. */
    static int iconRes(XmbMenus.Menu m) {
        if (m.icon != null) return XmbMenus.ICONS.get(m.icon);
        if (m.builtIn()) return iconRes(m.key);
        XmbMenus.Preset p = XmbMenus.preset(m.key);
        return p != null ? XmbMenus.ICONS.get(p.icon) : R.drawable.ic_xmb_star;
    }

    private static int iconRes(String category) {
        switch (category) {
            case SETTINGS: return R.drawable.ic_xmb_settings;
            case VIDEO: return R.drawable.ic_xmb_movie;
            case MUSIC: return R.drawable.ic_xmb_music_note;
            case APPS: return R.drawable.ic_xmb_apps;
            default: return R.drawable.ic_xmb_wifi;
        }
    }

    /**
     * The categories in cross-bar order: every built-in one with at least one item, and every menu of the owner's,
     * an empty one with a line saying how to fill it.
     */
    static List<Category> load(Context ctx, Config cfg, List<XmbMenus.Menu> menus, SettingsState state) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, List<Item>> byCat = new HashMap<>();
        List<String> appMenus = new ArrayList<>();
        for (XmbMenus.Menu m : menus) {
            byCat.put(m.key, new ArrayList<>());
            if (m.holdsApps() && !m.builtIn()) appMenus.add(m.key);
        }

        List<String> overrides = new ArrayList<>(cfg.xmbCategories);
        overrides.addAll(moves(ctx));
        Set<String> hidden = new HashSet<>(cfg.hiddenApps);
        Map<String, Integer> ranks = Order.ranks(Order.load(Order.file(ctx)), cfg.dockApps);
        List<ResolveInfo> found = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        String settingsPkg = settingsPackage(pm);
        Set<String> seen = new HashSet<>();
        Set<String> liveArt = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null || !ri.activityInfo.exported) continue;
            String pkg = ri.activityInfo.packageName;
            // The launcher itself, apps the owner hid, and the TV's Settings app (the Settings category covers it).
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg) || pkg.equals(settingsPkg)) continue;
            if (!seen.add(pkg)) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            String label = Cards.cleanLabel(cfg.appLabels.containsKey(pkg) ? cfg.appLabels.get(pkg)
                    : String.valueOf(ri.loadLabel(pm)), pkg);
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            String artKey = pkg + '|' + ri.activityInfo.name + '|' + (ai != null ? ai.sourceDir : "") + '|'
                    + cfg.appBanners.get(pkg);
            liveArt.add(artKey);
            Drawable banner, icon;
            Art art = ART.get(artKey);
            if (art != null) {
                banner = art.banner != null ? art.banner.newDrawable() : null;
                icon = art.icon != null ? art.icon.newDrawable() : null;
            } else {
                banner = bundledBanner(ctx, cfg.appBanners.get(pkg));
                if (banner == null) banner = AppAdapter.loadBanner(ctx, pm, ri);
                icon = banner == null ? ri.loadIcon(pm) : null;
                withMipmaps(banner);
                art = new Art(banner, icon);
                if (art.complete(banner != null, icon != null)) ART.put(artKey, art);
            }
            long last = LastOpened.get(ctx, pkg);
            String detail = last > 0 ? ctx.getString(R.string.xmb_last_opened, LastOpened.describe(ctx, last)) : "";
            Intent launch = new Intent(Intent.ACTION_MAIN).setComponent(component)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            String action = cfg.launchActions.get(pkg);
            if (action != null) {
                Intent byAction = new Intent(action).setPackage(pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                if (pm.resolveActivity(byAction, 0) != null) launch = byAction;
            }
            Item item = new Item(component.flattenToShortString(), label, detail, banner, icon, false, pkg, launch, null);
            int appCategory = ai != null ? ai.category : -1;
            @SuppressWarnings("deprecation")
            boolean game = ai != null && (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
            byCat.get(categoryFor(pkg, appCategory, game, overrides, appMenus)).add(item);
        }
        ART.keySet().retainAll(liveArt);
        // Same order as the tile grid: the owner's saved order, then dock_apps, then by name.
        for (XmbMenus.Menu m : menus) {
            if (!m.holdsApps()) continue;
            Collections.sort(byCat.get(m.key), (a, b) -> {
                int saved = Order.compare(ranks, a.key, b.key);
                if (saved != 0) return saved;
                int fa = cfg.dockApps.indexOf(a.pkg), fb = cfg.dockApps.indexOf(b.pkg);
                if (fa >= 0 || fb >= 0) {
                    if (fa < 0) return 1;
                    if (fb < 0) return -1;
                    return Integer.compare(fa, fb);
                }
                return a.title.compareToIgnoreCase(b.title);
            });
        }

        List<Item> settings = byCat.get(SETTINGS);
        settings.add(builtIn(ctx, "sys:settings", R.string.xmb_tv_settings, R.string.xmb_tv_settings_detail,
                R.drawable.ic_xmb_settings, settingsIntent(pm, settingsPkg, Settings.ACTION_SETTINGS, null)));
        settings.add(builtIn(ctx, "sys:display", R.string.xmb_display, R.string.xmb_display_detail,
                R.drawable.ic_xmb_tv, settingsIntent(pm, settingsPkg, Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SETTINGS)));
        settings.add(builtIn(ctx, "sys:apps", R.string.xmb_apps, R.string.xmb_apps_detail,
                R.drawable.ic_xmb_apps, settingsIntent(pm, settingsPkg, Settings.ACTION_APPLICATION_SETTINGS, Settings.ACTION_SETTINGS)));
        settings.add(new Item("sys:sounds", ctx.getString(state.soundsOn ? R.string.xmb_sounds_on : R.string.xmb_sounds_off),
                ctx.getString(state.soundsAvailable ? R.string.xmb_sounds_detail : R.string.xmb_sounds_missing), ctx,
                state.soundsOn ? R.drawable.ic_xmb_volume_up : R.drawable.ic_xmb_volume_off, null, ACTION_TOGGLE_SOUNDS));
        int[] music = {R.string.xmb_bgm_off, R.string.xmb_bgm_low, R.string.xmb_bgm_mid, R.string.xmb_bgm_high};
        settings.add(new Item("sys:music", ctx.getString(music[state.musicAvailable ? state.musicLevel : 0]),
                ctx.getString(state.musicAvailable ? R.string.xmb_bgm_detail : R.string.xmb_bgm_missing), ctx,
                R.drawable.ic_xmb_library_music, null, ACTION_MUSIC));
        settings.add(new Item("sys:color", ctx.getString(R.string.xmb_theme_color), state.colorName, ctx,
                R.drawable.ic_xmb_palette, null, ACTION_COLOR));
        settings.add(new Item("sys:menus", ctx.getString(R.string.xmb_menus), ctx.getString(R.string.xmb_menus_detail), ctx,
                R.drawable.ic_xmb_dashboard_customize, null, ACTION_MENUS));
        settings.add(new Item("sys:status", ctx.getString(R.string.xmb_status_bar), ctx.getString(R.string.xmb_status_bar_detail),
                ctx, R.drawable.ic_xmb_tune, null, ACTION_STATUS));
        settings.add(new Item("sys:diagnostics", ctx.getString(R.string.xmb_diag), ctx.getString(R.string.xmb_diag_detail),
                ctx, R.drawable.ic_xmb_monitor_heart, null, ACTION_DIAG));

        addNetwork(ctx, pm, settingsPkg, byCat.get(NETWORK));

        List<Category> out = new ArrayList<>();
        for (XmbMenus.Menu m : menus) {
            List<Item> items = byCat.get(m.key);
            int icon = iconRes(m);
            if (items.isEmpty() && !m.builtIn()) {
                items.add(new Item("sys:empty", ctx.getString(R.string.xmb_menu_empty),
                        ctx.getString(R.string.xmb_menu_empty_detail), ctx, icon, null, null));
            }
            if (!items.isEmpty()) out.add(new Category(m.key, label(ctx, m), ctx.getDrawable(icon), icon, items));
        }
        return out;
    }

    /**
     * Banners are drawn shrunk (to about half their pixels in the rows under the selection); with mipmaps the GPU
     * filters them from a level near the drawn size, so their lettering stays clean instead of turning grainy.
     */
    private static void withMipmaps(Drawable d) {
        if (!(d instanceof BitmapDrawable)) return;
        Bitmap b = ((BitmapDrawable) d).getBitmap();
        if (b != null) b.setHasMipMap(true);
    }

    /** A banner_* drawable bundled with the launcher (config.xml app_banners), as on the tile grid; null for none. */
    private static Drawable bundledBanner(Context ctx, String name) {
        if (name == null || !name.startsWith("banner_")) return null;
        int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
        return id != 0 ? ctx.getDrawable(id) : null;
    }

    private static Item builtIn(Context ctx, String key, int title, int detail, int glyph, Intent intent) {
        return new Item(key, ctx.getString(title), ctx.getString(detail), ctx, glyph, intent, null);
    }

    /** The package that answers ACTION_SETTINGS (the TV's Settings app), or null. */
    private static String settingsPackage(PackageManager pm) {
        ResolveInfo ri = pm.resolveActivity(new Intent(Settings.ACTION_SETTINGS), 0);
        return ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : null;
    }

    /**
     * A Settings screen, sent to the TV's own Settings app when it answers it. Unpinned, some intents also resolve
     * in a work profile and Android asks which one to use (seen on the Mi Box S with a managed profile).
     */
    static Intent settingsIntent(PackageManager pm, String settingsPkg, String action, String fallback) {
        Intent i = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent pinned = pinned(pm, settingsPkg, i);
        if (pinned != null) return pinned;
        if (pm.resolveActivity(i, 0) != null || fallback == null) return i;
        return settingsIntent(pm, settingsPkg, fallback, null);
    }

    /** App info for one app, in the TV's Settings app. */
    static Intent appInfoIntent(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent pinned = pinned(pm, settingsPackage(pm), i);
        return pinned != null ? pinned : i;
    }

    /** The intent sent to the TV's own Settings app, or null when that app does not answer it. */
    private static Intent pinned(PackageManager pm, String settingsPkg, Intent i) {
        if (settingsPkg == null) return null;
        Intent pinned = new Intent(i).setPackage(settingsPkg);
        return pm.resolveActivity(pinned, 0) != null ? pinned : null;
    }

    /** A speed test's result as a line: the speed, the round trip and when, "48 Mb/s, ping 12 ms, 2 minutes ago". */
    static String speedResult(Context ctx, XmbSpeedTest.Result r) {
        StringBuilder sb = new StringBuilder(XmbStatus.rate(r.mbps));
        if (r.pingMs >= 0) sb.append(", ").append(ctx.getString(R.string.xmb_speedtest_ping, r.pingMs));
        if (r.time > 0) sb.append(", ").append(LastOpened.describe(ctx, r.time));
        return sb.toString();
    }

    /** The connection (network name and address), the speed test and the Private DNS in use. */
    private static void addNetwork(Context ctx, PackageManager pm, String settingsPkg, List<Item> out) {
        XmbNet net = XmbNet.read(ctx);
        String title, detail = net.ip != null ? ctx.getString(R.string.xmb_connected, net.ip) : "";
        int glyph;
        switch (net.kind) {
            case XmbNet.LAN:
                title = ctx.getString(R.string.ethernet);
                glyph = R.drawable.ic_xmb_lan;
                break;
            case XmbNet.WIFI:
                title = net.ssid != null ? net.ssid : ctx.getString(R.string.wifi);
                glyph = R.drawable.ic_xmb_wifi;
                break;
            case XmbNet.OTHER:
                title = ctx.getString(R.string.xmb_cat_network);
                glyph = R.drawable.ic_xmb_lan;
                break;
            default:
                title = ctx.getString(R.string.offline);
                detail = ctx.getString(R.string.xmb_not_connected);
                glyph = R.drawable.ic_xmb_wifi_off;
                break;
        }
        Intent wifi = settingsIntent(pm, settingsPkg, Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_SETTINGS);
        out.add(new Item("sys:network", title, detail, ctx, glyph, wifi, null));
        XmbSpeedTest.Result last = XmbSpeedTest.last(ctx);
        String speed = XmbSpeedTest.running() ? ctx.getString(R.string.xmb_speedtest_start)
                : last != null ? speedResult(ctx, last) : ctx.getString(R.string.xmb_speedtest_never);
        out.add(new Item(SPEEDTEST, ctx.getString(R.string.xmb_speedtest), speed, ctx, R.drawable.ic_xmb_speed, null,
                ACTION_SPEEDTEST));
        out.add(new Item("sys:dns", ctx.getString(R.string.xmb_private_dns),
                net.privateDns != null ? net.privateDns : ctx.getString(R.string.xmb_private_dns_auto), ctx,
                R.drawable.ic_xmb_dns, wifi, null));
    }
}
