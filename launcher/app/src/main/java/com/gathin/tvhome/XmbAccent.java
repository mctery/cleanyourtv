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
        int base = MONTH_COLORS[Math.floorMod(month, 12)];
        float f = timeFactor(hour);
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
