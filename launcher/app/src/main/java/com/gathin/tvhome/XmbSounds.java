package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;

/**
 * The XMB's menu sounds, played from files the owner put on the TV (Config.dir()/sfx/: cursor.wav,
 * cursor-down.wav, confirm.wav, cancel.wav, opening.wav, or .ogg). None ship with the launcher. With no files,
 * or with the sounds switched off in the XMB's Settings category, every cue is silent.
 * Levels follow KittiwanaNotify's sfx.js: the cursor tic fires often, so it stays quiet.
 */
final class XmbSounds {
    static final String DIR = "sfx";
    static final String PREF = "xmb_sounds";
    static final String CURSOR = "cursor", CURSOR_DOWN = "cursor-down", CONFIRM = "confirm", CANCEL = "cancel",
            OPENING = "opening";
    private static final String[] NAMES = {CURSOR, CURSOR_DOWN, CONFIRM, CANCEL, OPENING};
    private static final float[] GAIN = {0.55f, 0.55f, 0.8f, 0.7f, 0.7f};

    private final SoundPool pool;
    private final int[] ids = new int[NAMES.length];
    private final boolean any;
    private boolean on;

    XmbSounds(Context ctx) {
        pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        File dir = Config.dir(ctx);
        boolean found = false;
        for (int i = 0; i < NAMES.length; i++) {
            File f = file(dir, NAMES[i]);
            ids[i] = f != null ? pool.load(f.getAbsolutePath(), 1) : 0;
            found |= ids[i] != 0;
        }
        any = found;
        on = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getBoolean(PREF, true);
    }

    private static File file(File dir, String name) {
        if (dir == null) return null;
        for (String ext : new String[]{".wav", ".ogg"}) {
            File f = new File(new File(dir, DIR), name + ext);
            if (f.isFile() && f.length() > 0) return f;
        }
        return null;
    }

    /** True when at least one sound file was found. */
    boolean available() {
        return any;
    }

    boolean isOn() {
        return on;
    }

    void setOn(Context ctx, boolean value) {
        on = value;
        ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF, value).apply();
    }

    void play(String name) {
        if (!on || !any) return;
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name) && ids[i] != 0) {
                pool.play(ids[i], GAIN[i], GAIN[i], 1, 0, 1f);
                return;
            }
        }
    }

    void release() {
        pool.release();
    }
}
