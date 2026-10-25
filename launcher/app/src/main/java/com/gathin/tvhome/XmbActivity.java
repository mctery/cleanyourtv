package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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

import static com.gathin.tvhome.XmbStyle.*;

/**
 * Home as a PlayStation-style cross-bar (XMB): categories across, the selected category's items down, the selected
 * item always right under the category icon (the PS3 layout; items already passed stack above the bar), over the
 * PS3 wave in the month's colour ({@link XmbWave}). Design and timings follow KittiwanaNotify's admin (xmb.js,
 * admin.css): anchor at 24% of the width, category row at about 30% of the height, 240 ms with the same easing,
 * the menu fading out after 45 s idle (xmb_idle_seconds) while the wave keeps flowing. Timings, light and panel
 * colours are in {@link XmbStyle}; what the owner may set, in {@link XmbConfig}.
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
    private static final float META_X = AX + 152, META_W = 1920 - META_X - 120;

    private float s;
    private Config cfg;
    private XmbSounds sounds;
    private XmbMusic music;
    private XmbStatus status;
    private List<XmbMenus.Menu> menus = Collections.emptyList();
    /** The colour shown while the owner moves through the theme colours; null otherwise. */
    private String previewColor;
    private XmbStatusPrefs statusPrefs;
    /** The last catalog shown, as text: a reload that would show the same is skipped (XmbCatalog.signature), one
     *  with the same categories and items (structure) changes in place. */
    private String shownSignature, shownStructure;
    private XmbWave wave;
    private GLSurfaceView waveView;
    private boolean reducedMotion;
    private long animMs = MOVE_MS;
    private final PathInterpolator ease = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Typeface light, regular, medium, extraLight;

    private FrameLayout ui, catsRow, column;
    /** Black over the wave, faded away when Home first comes up after the box starts. */
    private View veil;
    /** The clock with the status row, faded in last by the intro. */
    private View top;
    /** The activity was just made (the intro is due) / came back from an app (the return is due). */
    private boolean introDue = true, returnDue;
    /** The light under the selected row: a pool of the accent colour on a glossy floor, and a thin line of light. */
    private ImageView selBar;
    private TextView clockH, clockColon, clockM, dateView, toast;

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

    private static final class Row {
        XmbCatalog.Item item;
        FrameLayout root, thumbBox;
        /** The title and the detail line. */
        LinearLayout meta;
        ImageView glow;
        View ring;
        TextView title, detail;
        boolean tile;
        /** How bright layoutColumn last made the row: the intro brings it to that. */
        float alpha = 1f;
        /** The glyph on screen (its drawable resource), to tell a changed one by; 0 for an app's art. */
        int iconRes;
    }

    private static final class CatView {
        FrameLayout root;
        ImageView icon, glow;
        TextView label;
        /** The icon on screen (its drawable resource), to tell a changed one by. */
        int iconRes;
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        XmbDebug.init(this);
        cfg = Config.load(this);
        XmbCatalog.invalidate();   // packages may have changed while no Home was listening
        bootDue = firstStartSinceBoot();
        // The wave's rate as Diagnostics left it, while Diagnostics is listed; else as configured.
        wave60 = cfg.xmb.diagnostics ? prefs().getBoolean(PREF_WAVE60, cfg.xmb.waveFps >= 60) : cfg.xmb.waveFps >= 60;
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
        status.setMotion(!reducedMotion);
        status.setLimits(cfg.xmb.lowBattery, cfg.xmb.lowStorageMb);
        statusPrefs = XmbStatusPrefs.load(this, cfg.xmb.status);
        status.setPrefs(statusPrefs);
        // The connection changed (Wi-Fi up after the box starts): the Network category says so, soon.
        status.setOnNetwork(() -> {
            dirty = true;
            handler.removeCallbacks(reloadLater);
            if (resumed) handler.postDelayed(reloadLater, NETWORK_RELOAD_MS);
        });
        XmbRecorder.listener = (running, message) -> {
            XmbDebug.log(running ? R.string.xmb_log_recording_started : R.string.xmb_log_recording_ended);
            status.refresh();
            if (message != null) showToast(message);
        };

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        if (glesVersion() >= 0x30000) {
            wave = new XmbWave();
            waveView = new GLSurfaceView(this);
            waveView.setEGLContextFactory(new XmbWave.ContextFactory());   // GLES 3, the menu's frames first
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
        veil = new View(this);
        veil.setBackgroundColor(Color.BLACK);
        veil.setAlpha(0f);
        veil.setVisibility(View.GONE);
        root.addView(veil, new FrameLayout.LayoutParams(-1, -1));
        ui = new FrameLayout(this);
        // Faded as a whole (idle, return): its alpha goes to each child as it is drawn, rather than the whole screen
        // being drawn off-screen first on every frame of the fade (a 1920x1080 pass, which made the menu drop frames).
        ui.forceHasOverlappingRendering(false);
        ui.setClipChildren(false);
        root.addView(ui, new FrameLayout.LayoutParams(-1, -1));
        buildChrome();
        setContentView(root);
        debugPanel = new TextView(this);
        debugPanel.setTypeface(Typeface.MONOSPACE);
        debugPanel.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(17));
        debugPanel.setTextColor(PANEL_INK);
        debugPanel.setBackgroundColor(PANEL_BG);
        debugPanel.setPadding(px(16), px(10), px(16), px(10));
        debugPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(px(860), -2, Gravity.BOTTOM | Gravity.START);
        dlp.leftMargin = px(30);
        dlp.bottomMargin = px(30);
        root.addView(debugPanel, dlp);
        setDebugPanel(cfg.xmb.diagnostics && prefs().getBoolean(PREF_DEBUG_PANEL, false));

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

    /** After a change of connection, the Network category is built again this soon (callbacks come in bursts). */
    private static final long NETWORK_RELOAD_MS = 300;

    private final Runnable reloadLater = () -> {
        if (resumed && dirty) reload();
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Uri data = intent.getData();
            String what = intent.getAction();
            boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
            XmbDebug.log(Intent.ACTION_PACKAGE_ADDED.equals(what) ? replacing ? R.string.xmb_log_app_updated : R.string.xmb_log_app_added
                    : Intent.ACTION_PACKAGE_REMOVED.equals(what) ? R.string.xmb_log_app_removed : R.string.xmb_log_app_changed,
                    data != null ? data.getSchemeSpecificPart() : "?");
            // An update sends REMOVED (replacing) then ADDED: only the second one matters.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            XmbCatalog.invalidate();
            dirty = true;
            // Play Store updates come in bursts: one reload after they settle, not one per broadcast.
            handler.removeCallbacks(reloadLater);
            if (resumed) handler.postDelayed(reloadLater, RELOAD_SETTLE_MS);
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
            XmbDebug.log(dreaming ? R.string.xmb_log_dream_started : R.string.xmb_log_dream_ended);
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
        XmbCloser.homeShown();
        if (stopped && !introDue) returnDue = true;
        stopped = false;
        dirty = true;   // last-opened times and the network line change while an app is in front
        runningReadAt = 0;   // and which apps are left open
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // Only handed an intent (Home pressed on Home): the music, the wave and the status row never stopped.
        boolean brief = pausePending;
        pausePending = false;
        later.removeCallbacks(pauseFully);
        // A boot animation the short pause left without its timeout: the menu comes now, as for a key.
        if (booting) skipBoot();
        updateAccent();
        if (dirty) reload();
        // Back from closing apps, afterClosing reads them below: once is enough (each reading is work for Android).
        if (closed == null && !rows.isEmpty() && XmbCatalog.RUNNING.equals(selectedRow().item.key)) {
            readRunning(false, null);
        }
        tick();
        startBlink();
        // Standby long enough with Home in front: waking up is turning the TV on (xmb_boot_animation "power").
        if (screenOffAt > 0) {
            if (SystemClock.elapsedRealtime() - screenOffAt >= WAKE_BOOT_AFTER_MS
                    && XmbConfig.POWER.equals(cfg.xmb.bootAnimation)) bootDue = true;
            screenOffAt = 0;
        }
        boolean boot = bootDue && wave != null && animMs > 0 && !XmbConfig.OFF.equals(cfg.xmb.bootAnimation);
        bootDue = false;
        // Before the wave's first frame, so that frame is already the animation's black.
        if (boot) startBootAnimation();
        if (waveView != null) {
            if (!brief) {
                waveView.onResume();
                wave.resetClock();
            }
            startWave();
        }
        wake(false);
        // The start-up sound: with the boot animation's lines; without one, as Home first comes up after a start.
        boolean jingle = !boot && !openingPlayed && SystemClock.elapsedRealtime() < 5 * 60_000;
        if (jingle) sounds.play(XmbSounds.OPENING);
        openingPlayed = true;
        status.start();
        if (debugPanel.getVisibility() == View.VISIBLE) setDebugPanel(true);
        if (!dreaming && !brief) music.resume(boot ? 7500 : jingle ? 7000 : 1200);
        if (boot) {
            // the menu comes in at the animation's mark
        } else if (introDue) {
            playIntro(jingle);
        } else if (returnDue || launched) {
            // (launched: back before the app had come up, or it closed at once; Home was never stopped)
            if (closingApps) settle();   // the same picture as before the cover: nothing to bring in
            else playReturn();
        } else if (pendingAnim != null || launching) {
            settle();
        }
        introDue = returnDue = false;
        closingApps = false;
        if (closed != null) afterClosing();   // back from closing apps in Settings
    }

    @Override
    protected void onPause() {
        putDown();
        resumed = false;
        preloading = 0;
        if (!closingApps) closeSide(true);   // closing apps: the list stays for when Home is back
        closePrompt();
        toast.animate().cancel();
        toast.setAlpha(0f);
        handler.removeCallbacksAndMessages(null);
        // Home pressed on Home: Android pauses the activity, hands it the intent and resumes it, all in one message.
        // Stopping the music, the wave's surface and the status row for that made the music stop and start again,
        // the wave stall and the clock's colon flicker, so they stop at the next message, unless onResume came first.
        pausePending = true;
        later.post(pauseFully);
        super.onPause();
    }

    /** Its own handler: onPause clears this activity's. */
    private final Handler later = new Handler(Looper.getMainLooper());
    /** Paused, and what pausing stops (pauseFully) has not stopped yet. */
    private boolean pausePending;
    private final Runnable pauseFully = this::pauseFully;

    /** What a pause stops, once it is more than Home being handed an intent: at the next message, or at onStop. */
    private void pauseFully() {
        later.removeCallbacks(pauseFully);
        if (!pausePending) return;
        pausePending = false;
        if (booting) {
            // Something came up mid-animation: the wave as it always is; the return puts the menu back.
            booting = false;
            wave.endBoot();
        }
        XmbSpeedTest.cancel();
        status.stop();
        if (!closingApps) music.pause();   // closing apps takes a second: the music goes on
        if (waveView != null) waveView.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        XmbDebug.log(hasFocus ? R.string.xmb_log_focus_back : R.string.xmb_log_focus_lost);
        if (hasFocus) XmbCloser.homeBack();   // Home on screen again: the closing cover can go
        if (resumed && !dreaming) {
            if (hasFocus) music.resume(300);
            else if (!promptOpen) music.pause();
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

    /**
     * Gives memory back while an app is in front: the music players, the cached glows and selection light (drawn
     * again on return), and under more pressure the decoded app art (reloaded with the next load).
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            music.releasePlayers();
            XmbGlow.trim();
            selLight = null;
        }
        if (level >= TRIM_MEMORY_BACKGROUND) {
            XmbCatalog.trim();
            dirty = true;
            shownSignature = null;
            shownStructure = null;
        }
    }

    /**
     * A remote reconnecting, an HDMI mode change or a new language: handled here instead of recreating Home (which
     * replayed the intro with no key pressed). Texts are reloaded; a language change gets new strings that way.
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        XmbDebug.init(this);   // the event panel in the new language too
        XmbDebug.log(R.string.xmb_log_config);
        spaceDate();
        tick();   // the date in the new language
        XmbCatalog.invalidate();   // a new language: the apps' labels
        dirty = true;
        shownSignature = null;
        shownStructure = null;
        if (resumed) reload();
    }

    @Override
    protected void onStop() {
        pauseFully();
        stopped = true;
        XmbCloser.homeHidden();   // the closing cover's window, if one was kept, goes while Home is out of sight
        // Stopped because the TV went to standby with Home in front, not because an app came up: noted, so that
        // waking up after a while plays the boot animation.
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        if (pm != null && !pm.isInteractive()) screenOffAt = SystemClock.elapsedRealtime();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        unregisterReceiver(dreamReceiver);
        sounds.release();
        music.release();
        XmbRecorder.listener = null;
        XmbDebug.listener = null;
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while Home is already in front: back to Video, first item. From an app, keep the place: coming
        // back from one, Android starts Home before it hands over the intent, so stopped is already false here and
        // returnDue (set by onStart) tells the two apart.
        if (stopped || returnDue || introDue || intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        // Brought back by XmbCloser after closing apps, not the Home button: the place and the list stay.
        if (intent.getBooleanExtra(XmbCloser.EXTRA_BACK, false)) return;
        // Home pressed while an app was still coming up (Home paused for it, not yet stopped): the way back from that
        // app, not Home pressed on Home. Taken for the latter, the menu its opening had faded out never came back.
        if (launched || (!resumed && !pausePending)) return;
        XmbDebug.log(R.string.xmb_log_home_on_home);
        putDown();
        if (booting) {
            skipBoot();   // as any key does
            return;
        }
        closePrompt();
        closeSide(false);
        selectedKey.remove(XmbCatalog.VIDEO);
        int video = indexOfCat(XmbCatalog.VIDEO);
        if (video >= 0 && video != cat) selectCategory(video, false);
        else if (cat >= 0) layoutColumn(false);
        // Fades in only when the menu had faded to the wave: on screen already, fading it in again (2.4 s of the
        // whole menu drawn into a layer) left the wave short of the GPU.
        wake(false);
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back only closes the side list (options included) or the name prompt.
        if (promptVisible()) {
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
        }
    }

    // ---- wave pacing -----------------------------------------------------------------------------------------

    private int frame;
    /** The wave at the full rate always: Settings > Diagnostics, or xmb_wave_fps 60 (twice the GPU's work). */
    private boolean wave60;
    private static final String PREF_WAVE60 = "xmb_wave60";
    private final Choreographer.FrameCallback waveTick = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!resumed || waveView == null || dreaming) return;
            // 30 frames a second is smooth for a slow wave and halves the GPU's work; the boot animation, whose
            // lines cross the screen in under three seconds, gets every frame the display shows.
            if (wave.fullRate || wave60 || (frame++ & 1) == 0) waveView.requestRender();
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
        // Drifting into a new colour (the hour's, the month's, a saved choice) over a few seconds; while the owner
        // runs through the colours, quickly.
        if (wave != null) wave.setColor(c, reducedMotion ? 0f : previewColor != null ? COLOR_PICK_TAU : COLOR_DRIFT_TAU);
        if (waveView != null && reducedMotion) waveView.requestRender();
        if (selBar != null) selBar.setImageBitmap(selectionLight());
        status.setAccent(accentUi);
        // While the owner runs through colours only the wave and the bar follow; the glows catch up on the choice.
        if (previewColor != null) return;
        // The glows on screen (the selected category's and row's) cross-fade into the new colour, the rest switch.
        for (int i = 0; i < catViews.size(); i++) {
            int at = i;
            if (i == cat) crossfade(catViews.get(i).glow, () -> applyCatGlow(at));
            else applyCatGlow(i);
        }
        Row sel = rows.isEmpty() ? null : selectedRow();
        for (Row r : rows) {
            if (r == sel) crossfade(r.glow, () -> applyRowGlow(r));
            else applyRowGlow(r);
        }
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
        String theme = cfg.xmb.color == null ? XmbAccent.MONTH : cfg.xmb.color;
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
        if (fixed >= 0) return getString(R.string.xmb_color_custom_code, XmbAccent.hex(fixed));
        return getString(R.string.xmb_color_month);
    }

    // ---- layout helpers --------------------------------------------------------------------------------------

    private int px(float units) {
        return Math.round(units * s);
    }

    private static int withAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    private static ColorStateList tint(int color) {
        return ColorStateList.valueOf(color);
    }

    /** Fades a view from where it is now, starting at once (a view's animator keeps the last start delay). */
    private void fade(View v, float alpha, long ms) {
        v.animate().alpha(alpha).setStartDelay(0).setDuration(ms).setInterpolator(ease).start();
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
        // Too long for its place: the end fades out, rather than an ellipsis added to the text.
        t.setEllipsize(null);
        t.setHorizontalFadingEdgeEnabled(true);
        t.setFadingEdgeLength(px(48));
        return t;
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

    // ---- the fixed parts: clock, category row, column, side list, toast ---------------------------------------

    private void buildChrome() {
        LinearLayout time = new LinearLayout(this);
        time.setOrientation(LinearLayout.HORIZONTAL);
        clockH = text(extraLight, 50, INK);
        clockColon = text(extraLight, 50, INK);
        clockColon.setText(":");
        clockColon.setPadding(px(2), 0, px(2), 0);
        clockM = text(extraLight, 50, INK);
        clockH.setFontFeatureSettings("tnum");   // equal-width digits: the clock and the row beside it keep still
        clockM.setFontFeatureSettings("tnum");
        // A colon is drawn for lower-case text, its dots round the middle of x-height: lifted to the middle of the
        // figures, it sits halfway up the digits beside it (it was 7 px low).
        clockColon.setTranslationY(XmbStatus.inkCentre(clockColon.getPaint(), "0123456789")
                - XmbStatus.inkCentre(clockColon.getPaint(), ":"));
        time.addView(clockH);
        time.addView(clockColon);
        time.addView(clockM);
        dateView = text(regular, 21, INK_FAINT);
        spaceDate();
        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setGravity(Gravity.END);
        clock.addView(time, new LinearLayout.LayoutParams(-2, -2));   // not the date's width: that cut the minutes off
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = px(8);
        clock.addView(dateView, dlp);
        // The status row left of the clock, its icons centred on the digits.
        LinearLayout top = new LinearLayout(this);
        top.forceHasOverlappingRendering(false);
        this.top = top;
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
        catsRow.forceHasOverlappingRendering(false);   // three screens wide: never drawn off-screen to fade
        catsRow.setClipChildren(false);
        place(ui, catsRow, 0, CAT_TOP, 1920 * 3, 170);

        column = new FrameLayout(this);
        column.forceHasOverlappingRendering(false);   // full screen: its fades go to the rows, not an off-screen pass
        column.setClipChildren(false);
        ui.addView(column, new FrameLayout.LayoutParams(-1, -1));

        side = new FrameLayout(this);
        side.forceHasOverlappingRendering(false);
        side.setClipChildren(false);
        side.setVisibility(View.GONE);
        ui.addView(side, new FrameLayout.LayoutParams(-1, -1));

        toast = text(regular, 24, INK);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(TOAST_BG);
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
            clockColon.setAlpha(colonDim ? COLON_DIM : 1f);
            // Faded out to the wave (and the clock not kept on screen): nothing to blink for.
            if (resumed && !(idle && !statusPrefs.keepOnIdle)) handler.postDelayed(this, BLINK_MS);
        }
    };

    /** Spaced like KittiwanaNotify's date, except in Thai, whose marks sit on the letters and must not be pulled apart. */
    private void spaceDate() {
        dateView.setLetterSpacing("th".equals(Locale.getDefault().getLanguage()) ? 0f : 0.06f);
    }

    /** The colon blinks from bright, in one chain of steps: a second chain, a moment behind, made it flicker. */
    private void startBlink() {
        handler.removeCallbacks(blink);
        if (reducedMotion) return;
        colonDim = true;   // the first step brings it up
        handler.post(blink);
    }

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
        if (carrying != null) {
            dirty = true;   // after the app is put down: a rebuild now would drop it mid-move
            return;
        }
        long t0 = SystemClock.uptimeMillis();
        dirty = false;
        if (cat >= 0 && cat < cats.size()) selectedCat = cats.get(cat).key;
        menus = XmbMenus.load(this, cfg.xmb.menus);
        XmbCatalog.SettingsState state = new XmbCatalog.SettingsState();
        state.soundsAvailable = sounds.available();
        state.soundsOn = sounds.isOn();
        state.musicAvailable = music.available();
        state.musicLevel = music.level();
        state.colorName = colorName(colorMode());
        List<XmbCatalog.Category> loaded = XmbCatalog.load(this, cfg, menus, state);
        long loadMs = SystemClock.uptimeMillis() - t0;
        String sig = XmbCatalog.signature(loaded), structure = XmbCatalog.structure(loaded);
        if (sig.equals(shownSignature) && !catViews.isEmpty()) {
            // The same apps and items: only lines such as "last opened" may differ, and they change in place.
            refreshInPlace(loaded);
            XmbDebug.log(R.string.xmb_log_reload_same, loadMs);
            return;
        }
        if (structure.equals(shownStructure) && !catViews.isEmpty()) {
            // The same categories and items, some saying something else (a setting's value, a menu's name or icon):
            // they change where they are, cross-fading.
            refreshInPlace(loaded);
            shownSignature = sig;
            XmbDebug.log(R.string.xmb_log_reload_in_place, loadMs);
            return;
        }
        if (!cats.isEmpty() && loaded.size() != cats.size()) {
            XmbDebug.log(R.string.xmb_log_reload_rebuilt_was, loaded.size(), cats.size(), loadMs);
        } else {
            XmbDebug.log(R.string.xmb_log_reload_rebuilt, loaded.size(), loadMs);
        }
        // Only now: an unchanged reload (an app updating in the background) leaves an item's options open.
        if (optionsFor != null) closeSide(true);
        // Moves rather than a jump: categories and rows that stay slide from where they were to their new places,
        // new ones come in, those that went fade out where they stood.
        boolean moves = animMs > 0 && resumed && !booting && !catViews.isEmpty();
        Map<String, Float> catsWere = moves ? catPlaces() : null, rowsWere = moves ? rowPlaces() : null;
        String columnWas = cat >= 0 && cat < cats.size() ? cats.get(cat).key : null;
        if (moves) leaveFor(loaded, columnWas);
        shownSignature = sig;
        shownStructure = structure;
        cats = loaded;
        buildCategories();
        if (sideVisible()) dimOtherCategories(sideDims);
        int target = indexOfCat(selectedCat);
        if (target < 0) target = Math.max(0, indexOfCat(XmbCatalog.VIDEO));
        cat = -1;
        selectCategory(target, true);
        if (moves) arrive(catsWere, rowsWere, columnWas);
        if (booting) hideForIntro(false);   // rebuilt mid-animation (an app updated): still hidden for the intro
    }

    /**
     * Same categories and items as on screen: the new items are taken without rebuilding a view. A title, a
     * setting's value or an icon that changed cross-fades; an app's "last opened" line just changes.
     */
    private void refreshInPlace(List<XmbCatalog.Category> loaded) {
        List<XmbCatalog.Category> was = cats;
        cats = loaded;
        for (int i = 0; i < loaded.size() && i < was.size() && i < catViews.size(); i++) {
            XmbCatalog.Category before = was.get(i), now = loaded.get(i);
            CatView v = catViews.get(i);
            // Against what is on screen, not the last load: a change cut short earlier is made now.
            if (!TextUtils.equals(v.label.getText(), now.label)) crossfade(v.label, () -> v.label.setText(now.label));
            if (v.iconRes != now.iconRes) {
                int at = i;
                v.iconRes = now.iconRes;
                crossfade(v.icon, () -> v.icon.setImageDrawable(now.icon.mutate()));
                crossfade(v.glow, () -> applyCatGlow(at));
            }
        }
        if (cat < 0 || cat >= cats.size()) return;
        List<XmbCatalog.Item> items = cats.get(cat).items;
        for (int i = 0; i < rows.size() && i < items.size(); i++) {
            Row r = rows.get(i);
            XmbCatalog.Item now = items.get(i);
            r.item = now;
            if (!TextUtils.equals(r.title.getText(), now.title)) crossfade(r.title, () -> r.title.setText(now.title));
            if (!TextUtils.equals(r.detail.getText(), now.detail)) {
                if (now.pkg == null && r.detail.getVisibility() == View.VISIBLE) {
                    crossfade(r.detail, () -> r.detail.setText(now.detail));
                } else {
                    setNow(r.detail, now.detail);
                }
            }
            if (!r.tile && r.iconRes != now.iconRes) {
                r.iconRes = now.iconRes;
                ImageView glyph = (ImageView) r.thumbBox.getChildAt(1);
                crossfade(glyph, () -> glyph.setImageDrawable(now.icon.mutate()));
                crossfade(r.glow, () -> applyRowGlow(r));
            }
        }
    }

    /** Text set at once, which a cross-fade still under way on the view must not overwrite afterwards. */
    private static void setNow(TextView t, CharSequence text) {
        t.setTag(R.id.xmb_pending, null);
        t.setText(text);
    }

    /**
     * Fades a view out, makes the change, fades it back to the light it had; at once without animations. The change is
     * made even when another animation of the view cuts the fade short (layoutColumn, settle: the Network item kept
     * "Offline" after Wi-Fi came up), and only the latest of two quick changes to one view is made.
     */
    private void crossfade(View v, Runnable change) {
        Object token = new Object();
        v.setTag(R.id.xmb_pending, token);
        Runnable apply = () -> {
            if (v.getTag(R.id.xmb_pending) != token) return;
            v.setTag(R.id.xmb_pending, null);
            change.run();
        };
        if (animMs == 0) {
            apply.run();
            return;
        }
        float to = v.getAlpha();
        later.postDelayed(apply, CHANGE_OUT_MS + 100);   // the fade cut short: the change all the same
        v.animate().alpha(0f).setStartDelay(0).setDuration(CHANGE_OUT_MS).setInterpolator(null).withEndAction(() -> {
            later.removeCallbacks(apply);
            apply.run();
            v.animate().alpha(to).setStartDelay(0).setDuration(CHANGE_IN_MS).setInterpolator(ease).start();
        }).start();
    }

    /** Each category's place on screen (in the row's coordinates: place() positions by translation), by key. */
    private Map<String, Float> catPlaces() {
        Map<String, Float> out = new HashMap<>();
        for (int i = 0; i < catViews.size() && i < cats.size(); i++) {
            out.put(cats.get(i).key, catsRow.getTranslationX() + catViews.get(i).root.getTranslationX());
        }
        return out;
    }

    /** Each row of the column on screen, its place by its item's key. */
    private Map<String, Float> rowPlaces() {
        Map<String, Float> out = new HashMap<>();
        for (Row r : rows) out.put(r.item.key, column.getTranslationY() + r.root.getTranslationY());
        return out;
    }

    /** Before a rebuild: the categories, and the rows of the column on screen, that will not be there fade out. */
    private void leaveFor(List<XmbCatalog.Category> loaded, String columnWas) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        XmbCatalog.Category column = null;
        for (XmbCatalog.Category c : loaded) {
            keys.add(c.key);
            if (c.key.equals(columnWas)) column = c;
        }
        for (int i = 0; i < catViews.size() && i < cats.size(); i++) {
            if (!keys.contains(cats.get(i).key)) fadeAway(catViews.get(i).root);
        }
        java.util.Set<String> items = new java.util.HashSet<>();
        if (column != null) for (XmbCatalog.Item it : column.items) items.add(it.key);
        for (Row r : rows) if (!items.contains(r.item.key)) fadeAway(r.root);
    }

    /** A view going: moved above everything, where it is, to fade out and shrink a little. */
    private void fadeAway(View v) {
        if (v.getParent() == null) return;
        android.view.ViewGroupOverlay overlay = ui.getOverlay();
        overlay.add(v);
        v.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setStartDelay(0).setDuration(GO_MS).setInterpolator(ease)
                .withEndAction(() -> overlay.remove(v)).start();
    }

    /** After a rebuild: what stayed slides from where it was, what is new comes in. */
    private void arrive(Map<String, Float> catsWere, Map<String, Float> rowsWere, String columnWas) {
        for (int i = 0; i < catViews.size() && i < cats.size(); i++) {
            View root = catViews.get(i).root;
            Float was = catsWere.get(cats.get(i).key);
            float home = root.getTranslationX();
            if (was != null) {
                float dx = was - (catsRow.getTranslationX() + home);
                if (Math.abs(dx) < 1f) continue;
                root.setTranslationX(home + dx);
                root.animate().translationX(home).setStartDelay(0).setDuration(SHIFT_MS).setInterpolator(ease).start();
            } else {
                root.setAlpha(0f);
                root.setScaleX(0.85f);
                root.setScaleY(0.85f);
                root.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(COME_MS).setInterpolator(ease)
                        .start();
            }
        }
        boolean sameColumn = cat >= 0 && cats.get(cat).key.equals(columnWas);
        for (Row r : rows) {
            Float was = sameColumn ? rowsWere.get(r.item.key) : null;
            float home = r.root.getTranslationY();
            if (was != null) {
                float dy = was - (column.getTranslationY() + home);
                if (Math.abs(dy) < 1f) continue;
                r.root.setTranslationY(home + dy);
                r.root.animate().translationY(home).setStartDelay(0).setDuration(SHIFT_MS).setInterpolator(ease).start();
            } else {
                r.root.setAlpha(0f);
                r.root.animate().alpha(r.alpha).setStartDelay(0).setDuration(COME_MS).setInterpolator(ease).start();
            }
        }
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
            v.iconRes = c.iconRes;
            int pad = px(13 * CAT_ZOOM);
            v.icon.setPadding(pad, pad, pad, pad);
            v.icon.setImageTintList(tint(INK_FAINT));
            v.icon.setAlpha(CAT_DIM);
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
        XmbCatalog.Category c = cats.get(i);
        v.glow.setImageBitmap(XmbGlow.of(c.iconRes, c.icon, size, px(37 * CAT_ZOOM), withAlpha(accentUi, 0xC0)));
        v.glow.setScaleType(ImageView.ScaleType.CENTER);
    }

    private static final int FADE_ON = 1, FADE_NONE = 2;

    /** A move with a key: the column cross-fades, except while the key repeats (FADE_NONE: no pile of fading columns). */
    private void selectCategory(int index, int fade) {
        selectCategory(index, false, fade == FADE_ON);
    }

    /** The category row's translation that puts category index under the cross's anchor. */
    private float catsX(int index) {
        return px(AX - (index * SLOT + SLOT / 2));
    }

    private void selectCategory(int index, boolean instant) {
        selectCategory(index, instant, !instant);
    }

    private void selectCategory(int index, boolean instant, boolean fadeColumn) {
        if (index < 0 || index >= cats.size()) return;
        nudgeToken++;
        boolean changed = index != cat;
        cat = index;
        selectedCat = cats.get(cat).key;
        float tx = catsX(index);
        if (instant || animMs == 0) {
            catsRow.animate().cancel();
            catsRow.setTranslationX(tx);
        }
        else catsRow.animate().translationX(tx).setDuration(animMs).setInterpolator(ease).start();
        for (int i = 0; i < catViews.size(); i++) {
            CatView v = catViews.get(i);
            boolean sel = i == index;
            v.icon.setImageTintList(tint(sel ? Color.WHITE : INK_FAINT));
            animate(v.icon, sel ? 1f : CAT_DIM, sel ? 1f : 1f / CAT_ZOOM, instant);
            animate(v.glow, sel ? 1f : 0f, sel ? 1f : 1f / CAT_ZOOM, instant);
            animate(v.label, sel ? 1f : 0f, 1f, instant);
        }
        if (changed || instant) buildColumn(fadeColumn);
        if (changed && !instant && !rows.isEmpty()) {
            announce(XmbCatalog.join(this, cats.get(cat).label, selectedRow().item.title));
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
        column.forceHasOverlappingRendering(false);   // full screen: its fades go to the rows, not an off-screen pass
        column.setClipChildren(false);
        ui.addView(column, ui.indexOfChild(old) + 1, new FrameLayout.LayoutParams(-1, -1));
        if (fade && animMs > 0) {
            // setStartDelay(0): a view's animator keeps the last start delay, and the intro gives the column one of
            // 650 ms; the first category switch after it then left the old column up beside the new one (the icons
            // seen doubled after boot).
            old.animate().alpha(0f).setStartDelay(0).setDuration(COLUMN_OUT_MS).withEndAction(() -> ui.removeView(old)).start();
            column.setAlpha(0f);
            column.setTranslationX(px(COLUMN_FROM));
            column.animate().alpha(1f).translationX(0).setDuration(COLUMN_IN_MS).setInterpolator(ease).start();
        } else {
            ui.removeView(old);
        }
        if (idle && statusPrefs.keepOnIdle) column.setAlpha(0f);
        rows.clear();
        selBar = place(column, new ImageView(this), AX - SEL_X, 0, SEL_W, SEL_H);
        selBar.setImageBitmap(selectionLight());
        for (XmbCatalog.Item item : cats.get(cat).items) rows.add(buildRow(item));
        layoutColumn(true);
    }

    private Row buildRow(XmbCatalog.Item item) {
        Row r = new Row();
        r.item = item;
        r.tile = isTile(item);
        r.root = new FrameLayout(this);
        r.root.setClipChildren(false);
        // Faded rows are drawn straight, without an offscreen pass each: their glow is off, nothing overlaps.
        r.root.forceHasOverlappingRendering(false);
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
                thumb.setBackgroundColor(ICON_TILE);
                thumb.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                int pad = px(24);
                thumb.setPadding(pad, pad, pad, pad);
                thumb.setImageDrawable(item.icon);
            }
            r.ring = place(r.thumbBox, new View(this), -2, -2, w + 4, h + 4);
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(px(RADIUS + 2));
            ring.setStroke(Math.max(2, px(2.5f)), TILE_RING);
            r.ring.setBackground(ring);
            r.ring.setAlpha(0f);
        } else {
            thumb.setImageDrawable(item.icon.mutate());
            r.iconRes = item.iconRes;
            int pad = px(18);
            thumb.setPadding(pad, pad, pad, pad);
        }
        applyRowGlow(r);

        LinearLayout meta = new LinearLayout(this);
        r.meta = meta;
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        place(r.root, meta, META_X, 0, META_W, ROW_H);
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

    /** An app is drawn as a tile (its banner, or its icon on a quiet tile); a built-in item as a glyph. */
    private static boolean isTile(XmbCatalog.Item item) {
        return item.banner != null || !item.glyph;
    }

    /** The glow behind an item's tile or glyph when selected (the same for every tile; XmbGlow keeps them). */
    private Bitmap rowGlow(XmbCatalog.Item item) {
        if (isTile(item)) return XmbGlow.rect(px(THUMB_W), px(THUMB_H), px(RADIUS), px(34), withAlpha(accentUi, 0xB3));
        return XmbGlow.of(item.iconRes, item.icon, px(GLYPH - 36), px(24) + px(18), withAlpha(accentUi, 0xC0));
    }

    private void applyRowGlow(Row r) {
        r.glow.setImageBitmap(rowGlow(r.item));
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
            float alpha = isSel ? 1f : j < sel ? (centre[j] < GONE_Y ? 0f : ROW_ABOVE) : ROW_BELOW;
            r.alpha = alpha;
            float ty = px(centre[j] - ROW_H / 2);
            if (instant || animMs == 0) {
                r.root.animate().cancel();
                r.root.setTranslationY(ty);
                r.root.setAlpha(alpha);
            } else {
                // (setStartDelay(0): the intro gives rows a delay of their own, which a view's animator keeps.)
                r.root.animate().translationY(ty).alpha(alpha).setStartDelay(0).setDuration(animMs).setInterpolator(ease)
                        .start();
            }
            animate(r.thumbBox, 1f, isSel ? 1f : ROW_THUMB, instant);
            animate(r.glow, isSel ? 1f : 0f, 1f, instant);
            if (r.ring != null) animate(r.ring, isSel ? 1f : 0f, 1f, instant);
            if (!r.tile) {
                ((ImageView) r.thumbBox.getChildAt(1)).setImageTintList(
                        tint(isSel ? Color.WHITE : INK_FAINT));
            }
            r.title.setTypeface(isSel ? medium : regular);
            // Being carried (Reorder): its title in the accent colour, the keys' hint as its line.
            boolean carried = isSel && r.item.key.equals(carrying);
            r.title.setTextColor(carried ? 0xFF000000 | XmbAccent.floor(accentUi) : isSel ? INK : INK_FAINT);
            animate(r.title, 1f, isSel ? 1f : ROW_TITLE, instant);
            boolean showDetail = isSel && (carried || !TextUtils.isEmpty(r.item.detail));
            boolean wasShown = r.detail.getVisibility() == View.VISIBLE;
            // The title is centred with its detail line: as the line comes or goes the title glides to its new
            // place instead of jumping there.
            if (showDetail != wasShown && !(instant || animMs == 0)) glideTitle(r);
            r.detail.setVisibility(showDetail ? View.VISIBLE : View.GONE);
            if (showDetail && !wasShown) {
                r.detail.setAlpha(instant || animMs == 0 ? 1f : 0f);
                if (!(instant || animMs == 0)) {
                    r.detail.animate().alpha(1f).setStartDelay(0).setDuration(animMs).setInterpolator(ease).start();
                }
            }
        }
        // The line runs 13 units above the foot of the selected slot: under the text, below a tile's lower edge.
        float barY = px(centre[sel] + slotH(rows.get(sel), true) / 2 - 13 - SEL_LINE);
        if (instant || animMs == 0) {
            selBar.animate().cancel();
            selBar.setTranslationY(barY);
        } else {
            selBar.animate().translationY(barY).setStartDelay(0).setDuration(animMs).setInterpolator(ease).start();
        }
        // putIfAbsent: an item missing for one load (an app mid-update) must not lose the owner's place for good.
        selectedKey.putIfAbsent(cats.get(cat).key, rows.get(sel).item.key);
        if (sideVisible()) fitSelectedRow();
        // Open apps under the bar: its line (how many apps run, the memory free) is read now.
        if (resumed && XmbCatalog.RUNNING.equals(rows.get(sel).item.key)) readRunning(false, null);
    }

    /** The title stays where it is drawn while the row is laid out anew, then glides to its new place. */
    private void glideTitle(Row r) {
        if (!r.meta.isLaidOut()) return;
        float before = r.title.getTop() + r.title.getTranslationY();
        r.meta.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                r.meta.getViewTreeObserver().removeOnPreDrawListener(this);
                float dy = before - r.title.getTop();
                if (Math.abs(dy) < 1f) return true;
                r.title.setTranslationY(dy);
                r.title.animate().translationY(0f).setStartDelay(0).setDuration(animMs).setInterpolator(ease).start();
                return true;
            }
        });
    }

    private void selectItem(int index, boolean sound) {
        if (cat < 0 || rows.isEmpty()) return;
        int clamped = Math.max(0, Math.min(rows.size() - 1, index));
        int prev = selectedIndex();
        if (clamped == prev && sound) {
            if (index != clamped) nudgeColumn(index < clamped ? 1 : -1);
            return;
        }
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
        // Volume, mute, media, channel and number keys are not ours: the system (and HDMI-CEC to the TV) takes them,
        // in every state, the faded menu included.
        if (!isNavKey(keyCode)) return super.onKeyDown(keyCode, event);
        // A key that just closed something (Back, Left, Menu) does nothing more until it is let go: held, its
        // repeats would close the next level, then move the cross-bar.
        if (keyCode == swallowUntilUp) return true;
        if (booting) {
            // A key during the boot animation: the rest of it runs fast and the menu comes now.
            if (event.getRepeatCount() == 0) skipBoot();
            return true;
        }
        if (launching) return true;   // the 200 ms between OK and the app opening
        if (pendingAnim != null) settle();   // a key before the intro started: put everything where it belongs
        if (idle) {
            // The first key only brings the menu back, as on the PS3.
            if (event.getRepeatCount() == 0) wake(true);
            return true;
        }
        wake(false);
        if (carrying != null) return carryKey(keyCode, event);
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event);
        if (promptVisible()) return super.onKeyDown(keyCode, event);
        if (adjusting) return adjustKey(keyCode, event);
        if (sideVisible()) return sideKey(keyCode, event);
        keyRepeat = event.getRepeatCount();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (cat > 0) {
                    selectCategory(cat - 1, event.getRepeatCount() > 0 ? FADE_NONE : FADE_ON);
                    sounds.play(XmbSounds.CURSOR);
                } else {
                    nudgeCategories(1, event);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (cat < cats.size() - 1) {
                    selectCategory(cat + 1, event.getRepeatCount() > 0 ? FADE_NONE : FADE_ON);
                    sounds.play(XmbSounds.CURSOR);
                } else {
                    nudgeCategories(-1, event);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                selectItem(selectedIndex() - 1, true);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                selectItem(selectedIndex() + 1, true);
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (event.getRepeatCount() == 0) {
                    sounds.play(XmbSounds.CURSOR);
                    openOptions(0);
                }
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
        if (isOk(keyCode) && !idle && !sideVisible() && !promptVisible()) {
            okLongPressed = true;
            sounds.play(XmbSounds.CURSOR);
            openOptions(0);
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    /** The keys the cross-bar answers; everything else goes to the system. */
    private static boolean isNavKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BACK:
                return true;
            default:
                return isOk(keyCode);
        }
    }

    /** A key held after it closed a panel, ignored until released; 0 for none. */
    private int swallowUntilUp;
    /** OK was pressed on an app: the cross-bar is fading out and the app about to open. */
    private boolean launching;
    /** The menu faded out for an app it opened, until it is put back (restoreAfterLaunch), whatever came between. */
    private boolean launched;

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == swallowUntilUp) {
            swallowUntilUp = 0;
            return true;
        }
        if (isOk(keyCode)) {
            if (!okLongPressed && !event.isCanceled() && !idle && !sideVisible()
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
        if (XmbCatalog.ACTION_STATUS.equals(item.action)) {
            openStatus(0);
            return;
        }
        if (XmbCatalog.ACTION_DIAG.equals(item.action)) {
            openDiagnostics(0);
            return;
        }
        if (XmbCatalog.ACTION_SPEEDTEST.equals(item.action)) {
            runSpeedTest();
            return;
        }
        if (XmbCatalog.ACTION_RUNNING.equals(item.action)) {
            readRunning(false, () -> showRunning(1));
            return;
        }
        open(item);
    }

    private void open(XmbCatalog.Item item) {
        if (item.intent == null) return;
        Row r = rows.isEmpty() ? null : selectedRow();
        if (animMs == 0 || r == null || r.item != item) {
            start(item);
            return;
        }
        // As on the PS3: the chosen tile steps forward and brightens, the cross-bar fades, then the app opens.
        launching = true;
        launched = true;
        // (A view's animator keeps its start delay for the next animation, so every one here sets its own.)
        r.thumbBox.animate().scaleX(LAUNCH_ZOOM).scaleY(LAUNCH_ZOOM).setStartDelay(0).setDuration(LAUNCH_ZOOM_MS)
                .setInterpolator(ease).start();
        r.glow.animate().alpha(1f).scaleX(LAUNCH_GLOW).scaleY(LAUNCH_GLOW).setStartDelay(0).setDuration(LAUNCH_ZOOM_MS).start();
        catsRow.animate().alpha(0f).translationY(px(CAT_TOP - LAUNCH_RISE)).setStartDelay(0).setDuration(LAUNCH_ROW_MS).start();
        column.animate().alpha(0f).setStartDelay(LAUNCH_COLUMN_DELAY_MS).setDuration(LAUNCH_FADE_MS).start();
        top.animate().alpha(0f).setStartDelay(0).setDuration(LAUNCH_FADE_MS).start();
        handler.postDelayed(() -> start(item), LAUNCH_START_MS);
    }

    // A plain start: an app opened with ActivityOptions.makeScaleUpAnimation left this Home marked translucent on
    // the Mi Box S (Android 11), so on the way back the app behind stayed visible through the wave's surface.
    private void start(XmbCatalog.Item item) {
        launching = false;
        try {
            startActivity(item.intent);
            if (item.pkg != null) LastOpened.record(this, item.pkg);
        } catch (RuntimeException e) {
            restoreAfterLaunch();
            showToast(getString(R.string.cant_open, item.title));
        }
    }

    /** Puts back what the launch animation moved, at once (returning plays its own animation over it). */
    private void restoreAfterLaunch() {
        launching = false;
        launched = false;
        catsRow.animate().cancel();
        catsRow.setAlpha(1f);
        catsRow.setTranslationY(px(CAT_TOP));   // its place: place() positions by translation
        if (cat >= 0) catsRow.setTranslationX(catsX(cat));
        column.animate().cancel();
        column.setAlpha(1f);
        column.setTranslationX(0f);
        column.setTranslationY(0f);
        top.animate().cancel();
        top.setAlpha(1f);
        if (!rows.isEmpty()) {
            Row r = selectedRow();
            r.glow.setScaleX(1f);
            r.glow.setScaleY(1f);
        }
        layoutColumn(true);
    }

    // ---- intro and return ----------------------------------------------------------------------------------

    /**
     * The intro or the return waits, hidden, until the window has finished coming in (on a cold start Android shows
     * a black placeholder first, and a task switch slides the window in): played earlier, it would be over before
     * anyone saw it. onEnterAnimationComplete starts it; if that never comes, a fallback does after 900 ms.
     */
    private Runnable pendingAnim;
    private final Runnable runPendingAnim = () -> {
        Runnable r = pendingAnim;
        pendingAnim = null;
        if (r != null) r.run();
    };

    private void queueAnim(Runnable run) {
        pendingAnim = run;
        handler.removeCallbacks(runPendingAnim);
        handler.postDelayed(runPendingAnim, ANIM_WAIT_MS);
    }

    @Override
    public void onEnterAnimationComplete() {
        super.onEnterAnimationComplete();
        handler.removeCallbacks(runPendingAnim);
        runPendingAnim.run();
    }

    /**
     * Home comes up: the wave rises out of black, the categories come in from the right one after another, the
     * column settles from below, the clock and the status row last. The long version after the box starts (with
     * the start-up jingle), a short one when Home was only restarted.
     */
    private void playIntro(boolean boot) {
        if (animMs == 0) return;
        float k = boot ? 1f : INTRO_SHORT;
        hideForIntro(true);
        queueAnim(() -> runIntro(k, true));
    }

    /** Everything the intro brings in, where it comes from: hidden, the categories to the right, the column low. */
    private void hideForIntro(boolean veiled) {
        if (veiled) {
            veil.animate().cancel();
            veil.setAlpha(1f);
            veil.setVisibility(View.VISIBLE);
        }
        for (int i = 0; i < catViews.size(); i++) {
            View v = catViews.get(i).root;
            v.animate().cancel();
            v.setAlpha(0f);
            // Each category sits at its slot by its translation (place() uses setX), so move from there.
            v.setTranslationX(px(i * SLOT) + px(INTRO_ROW_FROM));
            v.setScaleX(INTRO_CAT_SCALE);
            v.setScaleY(INTRO_CAT_SCALE);
        }
        column.animate().cancel();
        column.setAlpha(1f);
        column.setTranslationY(px(INTRO_COLUMN_FROM));
        for (Row r : rows) {
            r.root.animate().cancel();
            r.root.setAlpha(0f);
        }
        if (selBar != null) {
            selBar.animate().cancel();
            selBar.setAlpha(0f);
        }
        top.animate().cancel();
        top.setAlpha(0f);
        top.setTranslationY(px(-INTRO_TOP_FROM));
    }

    /** The intro at k of its full length; veiled: the black over the wave lifts too. */
    private void runIntro(float k, boolean veiled) {
        if (veiled) {
            // Gone once clear: a full-screen view over the wave's surface, even transparent, is still drawn.
            veil.animate().alpha(0f).setStartDelay(0).setDuration((long) (INTRO_VEIL_MS * k)).setInterpolator(ease)
                    .withEndAction(() -> veil.setVisibility(View.GONE)).start();
        }
        for (int i = 0; i < catViews.size(); i++) {
            catViews.get(i).root.animate().alpha(1f).translationX(px(i * SLOT)).scaleX(1f).scaleY(1f)
                    .setStartDelay((long) ((INTRO_ROW_DELAY_MS + INTRO_ROW_STEP_MS * Math.abs(i - cat)) * k))
                    .setDuration((long) (INTRO_ROW_MS * k)).setInterpolator(ease).start();
        }
        long columnAt = (long) (INTRO_COLUMN_DELAY_MS * k), columnMs = (long) (INTRO_COLUMN_MS * k);
        column.animate().alpha(1f).translationY(0f).setStartDelay(columnAt).setDuration(columnMs).setInterpolator(ease)
                .start();
        int sel = rows.isEmpty() ? 0 : Math.min(selectedIndex(), rows.size() - 1);
        for (int j = 0; j < rows.size(); j++) {
            Row r = rows.get(j);
            r.root.animate().alpha(r.alpha).setStartDelay(columnAt + (long) (INTRO_CASCADE_MS * Math.abs(j - sel) * k))
                    .setDuration(columnMs).setInterpolator(ease).start();
        }
        if (selBar != null) {
            selBar.animate().alpha(1f).setStartDelay(columnAt).setDuration(columnMs).setInterpolator(ease).start();
        }
        top.animate().alpha(1f).translationY(0f).setStartDelay((long) (INTRO_TOP_DELAY_MS * k))
                .setDuration((long) (INTRO_TOP_MS * k)).setInterpolator(ease).start();
    }

    // ---- the boot animation ------------------------------------------------------------------------------------

    private static final String PREF_BOOT_COUNT = "xmb_boot_count";
    /** The boot animation is due at the next resume: the box just started, or the TV was turned on again. */
    private boolean bootDue;
    /** It is playing: the menu waits for its mark, or a key. */
    private boolean booting;
    /** When Home was stopped by the TV going to standby (0: it was not), to tell standby from a blink of the screen. */
    private long screenOffAt;
    /** Categories still being made ready by the preload; the menu waits for them (BOOT_TIMEOUT_MS at most). */
    private int preloading;

    /** True the first time Home starts after the box did: Android's boot count is not the one Home saw last. */
    private boolean firstStartSinceBoot() {
        int count = android.provider.Settings.Global.getInt(getContentResolver(),
                android.provider.Settings.Global.BOOT_COUNT, -1);
        SharedPreferences p = prefs();
        if (count < 0) return !openingPlayed && SystemClock.elapsedRealtime() < 5 * 60_000;
        if (count == p.getInt(PREF_BOOT_COUNT, -1)) return false;
        p.edit().putInt(PREF_BOOT_COUNT, count).apply();
        // A launcher installed or restarted long after the box started is not a start.
        return SystemClock.elapsedRealtime() < 10 * 60_000;
    }

    /**
     * Turning the TV on (xmb_boot_animation): the wave plays its own boot animation (XmbWave.startBoot) while the
     * menu waits hidden: black, the lines drawn in white from left to right with the start-up sound, the colour
     * spreading out from the middle, then the menu's intro. Meanwhile what the menu shows is made ready (preload).
     */
    private void startBootAnimation() {
        booting = true;
        closePrompt();
        closeSide(true);
        settle();
        hideForIntro(false);
        XmbDebug.log(R.string.xmb_log_boot);
        wave.startBoot(mark -> {
            if (!booting) return;
            if (mark == XmbWave.LINES) sounds.play(XmbSounds.OPENING);
            else if (mark == XmbWave.MENU) bootMenu();
        });
        handler.postDelayed(bootTimeout, BOOT_TIMEOUT_MS);
        preload();
    }

    /** Should the wave not draw the animation (no focus, a GPU that refused it): the menu comes anyway. */
    private final Runnable bootTimeout = () -> {
        preloading = 0;
        bootMenu();
    };

    private void bootMenu() {
        if (!booting) return;
        if (preloading > 0) {
            handler.postDelayed(this::bootMenu, 100);   // the timeout clears preloading if it never ends
            return;
        }
        booting = false;
        handler.removeCallbacks(bootTimeout);
        runIntro(BOOT_INTRO, false);
        wake(false);
    }

    /** A key: what is left of the animation runs five times as fast, and the menu comes now. */
    private void skipBoot() {
        wave.skipBoot();
        preloading = 0;
        bootMenu();
    }

    /**
     * While the boot animation plays, what the menu will show is made ready: the glow behind every item (blurred
     * in software, the slow part of a first visit to a category) and every banner handed to the GPU
     * (Bitmap.prepareToDraw), one category per message so the animation's marks are never held up.
     */
    private void preload() {
        List<XmbCatalog.Category> all = new ArrayList<>(cats);
        preloading = all.size();
        for (int i = 0; i < all.size(); i++) {
            XmbCatalog.Category c = all.get(i);
            handler.postDelayed(() -> {
                for (XmbCatalog.Item item : c.items) {
                    if (item.banner instanceof android.graphics.drawable.BitmapDrawable) {
                        Bitmap b = ((android.graphics.drawable.BitmapDrawable) item.banner).getBitmap();
                        if (b != null) b.prepareToDraw();
                    }
                    rowGlow(item);
                }
                preloading = Math.max(0, preloading - 1);
            }, 40L * (i + 1));
        }
    }

    /** Back from an app: the cross-bar fades in, settling from slightly larger, the categories closing in. */
    /**
     * Back from an app: the menu fades in where it was. Nothing moves: it came in from a little larger with the other
     * categories closing in, which moved the whole screen, the clock and the status row too, after every app.
     */
    private void playReturn() {
        settle();
        if (animMs == 0 || idle) return;
        ui.animate().cancel();
        ui.setAlpha(0f);
        // In a layer: the whole menu is not drawn again for every frame of the fade.
        queueAnim(() -> ui.animate().alpha(1f).setStartDelay(0).setDuration(RETURN_MS).setInterpolator(ease).withLayer()
                .start());
    }

    /** Puts everything the launch, intro or return animations move back where it belongs, at once. */
    private void settle() {
        pendingAnim = null;
        veil.animate().cancel();
        veil.setAlpha(0f);
        veil.setVisibility(View.GONE);
        ui.animate().cancel();
        if (!idle) ui.setAlpha(1f);
        ui.setScaleX(1f);
        ui.setScaleY(1f);
        for (int i = 0; i < catViews.size(); i++) {
            View v = catViews.get(i).root;
            v.animate().cancel();
            v.setAlpha(1f);
            v.setTranslationX(px(i * SLOT));
            v.setScaleX(1f);
            v.setScaleY(1f);
        }
        column.setTranslationY(0f);
        if (selBar != null) {
            selBar.animate().cancel();
            selBar.setAlpha(1f);
        }
        top.setTranslationY(0f);
        restoreAfterLaunch();
    }

    /** Counts nudges and real moves: a nudge springs back only if nothing moved the cross-bar meanwhile. */
    private int nudgeToken;

    /**
     * At the end of a row or a column, the cross-bar gives a little and springs back, the way a list does at its
     * end. Once per press: a held key repeats, and nudging again from halfway would leave the bar off its place.
     * The place it springs back to is computed, never read from the view mid-animation.
     */
    private void nudgeCategories(int direction, KeyEvent event) {
        if (animMs == 0 || event.getRepeatCount() > 0 || cat < 0) return;
        int token = ++nudgeToken;
        float home = catsX(cat);
        catsRow.animate().translationX(home + px(NUDGE_ROW * direction)).setStartDelay(0).setDuration(NUDGE_OUT_MS)
                .setInterpolator(ease).withEndAction(() -> {
                    if (token == nudgeToken) {
                        catsRow.animate().translationX(home).setStartDelay(0).setDuration(NUDGE_BACK_MS).setInterpolator(ease)
                                .start();
                    }
                }).start();
    }

    private void nudgeColumn(int direction) {
        if (animMs == 0 || keyRepeat > 0) return;
        View col = column;
        col.animate().translationY(px(NUDGE_COLUMN * direction)).setStartDelay(0).setDuration(NUDGE_OUT_MS).setInterpolator(ease)
                .withEndAction(() -> col.animate().translationY(0f).setStartDelay(0).setDuration(NUDGE_BACK_MS)
                        .setInterpolator(ease).start()).start();
    }

    /** The repeat count of the key being handled, for nudges triggered further down. */
    private int keyRepeat;

    // ---- idle: the menu fades, the wave keeps flowing ----------------------------------------------------------

    private final Runnable goIdle = () -> {
        if (!resumed || pickerOpen()) return;
        idle = true;
        status.setAnimating(false);
        XmbDebug.log(R.string.xmb_log_idle);
        long ms = reducedMotion ? 0 : IDLE_FADE_MS;
        if (statusPrefs.keepOnIdle) {
            // The clock and the status row stay, a little dimmer, over the wave; the cross-bar goes.
            fade(catsRow, 0f, ms);
            fade(column, 0f, ms);
            fade(top, TOP_ON_WAVE, ms);
        } else {
            ui.animate().alpha(0f).setDuration(ms).setInterpolator(ease).withLayer().start();
        }
    };

    private void wake(boolean fromIdle) {
        handler.removeCallbacks(goIdle);
        if (idle || fromIdle) {
            idle = false;
            status.setAnimating(true);
            startBlink();
            long ms = reducedMotion ? 0 : WAKE_FADE_MS;
            ui.animate().alpha(1f).setDuration(ms).setInterpolator(ease).withLayer().start();
            fade(catsRow, 1f, ms);
            fade(column, 1f, ms);
            fade(top, 1f, ms);
        }
        // xmb_idle_seconds: 0 keeps the menu up.
        if (resumed && cfg.xmb.idleSeconds > 0) handler.postDelayed(goIdle, cfg.xmb.idleSeconds * 1000L);
    }

    // ---- options (hold OK or Menu): a floating list beside the item, the PSP's ----------------------------------

    /** The item whose options the side list shows, or null when it shows something else (a Settings list). */
    private String optionsFor;

    /** Open, and for an app Move and App info, in the side list beside the item, the categories left as they are. */
    private void openOptions(int focus) {
        if (cat < 0 || rows.isEmpty()) return;
        XmbCatalog.Item item = selectedRow().item;
        List<String> texts = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        texts.add(getString(R.string.xmb_open));
        icons.add(R.drawable.ic_xmb_open_in_new);
        actions.add(() -> {
            closeSide(true);
            activate();
        });
        if (item.pkg != null) {
            texts.add(getString(R.string.xmb_move));
            icons.add(R.drawable.ic_xmb_drive_file_move);
            int move = texts.size() - 1;
            actions.add(() -> showMoveTargets(item, move));
            if (appsInColumn() > 1) {
                texts.add(getString(R.string.xmb_reorder));
                icons.add(R.drawable.ic_xmb_swap_vert);
                actions.add(() -> startCarry(item));
            }
            texts.add(getString(R.string.xmb_app_info));
            icons.add(R.drawable.ic_xmb_info);
            actions.add(() -> {
                closeSide(true);
                try {
                    startActivity(XmbCatalog.appInfoIntent(this, item.pkg));
                } catch (RuntimeException e) {
                    showToast(getString(R.string.not_available));
                }
            });
            // Left open behind Home (Settings > Open apps knows): Close, last, so the others keep their places.
            XmbRunning.Snapshot s = XmbRunning.last();
            XmbRunning.App open = s != null ? s.find(item.pkg) : null;
            if (open != null) {
                texts.add(getString(R.string.xmb_close_app));
                icons.add(R.drawable.ic_xmb_close);
                actions.add(() -> {
                    closeSide(true);
                    closeApps(Collections.singletonList(open), 0);
                });
            }
            // What is open may have changed since the last reading: read again, and offer Close or not.
            readRunning(false, () -> {
                XmbRunning.Snapshot now = XmbRunning.last();
                boolean isOpen = now != null && now.find(item.pkg) != null;
                if (item.key.equals(optionsFor) && sideVisible() && isOpen != (open != null)) {
                    sideQuiet = true;
                    openOptions(Math.min(sideIdx, texts.size() - 1));
                }
            });
        }
        showSide(texts, icons, focus, i -> actions.get(i).run(), null, MENU_X, false);
        optionsFor = item.key;
    }

    // ---- reorder: an app carried up and down its category ---------------------------------------------------------

    /** The app being carried (Reorder), by key; null when none is. */
    private String carrying;

    /** The apps in the column on screen. */
    private int appsInColumn() {
        int n = 0;
        for (Row r : rows) if (r.item.pkg != null) n++;
        return n;
    }

    /** Options > Reorder: the app is picked up; Up and Down carry it through its category, OK puts it down. */
    private void startCarry(XmbCatalog.Item item) {
        closeSide(true);
        carrying = item.key;
        Row r = selectedRow();
        setNow(r.detail, getString(R.string.xmb_reorder_hint));
        layoutColumn(false);
        handler.removeCallbacks(goIdle);
    }

    private boolean carryKey(int keyCode, KeyEvent event) {
        keyRepeat = event.getRepeatCount();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                carryBy(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                carryBy(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;   // it stays in its category
            default:   // OK, Back or Menu puts it down
                if (keyRepeat == 0) {
                    sounds.play(XmbSounds.CONFIRM);
                    putDown();
                    swallowUntilUp = keyCode;
                }
                return true;
        }
    }

    /** One place up or down: it stays under the bar and the app it passes moves to its other side. */
    private void carryBy(int by) {
        List<XmbCatalog.Item> items = cats.get(cat).items;
        int at = selectedIndex(), to = at + by;
        if (to < 0 || to >= items.size() || items.get(to).pkg == null) {
            nudgeColumn(-by);
            return;
        }
        Collections.swap(items, at, to);
        Collections.swap(rows, at, to);
        // What is on screen is the new order: a reload that brings the saved order changes nothing.
        shownSignature = XmbCatalog.signature(cats);
        shownStructure = XmbCatalog.structure(cats);
        layoutColumn(false);
        sounds.play(by > 0 ? XmbSounds.CURSOR_DOWN : XmbSounds.CURSOR);
    }

    /** The carried app put down where it is: the order saved, its line as before. */
    private void putDown() {
        if (carrying == null) return;
        String key = carrying;
        carrying = null;
        saveOrder();
        for (Row r : rows) if (r.item.key.equals(key)) setNow(r.detail, r.item.detail);
        layoutColumn(false);
        if (resumed) wake(false);
        if (dirty && resumed) reload();
    }

    /** The categories whose order the owner set: only theirs is saved, the others keep sorting by name. */
    private static final String PREF_REORDERED = "xmb_reordered";

    /**
     * The carried app's category as it stands now, kept for the next load (Order, as the tile grid keeps it), with
     * the other categories the owner reordered; a category put back in its own order is let go, so it sorts by name
     * again (in the TV's language).
     */
    private void saveOrder() {
        if (cat < 0 || cat >= cats.size()) return;
        XmbCatalog.Category here = cats.get(cat);
        java.util.Set<String> reordered = new java.util.HashSet<>(
                prefs().getStringSet(PREF_REORDERED, Collections.emptySet()));
        if (XmbCatalog.inDefaultOrder(here.items, cfg.dockApps)) reordered.remove(here.key);
        else reordered.add(here.key);
        List<String> keys = new ArrayList<>();
        for (XmbCatalog.Category c : cats) {
            if (!reordered.contains(c.key)) continue;
            for (XmbCatalog.Item i : c.items) if (i.pkg != null && !keys.contains(i.key)) keys.add(i.key);
        }
        prefs().edit().putStringSet(PREF_REORDERED, reordered).apply();
        if (!Order.save(Order.file(this), new Order(Order.dockKey(cfg.dockApps), keys))) {
            showToast(getString(R.string.xmb_reorder_failed));
        }
    }

    /** Move: the menus that hold apps, but the one the app is in; Back returns to the options. */
    private void showMoveTargets(XmbCatalog.Item item, int back) {
        List<String> texts = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        String current = cats.get(cat).key;
        for (XmbMenus.Menu m : menus) {
            String target = m.key;
            if (!m.holdsApps() || target.equals(current)) continue;
            String label = XmbCatalog.label(this, m);
            texts.add(label);
            icons.add(XmbCatalog.iconRes(m));
            actions.add(() -> {
                closeSide(true);
                XmbCatalog.move(this, item.pkg, target);
                showToast(getString(R.string.xmb_moved, item.title, label));
                reload();
            });
        }
        // A longer list than the options: the categories step back, as for Settings' lists.
        showSide(texts, icons, 0, i -> actions.get(i).run(), () -> openOptions(back), MENU_X, true);
        optionsFor = item.key;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (promptVisible()) return super.dispatchKeyEvent(event);
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
        return super.dispatchKeyEvent(event);
    }

    private final Runnable hideToast = () -> toast.animate().alpha(0f).setDuration(TOAST_FADE_MS).start();

    private void showToast(String message) {
        toast.setText(message);
        toast.animate().alpha(1f).setDuration(TOAST_FADE_MS).start();
        handler.removeCallbacks(hideToast);
        handler.postDelayed(hideToast, TOAST_MS);
    }

    // ---- the side list: a frameless list right of the column (theme colour, menus) ------------------------------

    /** Settings' lists stand at SIDE_X; an item's options closer, at MENU_X, beside its title (the PSP's). */
    private static final float SIDE_X = 1400, MENU_X = 1180, SIDE_STEP = 64, SIDE_ICON = 44;
    /** A list's widest entry ends by the clock's right edge, moving the list left as far as SIDE_MIN_X if need be. */
    private static final float SIDE_RIGHT = 1920 - 96, SIDE_MIN_X = 1000;
    /** Marks an entry's icon that is an app's own, not tinted. */
    private static final Object SIDE_ART = new Object();

    private interface SideHandler {
        default void moved(int index) {
        }

        /** Up or Down while the list is open; true when the level used it (moving an item, say). */
        default boolean upDown(int by) {
            return false;
        }

        void picked(int index);
    }

    /** The next showSide replaces the list in place (a value changed), without the entrance animation. */
    private boolean sideQuiet;
    /** An entry shown in the accent colour (an item being carried in a reorder), or -1. */
    private int sideMarked = -1;

    private FrameLayout side;
    /** Where the list on screen stands, and whether the other categories faded out for it. */
    private float sideX = SIDE_X;
    private boolean sideDims;
    private final List<String> sideTexts = new ArrayList<>();
    /** Who each entry is, matched across a quiet rebuild: its text, unless the list gave its own (sideNextIds). */
    private final List<String> sideIds = new ArrayList<>();
    /** For the next showSide: who each entry is when its text can change (an open app's memory), else null. */
    private List<String> sideNextIds;
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

    /** The name prompt is open (it may still be fading out after it closed; that does not count). */
    private boolean promptOpen;

    private boolean promptVisible() {
        return promptOpen;
    }

    /** The side list, the colour picker or the name prompt is open: Home does not fade to the wave meanwhile. */
    private boolean pickerOpen() {
        return sideVisible() || adjusting || promptVisible() || carrying != null;
    }

    /** Opens a level of a Settings list: one entry per text (null for an icon alone) with its icon (0 for none). */
    private void showSide(List<String> texts, List<Integer> icons, int start, SideHandler h, Runnable back) {
        showSide(texts, icons, start, h, back, SIDE_X, true);
    }

    /** A level standing at x; dim: the other categories fade out, so the list stands clear of the cross-bar. */
    private void showSide(List<String> texts, List<Integer> icons, int start, SideHandler h, Runnable back, float x,
                          boolean dim) {
        showSide(texts, icons, null, start, h, back, x, dim);
    }

    /** Art: an app's own icon for an entry, drawn in its colours rather than tinted (null, or null entries: glyphs). */
    private void showSide(List<String> texts, List<Integer> icons, List<Drawable> art, int start, SideHandler h,
                          Runnable back, float x, boolean dim) {
        optionsFor = null;
        boolean quiet = sideQuiet;
        sideQuiet = false;
        List<String> ids = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            ids.add(sideNextIds != null && i < sideNextIds.size() ? sideNextIds.get(i) : sideKey(texts.get(i), i));
        }
        sideNextIds = null;
        // What was on screen: the quiet way (a value changed, an item carried), entries that stay keep their place to
        // slide from and a changed one cross-fades; to another level, the old entries fade out where they stand.
        // An entry with an identity of its own (an open app) stays itself when its text changes: it slides, its new
        // figure in place, instead of fading out at one place and in at another.
        Map<String, Float> wasY = new HashMap<>();
        boolean wasShowing = sideVisible() && animMs > 0 && resumed;
        if (wasShowing) {
            for (int i = 0; i < sideViews.size(); i++) {
                View e = sideViews.get(i);
                String key = sideIds.get(i);
                if (quiet && ids.contains(key)) wasY.put(key, e.getY());
                else sideAway(e, quiet ? 0f : -SIDE_FROM / 2);
            }
        }
        // Within one level (a value changed) the list never moves right and moves left smoothly: values of other
        // lengths must not make it jitter.
        float wasX = sideX, fitted = fitSide(texts, icons, art, x);
        sideX = quiet && wasShowing ? Math.min(wasX, fitted) : fitted;
        sideDims = dim;
        side.removeAllViews();
        sideViews.clear();
        sideTexts.clear();
        sideTexts.addAll(texts);
        sideIds.clear();
        sideIds.addAll(ids);
        for (int i = 0; i < texts.size(); i++) {
            LinearLayout e = new LinearLayout(this);
            e.forceHasOverlappingRendering(false);   // an icon beside its text: nothing overlaps
            e.setOrientation(LinearLayout.HORIZONTAL);
            e.setGravity(Gravity.CENTER_VERTICAL);
            e.setClipChildren(false);
            String t = texts.get(i);
            int icon = icons.get(i);
            Drawable picture = art != null ? art.get(i) : null;
            if (picture != null) {
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(picture);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setTag(SIDE_ART);
                e.addView(iv, new LinearLayout.LayoutParams(px(SIDE_ICON), px(SIDE_ICON)));
            } else if (icon != 0) {
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(getDrawable(icon).mutate());
                float size = t == null ? 56 : SIDE_ICON;
                e.addView(iv, new LinearLayout.LayoutParams(px(size), px(size)));
            }
            if (t != null) {
                TextView tv = text(regular, 30, INK_DIM);
                tv.setText(t);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                if (icon != 0 || picture != null) lp.leftMargin = px(18);
                e.addView(tv, lp);
            }
            // Text too long even then fades at the clock's right edge, selected (larger) or not.
            float lead = t == null ? 0 : sideLead(icon, picture);
            side.addView(e, new FrameLayout.LayoutParams(px(lead + (SIDE_RIGHT - sideX - lead) / SIDE_TEXT_ZOOM), px(SIDE_STEP)));
            e.setX(px(sideX));
            sideViews.add(e);
        }
        sideIdx = Math.max(0, Math.min(texts.size() - 1, start));
        sideHandler = h;
        sideBackAction = back;
        sideOkDown = false;
        side.setVisibility(View.VISIBLE);
        dimOtherCategories(dim);
        layoutSide(true);
        fitSelectedRow();
        if (animMs > 0 && quiet) {
            for (int i = 0; i < sideViews.size(); i++) {
                View e = sideViews.get(i);
                Float was = wasY.get(ids.get(i));
                float to = e.getAlpha(), y = e.getY();
                if (was != null) {
                    if (Math.abs(was - y) < 1f && sideX == wasX) continue;
                    e.setY(was);
                    e.setTranslationX(px(wasX));
                    e.animate().y(y).translationX(px(sideX)).setStartDelay(0).setDuration(SIDE_STEP_MS).setInterpolator(ease)
                            .start();
                } else if (wasShowing) {
                    e.setAlpha(0f);
                    e.animate().alpha(to).setStartDelay(CHANGE_OUT_MS).setDuration(CHANGE_IN_MS).setInterpolator(ease)
                            .start();
                }
            }
        }
        if (animMs > 0 && !quiet) {
            for (int i = 0; i < sideViews.size(); i++) {
                View e = sideViews.get(i);
                float to = e.getAlpha();
                e.setAlpha(0f);
                e.setTranslationX(px(sideX + SIDE_FROM));   // entries sit at sideX through their translation (setX)
                e.animate().alpha(to).translationX(px(sideX)).setStartDelay(SIDE_STAGGER_MS * Math.abs(i - sideIdx))
                        .setDuration(SIDE_IN_MS).setInterpolator(ease).start();
            }
        }
        handler.removeCallbacks(goIdle);
    }

    /** The room before an entry's text: its icon and the gap after it, or none. */
    private static float sideLead(int icon, Drawable art) {
        return icon != 0 || art != null ? SIDE_ICON + 18 : 0;
    }

    /**
     * Where a list stands: at x, or further left when its widest entry, selected (larger, in the medium face), would
     * end past the clock's right edge (English runs longer than Thai); never left of SIDE_MIN_X.
     */
    private float fitSide(List<String> texts, List<Integer> icons, List<Drawable> art, float x) {
        Paint p = new Paint();
        p.setTypeface(medium);
        p.setTextSize(px(30));
        float widest = 0;
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i);
            if (t == null) continue;
            float lead = sideLead(icons.get(i), art != null ? art.get(i) : null);
            widest = Math.max(widest, lead + p.measureText(t) / s * SIDE_TEXT_ZOOM);
        }
        return Math.max(SIDE_MIN_X, Math.min(x, SIDE_RIGHT - widest));
    }

    /** An entry's identity across a quiet rebuild: its text, or for an icon alone its place. */
    private static String sideKey(String text, int i) {
        return text != null ? text : "#" + i;
    }

    /** An entry going: moved above the list, where it stands, to fade out sliding by dx units. */
    private void sideAway(View e, float dx) {
        if (e.getParent() == null) return;
        android.view.ViewGroupOverlay overlay = ui.getOverlay();
        overlay.add(e);
        e.animate().alpha(0f).translationXBy(px(dx)).setStartDelay(0).setDuration(GO_MS).setInterpolator(ease)
                .withEndAction(() -> overlay.remove(e)).start();
    }

    private float sideCentre() {
        if (rows.isEmpty()) return COL_TOP + 75;
        return COL_TOP + slotH(selectedRow(), true) / 2;
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
                e.animate().y(y).alpha(alpha).translationX(px(sideX)).setStartDelay(0).setDuration(SIDE_STEP_MS)
                        .setInterpolator(ease).start();
            }
            paintSideEntry((ViewGroup) e, i == sideIdx, instant);
        }
    }

    /**
     * The selected entry in white, a little larger and glowing. The size grows by scale rather than a bigger font
     * (a font size snaps, a scale can glide): the text from its left edge, the icon from its middle.
     */
    private void paintSideEntry(ViewGroup e, boolean sel, boolean instant) {
        boolean marked = sideViews.indexOf(e) == sideMarked;
        for (int k = 0; k < e.getChildCount(); k++) {
            View c = e.getChildAt(k);
            float scale;
            if (c instanceof TextView) {
                TextView t = (TextView) c;
                t.setTypeface(sel ? medium : regular);
                t.setTextColor(marked ? (0xFF000000 | XmbAccent.floor(accentUi)) : sel ? Color.WHITE : INK_DIM);
                t.setShadowLayer(sel ? px(10) : 0, 0, 0, sel ? TEXT_GLOW : 0);
                t.setPivotX(0f);
                t.setPivotY(t.getPaint().getFontMetricsInt(null) / 2f);   // its own line's middle
                scale = sel ? SIDE_TEXT_ZOOM : 1f;
            } else if (c instanceof ImageView) {
                // An app's own icon keeps its colours, a little dimmer when not selected; a glyph is tinted.
                if (c.getTag() == SIDE_ART) c.setAlpha(sel ? 1f : SIDE_ART_DIM);
                else ((ImageView) c).setImageTintList(tint(sel ? Color.WHITE : INK_DIM));
                scale = sel ? SIDE_ICON_ZOOM : 1f;
            } else {
                continue;
            }
            if (instant || animMs == 0) {
                c.animate().cancel();
                c.setScaleX(scale);
                c.setScaleY(scale);
            } else {
                c.animate().scaleX(scale).scaleY(scale).setStartDelay(0).setDuration(SIDE_STEP_MS).setInterpolator(ease)
                        .start();
            }
        }
    }

    /** While a Settings list is open the other categories fade out, so the list stands clear of the cross-bar. */
    private void dimOtherCategories(boolean dim) {
        for (int i = 0; i < catViews.size(); i++) {
            if (i == cat) continue;
            View icon = catViews.get(i).icon;
            if (instantAnim()) icon.setAlpha(dim ? 0f : CAT_DIM);
            else icon.animate().alpha(dim ? 0f : CAT_DIM).setDuration(SIDE_STEP_MS).start();
        }
    }

    private boolean instantAnim() {
        return animMs == 0;
    }

    private boolean sideKey(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (sideHandler != null && sideHandler.upDown(-1)) return true;
                if (sideIdx > 0) {
                    sideIdx--;
                    layoutSide(false);
                    sounds.play(XmbSounds.CURSOR);
                    if (sideHandler != null) sideHandler.moved(sideIdx);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (sideHandler != null && sideHandler.upDown(1)) return true;
                if (sideIdx < sideViews.size() - 1) {
                    sideIdx++;
                    layoutSide(false);
                    sounds.play(XmbSounds.CURSOR_DOWN);
                    if (sideHandler != null) sideHandler.moved(sideIdx);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MENU:
                if (event.getRepeatCount() > 0) return true;
                sounds.play(XmbSounds.CANCEL);
                backSide();
                swallowUntilUp = keyCode;
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
        // Closing: the entries fade out where they stand, drifting back to the right.
        if (animMs > 0 && resumed) for (View e : sideViews) sideAway(e, SIDE_FROM / 2);
        side.setVisibility(View.GONE);
        side.removeAllViews();
        sideViews.clear();
        sideTexts.clear();
        sideIds.clear();
        sideHandler = null;
        sideBackAction = null;
        sideOkDown = false;
        sideMarked = -1;
        optionsFor = null;
        dimOtherCategories(false);
        fitSelectedRow();
        if (previewColor != null) {
            previewColor = null;
            accent = -1;
            updateAccent();
            if (!rows.isEmpty()) {
                Row r = selectedRow();
                setNow(r.detail, r.item.detail);
            }
            if (!quiet) reload();
        }
        if (resumed) wake(false);
    }

    private Row selectedRow() {
        return rows.get(Math.min(selectedIndex(), rows.size() - 1));
    }

    /** While a list is open right of the column, the selected row's title and detail end short of it. */
    private void fitSelectedRow() {
        if (rows.isEmpty()) return;
        Row sel = selectedRow();
        int full = px(META_W), narrow = Math.min(full, px(sideX - 56 - META_X));
        for (Row r : rows) {
            int w = r == sel && sideVisible() ? narrow : full;
            ViewGroup.LayoutParams lp = r.meta.getLayoutParams();
            if (lp.width != w) {
                lp.width = w;
                r.meta.setLayoutParams(lp);
            }
        }
    }

    private void setSelectedDetail(String text) {
        if (rows.isEmpty()) return;
        Row r = selectedRow();
        setNow(r.detail, text);
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
            if (i != sideIdx) {
                sideViews.get(i).animate().alpha(i < sideIdx ? SIDE_UNDER_PICKER : 0f).setStartDelay(0)
                        .setDuration(COLUMN_OUT_MS).start();
            }
        }
        float y = sideCentre() + SIDE_STEP / 2 + 10;
        adjViews.clear();
        TextView hueLabel = place(ui, text(light, 21, INK_FAINT), sideX, y, 400, -1);
        hueLabel.setText(R.string.xmb_color_hue);
        View hueBar = place(ui, new View(this), sideX, y + 40, BAR_W, 4);
        int[] spectrum = new int[13];
        for (int i = 0; i < spectrum.length; i++) spectrum[i] = 0xFF000000 | XmbAccent.fromHueBrightness(i / 12f, 0.8f);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, spectrum);
        g.setCornerRadius(px(2));
        hueBar.setBackground(g);
        hueMark = place(ui, new View(this), sideX, y + 27, 4, 30);
        TextView brightLabel = place(ui, text(light, 21, INK_FAINT), sideX, y + 72, 400, -1);
        brightLabel.setText(R.string.xmb_color_brightness);
        brightBar = place(ui, new View(this), sideX, y + 112, BAR_W, 4);
        brightMark = place(ui, new View(this), sideX, y + 99, 4, 30);
        for (View mark : new View[]{hueMark, brightMark}) {
            GradientDrawable m = new GradientDrawable();
            m.setColor(Color.WHITE);
            m.setCornerRadius(px(2));
            mark.setBackground(m);
            mark.setElevation(px(4));
        }
        adjHex = place(ui, text(light, 21, INK_FAINT), sideX, y + 146, 500, -1);
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
        if (animMs > 0) {
            for (int i = 0; i < adjViews.size(); i++) {
                View v = adjViews.get(i);
                v.setAlpha(0f);
                v.animate().alpha(1f).setStartDelay(SIDE_STAGGER_MS * i).setDuration(SIDE_IN_MS).setInterpolator(ease).start();
            }
        }
    }

    private void updateAdjust() {
        int rgb = XmbAccent.fromHueBrightness(adjHue, adjBright);
        previewColor = XmbAccent.hex(rgb);
        updateAccent();
        hueMark.setX(px(sideX + BAR_W * adjHue - 2));
        brightMark.setX(px(sideX + BAR_W * adjBright - 2));
        GradientDrawable b = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{
                0xFF000000 | XmbAccent.fromHueBrightness(adjHue, 0f), 0xFF000000 | XmbAccent.fromHueBrightness(adjHue, 1f)});
        b.setCornerRadius(px(2));
        brightBar.setBackground(b);
        adjHex.setText(getString(R.string.xmb_color_hint, previewColor));
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
        for (View v : adjViews) {
            if (animMs > 0 && resumed) sideAway(v, 0f);
            else ui.removeView(v);
        }
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

    // ---- status bar settings -----------------------------------------------------------------------------------

    private static final String STATUS_ORDER = "+order", STATUS_KEEP = "+keep";

    private String presetName(String preset) {
        switch (preset) {
            case XmbStatusPrefs.SIMPLE: return getString(R.string.xmb_status_simple);
            case XmbStatusPrefs.FULL: return getString(R.string.xmb_status_full);
            case XmbStatusPrefs.CLOCK: return getString(R.string.xmb_status_clock);
            case XmbStatusPrefs.CUSTOM: return getString(R.string.xmb_status_custom);
            default: return getString(R.string.xmb_status_standard);
        }
    }

    private String modeName(String mode) {
        return getString(XmbStatusPrefs.ALWAYS.equals(mode) ? R.string.xmb_status_always
                : XmbStatusPrefs.AUTO.equals(mode) ? R.string.xmb_status_auto : R.string.xmb_status_hidden);
    }

    private String itemName(String key) {
        return XmbStatusPrefs.name(this, key);
    }

    private static int itemIcon(String key) {
        switch (key) {
            case "recording": return R.drawable.ic_xmb_videocam;
            case "battery": return R.drawable.ic_xmb_battery_h4;
            case "storage": return R.drawable.ic_xmb_hard_drive;
            case "adb": return R.drawable.ic_xmb_developer_mode_tv;
            case "helper": return R.drawable.ic_xmb_settings_remote;
            case "download": return R.drawable.ic_xmb_download;
            case "usb": return R.drawable.ic_xmb_usb;
            case "audio": return R.drawable.ic_xmb_headphones;
            case "temperature": return R.drawable.ic_xmb_thermostat;
            case "weather": return R.drawable.ic_xmb_partly_cloudy_day;
            case "air": return R.drawable.ic_xmb_pm25;
            case "speed": return R.drawable.ic_xmb_speed;
            case "network": return R.drawable.ic_xmb_wifi;
            default: return R.drawable.ic_xmb_palette;
        }
    }

    private void setStatus(XmbStatusPrefs p) {
        statusPrefs = p;
        p.save(this);
        status.setPrefs(p);
    }

    /**
     * Settings > Status bar: the look (a preset), each item with its mode (OK goes round them; the row changes at
     * once), Reorder, and whether the clock stays on the wave when the menu fades.
     */
    private void openStatus(int focus) {
        List<String> texts = new ArrayList<>(), keys = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        texts.add(getString(R.string.xmb_status_preset, presetName(statusPrefs.preset)));
        icons.add(R.drawable.ic_xmb_tune);
        keys.add(null);
        for (String key : statusChoices()) {
            texts.add(getString(R.string.xmb_status_item, itemName(key), modeName(statusPrefs.mode(key))));
            icons.add(itemIcon(key));
            keys.add(key);
        }
        texts.add(getString(R.string.xmb_status_order));
        icons.add(R.drawable.ic_xmb_swap_vert);
        keys.add(STATUS_ORDER);
        texts.add(getString(statusPrefs.keepOnIdle ? R.string.xmb_status_keep_on : R.string.xmb_status_keep_off));
        icons.add(R.drawable.ic_xmb_clear_night);
        keys.add(STATUS_KEEP);
        showSide(texts, icons, focus, i -> {
            String key = keys.get(i);
            if (key == null) setStatus(statusPrefs.nextPreset());
            else if (STATUS_ORDER.equals(key)) {
                showToast(getString(R.string.xmb_status_order_hint));
                openStatusOrder(0, null);
                return;
            } else if (STATUS_KEEP.equals(key)) setStatus(statusPrefs.withKeepOnIdle(!statusPrefs.keepOnIdle));
            else setStatus(statusPrefs.nextMode(key));
            sideQuiet = true;
            openStatus(i);
        }, null);
    }

    /** The items the row can show on this TV, in order: all but the temperature where apps cannot read it. */
    private List<String> statusListed() {
        List<String> out = new ArrayList<>();
        for (String key : statusPrefs.order) {
            if (!"temperature".equals(key) || XmbStatus.temperatureReadable()) out.add(key);
        }
        return out;
    }

    /** Those with a mode to choose (a recording always shows). */
    private List<String> statusChoices() {
        List<String> out = new ArrayList<>();
        for (String key : statusListed()) if (XmbStatusPrefs.item(key).modes.size() > 1) out.add(key);
        return out;
    }

    /** Reorder: OK picks an item up (it turns the accent colour), Up and Down carry it, OK puts it down. */
    private void openStatusOrder(int focus, String carrying) {
        List<String> order = statusListed(), texts = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        for (String key : order) {
            texts.add(itemName(key));
            icons.add(itemIcon(key));
        }
        showSide(texts, icons, focus, new SideHandler() {
            @Override
            public boolean upDown(int by) {
                if (carrying == null) return false;
                int to = order.indexOf(carrying) + by;
                if (to < 0 || to >= order.size()) return true;
                // Past the next item shown, over any this TV leaves out.
                setStatus(statusPrefs.movedPast(carrying, order.get(to), by));
                sounds.play(by < 0 ? XmbSounds.CURSOR : XmbSounds.CURSOR_DOWN);
                sideQuiet = true;
                openStatusOrder(to, carrying);
                return true;
            }

            @Override
            public void picked(int i) {
                sideQuiet = true;
                openStatusOrder(i, carrying == null ? order.get(i) : null);
            }
        }, () -> openStatus(1 + statusChoices().size()));   // back on Reorder
        sideMarked = carrying != null ? order.indexOf(carrying) : -1;
        layoutSide(true);
    }

    // ---- network speed test ------------------------------------------------------------------------------------

    /** Network > Test internet speed: the item's line counts up while it measures, the result stays there. */
    private void runSpeedTest() {
        if (XmbSpeedTest.running()) return;
        setItemDetail(XmbCatalog.SPEEDTEST, getString(R.string.xmb_speedtest_start));
        XmbDebug.log(R.string.xmb_log_speed_started);
        XmbSpeedTest.start(this, (running, r) -> {
            if (running) {
                setItemDetail(XmbCatalog.SPEEDTEST, getString(R.string.xmb_speedtest_running, XmbStatus.rate(r.mbps)));
                return;
            }
            if (r != null) XmbDebug.log(R.string.xmb_log_speed_result, XmbStatus.rate(r.mbps));
            else XmbDebug.log(R.string.xmb_log_speed_failed);
            if (!resumed) return;
            showToast(r != null ? XmbCatalog.speedResult(this, r) : getString(R.string.xmb_speedtest_failed));
            reload();   // the kept result becomes the item's line (in place: the line is not part of the signature)
        });
    }

    /** The line under an item in the column on screen, if it is there. */
    private void setItemDetail(String key, String text) {
        for (Row r : rows) {
            if (!r.item.key.equals(key)) continue;
            setNow(r.detail, text);
            if (r == selectedRow()) r.detail.setVisibility(View.VISIBLE);
        }
    }

    // ---- open apps ---------------------------------------------------------------------------------------------

    /** When the open apps were last read (elapsedRealtime): moving onto the item reads them at most this often. */
    private long runningReadAt;
    private static final long RUNNING_FRESH_MS = 3000;
    /** The open apps' list, to tell it from the side list's other levels once a reading comes back. */
    private SideHandler runningSide;

    /** The packages of the apps Home shows: the ones Open apps lists. */
    private java.util.Set<String> shownApps() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (XmbCatalog.Category c : cats) for (XmbCatalog.Item i : c.items) if (i.pkg != null) out.add(i.pkg);
        return out;
    }

    private String appTitle(String pkg) {
        for (XmbCatalog.Category c : cats) for (XmbCatalog.Item i : c.items) if (pkg.equals(i.pkg)) return i.title;
        return pkg;
    }

    /** Which apps are open, read in the background (about 0.1 s): the item's line changes, then runs then. */
    private void readRunning(boolean force, Runnable then) {
        long now = SystemClock.elapsedRealtime();
        if (!force && runningReadAt > 0 && now - runningReadAt < RUNNING_FRESH_MS && XmbRunning.last() != null) {
            if (then != null) then.run();
            return;
        }
        runningReadAt = now;
        XmbRunning.refresh(this, shownApps(), s -> {
            if (isDestroyed()) return;
            setItemDetail(XmbCatalog.RUNNING, XmbRunning.line(this, s));
            if (then != null && resumed) then.run();
        });
    }

    private boolean runningShown() {
        return runningSide != null && sideVisible() && sideHandler == runningSide;
    }

    /**
     * Settings > Open apps: Close all apps, then every app left running with the memory it holds, the most first;
     * OK closes. An app Android keeps (playing music in the background) says it is in use.
     */
    private void showRunning(int focus) {
        XmbRunning.Snapshot s = XmbRunning.last();
        boolean showing = runningShown();
        if (s == null || s.apps == null || s.apps.isEmpty()) {
            if (showing) closeSide(false);
            showToast(getString(s == null || s.apps == null ? R.string.xmb_running_no_access_toast : R.string.xmb_running_none));
            return;
        }
        List<String> texts = new ArrayList<>(), ids = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        List<Drawable> art = new ArrayList<>();
        texts.add(getString(R.string.xmb_running_close_all));
        icons.add(R.drawable.ic_xmb_cleaning_services);
        art.add(null);
        ids.add("#all");
        for (XmbRunning.App a : s.apps) {
            ids.add(a.pkg);   // its memory changes with every reading: the entry is the app
            // Its memory once Android has measured it (a process just started has no figure yet).
            String text = appTitle(a.pkg);
            if (a.kb > 0) text = XmbCatalog.join(this, text, XmbRunning.size(a.kb));
            if (!a.closable()) text = XmbCatalog.join(this, text, getString(R.string.xmb_running_in_use));
            texts.add(text);
            icons.add(R.drawable.ic_xmb_apps);
            art.add(a.icon);
        }
        List<XmbRunning.App> apps = s.apps;
        runningSide = i -> closeApps(i == 0 ? apps : Collections.singletonList(apps.get(i - 1)), i);
        sideQuiet = showing;   // after closing: the entries left slide into place
        sideNextIds = ids;
        // Nearer the column than Settings' lists: an app's name and its memory take more room than a setting.
        showSide(texts, icons, art, focus, runningSide, null, MENU_X, true);
    }

    /** Apps XmbCloser closed in Settings, and the entry picked: once Home is back, what came of it and what is left. */
    private List<XmbRunning.App> closed;
    private int closedAt;

    /**
     * Closes the apps (Close all: every one listed): Force stop in the TV's Settings, pressed by the remote-button
     * helper (XmbCloser) under a black screen, then Home again, saying what came of it and showing what is left.
     */
    private void closeApps(List<XmbRunning.App> which, int at) {
        List<String> pkgs = new ArrayList<>(), titles = new ArrayList<>();
        for (XmbRunning.App a : which) {
            pkgs.add(a.pkg);
            titles.add(appTitle(a.pkg));
        }
        XmbDebug.log(R.string.xmb_log_closing, TextUtils.join(", ", pkgs));
        // The screen as it is, behind the closing message: no black flash, and Home comes back to the same picture.
        snapshot(shot -> {
            if (!resumed) return;
            closingApps = XmbCloser.close(this, which, titles, shot, (stopped, cancelled) -> {
                closed = which;
                closedAt = at;
                if (resumed) {   // Settings never came up: Home was not left
                    closingApps = false;
                    XmbCloser.homeBack();
                    afterClosing();
                }
            });
            if (closingApps) return;
            // Without the helper, Android's own way, which some TVs still allow an app (not the Mi Box S: only its own).
            runningReadAt = SystemClock.elapsedRealtime();
            XmbRunning.close(this, pkgs, shownApps(), after -> report(which, after, at, false));
        });
    }

    /**
     * Apps being closed in Settings under XmbCloser's cover: Home keeps its place, side list and music meanwhile, and
     * comes back without the return animation (it moved the screen after every app closed).
     */
    private boolean closingApps;

    /** What is on screen, the wave under the menu, at half size; null when it cannot be copied. */
    private void snapshot(java.util.function.Consumer<Bitmap> then) {
        int w = 960, h = 540;
        Bitmap menu = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Bitmap[] wave = {null};
        java.util.function.IntConsumer menuDone = result -> {
            Bitmap out = wave[0];
            if (result == android.view.PixelCopy.SUCCESS) {
                if (out == null) {
                    out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    out.eraseColor(Color.BLACK);
                }
                new Canvas(out).drawBitmap(menu, 0f, 0f, null);
            }
            menu.recycle();
            then.accept(out);
        };
        try {
            if (waveView == null) {
                android.view.PixelCopy.request(getWindow(), menu, menuDone::accept, later);
                return;
            }
            Bitmap back = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            android.view.PixelCopy.request(waveView, back, result -> {
                if (result == android.view.PixelCopy.SUCCESS) wave[0] = back;
                else back.recycle();
                try {
                    android.view.PixelCopy.request(getWindow(), menu, menuDone::accept, later);
                } catch (RuntimeException e) {
                    menuDone.accept(android.view.PixelCopy.ERROR_UNKNOWN);
                }
            }, later);
        } catch (RuntimeException e) {
            menu.recycle();
            then.accept(null);
        }
    }

    private void afterClosing() {
        List<XmbRunning.App> which = closed;
        closed = null;
        if (which == null) return;
        int at = closedAt;
        runningReadAt = SystemClock.elapsedRealtime();
        XmbRunning.refresh(this, shownApps(), after -> report(which, after, at, true));
    }

    /** Says what closing came to; the list again without what went (back on it after Settings: reopen). */
    private void report(List<XmbRunning.App> which, XmbRunning.Snapshot after, int at, boolean reopen) {
        if (isDestroyed()) return;
        setItemDetail(XmbCatalog.RUNNING, XmbRunning.line(this, after));
        if (!resumed) return;
        int gone = 0;
        long freed = 0;
        for (XmbRunning.App a : which) {
            if (after.find(a.pkg) == null) {
                gone++;
                freed += a.kb;
            }
        }
        // The memory back only when Android had measured it (a process just started has no figure yet).
        if (which.size() == 1) {
            String title = appTitle(which.get(0).pkg);
            showToast(gone == 0 ? getString(R.string.xmb_running_failed, title) : freed > 0
                    ? getString(R.string.xmb_running_closed, title, XmbRunning.size(freed))
                    : getString(R.string.xmb_running_closed_plain, title));
        } else {
            showToast(gone == 0 ? getString(R.string.xmb_running_failed_all) : freed > 0
                    ? getResources().getQuantityString(R.plurals.xmb_running_closed_all, gone, gone, XmbRunning.size(freed))
                    : getResources().getQuantityString(R.plurals.xmb_running_closed_all_plain, gone, gone));
        }
        boolean left = after.apps != null && !after.apps.isEmpty();
        if (runningShown()) {
            // Still open (kept while Settings closed the app): the entries left close up; none left, it goes.
            if (left) showRunning(at);
            else closeSide(false);
        } else if (reopen && left && !sideVisible() && !rows.isEmpty()
                && XmbCatalog.RUNNING.equals(selectedRow().item.key)) {
            showRunning(at);
        }
    }

    // ---- diagnostics -------------------------------------------------------------------------------------------

    private static final int REQ_RECORD = 41;
    private static final String PREF_DEBUG_PANEL = "xmb_debug_panel";
    private int recordSeconds = 30;
    private TextView debugPanel;
    private int lastWaveFrames;

    /** Settings > Diagnostics: record the screen, stop a recording, the event panel on or off. */
    private void openDiagnostics(int focus) {
        List<String> texts = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (XmbRecorder.recording) {
            texts.add(getString(R.string.xmb_diag_stop));
            icons.add(R.drawable.ic_xmb_videocam);
            actions.add(() -> {
                XmbRecorder.stop(this);
                closeSide(true);
            });
        } else {
            for (int seconds : new int[]{30, 120}) {
                texts.add(getString(seconds == 30 ? R.string.xmb_diag_record30 : R.string.xmb_diag_record120));
                icons.add(R.drawable.ic_xmb_videocam);
                actions.add(() -> askToRecord(seconds));
            }
        }
        if (wave != null && !reducedMotion) {
            // The wave at 30 or 60 frames a second, to compare: 60 is smoother and twice the GPU's work.
            texts.add(getString(wave60 ? R.string.xmb_diag_wave60 : R.string.xmb_diag_wave30));
            icons.add(R.drawable.ic_xmb_speed);
            int self60 = texts.size();
            actions.add(() -> {
                wave60 = !wave60;
                prefs().edit().putBoolean(PREF_WAVE60, wave60).apply();
                XmbDebug.log(R.string.xmb_log_wave_fps, wave60 ? 60 : 30);
                sideQuiet = true;
                openDiagnostics(self60 - 1);
            });
        }
        boolean panel = debugPanel.getVisibility() == View.VISIBLE;
        texts.add(getString(panel ? R.string.xmb_diag_panel_on : R.string.xmb_diag_panel_off));
        icons.add(R.drawable.ic_xmb_bug_report);
        int self = texts.size() - 1;
        actions.add(() -> {
            setDebugPanel(!panel);
            prefs().edit().putBoolean(PREF_DEBUG_PANEL, !panel).apply();
            sideQuiet = true;
            openDiagnostics(self);
        });
        if (wave != null && animMs > 0) {
            // The boot animation now, to see it without turning the TV off and on.
            texts.add(getString(R.string.xmb_diag_boot));
            icons.add(R.drawable.ic_xmb_power_settings_new);
            actions.add(() -> {
                closeSide(true);
                music.pause();
                startBootAnimation();
                music.resume(7500);
            });
        }
        showSide(texts, icons, focus, i -> actions.get(i).run(), null);
    }

    private void askToRecord(int seconds) {
        closeSide(true);
        recordSeconds = seconds;
        try {
            startActivityForResult(XmbRecorder.permissionIntent(this), REQ_RECORD);
        } catch (RuntimeException e) {
            showToast(getString(R.string.xmb_diag_record_failed));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_RECORD) return;
        if (resultCode == RESULT_OK && data != null) XmbRecorder.start(this, resultCode, data, recordSeconds);
        else XmbDebug.log(R.string.xmb_log_recording_denied);
    }

    /** The event panel: the wave's frame rate and the newest lines of XmbDebug, bottom left over everything. */
    private void setDebugPanel(boolean on) {
        debugPanel.setVisibility(on ? View.VISIBLE : View.GONE);
        XmbDebug.listener = on ? this::paintDebugPanel : null;
        if (wave != null) wave.measuring = on;
        handler.removeCallbacks(debugTick);
        if (on) {
            lastWaveFrames = wave != null ? wave.framesDrawn : 0;
            handler.postDelayed(debugTick, 1000);
            paintDebugPanel();
        }
    }

    private int waveFps;
    private final Runnable debugTick = new Runnable() {
        @Override
        public void run() {
            if (wave != null) {
                waveFps = wave.framesDrawn - lastWaveFrames;
                lastWaveFrames = wave.framesDrawn;
            }
            paintDebugPanel();
            if (resumed && debugPanel.getVisibility() == View.VISIBLE) handler.postDelayed(this, 1000);
        }
    };

    private void paintDebugPanel() {
        if (debugPanel.getVisibility() != View.VISIBLE) return;
        Runtime rt = Runtime.getRuntime();
        StringBuilder sb = new StringBuilder();
        if (wave != null) {
            sb.append(wave.gpuMs >= 0 ? getString(R.string.xmb_panel_wave_gpu, waveFps, wave.cpuMs, wave.splineMs, wave.gpuMs)
                    : getString(R.string.xmb_panel_wave, waveFps, wave.cpuMs, wave.splineMs)).append('\n');
        }
        sb.append(getString(R.string.xmb_panel_memory, (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20,
                getString(idle ? R.string.xmb_panel_idle : R.string.xmb_panel_awake)));
        for (String line : XmbDebug.recent(8)) sb.append('\n').append(line);
        debugPanel.setText(sb);
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
            prompt.setBackgroundColor(PROMPT_BG);
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
            promptEdit.setBackgroundTintList(tint(withAlpha(accentUi, 0xFF)));
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
            prompt.setVisibility(View.GONE);   // shown below, fading in
        }
        promptDone = done;
        promptBack = back;
        promptTitle.setText(title);
        promptEdit.setText(initial);
        promptEdit.setSelection(promptEdit.getText().length());
        promptOpen = true;
        prompt.animate().cancel();
        if (prompt.getVisibility() != View.VISIBLE) prompt.setAlpha(0f);
        prompt.setVisibility(View.VISIBLE);
        prompt.animate().alpha(1f).setStartDelay(0).setDuration(animMs > 0 ? COME_MS / 2 : 0).setInterpolator(ease).start();
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
        if (!promptOpen) return;
        promptOpen = false;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(promptEdit.getWindowToken(), 0);
        promptEdit.clearFocus();
        if (animMs > 0 && resumed) {
            prompt.animate().alpha(0f).setStartDelay(0).setDuration(GO_MS).setInterpolator(ease)
                    .withEndAction(() -> {
                        if (!promptOpen) prompt.setVisibility(View.GONE);
                    }).start();
        } else {
            prompt.setVisibility(View.GONE);
        }
        promptDone = null;
        promptBack = null;
        if (resumed) wake(false);
    }
}
