package com.gathin.tvhome;

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
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.Handler;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroupOverlay;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** An icon's size, its glow's room round it, the gap between items and between kinds. */
    private static final float ICON = 40, PAD = 18, GAP = 34, GROUP_GAP = 52;
    /** Hysteresis round the storage warning: on below the limit less this, off above the limit plus this. */
    private static final long STORAGE_MARGIN = 50_000_000L;
    /** xmb_low_battery and xmb_low_storage_mb (XmbConfig). */
    private int lowBattery = 20;
    private long lowStorage = 500_000_000L;
    /** A helper that is still not running this long after Home started is not just slow to bind. */
    private static final long HELPER_GRACE_MS = 15_000;

    private final Activity act;
    final LinearLayout row;
    private final Units u;
    private final Typeface face;
    private final Handler handler;
    private final long createdAt = SystemClock.elapsedRealtime();
    private final Map<Integer, Float> installs = new HashMap<>();
    private int accent = 0xD6517A;
    private boolean started, rebindTried;
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

    void setAccent(int rgb) {
        if (rgb == accent) return;
        accent = rgb;
        refresh();
    }

    // ---- listening while Home is in front ------------------------------------------------------------------------

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (WifiManager.RSSI_CHANGED_ACTION.equals(intent.getAction())) XmbNet.forget();
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
                    XmbNet.forget();
                    refreshSoon();
                }

                @Override
                public void onLost(Network network) {
                    XmbNet.forget();
                    refreshSoon();
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    XmbNet.forget();
                    refreshSoon();
                }
            };
            cm.registerDefaultNetworkCallback(netCallback, handler);
        } catch (RuntimeException e) {
            netCallback = null;
        }
        watchInstalls();
        try {
            BluetoothAdapter adapter = XmbRemoteBattery.bluetoothAllowed(act) ? BluetoothAdapter.getDefaultAdapter() : null;
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
        XmbRemoteBattery.refresh(act, this::refreshSoon);
        scheduleSpeed();
        refresh();
    }

    void stop() {
        if (!started) return;
        started = false;
        proxyGen++;
        handler.removeCallbacks(refreshRunnable);
        handler.removeCallbacks(rebindRunnable);
        handler.removeCallbacks(speedTick);
        rxLast = -1;
        rate = 0f;
        busy = false;
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
        XmbRemoteBattery.refresh(act, this::refreshSoon);
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
        scheduleSpeed();
        refresh();
    }

    void refresh() {
        // Callbacks (weather, battery, the accent) can arrive after Home went behind an app: nothing to draw then,
        // and above all no accessibility repair while someone else's app is in front.
        if (!started) return;
        // Built aside first: when every item would look as it does now (a Wi-Fi signal event that changed nothing,
        // say), the row is left alone, so nothing on screen is laid out again.
        List<String> keys = new ArrayList<>();
        Map<String, View> built = new HashMap<>();
        Map<String, String> parts = new HashMap<>();
        StringBuilder sig = new StringBuilder();
        for (String key : setup.order) {
            String mode = setup.mode(key);
            if (XmbStatusPrefs.HIDDEN.equals(mode)) continue;
            part = null;
            View v = build(key, XmbStatusPrefs.ALWAYS.equals(mode));
            if (v == null) continue;
            keys.add(key);
            built.put(key, v);
            parts.put(key, part);
            sig.append(key).append('=').append(part).append(';');
        }
        String now0 = sig.toString();
        if (now0.equals(shownState) && row.getChildCount() == keys.size()) return;
        shownState = now0;

        // Moves as on the PS3, never a jump: an item that goes fades out where it was, one that comes glows up, one
        // that changes (Wi-Fi strength, the temperature, the dust's colour) cross-fades, and the others slide to
        // their new places (FLIP: where each was is noted, the row laid out anew, each moved back and let go).
        boolean flip = animate && row.isAttachedToWindow() && row.isLaidOut() && !onRow.isEmpty();
        Map<String, Integer> before = new HashMap<>();
        if (flip) for (Map.Entry<String, View> e : onRow.entrySet()) before.put(e.getKey(), xInWindow(e.getValue()));
        ViewGroupOverlay overlay = flip ? ((ViewGroup) row.getRootView()).getOverlay() : null;
        Map<String, View> next = new LinkedHashMap<>();
        List<View> coming = new ArrayList<>(), changing = new ArrayList<>();
        for (String key : keys) {
            View old = onRow.get(key), v = built.get(key);
            if (old != null && TextUtils.equals(partOf.get(key), parts.get(key))) {
                v = old;   // as it was: the same view stays
            } else if (old != null && old != v) {
                leave(overlay, old, false);
                changing.add(v);
            } else if (old == null) {
                coming.add(v);
            }
            next.put(key, v);
        }
        for (Map.Entry<String, View> e : onRow.entrySet()) {
            if (!next.containsKey(e.getKey())) leave(overlay, e.getValue(), true);
        }
        row.removeAllViews();
        int lastKind = -1;
        for (Map.Entry<String, View> e : next.entrySet()) {
            View v = e.getValue();
            if (v.getParent() != null) {
                // Still fading out from an earlier refresh (the download item, back again): take it as it is.
                v.animate().cancel();
                ((ViewGroup) v.getParent()).removeView(v);
                v.setAlpha(1f);
                v.setScaleX(1f);
                v.setScaleY(1f);
            }
            int kind = XmbStatusPrefs.item(e.getKey()).kind;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            // From the item's own base (an icon is pulled in by its glow's padding, a pair is not), never added on top
            // of an earlier refresh's gap: the download item is reused.
            lp.leftMargin = (v instanceof ImageView ? -u.px(PAD) : 0) + (lastKind >= 0 ? u.px(kind == lastKind ? GAP : GROUP_GAP) : 0);
            row.addView(v, lp);
            lastKind = kind;
        }
        if (flip) {
            row.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    row.getViewTreeObserver().removeOnPreDrawListener(this);
                    for (Map.Entry<String, View> e : next.entrySet()) {
                        View v = e.getValue();
                        if (coming.contains(v)) {
                            v.setAlpha(0f);
                            v.setScaleX(0.7f);
                            v.setScaleY(0.7f);
                            v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).setStartDelay(0)
                                    .setDuration(XmbStyle.COME_MS).setInterpolator(EASE).start();
                        } else if (changing.contains(v)) {
                            v.setAlpha(0f);
                            v.animate().alpha(1f).translationX(0f).setStartDelay(0).setDuration(XmbStyle.CHANGE_IN_MS + XmbStyle.CHANGE_OUT_MS)
                                    .setInterpolator(EASE).start();
                        } else {
                            Integer was = before.get(e.getKey());
                            if (was == null) continue;
                            float dx = was - (xInWindow(v) - v.getTranslationX());
                            if (Math.abs(dx) < 1f) continue;
                            v.setTranslationX(dx);
                            v.animate().translationX(0f).setStartDelay(0).setDuration(XmbStyle.SHIFT_MS)
                                    .setInterpolator(EASE).start();
                        }
                    }
                    return true;
                }
            });
        }
        Set<String> now = new HashSet<>(next.keySet());
        if (!now.contains("download") && downloadAnim != null) downloadAnim.cancel();
        if (!now.equals(shown)) {
            Set<String> gone = new HashSet<>(shown), added = new HashSet<>(now);
            gone.removeAll(now);
            added.removeAll(shown);
            if (!shown.isEmpty()) {
                String in = XmbStatusPrefs.names(act, added), out = XmbStatusPrefs.names(act, gone);
                if (gone.isEmpty()) XmbDebug.log(R.string.xmb_log_status_added, in);
                else if (added.isEmpty()) XmbDebug.log(R.string.xmb_log_status_removed, out);
                else XmbDebug.log(R.string.xmb_log_status_changed, in, out);
            }
        }
        shown.clear();
        shown.addAll(now);
        onRow.clear();
        onRow.putAll(next);
        partOf.clear();
        partOf.putAll(parts);
    }

    /** The items on the row by key, in order, and what each showed: kept, changed, new and gone are told apart. */
    private final Map<String, View> onRow = new LinkedHashMap<>();
    private final Map<String, String> partOf = new HashMap<>();
    private static final android.view.animation.PathInterpolator EASE =
            new android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f);

    /** An item leaving the row: it fades out where it is, above the row (overlay null: it just goes). */
    private void leave(ViewGroupOverlay overlay, View v, boolean gone) {
        if (overlay == null || v.getParent() == null) return;
        overlay.add(v);   // moved out of the row, kept where it is on screen
        v.animate().alpha(0f).scaleX(gone ? 0.7f : 1f).scaleY(gone ? 0.7f : 1f).setStartDelay(0).setDuration(XmbStyle.GO_MS).setInterpolator(EASE).withEndAction(() -> {
                    overlay.remove(v);
                    v.setAlpha(1f);
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    v.setTranslationX(0f);
                }).start();
    }

    private static int xInWindow(View v) {
        int[] at = new int[2];
        v.getLocationInWindow(at);
        return at[0];
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
                return XmbRecorder.recording ? image(bitmap(R.drawable.ic_xmb_videocam, XmbStyle.GLOW_RED)) : null;
            case "battery": {
                int level = XmbRemoteBattery.level(act);
                if (level < 0) return null;
                boolean low = level < lowBattery;
                if (!low && !always) return null;
                part = low + "/" + level;
                int res = BATTERY[batteryBlocks(level)];
                View icon = low ? warnIcon(res) : icon(res);
                return always ? pair(icon, percent(level)) : icon;
            }
            case "storage": {
                long free = freeBytes();
                if (free < 0) return null;
                // Low below 450 MB, fine again above 550 MB: no blinking around one value.
                boolean low = storageLow = storageLow ? free < lowStorage + STORAGE_MARGIN
                        : free < lowStorage - STORAGE_MARGIN;
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
                // Asked once: where apps may not read it, every try was a denied read in the system's log.
                if (!temperatureReadable()) return null;
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
            case "air": {
                // PM2.5: "PM" over "2.5" glowing in the colour of Thailand's scale (a mask was not read as dust);
                // when needed means from orange up.
                XmbWeather w = XmbWeather.current(act);
                if (w == null || w.pm25x10 < 0) return null;
                int band = XmbWeather.pmBand(w.pm25x10);
                if (!always && band < 3) return null;
                part = band + "/" + w.pm25();
                return pair(image(colored(R.drawable.ic_xmb_pm25, XmbWeather.PM_COLORS[band])), String.valueOf(w.pm25()));
            }
            case "speed": {
                // The box's download rate, all apps together; when needed means while something downloads.
                if (TrafficStats.getTotalRxBytes() == TrafficStats.UNSUPPORTED) return null;
                if (!always && !busy) return null;
                part = "on";   // the figure changes inside the item every second, not the row
                return speedItem();
            }
            case "network": {
                int net = network();
                part = String.valueOf(net);
                return net == R.drawable.ic_xmb_wifi_off
                        ? image(bitmap(net, XmbStyle.GLOW_FAINT)) : icon(net);
            }
            case "palette":
                part = Integer.toHexString(accent);
                return image(colored(R.drawable.ic_xmb_palette, accent));
            default:
                return null;
        }
    }

    private static final int HOT_CELSIUS = 75;
    /** A battery on its side in four blocks, as on the PSP and PS3, by blocks lit. */
    static final int[] BATTERY = {R.drawable.ic_xmb_battery_h0, R.drawable.ic_xmb_battery_h1,
            R.drawable.ic_xmb_battery_h2, R.drawable.ic_xmb_battery_h3, R.drawable.ic_xmb_battery_h4};

    /** Blocks lit for a level: four above 75%, three above 50%, two above 25%, one above 5%. */
    static int batteryBlocks(int level) {
        return level > 75 ? 4 : level > 50 ? 3 : level > 25 ? 2 : level > 5 ? 1 : 0;
    }

    private boolean storageLow;
    private static boolean tempLogged;
    private static Boolean tempReadable;

    /** Whether this firmware lets apps read the chip's temperature (the Mi Box S does not): else no such item. */
    static boolean temperatureReadable() {
        if (tempReadable == null) tempReadable = socTemperature() >= 0;
        return tempReadable;
    }

    /** The chip's temperature in degrees Celsius from the kernel's thermal zones, or -1 where apps may not read it. */
    private static int socTemperature() {
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

    private final Set<String> shown = new HashSet<>();
    private boolean animate = true;

    /** The warnings' thresholds: the remote's battery in percent, free storage in megabytes. */
    void setLimits(int batteryPercent, int storageMb) {
        lowBattery = batteryPercent;
        lowStorage = storageMb * 1_000_000L;
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
            tray.setImageBitmap(bitmap(R.drawable.ic_xmb_download_tray, XmbStyle.GLOW_WHITE));
            ImageView arrow = new ImageView(act);
            arrow.setImageBitmap(bitmap(R.drawable.ic_xmb_download_arrow, XmbStyle.GLOW_WHITE));
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
        downloadText.setText(percent(progress));
        if (download.getParent() == null) download.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        if (!downloadAnim.isStarted() && animating) downloadAnim.start();
        return download;
    }

    // ---- the network speed: the box's download rate, read once a second while the item may show ----------------

    /** Shown when needed from 2 Mb/s held for two seconds, hidden again after eight seconds under 0.5 Mb/s. */
    private static final float BUSY_MBPS = 2f, QUIET_MBPS = 0.5f;
    private static final long SPEED_EVERY_MS = 1000, QUIET_MS = 8000;
    private long rxLast = -1, rxAt, quietSince;
    /** Megabits a second, smoothed over the last few seconds. */
    private float rate;
    private boolean busy;
    private int fastSamples;
    private View speed;
    private TextView speedText;

    private final Runnable speedTick = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            sampleSpeed();
            handler.postDelayed(this, SPEED_EVERY_MS);
        }
    };

    private void scheduleSpeed() {
        handler.removeCallbacks(speedTick);
        if (!started || XmbStatusPrefs.HIDDEN.equals(setup.mode("speed"))) return;
        rxLast = -1;
        handler.post(speedTick);
    }

    private void sampleSpeed() {
        long rx = TrafficStats.getTotalRxBytes(), now = SystemClock.elapsedRealtime();
        if (rx == TrafficStats.UNSUPPORTED) return;
        if (rxLast >= 0 && now > rxAt && rx >= rxLast) {
            rate = rate * 0.4f + XmbSpeedTest.mbps(rx - rxLast, now - rxAt) * 0.6f;
        }
        rxLast = rx;
        rxAt = now;
        boolean was = busy;
        if (rate >= BUSY_MBPS) {
            quietSince = 0;
            if (++fastSamples >= 2) busy = true;
        } else {
            fastSamples = 0;
            if (rate >= QUIET_MBPS) quietSince = 0;
            else if (quietSince == 0) quietSince = now;
            else if (now - quietSince >= QUIET_MS) busy = false;
        }
        if (speedText != null) speedText.setText(rate(rate));
        if (busy != was) refresh();
    }

    /** A percentage written the way the TV's language writes one ("45%", "%45", "45 %"). */
    private static String percent(int value) {
        return java.text.NumberFormat.getPercentInstance().format(value / 100.0);
    }

    /** A rate as the row shows it: "4.5 Mb/s", "45 Mb/s", "1.2 Gb/s". */
    static String rate(float mbps) {
        if (mbps >= 999.5f) return String.format(java.util.Locale.ROOT, "%.1f Gb/s", mbps / 1000f);
        if (mbps >= 9.95f) return Math.round(mbps) + " Mb/s";
        return String.format(java.util.Locale.ROOT, "%.1f Mb/s", Math.max(0f, mbps));
    }

    /** The speed item, kept between refreshes; its figure is as wide as the widest, so the row keeps still. */
    private View speedItem() {
        if (speed == null) {
            speed = pair(icon(R.drawable.ic_xmb_speed), rate(rate));
            speedText = (TextView) ((ViewGroup) speed).getChildAt(1);
            Paint p = speedText.getPaint();
            float w = 0;
            for (String widest : new String[]{"000 Mb/s", "0.0 Gb/s", "0.0 Mb/s"}) w = Math.max(w, p.measureText(widest));
            ViewGroup.LayoutParams lp = speedText.getLayoutParams();
            lp.width = (int) Math.ceil(w) + 1;
            speedText.setLayoutParams(lp);
        }
        speedText.setText(rate(rate));
        if (speed.getParent() == null) speed.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        return speed;
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
        return image(bitmap(res, XmbStyle.GLOW_WHITE));
    }

    private View warnIcon(int res) {
        return image(bitmap(res, XmbStyle.GLOW_AMBER));
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
        t.setTextColor(XmbStyle.FIGURE_INK);
        t.setIncludeFontPadding(false);
        t.setShadowLayer(u.px(9), 0, 0, XmbStyle.FIGURE_GLOW);
        t.setFontFeatureSettings("tnum");   // equal-width digits: a changing number does not nudge the row
        t.setText(number);
        // The row is as tall as an icon, shorter than a line of this size. Left to wrap, the text was cut to the
        // row's height and drawn from its top, the figures 5 px below the icons; at its full height it is centred
        // on the row, and lifted by what the figures sit below the middle of their line.
        t.setTranslationY(-inkDrop(t.getPaint()));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, t.getPaint().getFontMetricsInt(null));
        tlp.leftMargin = u.px(8);
        p.addView(t, tlp);
        p.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        return p;
    }

    /** The icon in one of XmbStyle's glows: its colour over a tight glow and a wide one (XmbGlow keeps it). */
    private Bitmap bitmap(int res, int[] glow) {
        return XmbGlow.icon(res, act.getDrawable(res), u.px(ICON), u.px(PAD), glow[0], glow[1], u.px(3), glow[2],
                u.px(10));
    }

    /** A white icon whose wide glow is a colour of its own (the theme colour, the dust's band). */
    private Bitmap colored(int res, int rgb) {
        return bitmap(res, new int[]{Color.WHITE, XmbStyle.COLOR_GLOW_INNER, (XmbStyle.COLOR_GLOW_ALPHA << 24) | (rgb & 0xFFFFFF)});
    }

    /**
     * How far below the middle of its line (ascent to descent, the box a TextView without font padding centres) the
     * ink of figures sits, in pixels: lifted by that, a number lines up with the icon beside it. Outfit's figures
     * stand on the baseline and reach to cap height, so their middle is a little below the line's.
     */
    static float inkDrop(Paint p) {
        Paint.FontMetricsInt fm = p.getFontMetricsInt();
        return inkCentre(p, "0123456789") - (fm.ascent + fm.descent) / 2f;
    }

    /** The middle of a text's ink, from the baseline (negative: above it). */
    static float inkCentre(Paint p, String text) {
        Rect r = new Rect();
        p.getTextBounds(text, 0, text.length(), r);
        return (r.top + r.bottom) / 2f;
    }

    // ---- what the icons read -----------------------------------------------------------------------------------

    /** Wi-Fi in three strengths, the LAN (or another way out), or no connection. */
    private int network() {
        XmbNet net = XmbNet.read(act);
        if (net.kind == XmbNet.NONE) return R.drawable.ic_xmb_wifi_off;
        if (net.kind != XmbNet.WIFI) return R.drawable.ic_xmb_lan;
        int level = net.rssi != 0 ? wifiBars(net.rssi) : 3;
        return level >= 3 ? R.drawable.ic_xmb_wifi_3 : level == 2 ? R.drawable.ic_xmb_wifi_2 : R.drawable.ic_xmb_wifi_1;
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
        XmbDebug.log(R.string.xmb_log_helper);
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

    /** Headphones or a speaker playing over Bluetooth (A2DP), as its icon; 0 for none. */
    @SuppressLint("MissingPermission")   // bluetoothAllowed()
    private int bluetoothAudio() {
        if (a2dp == null || !XmbRemoteBattery.bluetoothAllowed(act)) return 0;
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
}
