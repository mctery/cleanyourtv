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
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                adapter.getProfileProxy(act, new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        if (started) {
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
        handler.removeCallbacks(refreshRunnable);
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
        refresh();
    }

    private final Runnable refreshRunnable = this::refresh;

    /** Coalesces bursts (download progress, signal changes) into one redraw. */
    private void refreshSoon() {
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, 250);
    }

    // ---- the row ---------------------------------------------------------------------------------------------------

    void refresh() {
        row.removeAllViews();
        List<View> warn = new ArrayList<>(), busy = new ArrayList<>(), always = new ArrayList<>();
        int battery = remoteBattery();
        if (battery >= 0 && battery < LOW_BATTERY) warn.add(warnIcon(R.drawable.ic_xmb_battery_1_bar));
        if (lowStorage()) warn.add(warnIcon(R.drawable.ic_xmb_hard_drive));
        if (adbOn()) warn.add(warnIcon(R.drawable.ic_xmb_developer_mode_tv));
        if (helperDown()) warn.add(warnIcon(R.drawable.ic_xmb_settings_remote));

        int progress = installProgress();
        if (progress >= 0) {
            busy.add(downloading(progress));
        } else if (downloadAnim != null) {
            downloadAnim.cancel();
        }
        if (usbDrive()) busy.add(icon(R.drawable.ic_xmb_usb));
        int audio = bluetoothAudio();
        if (audio != 0) busy.add(icon(audio));

        XmbWeather w = XmbWeather.current(act);
        if (w != null) always.add(pair(icon(XmbWeather.icon(w.code, w.day)), w.temperature + "°"));
        int net = network();
        always.add(net == R.drawable.ic_xmb_wifi_off
                ? image(bitmap(net, 0x73FFFFFF, 0x26FFFFFF, 0x14FFFFFF)) : icon(net));
        always.add(image(bitmap(R.drawable.ic_xmb_palette, Color.WHITE, 0x73FFFFFF, (0xF2 << 24) | (accent & 0xFFFFFF))));

        boolean first = true;
        for (List<View> group : java.util.Arrays.asList(warn, busy, always)) {
            if (group.isEmpty()) continue;
            for (int i = 0; i < group.size(); i++) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) group.get(i).getLayoutParams();
                if (!first) lp.leftMargin += u.px(i == 0 ? GROUP_GAP : GAP);
                row.addView(group.get(i), lp);
                first = false;
            }
        }
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
        if (download.getParent() != null) ((ViewGroup) download.getParent()).removeView(download);
        download.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        if (!downloadAnim.isStarted()) downloadAnim.start();
        return download;
    }

    /** The menu faded out to the wave: nothing in the row needs to move meanwhile. */
    void setAnimating(boolean on) {
        if (downloadAnim == null || !downloadAnim.isStarted()) return;
        if (on) downloadAnim.resume();
        else downloadAnim.pause();
    }

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
                int level = info != null ? WifiManager.calculateSignalLevel(info.getRssi(), 4) : 3;
                return level >= 3 ? R.drawable.ic_xmb_wifi_3 : level == 2 ? R.drawable.ic_xmb_wifi_2 : R.drawable.ic_xmb_wifi_1;
            }
            return R.drawable.ic_xmb_lan;
        } catch (RuntimeException e) {
            return R.drawable.ic_xmb_wifi_off;
        }
    }

    private boolean lowStorage() {
        try {
            return new StatFs(Environment.getDataDirectory().getPath()).getAvailableBytes() < LOW_STORAGE_BYTES;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** USB debugging, which on many boxes (the Mi Box S among them) also opens ADB on the network, port 5555. */
    private boolean adbOn() {
        return Settings.Global.getInt(act.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
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
            rebindTried = true;
            Log.i(TAG, "remote-button helper not running; turning accessibility off and on");
            if (ServiceRepair.rebind(act, handler)) {
                handler.postDelayed(refreshRunnable, 6000);
                return false;
            }
        }
        return true;
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
            return -1;
        }
    }
}
