package com.gathin.tvhome;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The XMB's status row, left of the clock: glowing white icons as on the PSP and PS3, no frames. From the clock
 * leftwards: the theme colour (a palette glowing in the wave's colour), the connection (Wi-Fi strength in lit arcs,
 * or the LAN), the weather. Further left, only while there is something to say: what is going on (an app
 * downloading, a USB drive, Bluetooth headphones or a speaker) and, leftmost in amber, what needs a hand (the
 * remote's battery, storage nearly full, network ADB still open, the remote-button helper not running).
 *
 * Listens only while Home is in front ({@link #start}, {@link #stop}); everything it reads is on the TV, except
 * the weather ({@link XmbWeather}).
 */
final class XmbStatus {
    interface Units {
        int px(float units);
    }

    private static final String TAG = "TvHome.Status";
    private static final float ICON = 40, PAD = 18, GAP = 34, GROUP_GAP = 52;
    private static final long LOW_STORAGE_BYTES = 500L * 1024 * 1024;
    private static final int LOW_BATTERY = 20;
    /** A helper that is still not running this long after Home started is not just slow to bind. */
    private static final long HELPER_GRACE_MS = 15_000;

    private final Activity act;
    final LinearLayout row;
    private final Units u;
    private final Typeface face;
    private final Handler handler;
    private final long createdAt = SystemClock.elapsedRealtime();
    private final Map<String, Bitmap> bitmaps = new HashMap<>();
    private final Map<Integer, Float> installs = new HashMap<>();
    private int accent = 0xD6517A;
    private boolean started, rebindTried, batteryLogged;
    private BluetoothProfile a2dp;
    /** Counts start() calls: a Bluetooth proxy that arrives for an older one is closed, not kept. */
    private int proxyGen;
    /** The download item, kept between refreshes so its animation runs on (refreshes come with every bit of progress). */
    private View download;
    private TextView downloadText;
    private ValueAnimator downloadAnim;
    private ConnectivityManager.NetworkCallback netCallback;
    private PackageInstaller.SessionCallback installCallback;

    XmbStatus(Activity act, Units u, Typeface face, Handler handler) {
        this.act = act;
        this.u = u;
        this.face = face;
        this.handler = handler;
        row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Memory is wanted and Home is hidden: the glow bitmaps are drawn again when next needed. */
    void trim() {
        bitmaps.clear();
    }

    void setAccent(int rgb) {
        if (rgb == accent) return;
        accent = rgb;
        refresh();
    }

    // ---- listening while Home is in front ------------------------------------------------------------------------

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshSoon();
        }
    };

    void start() {
        if (started) return;
        started = true;
        IntentFilter media = new IntentFilter();
        media.addAction(Intent.ACTION_MEDIA_MOUNTED);
        media.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        media.addAction(Intent.ACTION_MEDIA_EJECT);
        media.addAction(Intent.ACTION_MEDIA_REMOVED);
        media.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        media.addDataScheme("file");
        act.registerReceiver(receiver, media);
        IntentFilter other = new IntentFilter(WifiManager.RSSI_CHANGED_ACTION);
        other.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        other.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        other.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        act.registerReceiver(receiver, other);
        try {
            ConnectivityManager cm = act.getSystemService(ConnectivityManager.class);
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    refreshSoon();
                }

                @Override
                public void onLost(Network network) {
                    refreshSoon();
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    refreshSoon();
                }
            };
            cm.registerDefaultNetworkCallback(netCallback, handler);
        } catch (RuntimeException e) {
            netCallback = null;
        }
        watchInstalls();
        try {
            BluetoothAdapter adapter = bluetoothAllowed() ? BluetoothAdapter.getDefaultAdapter() : null;
            int gen = ++proxyGen;
            if (adapter != null) {
                adapter.getProfileProxy(act.getApplicationContext(), new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        if (started && gen == proxyGen && a2dp == null) {
                            a2dp = proxy;
                            refreshSoon();
                        } else {
                            adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy);
                        }
                    }

                    @Override
                    public void onServiceDisconnected(int profile) {
                        a2dp = null;
                    }
                }, BluetoothProfile.A2DP);
            }
        } catch (RuntimeException ignored) {
            // no Bluetooth, or no permission for it (Android 12 and later)
        }
        XmbWeather.refresh(act, this::refresh);
        if (bluetoothAllowed()) XmbRemoteBattery.refresh(act, this::refreshSoon);
        refresh();
    }

    void stop() {
        if (!started) return;
        started = false;
        proxyGen++;
        handler.removeCallbacks(refreshRunnable);
        handler.removeCallbacks(rebindRunnable);
        if (downloadAnim != null) downloadAnim.cancel();
        try {
            act.unregisterReceiver(receiver);
        } catch (RuntimeException ignored) {
        }
        if (netCallback != null) {
            try {
                act.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
            } catch (RuntimeException ignored) {
            }
            netCallback = null;
        }
        if (installCallback != null) {
            act.getPackageManager().getPackageInstaller().unregisterSessionCallback(installCallback);
            installCallback = null;
        }
        if (a2dp != null) {
            try {
                BluetoothAdapter.getDefaultAdapter().closeProfileProxy(BluetoothProfile.A2DP, a2dp);
            } catch (RuntimeException ignored) {
            }
            a2dp = null;
        }
    }

    /** Once a minute, with the clock: the weather may be due, storage and ADB are read again. */
    void tick() {
        if (!started) return;
        XmbWeather.refresh(act, this::refresh);
        if (bluetoothAllowed()) XmbRemoteBattery.refresh(act, this::refreshSoon);
        refresh();
    }

    private final Runnable refreshRunnable = this::refresh;

    /** Coalesces bursts (download progress, signal changes) into one redraw. */
    private void refreshSoon() {
        if (!started) return;
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, 250);
    }

    // ---- the row ---------------------------------------------------------------------------------------------------

    private XmbStatusPrefs setup = XmbStatusPrefs.preset(XmbStatusPrefs.STANDARD, XmbStatusPrefs.defaultOrder(), false);

    /** What the row shows and in which order (Settings > Status bar); takes effect at once. */
    void setPrefs(XmbStatusPrefs p) {
        setup = p;
        refresh();
    }

    void refresh() {
        // Callbacks (weather, battery, the accent) can arrive after Home went behind an app: nothing to draw then,
        // and above all no accessibility repair while someone else's app is in front.
        if (!started) return;
        // Built aside first: when every item would look as it does now (a Wi-Fi signal event that changed nothing,
        // say), the row is left alone, so nothing on screen is laid out again.
        List<View> views = new ArrayList<>();
        StringBuilder sig = new StringBuilder();
        for (String key : setup.order) {
            String mode = setup.mode(key);
            if (XmbStatusPrefs.HIDDEN.equals(mode)) continue;
            part = null;
            View v = build(key, XmbStatusPrefs.ALWAYS.equals(mode));
            if (v == null) continue;
            v.setTag(key);
            views.add(v);
            sig.append(key).append('=').append(part).append(';');
        }
        String now0 = sig.toString();
        if (now0.equals(shownState) && row.getChildCount() == views.size()) return;
        shownState = now0;
        row.removeAllViews();
        java.util.Set<String> now = new java.util.HashSet<>();
        int lastKind = -1;
        for (View v : views) {
            String key = (String) v.getTag();
            if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
            int kind = XmbStatusPrefs.item(key).kind;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            // From the item's own base (an icon is pulled in by its glow's padding, a pair is not), never added on top
            // of an earlier refresh's gap: the download item is reused.
            lp.leftMargin = (v instanceof ImageView ? -u.px(PAD) : 0) + (lastKind >= 0 ? u.px(kind == lastKind ? GAP : GROUP_GAP) : 0);
            row.addView(v, lp);
            lastKind = kind;
            now.add(key);
            // Something new to say glows up instead of popping in.
            if (!shown.isEmpty() && !shown.contains(key) && animate) {
                v.setAlpha(0f);
                v.setScaleX(0.7f);
                v.setScaleY(0.7f);
                v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420).start();
            }
        }
        if (!now.contains("download") && downloadAnim != null) downloadAnim.cancel();
        if (!now.equals(shown)) {
            java.util.Set<String> gone = new java.util.HashSet<>(shown), added = new java.util.HashSet<>(now);
            gone.removeAll(now);
            added.removeAll(shown);
            if (!shown.isEmpty()) XmbDebug.log("status row: +" + added + " -" + gone);
        }
        shown.clear();
        shown.addAll(now);
    }

    /** What the item being built shows, for the row's signature (set by build). */
    private String part;
    private String shownState;

    /** One item, or null when it has nothing to show; always: show it (with its value) even when all is well. */
    private View build(String key, boolean always) {
        View v = build0(key, always);
        if (part == null) part = v != null ? "1" : "-";
        return v;
    }

    private View build0(String key, boolean always) {
        switch (key) {
            case "recording":
                return XmbRecorder.recording ? image(bitmap(R.drawable.ic_xmb_videocam, 0xFFFFB4B4, 0xA6FF8080, 0xD9E0302A)) : null;
            case "battery": {
                int level = remoteBattery();
                if (level < 0) return null;
                boolean low = level < LOW_BATTERY;
                if (!low && !always) return null;
                part = low + "/" + level;
                View icon = low ? warnIcon(R.drawable.ic_xmb_battery_1_bar) : icon(R.drawable.ic_xmb_battery_full);
                return always ? pair(icon, level + "%") : icon;
            }
            case "storage": {
                long free = freeBytes();
                if (free < 0) return null;
                // Low below 450 MB, fine again above 550 MB: no blinking around one value.
                boolean low = storageLow = storageLow ? free < LOW_STORAGE_BYTES + 50_000_000L
                        : free < LOW_STORAGE_BYTES - 50_000_000L;
                if (!low && !always) return null;
                part = low + "/" + free / 100_000_000L;
                View icon = low ? warnIcon(R.drawable.ic_xmb_hard_drive) : icon(R.drawable.ic_xmb_hard_drive);
                return always ? pair(icon, String.format(java.util.Locale.ROOT, "%.1f GB", free / 1e9)) : icon;
            }
            case "adb":
                return adbOn() ? warnIcon(R.drawable.ic_xmb_developer_mode_tv) : null;
            case "helper":
                return helperDown() ? warnIcon(R.drawable.ic_xmb_settings_remote) : null;
            case "download": {
                int progress = installProgress();
                part = "on";   // the percentage changes inside the item, not the row
                return progress >= 0 ? downloading(progress) : null;
            }
            case "usb":
                return usbDrive() ? icon(R.drawable.ic_xmb_usb) : null;
            case "audio": {
                int audio = bluetoothAudio();
                part = String.valueOf(audio);
                return audio != 0 ? icon(audio) : null;
            }
            case "temperature": {
                int t = socTemperature();
                if (t < 0) return null;
                boolean hot = t >= HOT_CELSIUS;
                if (!hot && !always) return null;
                part = hot + "/" + t;
                return pair(hot ? warnIcon(R.drawable.ic_xmb_thermostat) : icon(R.drawable.ic_xmb_thermostat), t + "°");
            }
            case "weather": {
                XmbWeather w = XmbWeather.current(act);
                if (w != null) part = XmbWeather.icon(w.code, w.day) + "/" + w.temperature;
                return w != null ? pair(icon(XmbWeather.icon(w.code, w.day)), w.temperature + "°") : null;
            }
            case "network": {
                int net = network();
                part = String.valueOf(net);
                return net == R.drawable.ic_xmb_wifi_off
                        ? image(bitmap(net, 0x73FFFFFF, 0x26FFFFFF, 0x14FFFFFF)) : icon(net);
            }
            case "palette":
                part = Integer.toHexString(accent);
                return image(bitmap(R.drawable.ic_xmb_palette, Color.WHITE, 0x73FFFFFF, (0xF2 << 24) | (accent & 0xFFFFFF)));
            default:
                return null;
        }
    }

    private static final int HOT_CELSIUS = 75;
    private boolean storageLow;
    private boolean tempLogged;

    /** The chip's temperature in degrees Celsius from the kernel's thermal zones, or -1 where apps may not read it. */
    private int socTemperature() {
        java.io.File dir = new java.io.File("/sys/class/thermal");
        java.io.File[] zones = dir.listFiles((d, n) -> n.startsWith("thermal_zone"));
        int best = -1;
        if (zones != null) {
            for (java.io.File z : zones) {
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(new java.io.File(z, "temp")))) {
                    String line = r.readLine();
                    if (line == null) continue;
                    long v = Long.parseLong(line.trim());
                    int c = (int) (v > 1000 ? v / 1000 : v);   // most kernels report millidegrees
                    if (c > 0 && c < 150) best = Math.max(best, c);
                } catch (Exception ignored) {
                    // not readable for apps on this firmware
                }
            }
        }
        if (!tempLogged) {
            tempLogged = true;
            Log.i(TAG, "chip temperature: " + best);
        }
        return best;
    }

    private final java.util.Set<String> shown = new java.util.HashSet<>();
    private boolean animate = true;

    private static View tag(View v, String key) {
        v.setTag(key);
        return v;
    }

    /** Animations off (reduced motion): items appear as they are. */
    void setMotion(boolean on) {
        animate = on;
    }

    /**
     * The download item: the arrow falls into the tray, again and again, while an app downloads; the tray brightens
     * as it lands. 1.6 seconds a round, only this small view redrawn.
     */
    private View downloading(int progress) {
        if (download == null) {
            int pad = u.px(PAD), full = u.px(ICON) + 2 * pad, size = u.px(ICON);
            FrameLayout box = new FrameLayout(act);
            box.setClipChildren(false);
            ImageView tray = new ImageView(act);
            tray.setImageBitmap(bitmap(R.drawable.ic_xmb_download_tray, Color.WHITE, 0x8CFFFFFF, 0x47FFFFFF));
            ImageView arrow = new ImageView(act);
            arrow.setImageBitmap(bitmap(R.drawable.ic_xmb_download_arrow, Color.WHITE, 0x8CFFFFFF, 0x47FFFFFF));
            box.addView(tray, new FrameLayout.LayoutParams(full, full));
            box.addView(arrow, new FrameLayout.LayoutParams(full, full));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(full, full);
            lp.setMargins(-pad, -pad, -pad, -pad);
            box.setLayoutParams(lp);
            download = pair(box, "");
            downloadText = (TextView) ((ViewGroup) download).getChildAt(1);
            downloadAnim = ValueAnimator.ofFloat(0f, 1f);
            downloadAnim.setDuration(1600);
            downloadAnim.setRepeatCount(ValueAnimator.INFINITE);
            downloadAnim.setInterpolator(null);
            downloadAnim.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                float y, alpha, glow;
                if (t < 0.55f) {            // falls in from above, slowing as it lands
                    float k = t / 0.55f, ease = 1 - (1 - k) * (1 - k);
                    y = -0.45f * size * (1 - ease);
                    alpha = Math.min(1f, k / 0.35f);
                    glow = 0f;
                } else if (t < 0.8f) {      // rests in the tray, which lights up
                    y = 0f;
                    alpha = 1f;
                    glow = 1f - (t - 0.55f) / 0.25f;
                } else {                    // sinks in and fades
                    float k = (t - 0.8f) / 0.2f;
                    y = 0.12f * size * k;
                    alpha = 1f - k;
                    glow = 0f;
                }
                arrow.setTranslationY(y);
                arrow.setAlpha(alpha);
                tray.setAlpha(0.75f + 0.25f * glow);
            });
        }
        downloadText.setText(progress + "%");
        if (download.getParent() == null) download.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        if (!downloadAnim.isStarted() && animating) downloadAnim.start();
        return download;
    }

    /** The menu faded out to the wave: nothing in the row needs to move meanwhile. */
    void setAnimating(boolean on) {
        animating = on;
        if (downloadAnim == null) return;
        if (!on) {
            if (downloadAnim.isStarted()) downloadAnim.pause();
        } else if (downloadAnim.isPaused()) {
            downloadAnim.resume();
        } else if (!downloadAnim.isStarted() && download != null && download.getParent() != null) {
            downloadAnim.start();
        }
    }

    private boolean animating = true;

    private View icon(int res) {
        return image(bitmap(res, Color.WHITE, 0x8CFFFFFF, 0x47FFFFFF));
    }

    private View warnIcon(int res) {
        return image(bitmap(res, 0xFFFFE3B0, 0xA6FFD68C, 0xBFF0A028));
    }

    /** An icon drawn with its glow: the view is wider than the icon by the glow, and laid out at the icon's size. */
    private View image(Bitmap b) {
        ImageView v = new ImageView(act);
        v.setImageBitmap(b);
        int pad = u.px(PAD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(b.getWidth(), b.getHeight());
        lp.setMargins(-pad, -pad, -pad, -pad);
        v.setLayoutParams(lp);
        return v;
    }

    /** An icon and its number (the temperature, a download's progress), as one item. */
    private View pair(View icon, String number) {
        LinearLayout p = new LinearLayout(act);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(android.view.Gravity.CENTER_VERTICAL);
        p.setClipChildren(false);
        p.addView(icon, icon.getLayoutParams());
        TextView t = new TextView(act);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, u.px(38));
        t.setTextColor(0xE6FFFFFF);
        t.setIncludeFontPadding(false);
        t.setShadowLayer(u.px(9), 0, 0, 0x66FFFFFF);
        t.setFontFeatureSettings("tnum");   // equal-width digits: a changing number does not nudge the row
        t.setText(number);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = u.px(8);
        p.addView(t, tlp);
        p.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        return p;
    }

    /** The icon over two glows, a tight one and a wide one, made once per icon and colours. */
    private Bitmap bitmap(int res, int tint, int innerGlow, int outerGlow) {
        String key = res + "/" + tint + "/" + innerGlow + "/" + outerGlow;
        Bitmap cached = bitmaps.get(key);
        if (cached != null) return cached;
        int size = u.px(ICON), pad = u.px(PAD), full = size + 2 * pad;
        Drawable d = act.getDrawable(res).mutate();
        d.setBounds(pad, pad, pad + size, pad + size);
        Bitmap shape = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888);
        d.setTint(Color.WHITE);
        d.draw(new Canvas(shape));
        Bitmap alpha = shape.extractAlpha();
        shape.recycle();
        Bitmap out = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(outerGlow);
        p.setMaskFilter(new BlurMaskFilter(Math.max(1, u.px(10)), BlurMaskFilter.Blur.NORMAL));
        c.drawBitmap(alpha, 0, 0, p);
        p.setColor(innerGlow);
        p.setMaskFilter(new BlurMaskFilter(Math.max(1, u.px(3)), BlurMaskFilter.Blur.NORMAL));
        c.drawBitmap(alpha, 0, 0, p);
        alpha.recycle();
        d.setTint(tint);
        d.draw(c);
        if (bitmaps.size() > 48) bitmaps.clear();
        bitmaps.put(key, out);
        return out;
    }

    // ---- what the icons read -----------------------------------------------------------------------------------

    /** Wi-Fi in three strengths, the LAN, or no connection. */
    private int network() {
        try {
            ConnectivityManager cm = act.getSystemService(ConnectivityManager.class);
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return R.drawable.ic_xmb_wifi_off;
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) act.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                int level = info != null ? wifiBars(info.getRssi()) : 3;
                return level >= 3 ? R.drawable.ic_xmb_wifi_3 : level == 2 ? R.drawable.ic_xmb_wifi_2 : R.drawable.ic_xmb_wifi_1;
            }
            return R.drawable.ic_xmb_lan;
        } catch (RuntimeException e) {
            return R.drawable.ic_xmb_wifi_off;
        }
    }

    private int bars = 3;

    /**
     * One, two or three lit arcs from the signal (dBm), changing only when the signal is 3 dB past the step, so a
     * signal sitting on a step does not make the icon flicker.
     */
    private int wifiBars(int rssi) {
        int up = bars < 3 ? (bars == 2 ? -57 : -67) : Integer.MAX_VALUE;     // into the next step
        int down = bars > 1 ? (bars == 3 ? -63 : -73) : Integer.MIN_VALUE;   // out of this one
        if (rssi >= up) bars++;
        else if (rssi <= down) bars--;
        return bars = Math.max(1, Math.min(3, bars));
    }

    /** Free space for apps, or -1 when it cannot be read. */
    private long freeBytes() {
        try {
            return new StatFs(Environment.getDataDirectory().getPath()).getAvailableBytes();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** USB debugging, which on many boxes (the Mi Box S among them) also opens ADB on the network, port 5555. */
    private boolean adbOn() {
        // USB debugging, or wireless debugging (Android 11 and later), either one leaves a door open.
        return Settings.Global.getInt(act.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1
                || Settings.Global.getInt(act.getContentResolver(), "adb_wifi_enabled", 0) == 1;
    }

    /**
     * The remote-button helper should run and does not. Given a grace period after Home starts (Android binds it
     * a moment later), then one try at rebinding it (ServiceRepair.rebind); the icon shows only if that fails too.
     */
    private boolean helperDown() {
        if (InputKeyService.running) return false;
        if (!ServiceRepair.expected(act, InputKeyService.class)) return false;
        long age = SystemClock.elapsedRealtime() - createdAt;
        if (age < HELPER_GRACE_MS) {
            handler.postDelayed(refreshRunnable, HELPER_GRACE_MS - age + 100);
            return false;
        }
        if (!rebindTried) {
            // Not from here (a refresh must not switch accessibility off): a one-shot that stop() cancels.
            rebindTried = true;
            handler.post(rebindRunnable);
            return false;
        }
        return true;
    }

    private final Runnable rebindRunnable = this::rebind;

    private void rebind() {
        if (!started || InputKeyService.running) return;
        Log.i(TAG, "remote-button helper not running; turning accessibility off and on");
        XmbDebug.log("helper: rebinding accessibility");
        if (ServiceRepair.rebind(act, handler)) handler.postDelayed(refreshRunnable, 6000);
        else refreshSoon();
    }

    private void watchInstalls() {
        PackageInstaller pi = act.getPackageManager().getPackageInstaller();
        installs.clear();
        try {
            for (PackageInstaller.SessionInfo s : pi.getAllSessions()) {
                if (s.isActive()) installs.put(s.getSessionId(), s.getProgress());
            }
        } catch (RuntimeException ignored) {
        }
        installCallback = new PackageInstaller.SessionCallback() {
            @Override
            public void onCreated(int id) {
                update(id);
            }

            @Override
            public void onBadgingChanged(int id) {
            }

            @Override
            public void onActiveChanged(int id, boolean active) {
                update(id);
            }

            @Override
            public void onProgressChanged(int id, float progress) {
                if (installs.containsKey(id)) {
                    installs.put(id, progress);
                    refreshSoon();
                } else {
                    update(id);
                }
            }

            @Override
            public void onFinished(int id, boolean success) {
                installs.remove(id);
                refreshSoon();
            }

            private void update(int id) {
                PackageInstaller.SessionInfo s = pi.getSessionInfo(id);
                if (s != null && s.isActive()) installs.put(id, s.getProgress());
                else installs.remove(id);
                refreshSoon();
            }
        };
        pi.registerSessionCallback(installCallback, handler);
    }

    /** The average progress of the installs under way, 0 to 100, or -1 for none. */
    private int installProgress() {
        if (installs.isEmpty()) return -1;
        float sum = 0;
        for (float p : installs.values()) sum += p;
        return Math.max(0, Math.min(100, Math.round(sum / installs.size() * 100)));
    }

    private boolean usbDrive() {
        try {
            StorageManager sm = act.getSystemService(StorageManager.class);
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (v.isRemovable() && Environment.MEDIA_MOUNTED.equals(v.getState())) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    /** Up to Android 11 the install-time BLUETOOTH permission covers these reads; later it needs a runtime grant. */
    private boolean bluetoothAllowed() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || act.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    /** Headphones or a speaker playing over Bluetooth (A2DP), as its icon; 0 for none. */
    @SuppressLint("MissingPermission")   // bluetoothAllowed()
    private int bluetoothAudio() {
        if (a2dp == null || !bluetoothAllowed()) return 0;
        try {
            for (BluetoothDevice d : a2dp.getConnectedDevices()) {
                BluetoothClass c = d.getBluetoothClass();
                int dc = c != null ? c.getDeviceClass() : 0;
                return dc == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES
                        || dc == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET
                        || dc == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
                        ? R.drawable.ic_xmb_headphones : R.drawable.ic_xmb_speaker;
            }
        } catch (RuntimeException ignored) {
        }
        return 0;
    }

    /**
     * The lowest battery level a paired remote or other input device reports, or -1 when none does. Android keeps
     * the level it reads for each device but offers it to apps only through a hidden method; where it has none (an
     * LE remote on Android 11), the level read from the remote's own Battery Service.
     */
    @SuppressLint("MissingPermission")   // bluetoothAllowed()
    private int remoteBattery() {
        if (!bluetoothAllowed()) return -1;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return -1;
            Method level = BluetoothDevice.class.getMethod("getBatteryLevel");
            int lowest = -1;
            StringBuilder seen = new StringBuilder();
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                BluetoothClass c = d.getBluetoothClass();
                int major = c != null ? c.getMajorDeviceClass() : BluetoothClass.Device.Major.UNCATEGORIZED;
                if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) continue;
                int l = (Integer) level.invoke(d);
                seen.append(d.getType()).append('/').append(Integer.toHexString(major)).append('=').append(l).append(' ');
                if (l >= 0 && (lowest < 0 || l < lowest)) lowest = l;
            }
            if (!batteryLogged) {
                batteryLogged = true;
                Log.i(TAG, "input device battery levels: " + seen);
            }
            // Android 11 keeps none for an LE remote: read from the remote itself (XmbRemoteBattery).
            return lowest >= 0 ? lowest : XmbRemoteBattery.cached(act);
        } catch (Exception e) {
            if (!batteryLogged) {
                batteryLogged = true;
                Log.i(TAG, "battery level not readable: " + e);
            }
            return XmbRemoteBattery.cached(act);
        }
    }
}
