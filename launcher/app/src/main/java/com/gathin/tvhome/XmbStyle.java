package com.gathin.tvhome;

/**
 * The XMB's look in one place: how long its movements take, how bright what is not selected stays, and the colours
 * of its few panels and glows. Home and the status row draw with these; a change here changes it everywhere. Where
 * things sit, in 1920x1080 units, is with the layout at the top of XmbActivity; what the owner may set (the idle
 * time, the warnings, the apps the launcher knows) is in config.xml (XmbConfig).
 */
final class XmbStyle {
    private XmbStyle() {
    }

    // ---- movement, in milliseconds ---------------------------------------------------------------------------

    /** A move of the cursor or of the categories, with KittiwanaNotify's easing (xmb.js). */
    static final long MOVE_MS = 240;
    /** Changing category: the old column fades out, the new one fades in from COLUMN_FROM units to the left. */
    static final long COLUMN_OUT_MS = 120, COLUMN_IN_MS = 200;
    static final float COLUMN_FROM = -16;
    /** The side list: a step up or down, the entries coming in from SIDE_FROM units to the right, one after another. */
    static final long SIDE_STEP_MS = 160, SIDE_IN_MS = 200, SIDE_STAGGER_MS = 22;
    static final float SIDE_FROM = 36;
    /** At the end of a row or a column the cross-bar gives this many units and springs back. */
    static final long NUDGE_OUT_MS = 80, NUDGE_BACK_MS = 180;
    static final float NUDGE_ROW = 14, NUDGE_COLUMN = 10;
    /** Opening an app: the tile steps forward and its glow widens, the cross-bar rises and fades, then the app starts. */
    static final long LAUNCH_ZOOM_MS = 180, LAUNCH_FADE_MS = 200, LAUNCH_ROW_MS = 220, LAUNCH_COLUMN_DELAY_MS = 60,
            LAUNCH_START_MS = 200;
    static final float LAUNCH_ZOOM = 1.1f, LAUNCH_GLOW = 1.15f, LAUNCH_RISE = 20;
    /** Back from an app: Home fades in from a little larger, the other categories closing in from RETURN_SPREAD. */
    static final long RETURN_MS = 420, RETURN_ROW_MS = 480;
    static final float RETURN_ZOOM = 1.04f, RETURN_SPREAD = 40;
    /**
     * The intro: the wave out of black, the categories in from INTRO_ROW_FROM units to the right one after another
     * outwards from the selected one, the column up from INTRO_COLUMN_FROM, the clock last. When Home only restarted,
     * all of it at INTRO_SHORT of these.
     */
    static final long INTRO_VEIL_MS = 1600, INTRO_ROW_DELAY_MS = 300, INTRO_ROW_STEP_MS = 70, INTRO_ROW_MS = 520,
            INTRO_COLUMN_DELAY_MS = 650, INTRO_COLUMN_MS = 600, INTRO_TOP_DELAY_MS = 1000, INTRO_TOP_MS = 700;
    static final float INTRO_SHORT = 0.6f, INTRO_ROW_FROM = 90, INTRO_COLUMN_FROM = 40;
    /** The intro: the categories grow from this size as they come in; the column's rows come one after another, this
     *  much apart, outwards from the selected one; the clock and the status row settle from this far above. */
    static final float INTRO_CAT_SCALE = 0.9f, INTRO_TOP_FROM = 14;
    static final long INTRO_CASCADE_MS = 45;

    /**
     * The boot animation, in milliseconds from its first frame (XmbWave draws it, at 60 frames a second while the
     * lines travel, XmbBoot times it): black; the wave's lines drawn in white from the left edge to the right from BOOT_LINES_AT_MS for
     * BOOT_LINES_MS, the start-up sound with them; the colour spreading out from the middle band from
     * BOOT_COLOUR_AT_MS for BOOT_COLOUR_MS, starting while the last lines arrive; the menu coming in from
     * BOOT_MENU_AT_MS, over the colour still spreading, its intro at BOOT_INTRO of the full one's length.
     */
    static final long BOOT_LINES_AT_MS = 300, BOOT_LINES_MS = 2800, BOOT_COLOUR_AT_MS = 2600, BOOT_COLOUR_MS = 2100,
            BOOT_MENU_AT_MS = 3800;
    static final float BOOT_INTRO = 0.8f;
    /** The menu comes anyway this long after the boot animation was asked for, should the wave not draw it. */
    static final long BOOT_TIMEOUT_MS = 9000;
    /** The TV in standby at least this long counts as being turned on again (xmb_boot_animation "power"). */
    static final long WAKE_BOOT_AFTER_MS = 30_000;
    /** An intro or return waits for the window to finish coming in, at most this long. */
    static final long ANIM_WAIT_MS = 900;
    /** Idle: the menu fades out to the wave, and comes back with the first key. */
    static final long IDLE_FADE_MS = 1200, WAKE_FADE_MS = 2400;
    /**
     * Things that come, change and go, in the status row, the cross-bar and the side list: something new comes in
     * (COME_MS), something that goes fades out where it stood (GO_MS), what stays slides to its new place
     * (SHIFT_MS), and what only says something else cross-fades (out in CHANGE_OUT_MS, back in CHANGE_IN_MS).
     */
    static final long COME_MS = 420, GO_MS = 240, SHIFT_MS = 380, CHANGE_OUT_MS = 110, CHANGE_IN_MS = 220;
    /** The wave's colour eases to a new one (seconds, about a third of the time it takes): a picked one, the others. */
    static final float COLOR_PICK_TAU = 0.06f, COLOR_DRIFT_TAU = 0.7f;
    /** A message at the foot of the screen fades in, stays, fades out. */
    static final long TOAST_FADE_MS = 200, TOAST_MS = 2200;
    /** The clock's colon: lit, then dim, each this long. */
    static final long BLINK_MS = 500;
    /** Package changes come in bursts (Play Store updating apps): one reload once they have settled this long. */
    static final long RELOAD_SETTLE_MS = 1500;

    // ---- light ------------------------------------------------------------------------------------------------

    /** Text: titles and the clock, details, and what is not selected. */
    static final int INK = 0xF0FFFFFF, INK_DIM = 0xADFFFFFF, INK_FAINT = 0x85FFFFFF;
    /** A category not selected (the selected one is full white). */
    static final float CAT_DIM = 0.38f;
    /** Items already passed (above the bar) and still to come (below it). */
    static final float ROW_ABOVE = 0.4f, ROW_BELOW = 0.58f;
    /** An item not selected: its tile or glyph and its title, at this size. */
    static final float ROW_THUMB = 0.68f, ROW_TITLE = 0.82f;
    /** The clock and the status row on the wave while the menu has faded out. */
    static final float TOP_ON_WAVE = 0.75f;
    /** The clock's colon at its dim step. */
    static final float COLON_DIM = 0.25f;
    /** Side-list entries by their distance from the selected one; further ones are hidden. */
    static final float[] SIDE_ALPHA = {1f, 0.62f, 0.44f, 0.28f, 0.14f};
    /** The entries above the custom colour while its pickers are open (those below give way). */
    static final float SIDE_UNDER_PICKER = 0.2f;
    /** The glow round the selected side-list entry's text. */
    static final int TEXT_GLOW = 0x99FFFFFF;

    // ---- panels -----------------------------------------------------------------------------------------------

    /** The message pill, the name prompt over Home, the event panel and its text. */
    static final int TOAST_BG = 0xC7080B14, PROMPT_BG = 0xD9050710, PANEL_BG = 0xB3000000, PANEL_INK = 0xE6FFFFFF;
    /** The quiet tile behind an app that has only an icon, and the ring round a selected tile. */
    static final int ICON_TILE = 0x33FFFFFF, TILE_RING = 0xD9FFFFFF;

    // ---- the status row's glowing icons: the icon's colour, a tight glow and a wide glow -------------------------

    static final int[] GLOW_WHITE = {0xFFFFFFFF, 0x8CFFFFFF, 0x47FFFFFF};
    /** Something needs a hand. */
    static final int[] GLOW_AMBER = {0xFFFFE3B0, 0xA6FFD68C, 0xBFF0A028};
    /** The screen is being recorded. */
    static final int[] GLOW_RED = {0xFFFFB4B4, 0xA6FF8080, 0xD9E0302A};
    /** No connection. */
    static final int[] GLOW_FAINT = {0x73FFFFFF, 0x26FFFFFF, 0x14FFFFFF};
    /** A white icon whose wide glow is a colour of its own (the theme colour, the dust's band): tight glow, alpha. */
    static final int COLOR_GLOW_INNER = 0x73FFFFFF, COLOR_GLOW_ALPHA = 0xF2;
    /** A figure beside an icon, and its glow. */
    static final int FIGURE_INK = 0xE6FFFFFF, FIGURE_GLOW = 0x66FFFFFF;
}
