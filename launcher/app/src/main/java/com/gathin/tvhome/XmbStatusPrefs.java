package com.gathin.tvhome;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the status row shows and in which order: each item always, only when there is something to say, or never,
 * plus a few ready-made looks (presets) and whether the clock and the row stay on screen while the menu has faded
 * to the wave. Set on the TV (Settings > Status bar) or in config.xml / a theme as xmb_status, "key=mode" in order;
 * a setup made on the TV wins. Pure data, tested on the JVM (XmbStatusPrefsTest).
 */
final class XmbStatusPrefs {
    static final String ALWAYS = "always", AUTO = "auto", HIDDEN = "hidden";
    static final String STANDARD = "standard", SIMPLE = "simple", FULL = "full", CLOCK = "clock", CUSTOM = "custom";
    static final List<String> PRESETS = Collections.unmodifiableList(Arrays.asList(STANDARD, SIMPLE, FULL, CLOCK));
    static final String PREF = "xmb_status";

    /** Something that needs a hand (amber), something going on, or plain information. Changes of kind get a wider gap. */
    static final int WARN = 0, BUSY = 1, INFO = 2;

    static final class Item {
        final String key;
        final int kind;
        /** The modes it can take, the first its default. */
        final List<String> modes;

        Item(String key, int kind, String... modes) {
            this.key = key;
            this.kind = kind;
            this.modes = Collections.unmodifiableList(Arrays.asList(modes));
        }
    }

    /** Every item, in the default order (left to right, the clock to the right of the last). */
    static final List<Item> ITEMS = Collections.unmodifiableList(Arrays.asList(
            new Item("recording", WARN, AUTO),
            new Item("battery", WARN, AUTO, ALWAYS, HIDDEN),
            new Item("storage", WARN, AUTO, ALWAYS, HIDDEN),
            new Item("adb", WARN, AUTO, HIDDEN),
            new Item("helper", WARN, AUTO, HIDDEN),
            new Item("download", BUSY, AUTO, HIDDEN),
            new Item("usb", BUSY, AUTO, HIDDEN),
            new Item("audio", BUSY, AUTO, HIDDEN),
            new Item("temperature", INFO, HIDDEN, AUTO, ALWAYS),
            new Item("weather", INFO, ALWAYS, HIDDEN),
            new Item("air", INFO, ALWAYS, AUTO, HIDDEN),
            new Item("speed", INFO, AUTO, ALWAYS, HIDDEN),
            new Item("network", INFO, ALWAYS, HIDDEN),
            new Item("palette", INFO, ALWAYS, HIDDEN)));

    static Item item(String key) {
        for (Item i : ITEMS) if (i.key.equals(key)) return i;
        return null;
    }

    final List<String> order;
    final Map<String, String> modes;
    final String preset;
    /** The clock and the row stay (dimmed) while the menu has faded out to the wave. */
    final boolean keepOnIdle;

    XmbStatusPrefs(List<String> order, Map<String, String> modes, String preset, boolean keepOnIdle) {
        this.order = Collections.unmodifiableList(complete(order));
        Map<String, String> m = new HashMap<>();
        for (Item i : ITEMS) {
            String want = modes.get(i.key);
            m.put(i.key, want != null && i.modes.contains(want) ? want : i.modes.get(0));
        }
        this.modes = Collections.unmodifiableMap(m);
        this.preset = preset;
        this.keepOnIdle = keepOnIdle;
    }

    String mode(String key) {
        String m = modes.get(key);
        return m != null ? m : HIDDEN;
    }

    /**
     * The order given, unknown keys dropped, and each missing key put in before the first item that comes after it
     * in the default order (so the items given stay side by side), or at the end.
     */
    static List<String> complete(List<String> order) {
        List<String> out = new ArrayList<>();
        for (String k : order) if (item(k) != null && !out.contains(k)) out.add(k);
        for (int i = 0; i < ITEMS.size(); i++) {
            String k = ITEMS.get(i).key;
            if (out.contains(k)) continue;
            int at = out.size();
            for (int j = 0; j < out.size(); j++) {
                if (defaultIndex(out.get(j)) > i) {
                    at = j;
                    break;
                }
            }
            out.add(at, k);
        }
        return out;
    }

    private static int defaultIndex(String key) {
        for (int i = 0; i < ITEMS.size(); i++) if (ITEMS.get(i).key.equals(key)) return i;
        return ITEMS.size();
    }

    static List<String> defaultOrder() {
        List<String> out = new ArrayList<>();
        for (Item i : ITEMS) out.add(i.key);
        return out;
    }

    /** A ready-made look: standard (as shipped), simple (connection and warnings), full (everything with values), clock (nothing). */
    static XmbStatusPrefs preset(String name, List<String> order, boolean keepOnIdle) {
        Map<String, String> m = new HashMap<>();
        for (Item i : ITEMS) {
            String mode = i.modes.get(0);
            switch (name) {
                case SIMPLE:
                    if (i.kind == INFO && !i.key.equals("network")) mode = HIDDEN;
                    break;
                case FULL:
                    mode = i.modes.contains(ALWAYS) ? ALWAYS : i.modes.contains(AUTO) ? AUTO : mode;
                    break;
                case CLOCK:
                    mode = i.key.equals("recording") ? AUTO : HIDDEN;
                    break;
                default:
                    break;
            }
            m.put(i.key, mode);
        }
        return new XmbStatusPrefs(order, m, PRESETS.contains(name) ? name : STANDARD, keepOnIdle);
    }

    /** The next preset round (a custom setup goes to the first). */
    XmbStatusPrefs nextPreset() {
        int at = PRESETS.indexOf(preset);
        return preset(PRESETS.get((at + 1) % PRESETS.size()), order, keepOnIdle);
    }

    /** The item's next mode round; the setup is then custom unless it matches a preset. */
    XmbStatusPrefs nextMode(String key) {
        Item i = item(key);
        if (i == null || i.modes.size() < 2) return this;
        Map<String, String> m = new HashMap<>(modes);
        m.put(key, i.modes.get((i.modes.indexOf(mode(key)) + 1) % i.modes.size()));
        return new XmbStatusPrefs(order, m, matchingPreset(m), keepOnIdle);
    }

    XmbStatusPrefs moved(String key, int by) {
        List<String> o = new ArrayList<>(order);
        int at = o.indexOf(key), to = at + by;
        if (at < 0 || to < 0 || to >= o.size()) return this;
        o.add(to, o.remove(at));
        return new XmbStatusPrefs(o, modes, preset, keepOnIdle);
    }

    /** The item moved just past another (before it going up, after it going down), skipping any in between. */
    XmbStatusPrefs movedPast(String key, String other, int by) {
        List<String> o = new ArrayList<>(order);
        if (!o.remove(key) || !o.contains(other)) return this;
        o.add(o.indexOf(other) + (by > 0 ? 1 : 0), key);
        return new XmbStatusPrefs(o, modes, preset, keepOnIdle);
    }

    XmbStatusPrefs withKeepOnIdle(boolean keep) {
        return new XmbStatusPrefs(order, modes, preset, keep);
    }

    private String matchingPreset(Map<String, String> m) {
        for (String p : PRESETS) if (preset(p, order, keepOnIdle).modes.equals(m)) return p;
        return CUSTOM;
    }

    // ---- stored as text: "v1;preset;keep;key=mode,key=mode,..." in order --------------------------------------

    String encode() {
        StringBuilder sb = new StringBuilder("v1;").append(preset).append(';').append(keepOnIdle ? 1 : 0).append(';');
        for (int i = 0; i < order.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(order.get(i)).append('=').append(mode(order.get(i)));
        }
        return sb.toString();
    }

    static XmbStatusPrefs decode(String s) {
        if (s == null) return null;
        String[] p = s.split(";", -1);
        if (p.length != 4 || !p[0].equals("v1")) return null;
        Map<String, String> modes = new LinkedHashMap<>();
        for (String e : p[3].split(",")) {
            int eq = e.indexOf('=');
            if (eq > 0) modes.put(e.substring(0, eq), e.substring(eq + 1));
        }
        String preset = PRESETS.contains(p[1]) || CUSTOM.equals(p[1]) ? p[1] : CUSTOM;
        return new XmbStatusPrefs(new ArrayList<>(modes.keySet()), modes, preset, "1".equals(p[2]));
    }

    /** From config.xml / a theme: "key=mode" entries in order; empty means the standard row. */
    static XmbStatusPrefs fromConfig(List<String> entries) {
        if (entries == null || entries.isEmpty()) return preset(STANDARD, defaultOrder(), false);
        Map<String, String> modes = new LinkedHashMap<>();
        for (String e : entries) {
            int eq = e.indexOf('=');
            String k = eq > 0 ? e.substring(0, eq).trim() : e.trim();
            modes.put(k, eq > 0 ? e.substring(eq + 1).trim() : null);
        }
        Map<String, String> clean = new HashMap<>();
        for (Map.Entry<String, String> e : modes.entrySet()) if (e.getValue() != null) clean.put(e.getKey(), e.getValue());
        XmbStatusPrefs p = new XmbStatusPrefs(new ArrayList<>(modes.keySet()), clean, CUSTOM, false);
        return new XmbStatusPrefs(p.order, p.modes, p.matchingPreset(p.modes), false);
    }

    static XmbStatusPrefs load(Context ctx, List<String> configured) {
        XmbStatusPrefs saved = decode(Config.settings(ctx).getString(PREF, null));
        return saved != null ? saved : fromConfig(configured);
    }

    void save(Context ctx) {
        Config.settings(ctx).edit().putString(PREF, encode()).apply();
    }
}
