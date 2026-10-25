package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What changed the screen and when, for chasing "it moved by itself" (Settings > Diagnostics): every rebuild of the
 * cross-bar and why, the status row gaining or losing an icon, focus and screensaver changes, frames of the wave that
 * came late. Kept as the last 60 lines in memory, in the TV's language for the on-screen panel, and written to logcat
 * in English as TvHome.Debug, so a recording or `adb logcat -s TvHome.Debug` shows what happened at the moment the
 * screen moved. Thread-safe: the wave logs from its GL thread.
 */
final class XmbDebug {
    private static final String TAG = "TvHome.Debug";
    private static final int MAX = 60;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    /** Called on the main thread after a new line, when the on-screen panel is showing. */
    static volatile Runnable listener;
    /** The texts in the TV's language (the panel) and in English (logcat); set by Home, again when the language changes. */
    private static volatile Resources shown, logged;

    private XmbDebug() {
    }

    static void init(Context ctx) {
        shown = ctx.getResources();
        Configuration english = new Configuration(ctx.getResources().getConfiguration());
        english.setLocale(Locale.ENGLISH);
        logged = ctx.createConfigurationContext(english).getResources();
    }

    /** One line: a string resource and its arguments. */
    static void log(int text, Object... args) {
        Resources s = shown, l = logged;
        if (s == null || l == null) return;   // before Home started: nothing to show yet
        String line = String.format(Locale.ROOT, "%8.1f  %s", SystemClock.elapsedRealtime() / 1000.0, s.getString(text, args));
        synchronized (LINES) {
            LINES.addFirst(line);
            while (LINES.size() > MAX) LINES.removeLast();
        }
        Log.i(TAG, l.getString(text, args));
        Runnable r = listener;
        if (r != null) MAIN.post(r);
    }

    /** The newest lines first. */
    static List<String> recent(int n) {
        synchronized (LINES) {
            List<String> out = new ArrayList<>();
            for (String s : LINES) {
                if (out.size() >= n) break;
                out.add(s);
            }
            return out;
        }
    }
}
