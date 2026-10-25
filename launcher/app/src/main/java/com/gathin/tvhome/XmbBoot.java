package com.gathin.tvhome;

import static com.gathin.tvhome.XmbStyle.BOOT_COLOUR_AT_MS;
import static com.gathin.tvhome.XmbStyle.BOOT_COLOUR_MS;
import static com.gathin.tvhome.XmbStyle.BOOT_LINES_AT_MS;
import static com.gathin.tvhome.XmbStyle.BOOT_LINES_MS;

/**
 * The boot animation's timeline: where each of the wave's boot controls stands a number of milliseconds after its
 * first frame. XmbWave hands them to its shaders. Pure arithmetic, tested on the JVM (XmbBootTest).
 *
 * What makes it move like light rather than a wipe: every depth row of the wave has its own head (spread), so the
 * front is a soft fan; the heads start gently and glide to a stop (a Bézier curve, not a symmetric smoothstep); each
 * head carries its own light (tip) and leaves a trail that cools down; the lines swell with light as they meet the
 * right edge; the colour starts while the last lines are still arriving, rises like a dawn (exposure) as it spreads
 * through a wide soft edge, fast out of the middle and slowing towards the edges.
 */
final class XmbBoot {
    /** The lines' front in screen x (-1 left edge, 1 right edge) and how far back the last row's head runs. */
    float reveal, spread;
    /** The lines' light (1 as always), the light at each head, the extra light in the trail behind it. */
    float boost, tip, trail;
    /** The sparkles' light, the faint white haze behind the lines. */
    float spark, haze;
    /** How far the colour reaches from the middle band (past 1.2 everywhere) and how bright it has come up. */
    float bloom, expose;
    /** The light of the wave's other half of its rows (drawn every other row while the lines sweep, at 60 frames a
     *  second): 0, then fading in as the lines meet the right edge. */
    float rows2;
    /** The lines' heads are travelling fast: every frame the display shows. */
    boolean fast;
    /** Over: the wave is its own self, the controls at no-op values. */
    boolean done;

    static final float SPREAD = 0.5f;

    /** The controls at ms after the first frame. */
    XmbBoot at(float ms) {
        float lines = clamp01((ms - BOOT_LINES_AT_MS) / BOOT_LINES_MS);
        spread = SPREAD;
        reveal = ms < BOOT_LINES_AT_MS ? -9f : -1.15f + (2.3f + SPREAD) * bezier(0.42f, 0f, 0.18f, 1f, lines);
        float linesEnd = BOOT_LINES_AT_MS + BOOT_LINES_MS;
        // The heads' light and the warm trail go as the lines arrive at the right edge.
        float arrived = smooth(clamp01((ms - (linesEnd - 200f)) / 600f));
        tip = 1.6f * (1f - arrived);
        trail = 1f - arrived;
        // A swell of light as the last lines join, then down to the wave's own light while the colour comes.
        float swell = (float) Math.sin(Math.PI * clamp01((ms - (linesEnd - 300f)) / 800f));
        float settle = smooth(clamp01((ms - linesEnd) / 1400f));
        boost = 1f + 1.2f * (1f - settle) + 0.7f * swell;
        rows2 = smooth(clamp01((ms - (linesEnd - 300f)) / 800f));
        // 60 frames a second while the heads travel fast; by 400 ms before the end they glide (the last 2% of the
        // way), and 30 is plenty for that and for the colour; the other rows come in at 30 too.
        fast = ms < linesEnd - 400f;
        spark = 1f + 0.6f * (1f - settle);
        haze = 0.05f * smooth(clamp01((ms - BOOT_LINES_AT_MS) / 600f))
                * (1f - smooth(clamp01((ms - BOOT_COLOUR_AT_MS) / 1200f)));
        float colour = clamp01((ms - BOOT_COLOUR_AT_MS) / BOOT_COLOUR_MS);
        bloom = 2f * bezier(0.2f, 0.6f, 0.3f, 1f, colour);
        expose = smooth(Math.min(1f, colour * 1.3f));
        done = colour >= 1f && ms >= linesEnd + 1400f;
        return this;
    }

    /** The time it takes, from the first frame to the wave as it always is. */
    static float length() {
        return Math.max(BOOT_COLOUR_AT_MS + BOOT_COLOUR_MS, BOOT_LINES_AT_MS + BOOT_LINES_MS + 1400f);
    }

    static float clamp01(float x) {
        return Math.max(0f, Math.min(1f, x));
    }

    static float smooth(float k) {
        return k * k * (3f - 2f * k);
    }

    /**
     * Cubic Bézier easing as CSS and Android's PathInterpolator draw it, through (0,0), (x1,y1), (x2,y2), (1,1): the
     * curve's height where it has come x of the way across. Solved here so the timeline is plain arithmetic.
     */
    static float bezier(float x1, float y1, float x2, float y2, float x) {
        if (x <= 0f) return 0f;
        if (x >= 1f) return 1f;
        float t = x;
        for (int i = 0; i < 8; i++) {   // Newton's method on x(t) = x
            float dx = cubic(x1, x2, t) - x;
            if (Math.abs(dx) < 1e-6f) break;
            float slope = slope(x1, x2, t);
            if (Math.abs(slope) < 1e-6f) break;
            t -= dx / slope;
        }
        if (t < 0f || t > 1f || Math.abs(cubic(x1, x2, t) - x) > 1e-4f) {
            float lo = 0f, hi = 1f;   // a flat stretch threw Newton off: halve the interval instead
            t = x;
            for (int i = 0; i < 30; i++) {
                if (cubic(x1, x2, t) < x) lo = t;
                else hi = t;
                t = (lo + hi) / 2f;
            }
        }
        return cubic(y1, y2, t);
    }

    private static float cubic(float a, float b, float t) {
        float u = 1f - t;
        return 3f * u * u * t * a + 3f * u * t * t * b + t * t * t;
    }

    private static float slope(float a, float b, float t) {
        float u = 1f - t;
        return 3f * u * u * a + 6f * u * t * (b - a) + 3f * t * t * (1f - b);
    }
}
