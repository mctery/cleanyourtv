package com.gathin.tvhome;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Android takes an app's accessibility services off the enabled list when the app is force-stopped (Settings, a
 * cleaner app, or am force-stop), and the remote's Home, Input and app buttons then stop working until the helper
 * is turned on again over ADB. With restore_key_services on, a running helper records itself here and drops the
 * record when Android unbinds it (the user turned it off); a force stop kills the process before that, so the
 * record stays. Home starting again puts recorded helpers back on the list, when the installer granted
 * WRITE_SECURE_SETTINGS. With the flag off nothing is recorded and nothing is restored.
 */
final class ServiceRepair {
    private static final String PREFS = "accessibility_helpers";

    private ServiceRepair() {
    }

    static void connected(Context context, Class<?> service) {
        prefs(context).edit().putBoolean(service.getName(), true).apply();
    }

    static void disconnected(Context context, Class<?> service) {
        prefs(context).edit().remove(service.getName()).apply();
    }

    private static final String BACKUP = "accessibility_rebind_backup";

    /** Android's list of enabled accessibility services, "" for none (it can also hold null or "null"). */
    private static String enabledList(ContentResolver cr) {
        String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return list == null || list.equals("null") ? "" : list;
    }

    /** The services on such a list. */
    private static List<ComponentName> parse(String list) {
        List<ComponentName> out = new ArrayList<>();
        for (String entry : list.split(":")) {
            ComponentName name = ComponentName.unflattenFromString(entry);
            if (name != null) out.add(name);
        }
        return out;
    }

    /** Puts back a list rebind() took off, if it is still off (rebind's own delayed step, or restore after a crash). */
    private static void putBack(Context context) {
        SharedPreferences backup = context.getSharedPreferences(BACKUP, Context.MODE_PRIVATE);
        String list = backup.getString(BACKUP, null);
        if (list == null) return;
        try {
            ContentResolver cr = context.getContentResolver();
            if (enabledList(cr).isEmpty()) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list);
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
            backup.edit().remove(BACKUP).apply();
        } catch (RuntimeException ignored) {
        }
    }

    static void restore(Context context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return;
        putBack(context);
        try {
            ContentResolver cr = context.getContentResolver();
            String list = enabledList(cr);
            List<ComponentName> enabled = parse(list);
            StringBuilder updated = new StringBuilder(list);
            boolean missing = false;
            for (String service : prefs(context).getAll().keySet()) {
                ComponentName name = new ComponentName(context.getPackageName(), service);
                if (enabled.contains(name)) continue;
                if (updated.length() > 0) updated.append(':');
                updated.append(name.flattenToShortString());
                missing = true;
            }
            if (!missing) return;
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated.toString());
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (RuntimeException ignored) {
            // The setting is the system's; a refusal leaves the helper off, as before.
        }
    }

    /** The helper should be running: it is on Android's list, or it recorded itself and was not turned off. */
    static boolean expected(Context context, Class<?> service) {
        if (prefs(context).contains(service.getName())) return true;
        return parse(enabledList(context.getContentResolver()))
                .contains(new ComponentName(context.getPackageName(), service.getName()));
    }

    /**
     * Turns accessibility off and on again, the list kept, so Android binds a helper it still holds as crashed (seen
     * on the Mi Box S, Android 11, after a force stop: the helper back on the list but never started). Needs
     * WRITE_SECURE_SETTINGS; false when that was not granted.
     */
    static boolean rebind(Context context, android.os.Handler handler) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return false;
        restore(context);
        ContentResolver cr = context.getContentResolver();
        try {
            String list = enabledList(cr);
            if (list.isEmpty()) return false;
            // Written down first: if this process dies in the two seconds the list is empty, the next restore()
            // puts every app's helpers back, not just ours.
            context.getSharedPreferences(BACKUP, Context.MODE_PRIVATE).edit().putString(BACKUP, list).commit();
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            handler.postDelayed(() -> putBack(context), 2000);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
