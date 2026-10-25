package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Network > Test internet speed: how fast the box downloads from the internet right now, for when a film stutters.
 * Runs only when the owner presses OK, never by itself: four tiny requests for the round trip (the ping: the TCP
 * round trip the service's edge measured, which it reports with each reply, the lowest of the four), then a download
 * from Cloudflare's speed test service
 * (speed.cloudflare.com, which its own speed test page uses) for six seconds or 90 MB, whichever comes first, the
 * speed counted from the second second on (the first is the connection getting up to speed). The last result is
 * kept. Stopped when Home goes behind an app.
 */
final class XmbSpeedTest {
    private static final String TAG = "TvHome.Speed";
    private static final String PREF = "xmb_speedtest";
    private static final String DOWN = "https://speed.cloudflare.com/__down?bytes=";
    private static final long TEST_MS = 6000, WARMUP_MS = 1000, REPORT_MS = 250;
    /** The most the service sends in one reply is just under 100 MB (it refuses 100 MB itself). */
    private static final int MAX_BYTES = 90_000_000;
    private static volatile boolean running, cancelled;

    /** A measurement: megabits a second, the round trip in milliseconds (-1 unknown), and when (0 while running). */
    static final class Result {
        final float mbps;
        final int pingMs;
        final long time;

        Result(float mbps, int pingMs, long time) {
            this.mbps = mbps;
            this.pingMs = pingMs;
            this.time = time;
        }
    }

    /** Told on the main thread: the speed so far while it runs, then the result (null when it failed or stopped). */
    interface Listener {
        void speed(boolean running, Result r);
    }

    private XmbSpeedTest() {
    }

    static boolean running() {
        return running;
    }

    /** The last result kept, or null. */
    static Result last(Context ctx) {
        XmbStamped s = XmbStamped.load(ctx, PREF, 2);
        return s == null ? null : new Result(s.number(0, 0) / 10f, s.number(1, -1), s.time);
    }

    static void start(Context ctx, Listener listener) {
        if (running) return;
        running = true;
        cancelled = false;
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Result r = null;
            try {
                r = measure(main, listener);
            } catch (IOException | RuntimeException e) {
                Log.i(TAG, "speed test failed: " + e);
            }
            if (r != null) new XmbStamped(r.time, Math.round(r.mbps * 10), r.pingMs).save(app, PREF);
            Result done = r;
            running = false;
            main.post(() -> listener.speed(false, done));
        }, "xmb-speedtest").start();
    }

    static void cancel() {
        cancelled = true;
    }

    private static HttpURLConnection open(int bytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(DOWN + bytes).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setUseCaches(false);
        return c;
    }

    private static Result measure(Handler main, Listener listener) throws IOException {
        int best = -1;
        for (int i = 0; i < 4 && !cancelled; i++) {
            long t0 = SystemClock.elapsedRealtime();
            HttpURLConnection c = open(0);
            // Read to the end and closed, not disconnected: the connection goes back to the pool and the next
            // request reuses it.
            try (InputStream in = c.getInputStream()) {
                while (in.read() >= 0) {
                    // nothing to read: the reply is empty
                }
            }
            List<String> timing = new ArrayList<>();
            for (Map.Entry<String, List<String>> h : c.getHeaderFields().entrySet()) {
                if ("server-timing".equalsIgnoreCase(h.getKey())) timing.addAll(h.getValue());
            }
            int ms = roundTrip(timing, SystemClock.elapsedRealtime() - t0);
            // Timed by hand, the first one also includes setting the connection up.
            if ((i > 0 || hasRtt(timing)) && (best < 0 || ms < best)) best = ms;
        }
        final int ping = best;
        HttpURLConnection c = open(MAX_BYTES);
        long start = -1, from = -1, total = 0, counted = 0, reported = 0, end;
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while (!cancelled && (n = in.read(buf)) > 0) {
                long now = SystemClock.elapsedRealtime();
                if (start < 0) start = now;
                total += n;
                if (now - start >= WARMUP_MS) {
                    if (from < 0) from = now;
                    else counted += n;
                }
                if (from > 0 && now - reported >= REPORT_MS && now > from) {
                    reported = now;
                    float sofar = mbps(counted, now - from);
                    main.post(() -> listener.speed(true, new Result(sofar, ping, 0)));
                }
                if (now - start >= TEST_MS) break;
            }
            end = SystemClock.elapsedRealtime();
        } finally {
            c.disconnect();
        }
        if (cancelled || start < 0) return null;
        // A line so fast that it all came in the first second: the whole download counts.
        float speed = from > 0 && end > from && counted > 0 ? mbps(counted, end - from) : mbps(total, Math.max(1, end - start));
        Log.i(TAG, String.format(java.util.Locale.ROOT, "%.1f Mb/s, ping %d ms", speed, ping));
        return new Result(speed, ping, System.currentTimeMillis());
    }

    private static final Pattern MIN_RTT = Pattern.compile("min_rtt=(\\d+)");
    private static final Pattern DUR = Pattern.compile("dur=([\\d.]+)");

    private static boolean hasRtt(List<String> serverTiming) {
        for (String h : serverTiming) if (MIN_RTT.matcher(h).find()) return true;
        return false;
    }

    /**
     * The round trip in milliseconds from one reply's Server-Timing: the TCP round trip the edge measured ("cfL4 ...
     * min_rtt=10190", microseconds), else the time the reply took less what the server says it spent ("dur=").
     */
    static int roundTrip(List<String> serverTiming, long elapsedMs) {
        long spent = 0;
        for (String h : serverTiming) {
            Matcher m = MIN_RTT.matcher(h);
            if (m.find()) return (int) Math.max(1, Math.round(Long.parseLong(m.group(1)) / 1000.0));
            Matcher d = DUR.matcher(h);
            while (d.find()) spent += Math.round(Double.parseDouble(d.group(1)));
        }
        return (int) Math.max(1, elapsedMs - spent);
    }

    static float mbps(long bytes, long ms) {
        return ms <= 0 ? 0f : bytes * 8f / ms / 1000f;
    }
}
