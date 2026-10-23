package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Home as a PlayStation-style cross-bar (XMB): categories across, the selected category's items down, the selected
 * item always right under the category icon (the PS3 layout; items already passed stack above the bar), over the
 * PS3 wave in the month's colour ({@link XmbWave}). Design and timings follow KittiwanaNotify's admin (xmb.js,
 * admin.css): anchor at 24% of the width, category row at about 30% of the height, 240 ms with the same easing,
 * the menu fading out after 45 s idle while the wave keeps flowing.
 *
 * Keys: left/right change category, up/down move in it, OK opens, hold OK (or Menu) for options, Home when Home is
 * already in front goes back to Video. On this branch it is the launcher's only Home (the XmbHome alias; the tile
 * grid, MainActivity, is switched off in the manifest).
 *
 * Left of the clock, a status row ({@link XmbStatus}); background music ({@link XmbMusic}); in Settings, the theme
 * colour (a frameless list right of the column, the wave following the selection, and a hue and brightness picker)
 * and the menus ({@link XmbMenus}: add, rename, reorder, icons), in the same list.
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

    private float s;
    private Config cfg;
    private XmbSounds sounds;
    private XmbMusic music;
    private XmbStatus status;
    private List<XmbMenus.Menu> menus = Collections.emptyList();
    /** The colour shown while the owner moves through the theme colours; null otherwise. */
    private String previewColor;
    private XmbWave wave;
    private GLSurfaceView waveView;
    private boolean reducedMotion;
    private long animMs = 240;
    private final PathInterpolator ease = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Typeface light, regular, medium, extraLight;

    private FrameLayout ui, catsRow, column;
    /** The light under the selected row: a pool of the accent colour on a glossy floor, and a thin line of light. */
    private ImageView selBar;
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
        cfg = Config.load(this);
        // restore_key_services: after a force stop, put the remote's accessibility helpers back, as the tile grid
        // does when it starts (ServiceRepair; on this branch the grid is switched off and never starts).
        if (cfg.restoreKeyServices) ServiceRepair.restore(this);
        resetHomeSwitch();
        reducedMotion = cfg.reducedMotion || !cfg.animations;
        if (reducedMotion) animMs = 0;
        sounds = new XmbSounds(this);
        // Their own handlers: onPause clears this activity's, and the music's fade-out must still run after that.
        music = new XmbMusic(this, new Handler(Looper.getMainLooper()));
        s = getResources().getDisplayMetrics().widthPixels / 1920f;
        light = getResources().getFont(R.font.outfit_light);
        regular = getResources().getFont(R.font.outfit_regular);
        medium = getResources().getFont(R.font.outfit_medium);
        extraLight = getResources().getFont(R.font.outfit_extralight);
        status = new XmbStatus(this, this::px, extraLight, new Handler(Looper.getMainLooper()));

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

    /**
     * Earlier builds of this branch switched Home between the grid and the XMB at run time. Android keeps such a
     * switch through updates and only the app may change it (the adb shell cannot), so hand both back to the
     * manifest. Nothing changes here, but a later build with the grid as Home would otherwise find the grid off.
     */
    private void resetHomeSwitch() {
        PackageManager pm = getPackageManager();
        for (ComponentName c : new ComponentName[]{new ComponentName(this, MainActivity.class),
                new ComponentName(this, "com.gathin.tvhome.XmbHome")}) {
            if (pm.getComponentEnabledSetting(c) != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                pm.setComponentEnabledSetting(c, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                        PackageManager.DONT_KILL_APP);
            }
        }
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
            if (dreaming) music.pause();
            else if (resumed) music.resume(500);
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
        stopped = false;
        dirty = true;   // last-opened times and the network line change while an app is in front
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        boolean jingle = !openingPlayed && SystemClock.elapsedRealtime() < 5 * 60_000;
        if (jingle) sounds.play(XmbSounds.OPENING);
        openingPlayed = true;
        status.start();
        if (!dreaming) music.resume(jingle ? 7000 : 1200);
    }

    @Override
    protected void onPause() {
        resumed = false;
        closeOptions();
        closeSide(true);
        closePrompt();
        status.stop();
        music.pause();
        handler.removeCallbacksAndMessages(null);
        if (waveView != null) waveView.onPause();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (resumed && !dreaming) {
            if (hasFocus) music.resume(300);
            else if (prompt == null || prompt.getVisibility() != View.VISIBLE) music.pause();
        }
        if (waveView == null) return;
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
        unregisterReceiver(packageReceiver);
        unregisterReceiver(dreamReceiver);
        sounds.release();
        music.release();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while Home is already in front: back to Video, first item. From an app, keep the place.
        if (!stopped && intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) {
            closePrompt();
            closeSide(false);
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
        // Home has nowhere to go back to; Back only closes the options menu, the side list or the name prompt.
        if (prompt != null && prompt.getVisibility() == View.VISIBLE) {
            Runnable back = promptBack;
            closePrompt();
            sounds.play(XmbSounds.CANCEL);
            if (back != null) back.run();
            return;
        }
        if (sideVisible()) {
            sounds.play(XmbSounds.CANCEL);
            if (adjusting) closeAdjust(false);
            else backSide();
            return;
        }
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
        String mode = previewColor != null ? previewColor : colorMode();
        int c = XmbAccent.colorFor(mode, now.get(Calendar.MONTH), now.get(Calendar.HOUR_OF_DAY));
        if (c == accent && rows.size() > 0) return;
        accent = c;
        accentUi = XmbAccent.floor(c);
        if (wave != null) wave.setColor(c);
        if (waveView != null && reducedMotion) waveView.requestRender();
        if (selBar != null) selBar.setImageBitmap(selectionLight());
        status.setAccent(accentUi);
        // While the owner runs through colours only the wave and the bar follow; the glows catch up on the choice.
        if (previewColor != null) return;
        for (int i = 0; i < catViews.size(); i++) applyCatGlow(i);
        for (Row r : rows) applyRowGlow(r);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
    }

    private static final String PREF_COLOR = "xmb_color", PREF_COLOR_THEME = "xmb_color_theme";

    /**
     * The colour setting: the one picked on the TV, else xmb_color from config.xml or the theme. A theme that brings
     * a different xmb_color than last time is a new choice and replaces the TV's.
     */
    private String colorMode() {
        SharedPreferences p = prefs();
        String theme = cfg.xmbColor == null ? XmbAccent.MONTH : cfg.xmbColor;
        if (!theme.equals(p.getString(PREF_COLOR_THEME, null))) {
            p.edit().putString(PREF_COLOR_THEME, theme).remove(PREF_COLOR).apply();
        }
        return p.getString(PREF_COLOR, theme);
    }

    /** What the colour setting is called: Monthly, one of the twelve names, or Custom and its code. */
    private String colorName(String mode) {
        String[] names = getResources().getStringArray(R.array.xmb_color_names);
        for (int i = 0; i < 12; i++) if (("month" + (i + 1)).equals(mode)) return names[i];
        int fixed = XmbAccent.fixedColor(mode);
        if (fixed >= 0) return getString(R.string.xmb_color_custom) + " " + XmbAccent.hex(fixed);
        return getString(R.string.xmb_color_month);
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

    // The selection light, in 1920x1080 units: drawn in a box SEL_W x SEL_H whose left edge is SEL_X left of the
    // anchor, its line SEL_LINE below the box's top, and the box placed so the line runs just under the row.
    private static final float SEL_X = 330, SEL_W = 1200, SEL_H = 150, SEL_LINE = 62;
    private Bitmap selLight;
    private int selLightColor;

    /**
     * The row stands on a glossy floor: under it a soft pool of the accent colour, wide and flat, and along its foot a
     * thin line of light, white where the icon is and the accent colour further right, both fading out at the ends.
     * No box around the row. Made once per accent colour, in software (the blur is not drawn by the GPU).
     */
    private Bitmap selectionLight() {
        if (selLight != null && selLightColor == accentUi) return selLight;
        int w = px(SEL_W), h = px(SEL_H);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // The pool: an ellipse 600 x 52 units round a point 480 units in, three units above the line.
        float cx = px(480), cy = px(SEL_LINE - 3), rx = px(600), ry = px(52);
        p.setShader(new android.graphics.RadialGradient(cx, cy, rx,
                new int[]{withAlpha(accentUi, 0x8C), withAlpha(accentUi, 0x2E), withAlpha(accentUi, 0)},
                new float[]{0f, 0.5f, 0.8f}, android.graphics.Shader.TileMode.CLAMP));
        c.save();
        c.scale(1f, ry / rx, cx, cy);
        c.drawCircle(cx, cy, rx, p);
        c.restore();
        // The line: 1000 units long from 110 units in, with a glow of the accent colour round it.
        float x0 = px(110), x1 = px(1110), y = px(SEL_LINE), t = Math.max(1, px(2));
        p.setShader(new android.graphics.LinearGradient(x0, 0, x1, 0,
                new int[]{withAlpha(accentUi, 0), withAlpha(accentUi, 0xCC), withAlpha(accentUi, 0xCC), withAlpha(accentUi, 0)},
                new float[]{0f, 0.25f, 0.55f, 1f}, android.graphics.Shader.TileMode.CLAMP));
        p.setMaskFilter(new BlurMaskFilter(px(12), BlurMaskFilter.Blur.NORMAL));
        c.drawRect(x0, y - t, x1, y + t, p);
        p.setMaskFilter(null);
        p.setShader(new android.graphics.LinearGradient(x0, 0, x1, 0,
                new int[]{0x00FFFFFF, 0x99FFFFFF, withAlpha(accentUi, 0x80), withAlpha(accentUi, 0)},
                new float[]{0f, 0.25f, 0.55f, 1f}, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(x0, y - t / 2f, x1, y + t / 2f, p);
        selLight = b;
        selLightColor = accentUi;
        return b;
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
        // The status row left of the clock, its icons centred on the digits.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setClipChildren(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, px(40));
        slp.topMargin = px(13);
        slp.rightMargin = px(46);
        top.addView(status.row, slp);
        top.addView(clock, new LinearLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        clp.rightMargin = px(96);
        clp.topMargin = px(54);
        ui.addView(top, clp);

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

        side = new FrameLayout(this);
        side.setClipChildren(false);
        side.setVisibility(View.GONE);
        ui.addView(side, new FrameLayout.LayoutParams(-1, -1));

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
        status.tick();
        handler.removeCallbacks(tickRunnable);
        if (resumed) handler.postDelayed(tickRunnable, 60_000 - (System.currentTimeMillis() % 60_000) + 50);
    }

    // ---- content ---------------------------------------------------------------------------------------------

    private void reload() {
        dirty = false;
        closeOptions();
        if (cat >= 0 && cat < cats.size()) selectedCat = cats.get(cat).key;
        menus = XmbMenus.load(this, cfg.xmbMenus);
        XmbCatalog.SettingsState state = new XmbCatalog.SettingsState();
        state.soundsAvailable = sounds.available();
        state.soundsOn = sounds.isOn();
        state.musicAvailable = music.available();
        state.musicLevel = music.level();
        state.colorName = colorName(colorMode());
        cats = XmbCatalog.load(this, cfg, menus, state);
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
        selBar = place(column, new ImageView(this), AX - SEL_X, 0, SEL_W, SEL_H);
        selBar.setImageBitmap(selectionLight());
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
        // The line runs 13 units above the foot of the selected slot: under the text, below a tile's lower edge.
        float barY = px(centre[sel] + slotH(rows.get(sel), true) / 2 - 13 - SEL_LINE);
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
        if (prompt != null && prompt.getVisibility() == View.VISIBLE) return super.onKeyDown(keyCode, event);
        if (adjusting) return adjustKey(keyCode, event);
        if (sideVisible()) return sideKey(keyCode, event);
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
        if (isOk(keyCode) && options.getVisibility() != View.VISIBLE && !idle && !sideVisible()) {
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
                    && !sideVisible() && (event.getFlags() & KeyEvent.FLAG_TRACKING) != 0) {
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
        if (XmbCatalog.ACTION_MUSIC.equals(item.action)) {
            if (music.available()) {
                music.cycle();
                reload();
            }
            return;
        }
        if (XmbCatalog.ACTION_COLOR.equals(item.action)) {
            openColors();
            return;
        }
        if (XmbCatalog.ACTION_MENUS.equals(item.action)) {
            sideStack.clear();
            openMenus(null);
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
        if (!resumed || options.getVisibility() == View.VISIBLE || pickerOpen()) return;
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
        for (XmbMenus.Menu m : menus) {
            String target = m.key;
            if (!m.holdsApps() || target.equals(current)) continue;
            String label = XmbCatalog.label(this, m);
            labels.add(label);
            icons.add(XmbCatalog.iconRes(m));
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
        if (prompt != null && prompt.getVisibility() == View.VISIBLE) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_UP && isOk(event.getKeyCode()) && (sideVisible() || adjusting)) {
            if ((event.getFlags() & KeyEvent.FLAG_CANCELED) == 0 && sideOkDown) {
                sideOkDown = false;
                if (adjusting) {
                    sounds.play(XmbSounds.CONFIRM);
                    closeAdjust(true);
                } else if (sideHandler != null && sideIdx < sideTexts.size()) {
                    sounds.play(XmbSounds.CONFIRM);
                    sideHandler.picked(sideIdx);
                }
            }
            return true;
        }
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

    // ---- the side list: a frameless list right of the column (theme colour, menus) ------------------------------

    private static final float SIDE_X = 1400, SIDE_STEP = 64, SIDE_ICON = 44;
    private static final float[] SIDE_ALPHA = {1f, 0.62f, 0.44f, 0.28f, 0.14f};

    private interface SideHandler {
        default void moved(int index) {
        }

        void picked(int index);
    }

    private FrameLayout side;
    private final List<String> sideTexts = new ArrayList<>();
    private final List<View> sideViews = new ArrayList<>();
    private int sideIdx;
    private SideHandler sideHandler;
    /** What Back does on the level on screen: reopen the level above, or null to close the list. */
    private Runnable sideBackAction;
    /** OK went down while the list was open: only that press's release picks (not the one that opened it). */
    private boolean sideOkDown;
    private final ArrayDeque<Runnable> sideStack = new ArrayDeque<>();

    private boolean sideVisible() {
        return side != null && side.getVisibility() == View.VISIBLE;
    }

    /** The side list, the colour picker or the name prompt is open: Home does not fade to the wave meanwhile. */
    private boolean pickerOpen() {
        return sideVisible() || adjusting || (prompt != null && prompt.getVisibility() == View.VISIBLE);
    }

    /** Opens a level: one entry per text (null for an icon alone) with its icon (0 for none). */
    private void showSide(List<String> texts, List<Integer> icons, int start, SideHandler h, Runnable back) {
        closeOptions();
        side.removeAllViews();
        sideViews.clear();
        sideTexts.clear();
        sideTexts.addAll(texts);
        for (int i = 0; i < texts.size(); i++) {
            LinearLayout e = new LinearLayout(this);
            e.setOrientation(LinearLayout.HORIZONTAL);
            e.setGravity(Gravity.CENTER_VERTICAL);
            e.setClipChildren(false);
            String t = texts.get(i);
            int icon = icons.get(i);
            if (icon != 0) {
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(getDrawable(icon).mutate());
                float size = t == null ? 56 : SIDE_ICON;
                e.addView(iv, new LinearLayout.LayoutParams(px(size), px(size)));
            }
            if (t != null) {
                TextView tv = text(regular, 30, INK_DIM);
                tv.setText(t);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                if (icon != 0) lp.leftMargin = px(18);
                e.addView(tv, lp);
            }
            side.addView(e, new FrameLayout.LayoutParams(px(1920 - SIDE_X - 40), px(SIDE_STEP)));
            e.setX(px(SIDE_X));
            sideViews.add(e);
        }
        sideIdx = Math.max(0, Math.min(texts.size() - 1, start));
        sideHandler = h;
        sideBackAction = back;
        sideOkDown = false;
        side.setVisibility(View.VISIBLE);
        dimOtherCategories(true);
        layoutSide(true);
        handler.removeCallbacks(goIdle);
    }

    private float sideCentre() {
        if (rows.isEmpty()) return COL_TOP + 75;
        return COL_TOP + slotH(rows.get(Math.min(selectedIndex(), rows.size() - 1)), true) / 2;
    }

    private void layoutSide(boolean instant) {
        float centre = sideCentre();
        for (int i = 0; i < sideViews.size(); i++) {
            View e = sideViews.get(i);
            int d = Math.abs(i - sideIdx);
            float alpha = d < SIDE_ALPHA.length ? SIDE_ALPHA[d] : 0f;
            float y = px(centre + (i - sideIdx) * SIDE_STEP - SIDE_STEP / 2);
            if (instant || animMs == 0) {
                e.animate().cancel();
                e.setY(y);
                e.setAlpha(alpha);
            } else {
                e.animate().y(y).alpha(alpha).setDuration(160).setInterpolator(ease).start();
            }
            paintSideEntry((ViewGroup) e, i == sideIdx);
        }
    }

    private void paintSideEntry(ViewGroup e, boolean sel) {
        for (int k = 0; k < e.getChildCount(); k++) {
            View c = e.getChildAt(k);
            if (c instanceof TextView) {
                TextView t = (TextView) c;
                t.setTypeface(sel ? medium : regular);
                t.setTextColor(sel ? Color.WHITE : INK_DIM);
                t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(sel ? 33 : 30));
                t.setShadowLayer(sel ? px(10) : 0, 0, 0, sel ? 0x99FFFFFF : 0);
            } else if (c instanceof ImageView) {
                ((ImageView) c).setImageTintList(android.content.res.ColorStateList.valueOf(sel ? Color.WHITE : INK_DIM));
                c.setScaleX(sel ? 1.15f : 1f);
                c.setScaleY(sel ? 1.15f : 1f);
            }
        }
    }

    /** While the list is open the other categories fade out, so the list stands clear of the cross-bar. */
    private void dimOtherCategories(boolean dim) {
        for (int i = 0; i < catViews.size(); i++) {
            if (i == cat) continue;
            View icon = catViews.get(i).icon;
            if (instantAnim()) icon.setAlpha(dim ? 0f : 0.38f);
            else icon.animate().alpha(dim ? 0f : 0.38f).setDuration(160).start();
        }
    }

    private boolean instantAnim() {
        return animMs == 0;
    }

    private boolean sideKey(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (sideIdx > 0) {
                    sideIdx--;
                    layoutSide(false);
                    sounds.play(XmbSounds.CURSOR);
                    if (sideHandler != null) sideHandler.moved(sideIdx);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (sideIdx < sideViews.size() - 1) {
                    sideIdx++;
                    layoutSide(false);
                    sounds.play(XmbSounds.CURSOR_DOWN);
                    if (sideHandler != null) sideHandler.moved(sideIdx);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MENU:
                sounds.play(XmbSounds.CANCEL);
                backSide();
                return true;
            default:
                if (isOk(keyCode) && event.getRepeatCount() == 0) sideOkDown = true;
                return true;
        }
    }

    private void backSide() {
        Runnable back = sideBackAction;
        if (back != null) back.run();
        else closeSide(false);
    }

    private void closeSide(boolean quiet) {
        if (adjusting) removeAdjust();
        if (side == null || side.getVisibility() != View.VISIBLE) return;
        side.setVisibility(View.GONE);
        side.removeAllViews();
        sideViews.clear();
        sideTexts.clear();
        sideHandler = null;
        sideBackAction = null;
        sideOkDown = false;
        dimOtherCategories(false);
        if (previewColor != null) {
            previewColor = null;
            accent = -1;
            updateAccent();
            if (!quiet) reload();
        }
        if (resumed) wake(false);
    }

    private void setSelectedDetail(String text) {
        if (rows.isEmpty()) return;
        Row r = rows.get(Math.min(selectedIndex(), rows.size() - 1));
        r.detail.setText(text);
        r.detail.setVisibility(View.VISIBLE);
    }

    // ---- theme colour ------------------------------------------------------------------------------------------

    private static final String PREF_CUSTOM = "xmb_color_custom";
    private static final int CUSTOM = 13;

    private void openColors() {
        String mode = colorMode();
        String[] names = getResources().getStringArray(R.array.xmb_color_names);
        List<String> texts = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        texts.add(getString(R.string.xmb_color_month));
        for (String n : names) texts.add(n);
        texts.add(getString(R.string.xmb_color_custom));
        for (int i = 0; i < texts.size(); i++) icons.add(0);
        int start = 0;
        for (int i = 1; i <= 12; i++) if (("month" + i).equals(mode)) start = i;
        if (!mode.startsWith(XmbAccent.MONTH) && XmbAccent.fixedColor(mode) >= 0) start = CUSTOM;
        String custom = start == CUSTOM ? mode : prefs().getString(PREF_CUSTOM, null);
        previewColor = mode;
        showSide(texts, icons, start, new SideHandler() {
            @Override
            public void moved(int i) {
                previewColor = colorAt(i, custom);
                updateAccent();
                setSelectedDetail(i == CUSTOM ? colorName(previewColor) : texts.get(i));
            }

            @Override
            public void picked(int i) {
                if (i == CUSTOM) openAdjust(custom);
                else saveColor(colorAt(i, custom));
            }
        }, null);
    }

    private String colorAt(int i, String custom) {
        if (i <= 0) return XmbAccent.MONTH;
        if (i <= 12) return XmbAccent.MONTH + i;
        if (custom != null) return custom;
        Calendar now = Calendar.getInstance();
        return XmbAccent.hex(XmbAccent.base(colorMode(), now.get(Calendar.MONTH)));
    }

    private void saveColor(String mode) {
        SharedPreferences.Editor e = prefs().edit().putString(PREF_COLOR, mode);
        if (!mode.startsWith(XmbAccent.MONTH)) e.putString(PREF_CUSTOM, mode);
        e.apply();
        previewColor = null;
        closeSide(true);
        accent = -1;
        updateAccent();
        reload();
    }

    // The custom colour: Left and Right turn the hue, Up and Down the brightness, the wave follows.
    private boolean adjusting;
    private float adjHue, adjBright;
    private final List<View> adjViews = new ArrayList<>();
    private View hueMark, brightMark, brightBar;
    private TextView adjHex;
    private static final float BAR_W = 380;

    private void openAdjust(String start) {
        Calendar now = Calendar.getInstance();
        int base = start != null ? XmbAccent.fixedColor(start) : -1;
        if (base < 0) base = XmbAccent.base(colorMode(), now.get(Calendar.MONTH));
        float[] hb = XmbAccent.hueBrightness(base);
        adjHue = hb[0];
        adjBright = hb[1];
        // The list stays, faded, with the custom entry on top of the pickers.
        for (int i = 0; i < sideViews.size(); i++) {
            if (i != sideIdx) sideViews.get(i).animate().alpha(i < sideIdx ? 0.2f : 0f).setDuration(120).start();
        }
        float y = sideCentre() + SIDE_STEP / 2 + 10;
        adjViews.clear();
        TextView hueLabel = place(ui, text(light, 21, INK_FAINT), SIDE_X, y, 400, -1);
        hueLabel.setText(R.string.xmb_color_hue);
        View hueBar = place(ui, new View(this), SIDE_X, y + 40, BAR_W, 4);
        int[] spectrum = new int[13];
        for (int i = 0; i < spectrum.length; i++) spectrum[i] = 0xFF000000 | XmbAccent.fromHueBrightness(i / 12f, 0.8f);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, spectrum);
        g.setCornerRadius(px(2));
        hueBar.setBackground(g);
        hueMark = place(ui, new View(this), SIDE_X, y + 27, 4, 30);
        TextView brightLabel = place(ui, text(light, 21, INK_FAINT), SIDE_X, y + 72, 400, -1);
        brightLabel.setText(R.string.xmb_color_brightness);
        brightBar = place(ui, new View(this), SIDE_X, y + 112, BAR_W, 4);
        brightMark = place(ui, new View(this), SIDE_X, y + 99, 4, 30);
        for (View mark : new View[]{hueMark, brightMark}) {
            GradientDrawable m = new GradientDrawable();
            m.setColor(Color.WHITE);
            m.setCornerRadius(px(2));
            mark.setBackground(m);
            mark.setElevation(px(4));
        }
        adjHex = place(ui, text(light, 21, INK_FAINT), SIDE_X, y + 146, 500, -1);
        adjViews.add(hueLabel);
        adjViews.add(hueBar);
        adjViews.add(hueMark);
        adjViews.add(brightLabel);
        adjViews.add(brightBar);
        adjViews.add(brightMark);
        adjViews.add(adjHex);
        adjusting = true;
        sideOkDown = false;
        updateAdjust();
    }

    private void updateAdjust() {
        int rgb = XmbAccent.fromHueBrightness(adjHue, adjBright);
        previewColor = XmbAccent.hex(rgb);
        updateAccent();
        hueMark.setX(px(SIDE_X + BAR_W * adjHue - 2));
        brightMark.setX(px(SIDE_X + BAR_W * adjBright - 2));
        GradientDrawable b = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{
                0xFF000000 | XmbAccent.fromHueBrightness(adjHue, 0f), 0xFF000000 | XmbAccent.fromHueBrightness(adjHue, 1f)});
        b.setCornerRadius(px(2));
        brightBar.setBackground(b);
        adjHex.setText(previewColor + "      " + getString(R.string.xmb_color_hint));
        setSelectedDetail(colorName(previewColor));
    }

    private boolean adjustKey(int keyCode, KeyEvent event) {
        int r = event.getRepeatCount();
        float hueStep = r > 8 ? 1f / 40 : 1f / 120;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                adjHue = (adjHue - hueStep + 1f) % 1f;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                adjHue = (adjHue + hueStep) % 1f;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                adjBright = Math.min(1f, adjBright + 0.05f);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                adjBright = Math.max(0f, adjBright - 0.05f);
                break;
            case KeyEvent.KEYCODE_MENU:
                sounds.play(XmbSounds.CANCEL);
                closeAdjust(false);
                return true;
            default:
                if (isOk(keyCode) && r == 0) sideOkDown = true;
                return true;
        }
        if (r % 3 == 0) sounds.play(XmbSounds.CURSOR);
        updateAdjust();
        return true;
    }

    private void removeAdjust() {
        adjusting = false;
        for (View v : adjViews) ui.removeView(v);
        adjViews.clear();
    }

    private void closeAdjust(boolean save) {
        String picked = previewColor;
        removeAdjust();
        if (save && picked != null) {
            saveColor(picked);
        } else {
            closeSide(true);
            openColors();
        }
    }

    // ---- menus: add, rename, reorder, icons --------------------------------------------------------------------

    private static final String ADD = "+add";

    private int menuIndex(String key) {
        for (int i = 0; i < menus.size(); i++) if (menus.get(i).key.equals(key)) return i;
        return -1;
    }

    private void openMenus(String focusKey) {
        List<String> texts = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        for (XmbMenus.Menu m : menus) {
            texts.add(XmbCatalog.label(this, m));
            icons.add(XmbCatalog.iconRes(m));
        }
        texts.add(getString(R.string.xmb_menu_add));
        icons.add(R.drawable.ic_xmb_add);
        int start = ADD.equals(focusKey) ? menus.size() : Math.max(0, menuIndex(focusKey));
        showSide(texts, icons, start, i -> {
            if (i == menus.size()) openPresets();
            else openMenuActions(menus.get(i).key, 0);
        }, null);
    }

    private void openMenuActions(String key, int focus) {
        int at = menuIndex(key);
        if (at < 0) {
            openMenus(null);
            return;
        }
        XmbMenus.Menu m = menus.get(at);
        List<String> texts = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        texts.add(getString(R.string.xmb_menu_rename));
        icons.add(R.drawable.ic_xmb_edit);
        actions.add(() -> askText(getString(R.string.xmb_menu_name_hint), XmbCatalog.label(this, m),
                name -> {
                    replaceMenu(key, new XmbMenus.Menu(key, name, m.icon));
                    openMenuActions(key, 0);
                }, () -> openMenuActions(key, 0)));
        texts.add(getString(R.string.xmb_menu_icon));
        icons.add(R.drawable.ic_xmb_category);
        actions.add(() -> openIcons(key));
        if (at > 0) {
            texts.add(getString(R.string.xmb_menu_left));
            icons.add(R.drawable.ic_xmb_chevron_left);
            actions.add(() -> moveMenu(key, -1));
        }
        if (at < menus.size() - 1) {
            texts.add(getString(R.string.xmb_menu_right));
            icons.add(R.drawable.ic_xmb_chevron_right);
            actions.add(() -> moveMenu(key, 1));
        }
        if (!m.builtIn()) {
            texts.add(getString(R.string.xmb_menu_delete));
            icons.add(R.drawable.ic_xmb_delete);
            actions.add(() -> {
                String label = XmbCatalog.label(this, m);
                XmbMenus.remove(this, menus, key);
                closeSide(true);
                reload();
                showToast(getString(R.string.xmb_menu_removed, label));
                openMenus(null);
            });
        }
        showSide(texts, icons, focus, i -> actions.get(i).run(), () -> openMenus(key));
    }

    private void replaceMenu(String key, XmbMenus.Menu with) {
        List<XmbMenus.Menu> next = new ArrayList<>(menus);
        int at = menuIndex(key);
        if (at >= 0) next.set(at, with);
        XmbMenus.save(this, next);
        closeSide(true);
        reload();
    }

    private void moveMenu(String key, int by) {
        List<XmbMenus.Menu> next = new ArrayList<>(menus);
        int at = menuIndex(key), to = at + by;
        if (at < 0 || to < 0 || to >= next.size()) return;
        next.add(to, next.remove(at));
        XmbMenus.save(this, next);
        closeSide(true);
        reload();
        // The same move stays selected, so OK again moves it further (at an end, the other move): Rename, Icon,
        // then Move left when there is room on the left, then Move right.
        int now = menuIndex(key);
        boolean hasLeft = now > 0, hasRight = now < menus.size() - 1;
        openMenuActions(key, by > 0 && hasRight && hasLeft ? 3 : 2);
    }

    private void openIcons(String key) {
        int at = menuIndex(key);
        if (at < 0) return;
        XmbMenus.Menu m = menus.get(at);
        List<String> names = new ArrayList<>(XmbMenus.ICONS.keySet());
        List<String> texts = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        int current = XmbCatalog.iconRes(m), start = 0;
        for (int i = 0; i < names.size(); i++) {
            texts.add(null);
            icons.add(XmbMenus.ICONS.get(names.get(i)));
            if (icons.get(i) == current) start = i;
        }
        showSide(texts, icons, start, i -> {
            replaceMenu(key, new XmbMenus.Menu(key, m.label, names.get(i)));
            openMenuActions(key, 1);
        }, () -> openMenuActions(key, 1));
    }

    private void openPresets() {
        List<XmbMenus.Preset> free = new ArrayList<>();
        for (XmbMenus.Preset p : XmbMenus.PRESETS) if (menuIndex(p.key) < 0) free.add(p);
        List<String> texts = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        for (XmbMenus.Preset p : free) {
            texts.add(getString(p.label));
            icons.add(XmbMenus.ICONS.get(p.icon));
        }
        texts.add(getString(R.string.xmb_menu_type));
        icons.add(R.drawable.ic_xmb_edit);
        showSide(texts, icons, 0, i -> {
            if (i < free.size()) {
                XmbMenus.Preset p = free.get(i);
                String key = addMenu(p.key, getString(p.label), p.icon);
                openMenus(key);
            } else {
                askText(getString(R.string.xmb_menu_name_hint), "", name -> {
                    String key = addMenu(null, name, "star");
                    openIcons(key);
                }, this::openPresets);
            }
        }, () -> openMenus(ADD));
    }

    /** Adds a menu just before Network and returns its key. */
    private String addMenu(String wantedKey, String label, String icon) {
        String key = XmbMenus.newKey(menus, wantedKey);
        List<XmbMenus.Menu> next = new ArrayList<>(menus);
        int network = next.size();
        for (int i = 0; i < next.size(); i++) if (XmbCatalog.NETWORK.equals(next.get(i).key)) network = i;
        next.add(network, new XmbMenus.Menu(key, label, icon));
        XmbMenus.save(this, next);
        closeSide(true);
        reload();
        showToast(getString(R.string.xmb_menu_added, label));
        return key;
    }

    // ---- a name, typed with the TV's keyboard (or said into the remote) ----------------------------------------

    private FrameLayout prompt;
    private EditText promptEdit;
    private TextView promptTitle;
    private Consumer<String> promptDone;
    private Runnable promptBack;

    private void askText(String title, String initial, Consumer<String> done, Runnable back) {
        closeSide(true);
        if (prompt == null) {
            prompt = new FrameLayout(this);
            prompt.setBackgroundColor(0xD9050710);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            promptTitle = text(medium, 34, INK);
            box.addView(promptTitle, new LinearLayout.LayoutParams(-1, -2));
            promptEdit = new EditText(this);
            promptEdit.setTypeface(regular);
            promptEdit.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(44));
            promptEdit.setTextColor(Color.WHITE);
            promptEdit.setSingleLine(true);
            promptEdit.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            promptEdit.setBackgroundTintList(android.content.res.ColorStateList.valueOf(withAlpha(accentUi, 0xFF)));
            promptEdit.setOnEditorActionListener((v, actionId, ev) -> {
                boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && ev.getAction() == KeyEvent.ACTION_DOWN;
                if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                    finishPrompt();
                    return true;
                }
                return false;
            });
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
            elp.topMargin = px(24);
            box.addView(promptEdit, elp);
            TextView hint = text(light, 23, INK_FAINT);
            hint.setSingleLine(false);
            hint.setText(R.string.xmb_menu_keyboard);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
            hlp.topMargin = px(18);
            box.addView(hint, hlp);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(px(900), -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            blp.topMargin = px(220);
            prompt.addView(box, blp);
            ((FrameLayout) ui.getParent()).addView(prompt, new FrameLayout.LayoutParams(-1, -1));
        }
        promptDone = done;
        promptBack = back;
        promptTitle.setText(title);
        promptEdit.setText(initial);
        promptEdit.setSelection(promptEdit.getText().length());
        prompt.setVisibility(View.VISIBLE);
        handler.removeCallbacks(goIdle);
        promptEdit.requestFocus();
        promptEdit.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(promptEdit, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void finishPrompt() {
        String name = promptEdit.getText().toString().trim();
        Consumer<String> done = promptDone;
        Runnable back = promptBack;
        closePrompt();
        if (!name.isEmpty() && done != null) {
            sounds.play(XmbSounds.CONFIRM);
            done.accept(name.length() > 24 ? name.substring(0, 24) : name);
        } else if (back != null) {
            back.run();
        }
    }

    private void closePrompt() {
        if (prompt == null || prompt.getVisibility() != View.VISIBLE) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(promptEdit.getWindowToken(), 0);
        promptEdit.clearFocus();
        prompt.setVisibility(View.GONE);
        promptDone = null;
        promptBack = null;
        if (resumed) wake(false);
    }
}
