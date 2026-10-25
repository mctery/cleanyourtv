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
 * dimmed picture of Home with the app's name covers Settings.
 *
 * It presses nothing but those two: the row whose text is Settings' own "Force stop" (read from the Settings app, so
 * in the TV's language) on the page that shows the app's package name, then OK in the confirmation whose title and
 * description are Settings' own force-stop texts. Anything else, or nothing within a few seconds, and it leaves that
 * app as it is. Keys are held while it works; Back stops it. Home comes back at the end.
 */
final class XmbCloser {
    private static final String TAG = "TvHome.Closer";
    /** Steps and limits of the work in Settings; then how long after Home has the focus the cover goes (a frame or
     *  two for Home to draw), or at the latest. */
    private static final long STEP_MS = 120, PAGE_MS = 4000, CONFIRM_MS = 3000, AFTER_MS = 350, HOME_DRAWN_MS = 120,
            REVEAL_LATEST_MS = 3000;
    /** How dark the picture of the screen behind the message is: the menu's own text must not compete with it. */
    private static final float SCRIM = 0.74f;

    /** On the Home intent it sends at the end: Home comes back as it was, not as for the Home button. */
    static final String EXTRA_BACK = "com.gathin.tvhome.closer_back";

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

    /** A key while it works: held (the page under the cover must not move), Back stops it. */
    static boolean onKey(KeyEvent event) {
        XmbCloser c = current;
        if (c == null) return false;
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) c.cancelled = true;
        return true;
    }

    /**
     * Closes the apps one after another, a picture of the screen (backdrop, dimmed; black without one) behind the
     * message; false (nothing done) when the helper is not there or one already runs.
     */
    static boolean close(Context ctx, List<XmbRunning.App> apps, List<String> titles, android.graphics.Bitmap backdrop,
                         Listener listener) {
        InputKeyService svc = InputKeyService.instance;
        Texts t = texts(ctx);
        if (svc == null || t == null || current != null || apps.isEmpty()) return false;
        current = new XmbCloser(svc, t, apps, titles, backdrop, listener);
        current.start();
        return true;
    }

    /** The one whose cover waits for Home to be on screen again (homeBack), or null. */
    @android.annotation.SuppressLint("StaticFieldLeak")
    private static XmbCloser leaving;

    /** Home has the screen again (its window has the focus): the cover fades a frame later, showing it as it was. */
    static void homeBack() {
        XmbCloser c = leaving;
        if (c == null) return;
        leaving = null;
        c.handler.removeCallbacks(c.hideRunnable);
        c.handler.postDelayed(c.hideRunnable, HOME_DRAWN_MS);
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
    private final android.graphics.Bitmap backdrop;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable = this::hideCover;
    private final List<String> stopped = new ArrayList<>();
    private int index, phase;
    private long phaseAt;
    /** An app's page was asked for: Settings came up over Home, which must be brought back. */
    private boolean opened;
    private volatile boolean cancelled;
    /** The cover this closing shows, while it does. */
    private Cover cover;

    private XmbCloser(AccessibilityService svc, Texts t, List<XmbRunning.App> apps, List<String> titles,
                      android.graphics.Bitmap backdrop, Listener listener) {
        this.svc = svc;
        this.t = t;
        this.apps = apps;
        this.titles = titles;
        this.backdrop = backdrop;
        this.listener = listener;
    }

    private void start() {
        showCover();
        // A moment for the cover to come up before Settings does.
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
                opened = true;
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
        // Home in front again, under the cover; when no app's page was asked for, Home was never left.
        if (opened) {
            try {
                svc.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setPackage(svc.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_BACK, true));
            } catch (RuntimeException e) {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            }
        }
        // The cover goes once Home has the screen again (homeBack), showing the same picture it hid.
        leaving = this;
        handler.postDelayed(hideRunnable, REVEAL_LATEST_MS);
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

    // ---- the cover over Settings -----------------------------------------------------------------------------

    /**
     * The cover's window: made at the first closing, then kept between closings while Home is on screen, left out of
     * the picture (a window at alpha 0 is not drawn at all). Taking a window down holds up this process's drawing for
     * a quarter of a second, and the wave stopped every time the cover went; so it goes once Home is out of sight
     * (homeHidden), and the next closing uses it again.
     */
    private static final class Cover {
        final AccessibilityService owner;
        final WindowManager wm;
        final WindowManager.LayoutParams params;
        final FrameLayout root;
        final ImageView picture, icon;
        final View scrim;
        final TextView line, count;
        /** Coming up, up, or fading out: a closing uses it. */
        boolean busy;

        Cover(AccessibilityService c) {
            owner = c;
            wm = c.getSystemService(WindowManager.class);
            float s = c.getResources().getDisplayMetrics().widthPixels / 1920f;
            root = new FrameLayout(c);
            root.setBackgroundColor(Color.BLACK);
            root.setAlpha(0f);
            picture = new ImageView(c);
            picture.setScaleType(ImageView.ScaleType.FIT_XY);
            root.addView(picture, new FrameLayout.LayoutParams(-1, -1));
            scrim = new View(c);
            scrim.setBackgroundColor(Color.BLACK);
            scrim.setAlpha(SCRIM);
            root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            icon = new ImageView(c);
            col.addView(icon, new LinearLayout.LayoutParams(Math.round(112 * s), Math.round(112 * s)));
            line = new TextView(c);
            line.setTypeface(c.getResources().getFont(R.font.outfit_light));
            line.setTextSize(TypedValue.COMPLEX_UNIT_PX, 40 * s);
            line.setTextColor(XmbStyle.INK);
            line.setShadowLayer(12 * s, 0, 0, Color.BLACK);   // clear of the dimmed menu behind it
            line.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = Math.round(28 * s);
            col.addView(line, lp);
            count = new TextView(c);
            count.setTypeface(c.getResources().getFont(R.font.outfit_regular));
            count.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24 * s);
            count.setTextColor(XmbStyle.INK_DIM);
            count.setShadowLayer(10 * s, 0, 0, Color.BLACK);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            clp.topMargin = Math.round(12 * s);
            col.addView(count, clp);
            root.addView(col, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    PixelFormat.TRANSLUCENT);
            wm.addView(root, params);
        }

        /** In the picture, or left out of it: at alpha 0 the window is not drawn at all. */
        void inPicture(boolean on) {
            float a = on ? 1f : 0f;
            if (params.alpha == a) return;
            params.alpha = a;
            try {
                wm.updateViewLayout(root, params);
            } catch (RuntimeException ignored) {
                // gone with the service
            }
        }

        void remove() {
            root.animate().cancel();
            try {
                wm.removeView(root);
            } catch (RuntimeException ignored) {
                // already gone with the service
            }
        }
    }

    /** The cover kept for the next closing, or null. */
    @android.annotation.SuppressLint("StaticFieldLeak")
    private static Cover kept;
    /** Home is on screen (from its onStart to its onStop): the kept cover may stay. */
    private static boolean homeVisible = true;

    /** Home came up. */
    static void homeShown() {
        homeVisible = true;
    }

    /** Home is out of sight: the kept cover, unless a closing uses it, goes now, when its hitch is not seen. */
    static void homeHidden() {
        homeVisible = false;
        if (kept != null && !kept.busy) dropCover();
    }

    /** The helper was turned off: its windows went with it. */
    static void serviceGone() {
        dropCover();
    }

    private static void dropCover() {
        Cover c = kept;
        kept = null;
        if (c != null) c.remove();
    }

    private void showCover() {
        Cover c = kept;
        if (c != null && c.owner != svc) {
            dropCover();   // from an earlier binding of the helper
            c = null;
        }
        if (c == null) {
            try {
                c = new Cover(svc);
            } catch (RuntimeException e) {
                Log.w(TAG, "no cover: " + e);   // Settings shows instead; the closing works the same
                return;
            }
            kept = c;
        }
        cover = c;
        c.busy = true;
        // The screen as it was, dimmed: the message comes up over the menu, not over a black screen.
        c.picture.setImageBitmap(backdrop);
        c.picture.setVisibility(backdrop != null ? View.VISIBLE : View.GONE);
        c.scrim.setVisibility(backdrop != null ? View.VISIBLE : View.GONE);
        c.icon.setImageDrawable(null);
        c.line.setText("");
        c.count.setText("");
        c.root.animate().cancel();
        c.root.setAlpha(0f);
        c.inPicture(true);
        // In a layer drawn once: the full-screen picture is not drawn again for every frame of the fade.
        c.root.animate().alpha(1f).setDuration(150).withLayer().start();
    }

    private void label(XmbRunning.App app, int i) {
        Cover c = cover;
        if (c == null) return;
        Drawable d = app.icon;
        c.icon.setImageDrawable(d);
        c.icon.setVisibility(d != null ? View.VISIBLE : View.GONE);
        c.line.setText(svc.getString(R.string.xmb_closing, titles.get(i)));
        c.count.setText(apps.size() > 1 ? svc.getString(R.string.xmb_closing_count, i + 1, apps.size()) : "");
    }

    private void hideCover() {
        handler.removeCallbacks(hideRunnable);
        if (leaving == this) leaving = null;
        Cover c = cover;
        cover = null;
        if (c == null) return;
        c.root.animate().cancel();
        c.root.animate().alpha(0f).setDuration(300).withLayer().withEndAction(() -> {
            // Out of the picture and kept for the next closing; the screen's picture goes.
            c.inPicture(false);
            c.picture.setImageDrawable(null);
            c.icon.setImageDrawable(null);
            c.busy = false;
            if (c != kept) c.remove();
            else if (!homeVisible) dropCover();
        }).start();
    }
}
