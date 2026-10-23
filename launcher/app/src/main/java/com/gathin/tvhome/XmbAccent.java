package com.gathin.tvhome;

/**
 * The XMB home's one accent colour: a hue per month that goes once round the colour wheel over the year,
 * a little brighter at midday and deeper at night, as on the PS3 and in KittiwanaNotify's admin (wave.js).
 * The wave takes the colour as it is; text and focus use {@link #floor}, which lifts the darkest months
 * until they stay readable.
 */
final class XmbAccent {
    static final int[] MONTH_COLORS = {
            0x7FB4E0, 0x46B6D4, 0x58C06A, 0x79C44E, 0xA6C63A, 0xE0B53E,
            0xE08A32, 0xE06632, 0xDF4F3E, 0xD6517A, 0x9A6CF0, 0x5F6FE0,
    };

    private XmbAccent() {
    }

    /** Brightness for the hour: 1.08 from 07:00 to 17:00, 0.82 from 22:00 to 06:00, 0.96 in between. */
    static float timeFactor(int hour) {
        if (hour >= 7 && hour < 17) return 1.08f;
        if (hour >= 22 || hour < 6) return 0.82f;
        return 0.96f;
    }

    /** The wave colour, 0xRRGGBB, for a month (0 = January) at an hour of the day. */
    static int colorFor(int month, int hour) {
        return scaled(MONTH_COLORS[Math.floorMod(month, 12)], timeFactor(hour));
    }

    /** The same for a colour setting ({@link #base}): the owner's colour, dimmed at night like the month's. */
    static int colorFor(String mode, int month, int hour) {
        return scaled(base(mode, month), timeFactor(hour));
    }

    static final String MONTH = "month";

    /**
     * The colour a setting names, before the time of day: "month" (or anything unreadable) is this month's colour,
     * "month1" to "month12" one month's colour for good, "#RRGGBB" the owner's own.
     */
    static int base(String mode, int month) {
        int fixed = fixedColor(mode);
        return fixed >= 0 ? fixed : MONTH_COLORS[Math.floorMod(month, 12)];
    }

    /** The colour a setting fixes, or -1 for "month" and anything unreadable. */
    static int fixedColor(String mode) {
        if (mode == null) return -1;
        String m = mode.trim();
        if (m.length() == 7 && m.charAt(0) == '#') {
            try {
                return Integer.parseInt(m.substring(1), 16);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        if (m.startsWith(MONTH) && m.length() > MONTH.length()) {
            try {
                int n = Integer.parseInt(m.substring(MONTH.length()));
                return n >= 1 && n <= 12 ? MONTH_COLORS[n - 1] : -1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    static String hex(int rgb) {
        return String.format(java.util.Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /** Saturation of a colour made with the picker: as strong as the month colours, never neon. */
    private static final float PICKED_SATURATION = 0.62f;

    /** A colour from the picker's hue (0..1, round the wheel) and brightness (0..1, dim to full). */
    static int fromHueBrightness(float hue, float brightness) {
        float h = (hue - (float) Math.floor(hue)) * 6f;
        float v = 0.35f + 0.65f * Math.max(0f, Math.min(1f, brightness));
        float c = v * PICKED_SATURATION, x = c * (1 - Math.abs(h % 2 - 1)), m = v - c;
        float r, g, b;
        switch ((int) h) {
            case 0: r = c; g = x; b = 0; break;
            case 1: r = x; g = c; b = 0; break;
            case 2: r = 0; g = c; b = x; break;
            case 3: r = 0; g = x; b = c; break;
            case 4: r = x; g = 0; b = c; break;
            default: r = c; g = 0; b = x; break;
        }
        return (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
    }

    /** The picker position nearest a colour: {hue, brightness}, so it starts from the colour in use. */
    static float[] hueBrightness(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h = 0;
        if (d > 0) {
            if (max == r) h = ((g - b) / d) % 6;
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6f;
            if (h < 0) h += 1;
        }
        return new float[]{h, Math.max(0f, Math.min(1f, (max - 0.35f) / 0.65f))};
    }

    private static int scaled(int base, float f) {
        int r = Math.min(255, Math.round(((base >> 16) & 0xFF) * f));
        int g = Math.min(255, Math.round(((base >> 8) & 0xFF) * f));
        int b = Math.min(255, Math.round((base & 0xFF) * f));
        return (r << 16) | (g << 8) | b;
    }

    /** WCAG relative luminance of 0xRRGGBB. */
    static double luminance(int rgb) {
        return 0.2126 * channel((rgb >> 16) & 0xFF) + 0.7152 * channel((rgb >> 8) & 0xFF) + 0.0722 * channel(rgb & 0xFF);
    }

    private static double channel(int c) {
        double v = c / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** The colour moved 10% towards white at a time until its luminance reaches 0.25 (at most 32 steps). */
    static int floor(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        for (int guard = 0; guard < 32 && luminance((r << 16) | (g << 8) | b) < 0.25; guard++) {
            r = Math.min(255, Math.round(r + (255 - r) * 0.1f));
            g = Math.min(255, Math.round(g + (255 - g) * 0.1f));
            b = Math.min(255, Math.round(b + (255 - b) * 0.1f));
        }
        return (r << 16) | (g << 8) | b;
    }
}
