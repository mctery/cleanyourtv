package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;

/**
 * The XMB's menu sounds, played from files the owner put on the TV (Config.dir()/sfx/: cursor.wav,
 * cursor-down.wav, confirm.wav, cancel.wav, opening.wav, or .ogg). None ship with the launcher. With no files,
 * or with the sounds switched off in the XMB's Settings category, every cue is silent.
 *
 * A quiet 16-bit WAV is brought up to full level first ({@link XmbWav}, into the cache): sounds made for a
 * desktop browser peak 20 to 30 dB lower, and on a TV, next to programmes mastered near full scale, they were
 * hard to hear at all. All cues then play at full volume except the start-up jingle.
 */
final class XmbSounds {
    static final String DIR = "sfx";
    static final String PREF = "xmb_sounds";
    static final String CURSOR = "cursor", CURSOR_DOWN = "cursor-down", CONFIRM = "confirm", CANCEL = "cancel",
            OPENING = "opening";
    private static final String[] NAMES = {CURSOR, CURSOR_DOWN, CONFIRM, CANCEL, OPENING};
    private static final float[] GAIN = {1f, 1f, 1f, 1f, 0.85f};

    private final SoundPool pool;
    private final int[] ids = new int[NAMES.length];
    private final boolean[] loaded = new boolean[NAMES.length];
    private final boolean any;
    private boolean on;
    /** A cue asked for before SoundPool finished loading it (the start-up jingle, right after Home starts). */
    private int pending = -1;

    XmbSounds(Context ctx) {
        pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        pool.setOnLoadCompleteListener((p, sampleId, status) -> {
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] != sampleId) continue;
                loaded[i] = status == 0;
                if (loaded[i] && pending == i) {
                    pending = -1;
                    if (on) pool.play(ids[i], GAIN[i], GAIN[i], 1, 0, 1f);
                }
            }
        });
        File dir = Config.dir(ctx);
        boolean found = false;
        for (int i = 0; i < NAMES.length; i++) {
            File f = loud(ctx, file(dir, NAMES[i]));
            ids[i] = f != null ? pool.load(f.getAbsolutePath(), 1) : 0;
            found |= ids[i] != 0;
        }
        any = found;
        on = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getBoolean(PREF, true);
    }

    /**
     * A full-level copy of a quiet WAV in the cache, made fresh each time the XMB starts (a few milliseconds for
     * these sizes), or the file itself when it is not a 16-bit WAV, is already loud, or the copy cannot be written.
     */
    private static File loud(Context ctx, File f) {
        if (f == null || !f.getName().endsWith(".wav")) return f;
        try {
            byte[] louder = XmbWav.normalize(java.nio.file.Files.readAllBytes(f.toPath()));
            if (louder == null) return f;
            File dir = new File(ctx.getCacheDir(), DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) return f;
            File out = new File(dir, f.getName());
            java.nio.file.Files.write(out.toPath(), louder);
            return out;
        } catch (java.io.IOException | RuntimeException e) {
            return f;
        }
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
                // Only the jingle waits for its load: a late cursor tic would sound after the move it belongs to.
                if (!loaded[i]) {
                    if (OPENING.equals(name)) pending = i;
                    return;
                }
                pool.play(ids[i], GAIN[i], GAIN[i], 1, 0, 1f);
                return;
            }
        }
    }

    void release() {
        pool.release();
    }
}
