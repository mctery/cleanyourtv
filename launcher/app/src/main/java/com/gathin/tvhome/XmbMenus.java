package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The XMB's categories and their order: the five built-in ones and the owner's own (Games, Tools, Social...), each
 * with a label and an icon of its choice. Made on the TV (Settings > Menus) or listed in config.xml / a theme as
 * xmb_menus, "key:label:icon". A list edited on the TV is kept in the settings and wins; a menu the theme adds
 * later still appears, unless it was removed on the TV.
 *
 * Some keys are presets that also collect apps by themselves ({@link #PRESETS}): games takes every app that says
 * it is a game, social the social apps, and so on, from the app's own category and a few known package names.
 */
final class XmbMenus {
    static final String PREF_LIST = "xmb_menu_list";
    static final String PREF_REMOVED = "xmb_menu_removed";
    static final List<String> BUILT_IN = Collections.unmodifiableList(Arrays.asList(
            XmbCatalog.SETTINGS, XmbCatalog.VIDEO, XmbCatalog.MUSIC, XmbCatalog.APPS, XmbCatalog.NETWORK));

    /** The icons a menu can take, by Material Symbols name, in the order the picker shows them. */
    static final Map<String, Integer> ICONS = new LinkedHashMap<>();

    static {
        ICONS.put("sports_esports", R.drawable.ic_xmb_sports_esports);
        ICONS.put("stadia_controller", R.drawable.ic_xmb_stadia_controller);
        ICONS.put("joystick", R.drawable.ic_xmb_joystick);
        ICONS.put("handyman", R.drawable.ic_xmb_handyman);
        ICONS.put("build", R.drawable.ic_xmb_build);
        ICONS.put("forum", R.drawable.ic_xmb_forum);
        ICONS.put("groups", R.drawable.ic_xmb_groups);
        ICONS.put("chat", R.drawable.ic_xmb_chat);
        ICONS.put("sports_soccer", R.drawable.ic_xmb_sports_soccer);
        ICONS.put("sports_basketball", R.drawable.ic_xmb_sports_basketball);
        ICONS.put("fitness_center", R.drawable.ic_xmb_fitness_center);
        ICONS.put("newspaper", R.drawable.ic_xmb_newspaper);
        ICONS.put("toys", R.drawable.ic_xmb_toys);
        ICONS.put("child_care", R.drawable.ic_xmb_child_care);
        ICONS.put("school", R.drawable.ic_xmb_school);
        ICONS.put("menu_book", R.drawable.ic_xmb_menu_book);
        ICONS.put("shopping_bag", R.drawable.ic_xmb_shopping_bag);
        ICONS.put("photo_library", R.drawable.ic_xmb_photo_library);
        ICONS.put("live_tv", R.drawable.ic_xmb_live_tv);
        ICONS.put("movie", R.drawable.ic_xmb_movie);
        ICONS.put("theaters", R.drawable.ic_xmb_theaters);
        ICONS.put("music_note", R.drawable.ic_xmb_music_note);
        ICONS.put("headphones", R.drawable.ic_xmb_headphones);
        ICONS.put("radio", R.drawable.ic_xmb_radio);
        ICONS.put("podcasts", R.drawable.ic_xmb_podcasts);
        ICONS.put("apps", R.drawable.ic_xmb_apps);
        ICONS.put("star", R.drawable.ic_xmb_star);
        ICONS.put("favorite", R.drawable.ic_xmb_favorite);
        ICONS.put("home", R.drawable.ic_xmb_home);
        ICONS.put("restaurant", R.drawable.ic_xmb_restaurant);
        ICONS.put("travel_explore", R.drawable.ic_xmb_travel_explore);
        ICONS.put("language", R.drawable.ic_xmb_language);
        ICONS.put("folder", R.drawable.ic_xmb_folder);
        ICONS.put("mic", R.drawable.ic_xmb_mic);
        ICONS.put("pets", R.drawable.ic_xmb_pets);
        ICONS.put("rocket_launch", R.drawable.ic_xmb_rocket_launch);
        ICONS.put("auto_awesome", R.drawable.ic_xmb_auto_awesome);
        ICONS.put("settings", R.drawable.ic_xmb_settings);
        ICONS.put("wifi", R.drawable.ic_xmb_wifi);
        ICONS.put("tv", R.drawable.ic_xmb_tv);
    }

    /** A menu that collects apps by itself: by the app's own category, and by package name (a prefix ending in '.'). */
    static final class Preset {
        final String key;
        final int label;
        final String icon;
        final int[] appCategories;
        final String[] packages;

        Preset(String key, int label, String icon, int[] appCategories, String... packages) {
            this.key = key;
            this.label = label;
            this.icon = icon;
            this.appCategories = appCategories;
            this.packages = packages;
        }

        boolean takesPackage(String pkg) {
            for (String p : packages) if (p.endsWith(".") ? pkg.startsWith(p) : pkg.equals(p)) return true;
            return false;
        }

        boolean takesCategory(int appCategory, boolean game) {
            for (int c : appCategories) if (c == appCategory || (c == ApplicationInfo.CATEGORY_GAME && game)) return true;
            return false;
        }
    }

    static final List<Preset> PRESETS = Collections.unmodifiableList(Arrays.asList(
            new Preset("games", R.string.xmb_menu_games, "sports_esports", new int[]{ApplicationInfo.CATEGORY_GAME}),
            new Preset("tools", R.string.xmb_menu_tools, "handyman", new int[]{ApplicationInfo.CATEGORY_PRODUCTIVITY},
                    "com.android.vending", "com.internet.tvbrowser", "com.phlox.tvwebbrowser", "org.mozilla.tv.",
                    "com.opera.", "com.anydesk.", "com.teamviewer.", "com.estrongs.", "com.alphainventor.",
                    "org.zwanoo.android.speedtest", "com.ookla.", "com.android.chrome", "com.cxinventor.file.explorer"),
            new Preset("social", R.string.xmb_menu_social, "forum", new int[]{ApplicationInfo.CATEGORY_SOCIAL},
                    "com.facebook.", "com.instagram.", "com.zhiliaoapp.musically", "com.tiktok.tv", "jp.naver.line.",
                    "com.twitter.", "com.discord", "com.reddit.", "com.twitch.", "tv.twitch."),
            new Preset("sports", R.string.xmb_menu_sports, "sports_soccer", new int[0], "com.espn.", "com.dazn",
                    "com.fifa.", "com.nba.", "com.nfl."),
            new Preset("news", R.string.xmb_menu_news, "newspaper", new int[]{ApplicationInfo.CATEGORY_NEWS},
                    "com.google.android.apps.magazines", "com.cnn.", "com.bbc.", "com.thairath.", "com.nationtv."),
            new Preset("kids", R.string.xmb_menu_kids, "toys", new int[0], "com.google.android.youtube.tvkids",
                    "com.pbs.", "com.nickelodeon.", "com.disney.disneyjunior"),
            new Preset("learn", R.string.xmb_menu_learn, "school", new int[0], "com.duolingo", "org.khanacademy.",
                    "com.ted."),
            new Preset("shopping", R.string.xmb_menu_shopping, "shopping_bag", new int[0], "com.lazada.",
                    "com.shopee."),
            new Preset("photos", R.string.xmb_menu_photos, "photo_library", new int[]{ApplicationInfo.CATEGORY_IMAGE},
                    "com.google.android.apps.photos", "com.mitv.gallery", "com.amazon.clouddrive.photos")));

    static Preset preset(String key) {
        for (Preset p : PRESETS) if (p.key.equals(key)) return p;
        return null;
    }

    static final class Menu {
        final String key;
        /** The owner's label, or null for a built-in menu's own (translated) label. */
        final String label;
        /** An {@link #ICONS} name, or null for a built-in menu's own icon. */
        final String icon;

        Menu(String key, String label, String icon) {
            this.key = key;
            this.label = label == null || label.trim().isEmpty() ? null : label.trim();
            this.icon = icon == null || !ICONS.containsKey(icon) ? null : icon;
        }

        boolean builtIn() {
            return BUILT_IN.contains(key);
        }

        /** Apps can live here: everything but Settings and Network. */
        boolean holdsApps() {
            return !XmbCatalog.SETTINGS.equals(key) && !XmbCatalog.NETWORK.equals(key);
        }
    }

    private XmbMenus() {
    }

    /** "key:label:icon" (label and icon optional), or null when the key is not a plain lower-case word. */
    static Menu parse(String entry) {
        if (entry == null) return null;
        String[] p = entry.split(":", 3);
        String key = p[0].trim();
        if (!key.matches("[a-z][a-z0-9_]{0,23}")) return null;
        return new Menu(key, p.length > 1 ? p[1] : null, p.length > 2 ? p[2].trim() : null);
    }

    static String encode(List<Menu> menus) {
        JSONArray a = new JSONArray();
        for (Menu m : menus) {
            JSONObject o = new JSONObject();
            try {
                o.put("k", m.key);
                if (m.label != null) o.put("l", m.label);
                if (m.icon != null) o.put("i", m.icon);
            } catch (org.json.JSONException ignored) {
            }
            a.put(o);
        }
        return a.toString();
    }

    static List<Menu> decode(String json) {
        if (json == null) return null;
        try {
            JSONArray a = new JSONArray(json);
            List<Menu> out = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Menu m = parse(o.optString("k", ""));
                if (m == null || !keys.add(m.key)) continue;
                out.add(new Menu(m.key, o.optString("l", null), o.optString("i", null)));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The menus in cross-bar order: the list saved on the TV if there is one (plus any theme menu added since and
     * not removed on the TV, before Network), else the built-in ones with the theme's before Network. Every built-in
     * menu is always there.
     */
    static List<Menu> load(Context ctx, List<String> configured) {
        SharedPreferences prefs = prefs(ctx);
        List<Menu> saved = decode(prefs.getString(PREF_LIST, null));
        Set<String> removed = new HashSet<>(prefs.getStringSet(PREF_REMOVED, Collections.emptySet()));
        return merge(saved, configured, removed);
    }

    static List<Menu> merge(List<Menu> saved, List<String> configured, Set<String> removed) {
        List<Menu> out = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        if (saved != null) {
            for (Menu m : saved) if (keys.add(m.key)) out.add(m);
        } else {
            for (String k : BUILT_IN) if (!XmbCatalog.NETWORK.equals(k) && keys.add(k)) out.add(new Menu(k, null, null));
        }
        for (String entry : configured) {
            Menu m = parse(entry);
            if (m == null || removed.contains(m.key) || !keys.add(m.key)) continue;
            Preset p = preset(m.key);
            if (m.icon == null && p != null) m = new Menu(m.key, m.label, p.icon);
            out.add(networkIndex(out), m);
        }
        for (String k : BUILT_IN) if (keys.add(k)) out.add(XmbCatalog.NETWORK.equals(k) ? out.size() : networkIndex(out), new Menu(k, null, null));
        return out;
    }

    private static int networkIndex(List<Menu> menus) {
        for (int i = 0; i < menus.size(); i++) if (XmbCatalog.NETWORK.equals(menus.get(i).key)) return i;
        return menus.size();
    }

    static void save(Context ctx, List<Menu> menus) {
        prefs(ctx).edit().putString(PREF_LIST, encode(menus)).apply();
    }

    /** Removes a menu of the owner's; its apps go back where they would be without it. */
    static void remove(Context ctx, List<Menu> menus, String key) {
        List<Menu> next = new ArrayList<>();
        for (Menu m : menus) if (!m.key.equals(key) || m.builtIn()) next.add(m);
        Set<String> removed = new HashSet<>(prefs(ctx).getStringSet(PREF_REMOVED, Collections.emptySet()));
        removed.add(key);
        prefs(ctx).edit().putString(PREF_LIST, encode(next)).putStringSet(PREF_REMOVED, removed).apply();
    }

    /** A key for a new menu: the preset's, or "menu2", "menu3"... */
    static String newKey(List<Menu> menus, String wanted) {
        Set<String> keys = new HashSet<>();
        for (Menu m : menus) keys.add(m.key);
        if (wanted != null && !keys.contains(wanted)) return wanted;
        for (int i = 1; ; i++) if (!keys.contains("menu" + i)) return "menu" + i;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }
}
