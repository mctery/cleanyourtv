package com.gathin.tvhome;

import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What changed the screen and when, for chasing "it moved by itself" (Settings > Diagnostics): every rebuild of the
 * cross-bar and why, the status row gaining or losing an icon, focus and screensaver changes, frames of the wave that
 * came late. Kept as the last 60 lines in memory and written to logcat as TvHome.Debug, so a recording or
 * `adb logcat -s TvHome.Debug` shows what happened at the moment the screen moved. Thread-safe: the wave logs from
 * its GL thread.
 */
final class XmbDebug {
    private static final String TAG = "TvHome.Debug";
    private static final int MAX = 60;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    /** Called on the main thread after a new line, when the on-screen panel is showing. */
    static volatile Runnable listener;

    private XmbDebug() {
    }

    static void log(String what) {
        String line = String.format(Locale.ROOT, "%8.1f  %s", SystemClock.elapsedRealtime() / 1000.0, what);
        synchronized (LINES) {
            LINES.addFirst(line);
            while (LINES.size() > MAX) LINES.removeLast();
        }
        Log.i(TAG, what);
        Runnable l = listener;
        if (l != null) MAIN.post(l);
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
