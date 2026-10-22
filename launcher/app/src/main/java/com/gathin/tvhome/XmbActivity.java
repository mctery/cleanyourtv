package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Home as a PlayStation-style cross-bar (XMB): categories across, the selected category's items down, the selected
 * item always right under the category icon (the PS3 layout; items already passed stack above the bar), over the
 * PS3 wave in the month's colour ({@link XmbWave}). Design and timings follow KittiwanaNotify's admin (xmb.js,
 * admin.css): anchor at 24% of the width, category row at about 30% of the height, 240 ms with the same easing,
 * the menu fading out after 45 s idle while the wave keeps flowing.
 *
 * Keys: left/right change category, up/down move in it, OK opens, hold OK (or Menu) for options, Home when Home is
 * already in front goes back to Video. Becomes Home from the tile grid's Settings menu (hold OK on Settings, "XMB
 * Home") or with {@code am start -n com.gathin.tvhome/.XmbActivity -a com.gathin.tvhome.action.XMB_HOME}; its own
 * "Classic Home" item gives Home back to the grid ({@link #setXmbHome}).
 *
 * Everything is laid out in a 1920x1080 coordinate space and scaled to the screen ({@link #px}).
 */
public class XmbActivity extends Activity {
    // Geometry, in 1920x1080 units.
    private static final float AX = 1920 * 0.24f;          // the cross anchor: centre of the category icon
    private static final float SLOT = 200;                  // category spacing
    private static final float CAT_TOP = 296, CAT_ICON = 110, CAT_LABEL_Y = 124;
    /** The selected category icon's zoom. Icons are drawn at that size and shrunk when not selected: a vector icon
     *  is rasterised at its view's size, and scaling the view up after that blurs it. */
    private static final float CAT_ZOOM = 1.3f, CAT_BOX = CAT_ICON * CAT_ZOOM;
    private static final float COL_TOP = 488;               // the selected item's top edge
    private static final float ABOVE_BOTTOM = 286;          // passed items stack upward from here
    private static final float GONE_Y = 80;                 // passed items fade out above this centre
    private static final float ROW_H = 176;
    private static final float THUMB_W = 240, THUMB_H = 134, GLYPH = 120, RADIUS = 20;
    private static final float META_X = AX + 152;
    private static final long IDLE_MS = 45_000;
    private static final int INK = 0xF0FFFFFF, INK_DIM = 0xADFFFFFF, INK_FAINT = 0x85FFFFFF;
    /** Starting the XMB with this action makes it Home (enables the XmbHome alias). */
    static final String ACTION_XMB_HOME = "com.gathin.tvhome.action.XMB_HOME";
    /** And this one makes the tile grid Home again (for scripts: the shell cannot switch our components itself). */
    static final String ACTION_CLASSIC_HOME = "com.gathin.tvhome.action.CLASSIC_HOME";

    private float s;
    private Config cfg;
    private XmbSounds sounds;
    private XmbWave wave;
    private GLSurfaceView waveView;
    private boolean reducedMotion;
    private long animMs = 240;
    private final PathInterpolator ease = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Typeface light, regular, medium, extraLight;

    private FrameLayout ui, catsRow, column;
    private View selBar;
    private TextView clockH, clockColon, clockM, dateView, toast;
    private LinearLayout options;

    private List<XmbCatalog.Category> cats = Collections.emptyList();
    private int cat = -1;
    /** Category key to the key of its selected item, so a reload keeps every category where it was. */
    private final Map<String, String> selectedKey = new HashMap<>();
    private String selectedCat = XmbCatalog.VIDEO;
    private final List<Row> rows = new ArrayList<>();
    private final List<CatView> catViews = new ArrayList<>();
    private int accent = 0xD6517A, accentUi = 0xD6517A;
    private boolean resumed, idle, okLongPressed, dirty = true;
    /** Stopped: another app is in front. A new intent pauses even a visible activity, so resumed cannot tell. */
    private boolean stopped = true;
    private static boolean openingPlayed;

    // Options menu (hold OK or Menu).
    private final List<Runnable> optionActions = new ArrayList<>();
    private int optionIdx;

    private static final class Row {
        XmbCatalog.Item item;
        FrameLayout root, thumbBox;
        ImageView glow;
        View ring;
        TextView title, detail;
        boolean tile;
    }

    private static final class CatView {
        FrameLayout root;
        ImageView icon, glow;
        TextView label;
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Started only to switch Home: switch, hand over to whatever Home is now, and close. Staying open would leave
        // a second XMB (this one, started by component) behind the one Home starts through the XmbHome alias.
        String action = getIntent() != null ? getIntent().getAction() : null;
        if (ACTION_XMB_HOME.equals(action) || ACTION_CLASSIC_HOME.equals(action)) {
            switchHome(ACTION_XMB_HOME.equals(action));
            return;
        }
        cfg = Config.load(this);
        // restore_key_services: after a force stop, put the remote's accessibility helpers back, as the tile grid
        // does when it starts (ServiceRepair; while the XMB is Home the grid is switched off and never starts).
        if (cfg.restoreKeyServices) ServiceRepair.restore(this);
        reducedMotion = cfg.reducedMotion || !cfg.animations;
        if (reducedMotion) animMs = 0;
        sounds = new XmbSounds(this);
        s = getResources().getDisplayMetrics().widthPixels / 1920f;
        light = getResources().getFont(R.font.outfit_light);
        regular = getResources().getFont(R.font.outfit_regular);
        medium = getResources().getFont(R.font.outfit_medium);
        extraLight = getResources().getFont(R.font.outfit_extralight);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        if (glesVersion() >= 0x30000) {
            wave = new XmbWave();
            waveView = new GLSurfaceView(this);
            waveView.setEGLContextClientVersion(3);
            waveView.setEGLConfigChooser(new XmbWave.ConfigChooser());   // 4x MSAA where offered: the wave's thin lines
            waveView.setPreserveEGLContextOnPause(true);
            // Drawn at the screen's own resolution, up to 1920x1080: a TV whose interface runs at 4K would otherwise
            // draw four times the pixels for a wave that is soft anyway.
            int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
            if (w > 1920) waveView.getHolder().setFixedSize(1920, Math.round(1920f * h / w));
            waveView.setRenderer(wave);
            waveView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
            root.addView(waveView, new FrameLayout.LayoutParams(-1, -1));
        }
        ui = new FrameLayout(this);
        ui.setClipChildren(false);
        root.addView(ui, new FrameLayout.LayoutParams(-1, -1));
        buildChrome();
        setContentView(root);

        IntentFilter pkgs = new IntentFilter();
        pkgs.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgs.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgs.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgs.addDataScheme("package");
        registerReceiver(packageReceiver, pkgs);
        IntentFilter dreams = new IntentFilter(Intent.ACTION_DREAMING_STARTED);
        dreams.addAction(Intent.ACTION_DREAMING_STOPPED);
        registerReceiver(dreamReceiver, dreams);
    }

    private int glesVersion() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        return am != null ? am.getDeviceConfigurationInfo().reqGlEsVersion : 0;
    }

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            dirty = true;
            if (resumed) reload();
        }
    };

    /**
     * The screensaver (Google TV's ambient mode, for one) runs over Home without pausing it, and does not take the
     * window's focus either, so the wave would go on drawing under it. Stop it for as long as the system dreams.
     */
    private boolean dreaming;
    private final BroadcastReceiver dreamReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            dreaming = Intent.ACTION_DREAMING_STARTED.equals(intent.getAction());
            if (waveView == null) return;
            if (dreaming) {
                Choreographer.getInstance().removeFrameCallback(waveTick);
            } else if (resumed) {
                wave.resetClock();
                startWave();
            }
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        if (sounds == null) return;   // only here to switch Home (switchHome)
        stopped = false;
        dirty = true;   // last-opened times and the network line change while an app is in front
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sounds == null) return;
        resumed = true;
        updateAccent();
        if (dirty) reload();
        tick();
        if (!reducedMotion) handler.post(blink);
        if (waveView != null) {
            waveView.onResume();
            wave.resetClock();
            startWave();
        }
        wake(false);
        if (!openingPlayed && SystemClock.elapsedRealtime() < 5 * 60_000) sounds.play(XmbSounds.OPENING);
        openingPlayed = true;
    }

    @Override
    protected void onPause() {
        if (sounds == null) {
            super.onPause();
            return;
        }
        resumed = false;
        closeOptions();
        handler.removeCallbacksAndMessages(null);
        if (waveView != null) waveView.onPause();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (sounds == null || waveView == null) return;
        // The assistant or another window over Home: the wave would keep drawing 30 frames a second under it.
        if (!hasFocus) {
            Choreographer.getInstance().removeFrameCallback(waveTick);
        } else if (resumed) {
            wave.resetClock();
            startWave();
        }
    }

    @Override
    protected void onStop() {
        stopped = true;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (sounds != null) {   // null when started only to switch Home back to the grid
            unregisterReceiver(packageReceiver);
            unregisterReceiver(dreamReceiver);
            sounds.release();
        }
        super.onDestroy();
    }

    /**
     * Makes the XMB Home (the XmbHome alias on, the tile grid's MainActivity off), or the grid again. Exactly one of the
     * two answers HOME at a time: with both, Android has no saved choice between two equal activities of one package
     * and shows its chooser, and only the shell or the chooser itself can save one. The one coming in is switched on
     * before the other goes off, so something always answers HOME. The grid's settings stay as they were.
     */
    static void setXmbHome(Context ctx, boolean on) {
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        android.content.ComponentName xmb = new android.content.ComponentName(ctx, "com.gathin.tvhome.XmbHome");
        android.content.ComponentName grid = new android.content.ComponentName(ctx, MainActivity.class);
        int enabled = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        int disabled = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        int keep = android.content.pm.PackageManager.DONT_KILL_APP;
        pm.setComponentEnabledSetting(on ? xmb : grid, enabled, keep);
        pm.setComponentEnabledSetting(on ? grid : xmb, disabled, keep);
    }

    /**
     * The XMB or the tile grid becomes Home and comes to the front; this instance closes. Switching the alias changes
     * the set of Home activities, and Android settles the new Home a moment later: a HOME intent sent before that
     * shows its chooser. So wait (up to 3 s) until HOME resolves to the one we chose, then send it.
     */
    private void switchHome(boolean xmb) {
        setXmbHome(this, xmb);
        String want = xmb ? "com.gathin.tvhome.XmbHome" : MainActivity.class.getName();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final int[] tries = {0};
        Runnable attempt = new Runnable() {
            @Override
            public void run() {
                android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(home,
                        android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
                boolean settled = ri != null && ri.activityInfo != null && want.equals(ri.activityInfo.name);
                if (!settled && ++tries[0] < 30) {
                    handler.postDelayed(this, 100);
                    return;
                }
                // Unrestricted on purpose: a HOME intent limited to this package resolves to a different set of
                // activities than the saved Home preference, and Android then drops that preference.
                try {
                    startActivity(home);
                } catch (RuntimeException ignored) {
                }
                finish();
            }
        };
        handler.post(attempt);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && ACTION_CLASSIC_HOME.equals(intent.getAction())) {
            switchHome(false);
            return;
        }
        // Home pressed while Home is already in front: back to Video, first item. From an app, keep the place.
        if (!stopped && intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) {
            closeOptions();
            selectedKey.remove(XmbCatalog.VIDEO);
            int video = indexOfCat(XmbCatalog.VIDEO);
            if (video >= 0 && video != cat) selectCategory(video, false);
            else if (cat >= 0) layoutColumn(false);
            wake(true);
        }
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back only closes the options menu.
        if (options != null && options.getVisibility() == View.VISIBLE) {
            closeOptions();
            sounds.play(XmbSounds.CANCEL);
        }
    }

    // ---- wave pacing -----------------------------------------------------------------------------------------

    private int frame;
    private final Choreographer.FrameCallback waveTick = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!resumed || waveView == null || dreaming) return;
            // 30 frames a second is smooth for a slow wave and halves the GPU's work.
            if ((frame++ & 1) == 0) waveView.requestRender();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void startWave() {
        Choreographer.getInstance().removeFrameCallback(waveTick);
        if (dreaming) return;
        if (reducedMotion) {
            waveView.requestRender();   // one still frame
            return;
        }
        Choreographer.getInstance().postFrameCallback(waveTick);
    }

    private void updateAccent() {
        Calendar now = Calendar.getInstance();
        int c = XmbAccent.colorFor(now.get(Calendar.MONTH), now.get(Calendar.HOUR_OF_DAY));
        if (c == accent && rows.size() > 0) return;
        accent = c;
        accentUi = XmbAccent.floor(c);
        if (wave != null) wave.setColor(c);
        if (waveView != null && reducedMotion) waveView.requestRender();
        if (selBar != null) selBar.setBackground(selBarDrawable());
        for (int i = 0; i < catViews.size(); i++) applyCatGlow(i);
        for (Row r : rows) applyRowGlow(r);
    }

    // ---- layout helpers --------------------------------------------------------------------------------------

    private int px(float units) {
        return Math.round(units * s);
    }

    private static int withAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    private <T extends View> T place(ViewGroup parent, T v, float x, float y, float w, float h) {
        parent.addView(v, new FrameLayout.LayoutParams(w < 0 ? -2 : px(w), h < 0 ? -2 : px(h)));
        v.setX(px(x));
        v.setY(px(y));
        return v;
    }

    private TextView text(Typeface face, float size, int color) {
        TextView t = new TextView(this);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** A blurred silhouette of the drawable in the colour, for the glow behind a selected icon. */
    private Bitmap glowOf(Drawable d, int size, int blur, int color) {
        int full = size + 2 * blur;
        Bitmap shape = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888);
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        copy.setTint(Color.WHITE);
        copy.setBounds(blur, blur, blur + size, blur + size);
        copy.draw(new Canvas(shape));
        return blurred(shape, blur, color);
    }

    private Bitmap glowRect(int w, int h, int radius, int blur, int color) {
        Bitmap shape = Bitmap.createBitmap(w + 2 * blur, h + 2 * blur, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        new Canvas(shape).drawRoundRect(new RectF(blur, blur, blur + w, blur + h), radius, radius, p);
        return blurred(shape, blur, color);
    }

    private static Bitmap blurred(Bitmap shape, int blur, int color) {
        Bitmap alpha = shape.extractAlpha();
        shape.recycle();
        Bitmap out = Bitmap.createBitmap(alpha.getWidth(), alpha.getHeight(), Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setMaskFilter(new BlurMaskFilter(Math.max(1, blur * 0.6f), BlurMaskFilter.Blur.NORMAL));
        new Canvas(out).drawBitmap(alpha, 0, 0, p);
        alpha.recycle();
        return out;
    }

    private Drawable selBarDrawable() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{withAlpha(accentUi, 0x42), withAlpha(accentUi, 0x18), withAlpha(accentUi, 0)});
        g.setCornerRadius(px(18));
        return g;
    }

    // ---- the fixed parts: clock, category row, column, options, toast -----------------------------------------

    private void buildChrome() {
        LinearLayout time = new LinearLayout(this);
        time.setOrientation(LinearLayout.HORIZONTAL);
        clockH = text(extraLight, 50, INK);
        clockColon = text(extraLight, 50, INK);
        clockColon.setText(":");
        clockColon.setPadding(px(2), 0, px(2), 0);
        clockM = text(extraLight, 50, INK);
        time.addView(clockH);
        time.addView(clockColon);
        time.addView(clockM);
        dateView = text(regular, 21, INK_FAINT);
        // Spaced like KittiwanaNotify's date, except in Thai, whose marks sit on the letters and must not be pulled apart.
        dateView.setLetterSpacing("th".equals(Locale.getDefault().getLanguage()) ? 0f : 0.06f);
        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setGravity(Gravity.END);
        clock.addView(time, new LinearLayout.LayoutParams(-2, -2));   // not the date's width: that cut the minutes off
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = px(8);
        clock.addView(dateView, dlp);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        clp.rightMargin = px(96);
        clp.topMargin = px(54);
        ui.addView(clock, clp);

        catsRow = new FrameLayout(this);
        catsRow.setClipChildren(false);
        place(ui, catsRow, 0, CAT_TOP, 1920 * 3, 170);

        column = new FrameLayout(this);
        column.setClipChildren(false);
        ui.addView(column, new FrameLayout.LayoutParams(-1, -1));

        options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(px(10), px(10), px(10), px(10));
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0xDB080B14);
        glass.setCornerRadius(px(18));
        glass.setStroke(Math.max(1, px(1.5f)), 0x1FFFFFFF);
        options.setBackground(glass);
        options.setVisibility(View.GONE);
        place(ui, options, 1250, 0, 460, -1);

        toast = text(regular, 24, INK);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xC7080B14);
        pill.setCornerRadius(px(40));
        toast.setBackground(pill);
        toast.setPadding(px(28), px(14), px(28), px(14));
        toast.setAlpha(0f);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tlp.bottomMargin = px(64);
        ui.addView(toast, tlp);
    }

    private boolean colonDim;
    private final Runnable blink = new Runnable() {
        @Override
        public void run() {
            // A step, not a fade: a fade redraws the whole screen 60 times a second for one character.
            colonDim = !colonDim;
            clockColon.setAlpha(colonDim ? 0.25f : 1f);
            if (resumed) handler.postDelayed(this, 500);
        }
    };

    private final Runnable tickRunnable = this::tick;

    private void tick() {
        Date now = new Date();
        boolean h24 = DateFormat.is24HourFormat(this);
        Calendar c = Calendar.getInstance();
        int hour = c.get(Calendar.HOUR_OF_DAY);
        if (!h24) hour = hour % 12 == 0 ? 12 : hour % 12;
        clockH.setText(String.format(Locale.ROOT, h24 ? "%02d" : "%d", hour));
        clockM.setText(String.format(Locale.ROOT, "%02d", c.get(Calendar.MINUTE)));
        Locale locale = Locale.getDefault();
        dateView.setText(DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), now));
        updateAccent();
        handler.removeCallbacks(tickRunnable);
        if (resumed) handler.postDelayed(tickRunnable, 60_000 - (System.currentTimeMillis() % 60_000) + 50);
    }

    // ---- content ---------------------------------------------------------------------------------------------

    private void reload() {
        dirty = false;
        closeOptions();
        if (cat >= 0 && cat < cats.size()) selectedCat = cats.get(cat).key;
        cats = XmbCatalog.load(this, cfg, sounds.available(), sounds.isOn());
        buildCategories();
        int target = indexOfCat(selectedCat);
        if (target < 0) target = Math.max(0, indexOfCat(XmbCatalog.VIDEO));
        cat = -1;
        selectCategory(target, true);
    }

    private int indexOfCat(String key) {
        for (int i = 0; i < cats.size(); i++) if (cats.get(i).key.equals(key)) return i;
        return -1;
    }

    private int selectedIndex() {
        if (cat < 0) return 0;
        String key = selectedKey.get(cats.get(cat).key);
        List<XmbCatalog.Item> items = cats.get(cat).items;
        for (int i = 0; i < items.size(); i++) if (items.get(i).key.equals(key)) return i;
        return 0;
    }

    private void buildCategories() {
        catsRow.removeAllViews();
        catViews.clear();
        for (int i = 0; i < cats.size(); i++) {
            XmbCatalog.Category c = cats.get(i);
            CatView v = new CatView();
            v.root = new FrameLayout(this);
            v.root.setClipChildren(false);
            place(catsRow, v.root, i * SLOT, 0, SLOT, 170);
            // Centred where a CAT_ICON-sized icon would sit, at the selected size (see CAT_ZOOM).
            float top = (CAT_ICON - CAT_BOX) / 2, glowPad = 24 * CAT_ZOOM;
            v.glow = place(v.root, new ImageView(this), (SLOT - CAT_BOX) / 2 - glowPad, top - glowPad,
                    CAT_BOX + 2 * glowPad, CAT_BOX + 2 * glowPad);
            v.glow.setAlpha(0f);
            v.icon = place(v.root, new ImageView(this), (SLOT - CAT_BOX) / 2, top, CAT_BOX, CAT_BOX);
            v.icon.setImageDrawable(c.icon.mutate());
            int pad = px(13 * CAT_ZOOM);
            v.icon.setPadding(pad, pad, pad, pad);
            v.icon.setImageTintList(android.content.res.ColorStateList.valueOf(INK_FAINT));
            v.icon.setAlpha(0.38f);
            v.label = place(v.root, text(medium, 25, INK), -50, CAT_LABEL_Y, SLOT + 100, -1);
            v.label.setGravity(Gravity.CENTER_HORIZONTAL);
            v.label.setText(c.label);
            v.label.setAlpha(0f);
            catViews.add(v);
            applyCatGlow(i);
        }
    }

    private void applyCatGlow(int i) {
        if (i >= cats.size()) return;
        CatView v = catViews.get(i);
        int size = px(CAT_BOX - 2 * 13 * CAT_ZOOM);
        v.glow.setImageBitmap(glowOf(cats.get(i).icon, size, px(37 * CAT_ZOOM), withAlpha(accentUi, 0xC0)));
        v.glow.setScaleType(ImageView.ScaleType.CENTER);
    }

    private void selectCategory(int index, boolean instant) {
        if (index < 0 || index >= cats.size()) return;
        boolean changed = index != cat;
        cat = index;
        selectedCat = cats.get(cat).key;
        float tx = px(AX - (index * SLOT + SLOT / 2));
        if (instant || animMs == 0) catsRow.setTranslationX(tx);
        else catsRow.animate().translationX(tx).setDuration(animMs).setInterpolator(ease).start();
        for (int i = 0; i < catViews.size(); i++) {
            CatView v = catViews.get(i);
            boolean sel = i == index;
            v.icon.setImageTintList(android.content.res.ColorStateList.valueOf(sel ? Color.WHITE : INK_FAINT));
            animate(v.icon, sel ? 1f : 0.38f, sel ? 1f : 1f / CAT_ZOOM, instant);
            animate(v.glow, sel ? 1f : 0f, sel ? 1f : 1f / CAT_ZOOM, instant);
            animate(v.label, sel ? 1f : 0f, 1f, instant);
        }
        if (changed || instant) buildColumn(!instant);
        if (changed && !instant && !rows.isEmpty()) {
            announce(cats.get(cat).label + ", " + rows.get(Math.min(selectedIndex(), rows.size() - 1)).item.title);
        }
    }

    /** Spoken by TalkBack only: nothing on screen takes focus, so it would otherwise say nothing. */
    private void announce(CharSequence text) {
        android.view.accessibility.AccessibilityManager am =
                (android.view.accessibility.AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am != null && am.isTouchExplorationEnabled()) ui.announceForAccessibility(text);
    }

    private void animate(View v, float alpha, float scale, boolean instant) {
        if (instant || animMs == 0) {
            v.animate().cancel();
            v.setAlpha(alpha);
            v.setScaleX(scale);
            v.setScaleY(scale);
        } else {
            v.animate().alpha(alpha).scaleX(scale).scaleY(scale).setDuration(animMs).setInterpolator(ease).start();
        }
    }

    private void buildColumn(boolean fade) {
        FrameLayout old = column;
        column = new FrameLayout(this);
        column.setClipChildren(false);
        ui.addView(column, ui.indexOfChild(old) + 1, new FrameLayout.LayoutParams(-1, -1));
        if (fade && animMs > 0) {
            old.animate().alpha(0f).setDuration(120).withEndAction(() -> ui.removeView(old)).start();
            column.setAlpha(0f);
            column.setTranslationX(px(-16));
            column.animate().alpha(1f).translationX(0).setDuration(200).setInterpolator(ease).start();
        } else {
            ui.removeView(old);
        }
        rows.clear();
        selBar = place(column, new View(this), AX - 170, 0, 1180, ROW_H - 10);
        selBar.setBackground(selBarDrawable());
        for (XmbCatalog.Item item : cats.get(cat).items) rows.add(buildRow(item));
        layoutColumn(true);
    }

    private Row buildRow(XmbCatalog.Item item) {
        Row r = new Row();
        r.item = item;
        r.tile = item.banner != null || !item.glyph;
        r.root = new FrameLayout(this);
        r.root.setClipChildren(false);
        place(column, r.root, 0, 0, 1920, ROW_H);

        float w = r.tile ? THUMB_W : GLYPH, h = r.tile ? THUMB_H : GLYPH;
        r.thumbBox = new FrameLayout(this);
        r.thumbBox.setClipChildren(false);
        place(r.root, r.thumbBox, AX - w / 2, (ROW_H - h) / 2, w, h);
        r.thumbBox.setPivotX(px(w / 2));
        r.thumbBox.setPivotY(px(h / 2));

        float g = r.tile ? 34 : 24;
        r.glow = place(r.thumbBox, new ImageView(this), -g, -g, w + 2 * g, h + 2 * g);
        r.glow.setScaleType(ImageView.ScaleType.CENTER);
        r.glow.setAlpha(0f);

        ImageView thumb = place(r.thumbBox, new ImageView(this), 0, 0, w, h);
        if (r.tile) {
            // Rounded by the view outline, which the renderer antialiases, so scaled corners stay smooth.
            final float radius = px(RADIUS);
            thumb.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            thumb.setClipToOutline(true);
            if (item.banner != null) {
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setImageDrawable(item.banner);
            } else {
                // An app with only an icon: the icon on a quiet tile.
                thumb.setBackgroundColor(0x33FFFFFF);
                thumb.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                int pad = px(24);
                thumb.setPadding(pad, pad, pad, pad);
                thumb.setImageDrawable(item.icon);
            }
            r.ring = place(r.thumbBox, new View(this), -2, -2, w + 4, h + 4);
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(px(RADIUS + 2));
            ring.setStroke(Math.max(2, px(2.5f)), 0xD9FFFFFF);
            r.ring.setBackground(ring);
            r.ring.setAlpha(0f);
        } else {
            thumb.setImageDrawable(item.icon.mutate());
            int pad = px(18);
            thumb.setPadding(pad, pad, pad, pad);
        }
        applyRowGlow(r);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        place(r.root, meta, META_X, 0, 1920 - META_X - 120, ROW_H);
        r.title = text(regular, 38, INK_FAINT);
        r.title.setText(item.title);
        r.title.setPivotX(0);
        r.title.setPivotY(px(23));
        meta.addView(r.title, new LinearLayout.LayoutParams(-1, -2));
        r.detail = text(light, 24, INK_DIM);
        r.detail.setText(item.detail);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = px(6);
        meta.addView(r.detail, dlp);
        r.detail.setVisibility(View.GONE);
        return r;
    }

    /** The glow behind a selected tile: the same for every tile, so made once per accent colour. */
    private Bitmap tileGlow;
    private int tileGlowColor;

    private void applyRowGlow(Row r) {
        if (r.tile) {
            int color = withAlpha(accentUi, 0xB3);
            if (tileGlow == null || tileGlowColor != color) {
                tileGlow = glowRect(px(THUMB_W), px(THUMB_H), px(RADIUS), px(34), color);
                tileGlowColor = color;
            }
            r.glow.setImageBitmap(tileGlow);
        } else {
            int blur = px(24) + px(18);
            r.glow.setImageBitmap(glowOf(r.item.icon, px(GLYPH - 36), blur, withAlpha(accentUi, 0xC0)));
        }
    }

    private static float slotH(Row r, boolean sel) {
        if (r.tile) return sel ? 176 : 112;
        return sel ? 150 : 104;
    }

    /** Positions every row: the selected one right under the category, passed ones above the bar (PS3). */
    private void layoutColumn(boolean instant) {
        if (rows.isEmpty()) return;
        int sel = Math.min(selectedIndex(), rows.size() - 1);
        float[] centre = new float[rows.size()];
        centre[sel] = COL_TOP + slotH(rows.get(sel), true) / 2;
        float y = COL_TOP + slotH(rows.get(sel), true);
        for (int j = sel + 1; j < rows.size(); j++) {
            float h = slotH(rows.get(j), false);
            centre[j] = y + h / 2;
            y += h;
        }
        y = ABOVE_BOTTOM;
        for (int j = sel - 1; j >= 0; j--) {
            float h = slotH(rows.get(j), false);
            centre[j] = y - h / 2;
            y -= h;
        }
        for (int j = 0; j < rows.size(); j++) {
            Row r = rows.get(j);
            boolean isSel = j == sel;
            float alpha = isSel ? 1f : j < sel ? (centre[j] < GONE_Y ? 0f : 0.4f) : 0.58f;
            float ty = px(centre[j] - ROW_H / 2);
            if (instant || animMs == 0) {
                r.root.animate().cancel();
                r.root.setTranslationY(ty);
                r.root.setAlpha(alpha);
            } else {
                r.root.animate().translationY(ty).alpha(alpha).setDuration(animMs).setInterpolator(ease).start();
            }
            animate(r.thumbBox, 1f, isSel ? 1f : 0.68f, instant);
            animate(r.glow, isSel ? 1f : 0f, 1f, instant);
            if (r.ring != null) animate(r.ring, isSel ? 1f : 0f, 1f, instant);
            if (!r.tile) {
                ((ImageView) r.thumbBox.getChildAt(1)).setImageTintList(
                        android.content.res.ColorStateList.valueOf(isSel ? Color.WHITE : INK_FAINT));
            }
            r.title.setTypeface(isSel ? medium : regular);
            r.title.setTextColor(isSel ? INK : INK_FAINT);
            animate(r.title, 1f, isSel ? 1f : 0.82f, instant);
            boolean showDetail = isSel && !TextUtils.isEmpty(r.item.detail);
            r.detail.setVisibility(showDetail ? View.VISIBLE : View.GONE);
            if (showDetail) {
                r.detail.setAlpha(instant || animMs == 0 ? 1f : 0f);
                if (!(instant || animMs == 0)) r.detail.animate().alpha(1f).setDuration(animMs).start();
            }
        }
        float barH = slotH(rows.get(sel), true) - 10;
        ViewGroup.LayoutParams lp = selBar.getLayoutParams();
        if (lp.height != px(barH)) {
            lp.height = px(barH);
            selBar.setLayoutParams(lp);
        }
        float barY = px(centre[sel] - barH / 2);
        if (instant || animMs == 0) {
            selBar.animate().cancel();
            selBar.setTranslationY(barY);
        } else {
            selBar.animate().translationY(barY).setDuration(animMs).setInterpolator(ease).start();
        }
        selectedKey.put(cats.get(cat).key, rows.get(sel).item.key);
    }

    private void selectItem(int index, boolean sound) {
        if (cat < 0 || rows.isEmpty()) return;
        int clamped = Math.max(0, Math.min(rows.size() - 1, index));
        int prev = selectedIndex();
        if (clamped == prev && sound) return;
        selectedKey.put(cats.get(cat).key, rows.get(clamped).item.key);
        layoutColumn(false);
        announce(rows.get(clamped).item.title);
        if (sound) sounds.play(clamped > prev ? XmbSounds.CURSOR_DOWN : XmbSounds.CURSOR);
    }

    // ---- keys ------------------------------------------------------------------------------------------------

    private static boolean isOk(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (idle) {
            // The first key only brings the menu back, as on the PS3.
            if (event.getRepeatCount() == 0) wake(true);
            return true;
        }
        wake(false);
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event);
        if (options.getVisibility() == View.VISIBLE) return optionsKey(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (cat > 0) {
                    selectCategory(cat - 1, false);
                    sounds.play(XmbSounds.CURSOR);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (cat < cats.size() - 1) {
                    selectCategory(cat + 1, false);
                    sounds.play(XmbSounds.CURSOR);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                selectItem(selectedIndex() - 1, true);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                selectItem(selectedIndex() + 1, true);
                return true;
            case KeyEvent.KEYCODE_MENU:
                openOptions();
                return true;
            default:
                if (isOk(keyCode)) {
                    if (event.getRepeatCount() == 0) {
                        okLongPressed = false;
                        event.startTracking();
                    }
                    return true;
                }
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (isOk(keyCode) && options.getVisibility() != View.VISIBLE && !idle) {
            okLongPressed = true;
            openOptions();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isOk(keyCode)) {
            if (!okLongPressed && !event.isCanceled() && options.getVisibility() != View.VISIBLE && !idle
                    && (event.getFlags() & KeyEvent.FLAG_TRACKING) != 0) {
                activate();
            }
            okLongPressed = false;
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void activate() {
        if (cat < 0 || rows.isEmpty()) return;
        XmbCatalog.Item item = rows.get(selectedIndex()).item;
        sounds.play(XmbSounds.CONFIRM);
        if (XmbCatalog.ACTION_TOGGLE_SOUNDS.equals(item.action)) {
            sounds.setOn(this, !sounds.isOn());
            if (sounds.isOn()) sounds.play(XmbSounds.CONFIRM);
            reload();
            return;
        }
        if (XmbCatalog.ACTION_CLASSIC_HOME.equals(item.action)) {
            switchHome(false);
            return;
        }
        open(item);
    }

    private void open(XmbCatalog.Item item) {
        if (item.intent == null) return;
        try {
            startActivity(item.intent);
            if (item.pkg != null) LastOpened.record(this, item.pkg);
        } catch (RuntimeException e) {
            showToast(getString(R.string.cant_open, item.title));
        }
    }

    // ---- idle: the menu fades, the wave keeps flowing ----------------------------------------------------------

    private final Runnable goIdle = () -> {
        if (!resumed || options.getVisibility() == View.VISIBLE) return;
        idle = true;
        ui.animate().alpha(0f).setDuration(reducedMotion ? 0 : 1200).setInterpolator(ease).start();
    };

    private void wake(boolean fromIdle) {
        handler.removeCallbacks(goIdle);
        if (idle || fromIdle) {
            idle = false;
            ui.animate().alpha(1f).setDuration(reducedMotion ? 0 : 2400).setInterpolator(ease).start();
        }
        if (resumed) handler.postDelayed(goIdle, IDLE_MS);
    }

    // ---- options menu (PS3 Triangle) ---------------------------------------------------------------------------

    private void openOptions() {
        if (cat < 0 || rows.isEmpty()) return;
        XmbCatalog.Item item = rows.get(selectedIndex()).item;
        List<String> labels = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        optionActions.clear();
        labels.add(getString(R.string.xmb_open));
        icons.add(R.drawable.ic_xmb_open_in_new);
        optionActions.add(() -> {
            closeOptions();
            activate();
        });
        if (item.pkg != null) {
            labels.add(getString(R.string.xmb_move));
            icons.add(R.drawable.ic_xmb_drive_file_move);
            optionActions.add(() -> showMoveTargets(item));
            labels.add(getString(R.string.xmb_app_info));
            icons.add(R.drawable.ic_xmb_info);
            optionActions.add(() -> {
                closeOptions();
                try {
                    startActivity(XmbCatalog.appInfoIntent(this, item.pkg));
                } catch (RuntimeException e) {
                    showToast(getString(R.string.not_available));
                }
            });
        }
        showOptions(labels, icons);
        sounds.play(XmbSounds.CURSOR);
    }

    private void showMoveTargets(XmbCatalog.Item item) {
        List<String> labels = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        optionActions.clear();
        String current = cats.get(cat).key;
        for (String target : XmbCatalog.APP_CATEGORIES) {
            if (target.equals(current)) continue;
            String label = XmbCatalog.label(this, target);
            labels.add(label);
            icons.add(target.equals(XmbCatalog.VIDEO) ? R.drawable.ic_xmb_movie
                    : target.equals(XmbCatalog.MUSIC) ? R.drawable.ic_xmb_music_note : R.drawable.ic_xmb_apps);
            optionActions.add(() -> {
                closeOptions();
                XmbCatalog.move(this, item.pkg, target);
                showToast(getString(R.string.xmb_moved, item.title, label));
                reload();
            });
        }
        showOptions(labels, icons);
    }

    private void showOptions(List<String> labels, List<Integer> icons) {
        options.removeAllViews();
        for (int i = 0; i < labels.size(); i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(px(20), px(12), px(24), px(12));
            ImageView icon = new ImageView(this);
            icon.setImageResource(icons.get(i));
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(withAlpha(accentUi, 0xFF)));
            row.addView(icon, new LinearLayout.LayoutParams(px(34), px(34)));
            TextView t = text(regular, 25, INK_DIM);
            t.setText(labels.get(i));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.leftMargin = px(16);
            row.addView(t, tlp);
            options.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        optionIdx = 0;
        paintOptions();
        options.setVisibility(View.VISIBLE);
        options.measure(View.MeasureSpec.makeMeasureSpec(px(460), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        float centre = COL_TOP + slotH(rows.get(selectedIndex()), true) / 2;
        options.setY(Math.max(px(60), Math.min(px(1080) - options.getMeasuredHeight() - px(40),
                px(centre) - options.getMeasuredHeight() / 2f)));
        handler.removeCallbacks(goIdle);
    }

    private void paintOptions() {
        for (int i = 0; i < options.getChildCount(); i++) {
            View row = options.getChildAt(i);
            boolean sel = i == optionIdx;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(px(12));
            bg.setColor(sel ? withAlpha(accentUi, 0x38) : 0);
            row.setBackground(bg);
            ((TextView) ((ViewGroup) row).getChildAt(1)).setTextColor(sel ? Color.WHITE : INK_DIM);
        }
    }

    private boolean optionsKey(int keyCode, KeyEvent event) {
        int n = options.getChildCount();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                optionIdx = (optionIdx + n - 1) % n;
                paintOptions();
                sounds.play(XmbSounds.CURSOR);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                optionIdx = (optionIdx + 1) % n;
                paintOptions();
                sounds.play(XmbSounds.CURSOR_DOWN);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MENU:
                closeOptions();
                sounds.play(XmbSounds.CANCEL);
                return true;
            default:
                if (isOk(keyCode)) {
                    // Act on release, so the press that opened the menu (a long OK) does not also pick an option.
                    if (event.getRepeatCount() == 0) event.startTracking();
                    return true;
                }
                return true;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (sounds == null) return true;   // only here to switch Home, for a moment: nothing to act on
        if (event.getAction() == KeyEvent.ACTION_UP && isOk(event.getKeyCode())
                && options.getVisibility() == View.VISIBLE && !okLongPressed
                && (event.getFlags() & KeyEvent.FLAG_CANCELED) == 0 && optionIdx < optionActions.size()) {
            sounds.play(XmbSounds.CONFIRM);
            optionActions.get(optionIdx).run();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && isOk(event.getKeyCode()) && okLongPressed
                && options.getVisibility() == View.VISIBLE) {
            okLongPressed = false;   // the long press that opened the menu has ended
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void closeOptions() {
        if (options != null) options.setVisibility(View.GONE);
        optionActions.clear();
        if (resumed) wake(false);
    }

    private final Runnable hideToast = () -> toast.animate().alpha(0f).setDuration(200).start();

    private void showToast(String message) {
        toast.setText(message);
        toast.animate().alpha(1f).setDuration(200).start();
        handler.removeCallbacks(hideToast);
        handler.postDelayed(hideToast, 2200);
    }
}
