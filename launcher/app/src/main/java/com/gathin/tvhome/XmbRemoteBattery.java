package com.gathin.tvhome;

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
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.UUID;

/**
 * A Bluetooth LE remote's battery, read from the remote itself: every LE remote that types over Bluetooth (HID over
 * GATT) also carries the standard Battery Service, but Android 11 does not pass its level on to apps (the Mi Box S
 * reports -1 for its remote). So, at most every six hours and only while Home is on screen, Home opens its own GATT
 * connection beside Android's, reads the one byte and closes it again; the remote's own connection is untouched.
 * The last level is kept for a day.
 */
final class XmbRemoteBattery {
    private static final String TAG = "TvHome.Battery";
    private static final String PREF = "xmb_remote_battery";
    private static final long EVERY_MS = 6 * 3600_000L, KEEP_MS = 24 * 3600_000L, TIMEOUT_MS = 20_000;
    private static final UUID BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static long lastAttempt;

    private XmbRemoteBattery() {
    }

    /** The last level read, 0 to 100, or -1 when there is none from the last day. */
    static int cached(Context ctx) {
        String s = prefs(ctx).getString(PREF, null);
        if (s == null) return -1;
        String[] p = s.split("\\|");
        try {
            long at = Long.parseLong(p[1]);
            return Math.abs(System.currentTimeMillis() - at) < KEEP_MS ? Integer.parseInt(p[0]) : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Reads the level again when the last try is six hours old; calls back on the main thread with a new level. */
    @SuppressLint("MissingPermission")   // the caller checks XmbStatus.bluetoothAllowed()
    static void refresh(Context ctx, Runnable changed) {
        long now = System.currentTimeMillis();
        if (now - lastAttempt < EVERY_MS) return;
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return;
            BluetoothDevice remote = null;
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                BluetoothClass c = d.getBluetoothClass();
                int major = c != null ? c.getMajorDeviceClass() : BluetoothClass.Device.Major.UNCATEGORIZED;
                if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) continue;
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

    private static final android.os.ParcelUuid HID = android.os.ParcelUuid.fromString("00001812-0000-1000-8000-00805f9b34fb");

    @SuppressLint("MissingPermission")
    private static boolean offersHid(BluetoothDevice d) {
        android.os.ParcelUuid[] uuids = d.getUuids();
        if (uuids == null) return false;
        for (android.os.ParcelUuid u : uuids) if (HID.equals(u)) return true;
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
                        prefs(app).edit().putString(PREF, level + "|" + System.currentTimeMillis()).apply();
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

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }
}
