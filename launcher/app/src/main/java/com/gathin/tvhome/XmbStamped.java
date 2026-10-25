package com.gathin.tvhome;

import android.content.Context;

/**
 * A few values the XMB keeps in its settings with the time they were read, as "value|value|...|millis": the
 * weather and the air, the remote's battery, the last speed test. Read back only whole: a different number of values
 * (an older build's) or a time that is not a number counts as nothing kept. Pure data, tested on the JVM.
 */
final class XmbStamped {
    final String[] values;
    final long time;

    XmbStamped(long time, Object... values) {
        this.time = time;
        this.values = new String[values.length];
        for (int i = 0; i < values.length; i++) this.values[i] = String.valueOf(values[i]);
    }

    /** Read within maxAgeMs of now, either way round (a clock set back is no reason to keep an old value). */
    boolean fresh(long now, long maxAgeMs) {
        return Math.abs(now - time) < maxAgeMs;
    }

    /** The value as a whole number, or the fallback when it is not one. */
    int number(int i, int fallback) {
        try {
            return Integer.parseInt(values[i]);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    String encode() {
        StringBuilder sb = new StringBuilder();
        for (String v : values) sb.append(v).append('|');
        return sb.append(time).toString();
    }

    /** The stamped values from encode(), or null unless there are exactly count of them and a time. */
    static XmbStamped decode(String s, int count) {
        if (s == null) return null;
        String[] p = s.split("\\|", -1);
        if (p.length != count + 1) return null;
        try {
            long time = Long.parseLong(p[count]);
            Object[] values = new Object[count];
            System.arraycopy(p, 0, values, 0, count);
            return new XmbStamped(time, values);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static XmbStamped load(Context ctx, String pref, int count) {
        return decode(Config.settings(ctx).getString(pref, null), count);
    }

    void save(Context ctx, String pref) {
        Config.settings(ctx).edit().putString(pref, encode()).apply();
    }
}
