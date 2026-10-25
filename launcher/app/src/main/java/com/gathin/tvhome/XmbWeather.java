package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The weather for the XMB's status row: the temperature and the sky now, from Open-Meteo (no account or key) for
 * where the TV is. The place is the box's own last known location (it has one from Wi-Fi), rounded to 0.01 degree
 * (about 1 km) before it leaves the TV. Read at most every 30 minutes and only while Home is on screen; the last
 * answer is kept in the settings so Home shows it at once, and is dropped when it is more than three hours old.
 */
final class XmbWeather {
    private static final String TAG = "TvHome.Weather";
    private static final String PREF = "xmb_weather";
    static final long REFRESH_MS = 30 * 60_000L;
    private static final long RETRY_MS = 10 * 60_000L;
    static final long STALE_MS = 3 * 3600_000L;
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static long lastAttempt;

    final int temperature;
    final int code;
    final boolean day;
    final long time;

    XmbWeather(int temperature, int code, boolean day, long time) {
        this.temperature = temperature;
        this.code = code;
        this.day = day;
        this.time = time;
    }

    /** The last answer if it is fresh enough to show, else null. */
    static XmbWeather current(Context ctx) {
        XmbWeather w = decode(Config.settings(ctx).getString(PREF, null));
        return w != null && Math.abs(System.currentTimeMillis() - w.time) < STALE_MS ? w : null;
    }

    /** Reads the weather again when the last answer is 30 minutes old; calls back on the main thread if it changed. */
    static void refresh(Context ctx, Runnable changed) {
        long now = System.currentTimeMillis();
        XmbWeather last = decode(Config.settings(ctx).getString(PREF, null));
        if (last != null && now - last.time < REFRESH_MS) return;
        if (now - lastAttempt < RETRY_MS) return;
        lastAttempt = now;
        Context app = ctx.getApplicationContext();
        NET.execute(() -> {
            XmbWeather w = fetch(app);
            if (w == null) return;
            Config.settings(app).edit().putString(PREF, encode(w)).apply();
            new Handler(Looper.getMainLooper()).post(changed);
        });
    }

    private static XmbWeather fetch(Context ctx) {
        double[] at = location(ctx);
        if (at == null) {
            Log.i(TAG, "no location yet; no weather");
            return null;
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url(at[0], at[1])).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            if (c.getResponseCode() != 200) return null;
            JSONObject now = new JSONObject(read(c.getInputStream())).getJSONObject("current");
            return new XmbWeather((int) Math.round(now.getDouble("temperature_2m")), now.getInt("weather_code"),
                    now.optInt("is_day", 1) == 1, System.currentTimeMillis());
        } catch (Exception e) {
            Log.i(TAG, "weather not read: " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** The request for one place; only the rounded coordinates are sent. */
    static String url(double lat, double lon) {
        return String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f"
                + "&current=temperature_2m,weather_code,is_day&timezone=auto", round(lat), round(lon));
    }

    /** To 0.01 degree, about 1 km: plenty for the weather, and no closer to the house than that. */
    static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** The newest last known position from any provider, or null (none yet, or no location permission). */
    private static double[] location(Context ctx) {
        // Granted by the install script for the Wi-Fi name (Android 10 and later); never asked for on screen.
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        for (String provider : new String[]{"fused", LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER,
                LocationManager.GPS_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (RuntimeException ignored) {
                // an unknown provider, or the permission is not granted
            }
        }
        return best == null ? null : new double[]{best.getLatitude(), best.getLongitude()};
    }

    /** The sky icon for a WMO weather code (the codes Open-Meteo reports) by day or night. */
    static int icon(int code, boolean day) {
        switch (code) {
            case 0:
                return day ? R.drawable.ic_xmb_sunny : R.drawable.ic_xmb_clear_night;
            case 1:
            case 2:
                return day ? R.drawable.ic_xmb_partly_cloudy_day : R.drawable.ic_xmb_partly_cloudy_night;
            case 45:
            case 48:
                return R.drawable.ic_xmb_foggy;
            case 71: case 73: case 75: case 77: case 85: case 86:
                return R.drawable.ic_xmb_weather_snowy;
            case 95: case 96: case 99:
                return R.drawable.ic_xmb_thunderstorm;
            default:
                if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return R.drawable.ic_xmb_rainy;
                return R.drawable.ic_xmb_cloud;
        }
    }

    static String encode(XmbWeather w) {
        return new XmbStamped(w.time, w.temperature, w.code, w.day ? 1 : 0).encode();
    }

    static XmbWeather decode(String s) {
        XmbStamped st = XmbStamped.decode(s, 3);
        if (st == null || st.number(0, Integer.MIN_VALUE) == Integer.MIN_VALUE || st.number(1, -1) < 0) return null;
        return new XmbWeather(st.number(0, 0), st.number(1, 0), st.number(2, 1) == 1, st.time);
    }

    private static String read(InputStream in) throws java.io.IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = s.read(buf)) > 0 && out.size() < 65536) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
