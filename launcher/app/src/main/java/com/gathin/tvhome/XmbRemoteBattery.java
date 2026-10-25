package com.gathin.tvhome;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The battery of the remote (or another Bluetooth input device), for the status row. Android keeps the level it reads
 * for each device but offers it to apps only through a hidden method, and on Android 11 has none for an LE remote
 * (the Mi Box S reports -1). So the level is also read from the remote itself: every LE remote that types over
 * Bluetooth (HID over GATT) carries the standard Battery Service. At most every six hours and only while Home is on
 * screen, Home opens its own GATT connection beside Android's, reads the one byte and closes it again; the remote's
 * own connection is untouched. The last level read is kept for a day.
 */
final class XmbRemoteBattery {
    private static final String TAG = "TvHome.Battery";
    private static final String PREF = "xmb_remote_battery";
    private static final long EVERY_MS = 6 * 3600_000L, KEEP_MS = 24 * 3600_000L, TIMEOUT_MS = 20_000;
    private static final UUID BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final ParcelUuid HID = ParcelUuid.fromString("00001812-0000-1000-8000-00805f9b34fb");
    private static long lastAttempt;
    private static boolean logged;

    private XmbRemoteBattery() {
    }

    /** Up to Android 11 the install-time BLUETOOTH permission covers these reads; later it needs a runtime grant. */
    static boolean bluetoothAllowed(Context ctx) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    /** Paired devices that are not headphones or speakers: remotes, keyboards, game pads. */
    @SuppressLint("MissingPermission")   // the callers check bluetoothAllowed()
    private static List<BluetoothDevice> inputDevices(BluetoothAdapter adapter) {
        List<BluetoothDevice> out = new ArrayList<>();
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            BluetoothClass c = d.getBluetoothClass();
            int major = c != null ? c.getMajorDeviceClass() : BluetoothClass.Device.Major.UNCATEGORIZED;
            if (major != BluetoothClass.Device.Major.AUDIO_VIDEO) out.add(d);
        }
        return out;
    }

    /** The lowest level, 0 to 100, a paired input device reports, else the one read from the remote; -1 for none. */
    @SuppressLint("MissingPermission")   // checked: bluetoothAllowed()
    static int level(Context ctx) {
        if (!bluetoothAllowed(ctx)) return -1;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return -1;
            Method level = BluetoothDevice.class.getMethod("getBatteryLevel");
            int lowest = -1;
            StringBuilder seen = new StringBuilder();
            for (BluetoothDevice d : inputDevices(adapter)) {
                int l = (Integer) level.invoke(d);
                seen.append(d.getType()).append('=').append(l).append(' ');
                if (l >= 0 && (lowest < 0 || l < lowest)) lowest = l;
            }
            if (!logged) {
                logged = true;
                Log.i(TAG, "input device battery levels: " + seen);
            }
            return lowest >= 0 ? lowest : cached(ctx);
        } catch (Exception e) {
            if (!logged) {
                logged = true;
                Log.i(TAG, "battery level not readable: " + e);
            }
            return cached(ctx);
        }
    }

    /** The last level read from the remote, or -1 when there is none from the last day. */
    static int cached(Context ctx) {
        XmbStamped s = XmbStamped.load(ctx, PREF, 1);
        return s != null && s.fresh(System.currentTimeMillis(), KEEP_MS) ? s.number(0, -1) : -1;
    }

    /** Reads the level again when the last try is six hours old; calls back on the main thread with a new level. */
    @SuppressLint("MissingPermission")   // checked: bluetoothAllowed()
    static void refresh(Context ctx, Runnable changed) {
        long now = System.currentTimeMillis();
        if (now - lastAttempt < EVERY_MS || !bluetoothAllowed(ctx)) return;
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return;
            BluetoothDevice remote = null;
            for (BluetoothDevice d : inputDevices(adapter)) {
                if (d.getType() != BluetoothDevice.DEVICE_TYPE_LE && d.getType() != BluetoothDevice.DEVICE_TYPE_DUAL) continue;
                if (offersHid(d)) {
                    remote = d;     // a remote, keyboard or pad: it types over Bluetooth (HID over GATT)
                    break;
                }
                if (remote == null) remote = d;   // UUIDs not cached yet: the first LE input-like device
            }
            if (remote == null) return;
            lastAttempt = now;
            read(app, remote, main, changed);
        } catch (RuntimeException e) {
            Log.i(TAG, "not read: " + e);
        }
    }

    @SuppressLint("MissingPermission")
    private static boolean offersHid(BluetoothDevice d) {
        ParcelUuid[] uuids = d.getUuids();
        if (uuids == null) return false;
        for (ParcelUuid u : uuids) if (HID.equals(u)) return true;
        return false;
    }

    @SuppressLint("MissingPermission")
    private static void read(Context app, BluetoothDevice device, Handler main, Runnable changed) {
        BluetoothGatt[] held = new BluetoothGatt[1];
        Runnable giveUp = () -> close(held[0]);
        BluetoothGattCallback cb = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int state) {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    if (!gatt.discoverServices()) finish(gatt);
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    finish(gatt);
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                BluetoothGattService s = gatt.getService(BATTERY_SERVICE);
                BluetoothGattCharacteristic c = s != null ? s.getCharacteristic(BATTERY_LEVEL) : null;
                if (c == null || !gatt.readCharacteristic(c)) {
                    Log.i(TAG, "no battery service on the remote");
                    finish(gatt);
                }
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic c, int status) {
                byte[] v = c.getValue();
                if (status == BluetoothGatt.GATT_SUCCESS && v != null && v.length > 0) {
                    int level = v[0] & 0xFF;
                    Log.i(TAG, "remote battery " + level + "%");
                    if (level <= 100) {
                        new XmbStamped(System.currentTimeMillis(), level).save(app, PREF);
                        main.post(changed);
                    }
                }
                finish(gatt);
            }

            private void finish(BluetoothGatt gatt) {
                main.removeCallbacks(giveUp);
                main.post(() -> close(gatt));
            }
        };
        held[0] = device.connectGatt(app, false, cb, BluetoothDevice.TRANSPORT_LE);
        main.postDelayed(giveUp, TIMEOUT_MS);
    }

    @SuppressLint("MissingPermission")
    private static void close(BluetoothGatt gatt) {
        if (gatt == null) return;
        try {
            gatt.disconnect();
            gatt.close();
        } catch (RuntimeException ignored) {
        }
    }
}
