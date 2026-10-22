package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;

import java.net.Inet4Address;
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
 * What the XMB home shows: five categories (Settings, Video, Music, Apps, Network) and their items. TV apps go in
 * Video, Music or Apps by {@link #categoryFor}; Settings and Network hold built-in items. Built fresh on every
 * reload; what it keeps is the owner's own moves (SharedPreferences, {@link #PREF_MOVES}) and the decoded app art
 * ({@link #ART}), so coming back to Home does not decode every banner again.
 */
final class XmbCatalog {
    static final String SETTINGS = "settings", VIDEO = "video", MUSIC = "music", APPS = "apps", NETWORK = "network";
    /** The categories an app can be moved to. */
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
        /** The app, for app items; null for built-in ones. */
        final String pkg;
        /** What OK opens; null when {@link #action} runs instead. */
        final Intent intent;
        final String action;

        Item(String key, String title, String detail, Drawable banner, Drawable icon, boolean glyph, String pkg,
             Intent intent, String action) {
            this.key = key;
            this.title = title;
            this.detail = detail;
            this.banner = banner;
            this.icon = icon;
            this.glyph = glyph;
            this.pkg = pkg;
            this.intent = intent;
            this.action = action;
        }
    }

    static final class Category {
        final String key;
        final String label;
        final Drawable icon;
        final List<Item> items;

        Category(String key, String label, Drawable icon, List<Item> items) {
            this.key = key;
            this.label = label;
            this.icon = icon;
            this.items = items;
        }
    }

    /** Built-in actions run by XmbActivity. */
    static final String ACTION_TOGGLE_SOUNDS = "toggle_sounds";
    static final String ACTION_CLASSIC_HOME = "classic_home";

    private XmbCatalog() {
    }

    /**
     * The category an app goes in: the owner's choice first ("package=category" entries, later ones win), then the
     * known lists, then the app's own android:appCategory, else Apps.
     */
    static String categoryFor(String pkg, int appCategory, List<String> overrides) {
        String chosen = null;
        for (String entry : overrides) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String cat = entry.substring(eq + 1).trim();
            if (entry.substring(0, eq).trim().equals(pkg) && APP_CATEGORIES.contains(cat)) chosen = cat;
        }
        if (chosen != null) return chosen;
        if (VIDEO_PACKAGES.contains(pkg)) return VIDEO;
        for (String prefix : VIDEO_PREFIXES) if (pkg.startsWith(prefix)) return VIDEO;
        if (MUSIC_PACKAGES.contains(pkg)) return MUSIC;
        if (appCategory == ApplicationInfo.CATEGORY_VIDEO) return VIDEO;
        if (appCategory == ApplicationInfo.CATEGORY_AUDIO) return MUSIC;
        return APPS;
    }

    /** The owner's moves from the options menu, as "package=category". */
    static List<String> moves(Context ctx) {
        return new ArrayList<>(prefs(ctx).getStringSet(PREF_MOVES, Collections.emptySet()));
    }

    static void move(Context ctx, String pkg, String category) {
        Set<String> next = new LinkedHashSet<>();
        for (String e : prefs(ctx).getStringSet(PREF_MOVES, Collections.emptySet())) {
            if (!e.startsWith(pkg + "=")) next.add(e);
        }
        next.add(pkg + "=" + category);
        prefs(ctx).edit().putStringSet(PREF_MOVES, next).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
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

    private static int iconRes(String category) {
        switch (category) {
            case SETTINGS: return R.drawable.ic_xmb_settings;
            case VIDEO: return R.drawable.ic_xmb_movie;
            case MUSIC: return R.drawable.ic_xmb_music_note;
            case APPS: return R.drawable.ic_xmb_apps;
            default: return R.drawable.ic_xmb_wifi;
        }
    }

    /** Every category with at least one item, in cross-bar order. */
    static List<Category> load(Context ctx, Config cfg, boolean soundsAvailable, boolean soundsOn) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, List<Item>> byCat = new HashMap<>();
        for (String c : new String[]{SETTINGS, VIDEO, MUSIC, APPS, NETWORK}) byCat.put(c, new ArrayList<>());

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
            int appCategory = ri.activityInfo.applicationInfo != null ? ri.activityInfo.applicationInfo.category : -1;
            byCat.get(categoryFor(pkg, appCategory, overrides)).add(item);
        }
        ART.keySet().retainAll(liveArt);
        // Same order as the tile grid: the owner's saved order, then dock_apps, then by name.
        for (String c : APP_CATEGORIES) {
            Collections.sort(byCat.get(c), (a, b) -> {
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
        settings.add(new Item("sys:sounds", ctx.getString(soundsOn ? R.string.xmb_sounds_on : R.string.xmb_sounds_off),
                ctx.getString(soundsAvailable ? R.string.xmb_sounds_detail : R.string.xmb_sounds_missing), null,
                ctx.getDrawable(soundsOn ? R.drawable.ic_xmb_volume_up : R.drawable.ic_xmb_volume_off), true, null, null,
                ACTION_TOGGLE_SOUNDS));
        settings.add(new Item("sys:classic", ctx.getString(R.string.xmb_classic_home),
                ctx.getString(R.string.xmb_classic_home_detail), null, ctx.getDrawable(R.drawable.ic_xmb_grid_view), true,
                null, null, ACTION_CLASSIC_HOME));

        addNetwork(ctx, pm, settingsPkg, byCat.get(NETWORK));

        List<Category> out = new ArrayList<>();
        for (String c : new String[]{SETTINGS, VIDEO, MUSIC, APPS, NETWORK}) {
            List<Item> items = byCat.get(c);
            if (!items.isEmpty()) out.add(new Category(c, label(ctx, c), ctx.getDrawable(iconRes(c)), items));
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
        return new Item(key, ctx.getString(title), ctx.getString(detail), null, ctx.getDrawable(glyph), true, null,
                intent, null);
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
        if (settingsPkg != null) {
            Intent pinned = new Intent(i).setPackage(settingsPkg);
            if (pm.resolveActivity(pinned, 0) != null) return pinned;
        }
        if (pm.resolveActivity(i, 0) != null || fallback == null) return i;
        return settingsIntent(pm, settingsPkg, fallback, null);
    }

    /** App info for one app, in the TV's Settings app. */
    static Intent appInfoIntent(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String settingsPkg = settingsPackage(pm);
        if (settingsPkg != null) {
            Intent pinned = new Intent(i).setPackage(settingsPkg);
            if (pm.resolveActivity(pinned, 0) != null) return pinned;
        }
        return i;
    }

    /** The connection (network name and address) and the Private DNS in use. */
    private static void addNetwork(Context ctx, PackageManager pm, String settingsPkg, List<Item> out) {
        String title = ctx.getString(R.string.offline), detail = ctx.getString(R.string.xmb_not_connected);
        String dns = null;
        int glyph = R.drawable.ic_xmb_wifi_off;
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                LinkProperties lp = cm.getLinkProperties(net);
                String ip = ipv4(lp);
                detail = ip != null ? ctx.getString(R.string.xmb_connected, ip) : "";
                if (lp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && lp.isPrivateDnsActive()) {
                    dns = lp.getPrivateDnsServerName();
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    title = ctx.getString(R.string.ethernet);
                    glyph = R.drawable.ic_xmb_lan;
                    break;
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    title = ctx.getString(R.string.wifi);
                    glyph = R.drawable.ic_xmb_wifi;
                    WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                    String ssid = info != null ? info.getSSID() : null;
                    if (ssid != null) {
                        ssid = ssid.replace("\"", "").trim();
                        if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) title = ssid;
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        Intent wifi = settingsIntent(pm, settingsPkg, Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_SETTINGS);
        out.add(new Item("sys:network", title, detail, null, ctx.getDrawable(glyph), true, null, wifi, null));
        out.add(new Item("sys:dns", ctx.getString(R.string.xmb_private_dns),
                dns != null && !dns.isEmpty() ? dns : ctx.getString(R.string.xmb_private_dns_auto), null,
                ctx.getDrawable(R.drawable.ic_xmb_dns), true, null, wifi, null));
    }

    private static String ipv4(LinkProperties lp) {
        if (lp == null) return null;
        for (LinkAddress a : lp.getLinkAddresses()) {
            if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
        }
        return null;
    }
}
