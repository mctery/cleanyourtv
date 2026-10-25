package com.gathin.tvhome;

import android.content.res.Resources;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What config.xml and a theme's config.json say about the XMB home, the xmb_* keys, read with the rest of
 * {@link Config}: where apps go, the owner's menus, the wave's colour, the status row, how long before the menu fades,
 * the warnings' thresholds, and the apps the launcher knows by name. A theme's value replaces config.xml's, a list
 * whole; a choice made on the TV wins over both where the TV has one.
 */
final class XmbConfig {
    /** "package=category" for apps the built-in choice puts in the wrong place (xmb_categories). */
    final List<String> categories;
    /** The owner's own categories, "key:label:icon" (xmb_menus). */
    final List<String> menus;
    /** The wave colour: "month", "month1".."month12" or "#RRGGBB" (xmb_color). */
    final String color;
    /** The status row, "key=mode" in order (xmb_status). */
    final List<String> status;
    /** Seconds without a key before the menu fades to the wave; 0 never (xmb_idle_seconds). */
    final int idleSeconds;
    /** The remote's battery is a warning under this many percent (xmb_low_battery). */
    final int lowBattery;
    /** Free storage is a warning under this many megabytes (xmb_low_storage_mb). */
    final int lowStorageMb;
    /** What the launcher knows about apps that do not say what they are (xmb_video_apps, xmb_music_apps, xmb_menu_apps). */
    final XmbCatalog.Known known;
    /** When the boot animation plays: POWER (the box started, or woke from standby), BOOT (it started) or OFF. */
    final String bootAnimation;
    static final String POWER = "power", BOOT = "boot", OFF = "off";

    XmbConfig(List<String> categories, List<String> menus, String color, List<String> status, int idleSeconds,
              int lowBattery, int lowStorageMb, XmbCatalog.Known known, String bootAnimation) {
        this.categories = Collections.unmodifiableList(categories);
        this.menus = Collections.unmodifiableList(menus);
        this.color = color;
        this.status = Collections.unmodifiableList(status);
        this.idleSeconds = idleSeconds;
        this.lowBattery = lowBattery;
        this.lowStorageMb = lowStorageMb;
        this.known = known;
        this.bootAnimation = BOOT.equals(bootAnimation) || OFF.equals(bootAnimation) ? bootAnimation : POWER;
    }

    static XmbConfig load(Resources res, JSONObject json) {
        List<String> categories = list(res, R.array.xmb_categories), menus = list(res, R.array.xmb_menus);
        List<String> status = list(res, R.array.xmb_status), video = list(res, R.array.xmb_video_apps);
        List<String> music = list(res, R.array.xmb_music_apps), menuApps = list(res, R.array.xmb_menu_apps);
        String color = res.getString(R.string.xmb_color);
        int idle = res.getInteger(R.integer.xmb_idle_seconds), battery = res.getInteger(R.integer.xmb_low_battery);
        int storage = res.getInteger(R.integer.xmb_low_storage_mb);
        String boot = res.getString(R.string.xmb_boot_animation);
        if (json != null) {
            categories = Config.stringList(json, "xmb_categories", categories);
            menus = Config.stringList(json, "xmb_menus", menus);
            color = Config.string(json, "xmb_color", color);
            status = Config.stringList(json, "xmb_status", status);
            idle = Config.integer(json, "xmb_idle_seconds", idle, 0, 3600);
            battery = Config.integer(json, "xmb_low_battery", battery, 0, 100);
            storage = Config.integer(json, "xmb_low_storage_mb", storage, 0, 100_000);
            video = Config.stringList(json, "xmb_video_apps", video);
            music = Config.stringList(json, "xmb_music_apps", music);
            menuApps = Config.stringList(json, "xmb_menu_apps", menuApps);
            boot = Config.string(json, "xmb_boot_animation", boot);
        }
        return new XmbConfig(categories, menus, color, status, Math.max(0, idle), Math.max(0, Math.min(100, battery)),
                Math.max(0, storage), new XmbCatalog.Known(video, music, menuApps), boot == null ? POWER : boot.trim());
    }

    private static List<String> list(Resources res, int id) {
        return new ArrayList<>(Arrays.asList(res.getStringArray(id)));
    }
}
