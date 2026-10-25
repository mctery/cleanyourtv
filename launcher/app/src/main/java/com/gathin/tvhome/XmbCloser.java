package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Closes apps for Settings > Open apps the way the owner would by hand: the app's page in the TV's Settings, Force
 * stop, OK. On the Mi Box S (Android 11 with a later security change) an ordinary app may end only its own processes
 * (killBackgroundProcesses answers "Invalid packageName" for any other), and Force stop is the system's own way to
 * close an app; the remote-button helper, an accessibility service already running, presses the two buttons while a
 * black screen with the app's name covers Settings.
 *
 * It presses nothing but those two: the row whose text is Settings' own "Force stop" (read from the Settings app, so
 * in the TV's language) on the page that shows the app's package name, then OK in the confirmation whose title and
 * description are Settings' own force-stop texts. Anything else, or nothing within a few seconds, and it leaves that
 * app as it is. Keys are held while it works; Back stops it. Home comes back at the end.
 */
final class XmbCloser {
    private static final String TAG = "TvHome.Closer";
    private static final long STEP_MS = 120, PAGE_MS = 4000, CONFIRM_MS = 3000, AFTER_MS = 350, REVEAL_MS = 450;

    /** Told on the main thread when it is over: the apps it pressed Force stop for, and whether Back stopped it. */
    interface Listener {
        void done(List<String> stopped, boolean cancelled);
    }

    /** The one at work: it holds the helper only while it works, and lets go when it is over. */
    @android.annotation.SuppressLint("StaticFieldLeak")
    private static XmbCloser current;

    static boolean running() {
        return current != null;
    }

    /** A key while it works: held (the page under the black screen must not move), Back stops it. */
    static boolean onKey(KeyEvent event) {
        XmbCloser c = current;
        if (c == null) return false;
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) c.cancelled = true;
        return true;
    }

    /** Closes the apps one after another; false (nothing done) when the helper is not there or one already runs. */
    static boolean close(Context ctx, List<XmbRunning.App> apps, List<String> titles, Listener listener) {
        InputKeyService svc = InputKeyService.instance;
        Texts t = texts(ctx);
        if (svc == null || t == null || current != null || apps.isEmpty()) return false;
        current = new XmbCloser(svc, t, apps, titles, listener);
        current.start();
        return true;
    }

    /** The Settings app and its texts, in the TV's language: Force stop, its warning, and OK. */
    private static final class Texts {
        String settingsPkg, forceStop, forceStopDesc;
        final Set<String> ok = new HashSet<>();
    }

    private static Texts texts(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = XmbCatalog.appInfoIntent(ctx, ctx.getPackageName());
        String pkg = probe.getPackage();
        if (pkg == null) {
            ResolveInfo ri = pm.resolveActivity(probe, 0);
            if (ri == null || ri.activityInfo == null) return null;
            pkg = ri.activityInfo.packageName;
        }
        try {
            Resources res = pm.getResourcesForApplication(pkg);
            Texts t = new Texts();
            t.settingsPkg = pkg;
            t.forceStop = string(res, pkg, "device_apps_app_management_force_stop");
            t.forceStopDesc = string(res, pkg, "device_apps_app_management_force_stop_desc");
            for (String name : new String[]{"settings_ok", "okay"}) {
                String s = string(res, pkg, name);
                if (s != null) t.ok.add(s);
            }
            t.ok.add(Resources.getSystem().getString(android.R.string.ok));
            return t.forceStop != null ? t : null;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** One of the Settings app's own texts: another app's resources can only be found by name. */
    @android.annotation.SuppressLint("DiscouragedApi")
    private static String string(Resources res, String pkg, String name) {
        int id = res.getIdentifier(name, "string", pkg);
        if (id == 0) return null;
        try {
            return res.getString(id).trim();
        } catch (Resources.NotFoundException e) {
            return null;
        }
    }

    private static final int OPEN = 0, FIND = 1, CONFIRM = 2, SETTLE = 3;

    private final AccessibilityService svc;
    private final Texts t;
    private final List<XmbRunning.App> apps;
    private final List<String> titles;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> stopped = new ArrayList<>();
    private int index, phase;
    private long phaseAt;
    private volatile boolean cancelled;
    private View cover;
    private TextView line, count;
    private ImageView icon;

    private XmbCloser(AccessibilityService svc, Texts t, List<XmbRunning.App> apps, List<String> titles, Listener listener) {
        this.svc = svc;
        this.t = t;
        this.apps = apps;
        this.titles = titles;
        this.listener = listener;
    }

    private void start() {
        showCover();
        // A moment for the black to come up before Settings does.
        handler.postDelayed(this::step, 160);
    }

    private final Runnable stepRunnable = this::step;

    private void step() {
        if (cancelled || index >= apps.size()) {
            finish();
            return;
        }
        try {
            act(apps.get(index));
        } catch (RuntimeException e) {
            // The helper went away mid-way (turned off, the app updated): what is done is done, Home again.
            Log.w(TAG, "closing stopped: " + e);
            finish();
            return;
        }
        if (current == this) handler.postDelayed(stepRunnable, STEP_MS);
    }

    private void act(XmbRunning.App app) {
        long now = SystemClock.uptimeMillis();
        switch (phase) {
            case OPEN:
                label(app, index);
                try {
                    svc.startActivity(XmbCatalog.appInfoIntent(svc, app.pkg).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
                } catch (RuntimeException e) {
                    Log.w(TAG, "no app page for " + app.pkg + ": " + e);
                    next();
                    break;
                }
                to(FIND);
                break;
            case FIND: {
                AccessibilityNodeInfo root = settingsRoot();
                // This app's page (its package name is on it), not the one before, still on screen.
                AccessibilityNodeInfo row = root != null && hasText(root, app.pkg)
                        ? clickable(find(root, "android:id/title", Collections.singleton(t.forceStop))) : null;
                if (row != null && !row.isEnabled()) {
                    next();   // greyed out: the app is not running any more
                } else if (row != null && row.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    to(CONFIRM);
                } else if (now - phaseAt > PAGE_MS) {
                    Log.w(TAG, "no Force stop for " + app.pkg);
                    next();
                }
                break;
            }
            case CONFIRM: {
                AccessibilityNodeInfo root = settingsRoot();
                String id = t.settingsPkg + ":id/";
                boolean asks = root != null && find(root, id + "guidance_title", Collections.singleton(t.forceStop)) != null
                        && (t.forceStopDesc == null
                        || find(root, id + "guidance_description", Collections.singleton(t.forceStopDesc)) != null);
                AccessibilityNodeInfo ok = asks ? clickable(find(root, id + "guidedactions_item_title", t.ok)) : null;
                if (ok != null && ok.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    stopped.add(app.pkg);
                    to(SETTLE);
                } else if (now - phaseAt > CONFIRM_MS) {
                    Log.w(TAG, "no confirmation for " + app.pkg);
                    next();
                }
                break;
            }
            default:
                if (now - phaseAt >= AFTER_MS) next();
                break;
        }
    }

    private void to(int p) {
        phase = p;
        phaseAt = SystemClock.uptimeMillis();
    }

    private void next() {
        index++;
        to(OPEN);
    }

    private void finish() {
        handler.removeCallbacks(stepRunnable);
        current = null;
        // Home in front again (its return animation plays under the black), then the black goes.
        try {
            svc.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setPackage(svc.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        }
        handler.postDelayed(this::hideCover, REVEAL_MS);
        Log.i(TAG, "stopped " + stopped + (cancelled ? " (stopped with Back)" : ""));
        listener.done(stopped, cancelled);
    }

    // ---- the page --------------------------------------------------------------------------------------------

    /** The window in front, if it is the Settings app's. */
    private AccessibilityNodeInfo settingsRoot() {
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return null;
        root.refresh();
        CharSequence pkg = root.getPackageName();
        return pkg != null && pkg.toString().equals(t.settingsPkg) ? root : null;
    }

    private static boolean hasText(AccessibilityNodeInfo root, String text) {
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(text)) {
            CharSequence s = n.getText();
            if (s != null && s.toString().trim().equals(text)) return true;
        }
        return false;
    }

    /** The node with that view id whose whole text is one of texts. */
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, String viewId, Collection<String> texts) {
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(viewId)) {
            CharSequence s = n.getText();
            if (s != null && texts.contains(s.toString().trim())) return n;
        }
        return null;
    }

    /** The row a text sits in: the text itself, or the nearest of its parents that takes a click. */
    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        for (int depth = 0; n != null && depth < 4; depth++) {
            n.refresh();
            if (n.isClickable()) return n;
            n = n.getParent();
        }
        return null;
    }

    // ---- the black screen over Settings ------------------------------------------------------------------------

    private void showCover() {
        Context c = svc;
        float s = c.getResources().getDisplayMetrics().widthPixels / 1920f;
        FrameLayout root = new FrameLayout(c);
        root.setBackgroundColor(Color.BLACK);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        icon = new ImageView(c);
        col.addView(icon, new LinearLayout.LayoutParams(Math.round(112 * s), Math.round(112 * s)));
        line = new TextView(c);
        line.setTypeface(c.getResources().getFont(R.font.outfit_light));
        line.setTextSize(TypedValue.COMPLEX_UNIT_PX, 40 * s);
        line.setTextColor(XmbStyle.INK);
        line.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Math.round(28 * s);
        col.addView(line, lp);
        count = new TextView(c);
        count.setTypeface(c.getResources().getFont(R.font.outfit_regular));
        count.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24 * s);
        count.setTextColor(XmbStyle.INK_DIM);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = Math.round(12 * s);
        col.addView(count, clp);
        root.addView(col, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT);
        try {
            svc.getSystemService(WindowManager.class).addView(root, wlp);
            cover = root;
            root.setAlpha(0f);
            root.animate().alpha(1f).setDuration(150).start();
        } catch (RuntimeException e) {
            Log.w(TAG, "no cover: " + e);   // Settings shows instead; the closing works the same
        }
    }

    private void label(XmbRunning.App app, int i) {
        if (cover == null) return;
        Drawable d = app.icon;
        icon.setImageDrawable(d);
        icon.setVisibility(d != null ? View.VISIBLE : View.GONE);
        line.setText(svc.getString(R.string.xmb_closing, titles.get(i)));
        count.setText(apps.size() > 1 ? svc.getString(R.string.xmb_closing_count, i + 1, apps.size()) : "");
    }

    private void hideCover() {
        View v = cover;
        cover = null;
        if (v == null) return;
        v.animate().alpha(0f).setDuration(300).withEndAction(() -> {
            try {
                svc.getSystemService(WindowManager.class).removeView(v);
            } catch (RuntimeException ignored) {
                // already gone with the service
            }
        }).start();
    }
}
