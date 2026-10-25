package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.util.Log;

import java.io.File;

/**
 * Background music for the XMB, the way the PlayStation's home plays a quiet loop: one file the owner put on the
 * TV (Config.dir()/sfx/bgm.flac, .ogg, .mp3, .m4a or .wav), looped without a gap (two players handing over with
 * setNextMediaPlayer), faded in when Home comes to the front and out when anything else does. None ships with the
 * launcher; with no file the setting says so and nothing plays.
 *
 * It never plays over other sound: it does not start while another app plays music (a phone casting to the TV,
 * music left playing in the background), and it stops when anything else takes the audio focus.
 */
final class XmbMusic {
    private static final String TAG = "TvHome.Music";
    static final String PREF = "xmb_bgm";
    static final String NAME = "bgm";
    /** Off, low, medium, high: the player's volume, under the TV's own. */
    static final float[] LEVELS = {0f, 0.2f, 0.36f, 0.6f};
    static final int DEFAULT_LEVEL = 2;
    private static final String[] TYPES = {".flac", ".ogg", ".mp3", ".m4a", ".wav"};
    private static final long FADE_IN_MS = 2500, FADE_OUT_MS = 600, STEP_MS = 50;

    private final Context ctx;
    private final Handler handler;
    private final AudioManager audio;
    private final File file;
    private MediaPlayer current, next;
    private int level;
    private float volume, target;
    /** Home is in front and wants the music; the player follows with a fade. */
    private boolean wanted;
    private AudioFocusRequest focus;
    /** Another app took the sound for good (not just for a moment): give the focus back once silent. */
    private boolean lostForGood;

    XmbMusic(Context ctx, Handler handler) {
        this.ctx = ctx.getApplicationContext();
        this.handler = handler;
        audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        file = find(Config.dir(ctx));
        level = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getInt(PREF, DEFAULT_LEVEL);
        if (level < 0 || level >= LEVELS.length) level = DEFAULT_LEVEL;
    }

    private static File find(File dir) {
        if (dir == null) return null;
        for (String type : TYPES) {
            File f = new File(new File(dir, XmbSounds.DIR), NAME + type);
            if (f.isFile() && f.length() > 0) return f;
        }
        return null;
    }

    boolean available() {
        return file != null;
    }

    int level() {
        return level;
    }

    /** The next level round (off, low, medium, high), saved. */
    void cycle() {
        level = (level + 1) % LEVELS.length;
        ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit().putInt(PREF, level).apply();
        if (level == 0) {
            fadeTo(0f);
        } else if (wanted) {
            start();
        }
    }

    /** Home is in front: start after the delay (longer when the start-up jingle is playing). */
    void resume(long delayMs) {
        wanted = true;
        handler.removeCallbacks(startRunnable);
        handler.postDelayed(startRunnable, delayMs);
    }

    /** Something else is in front, or the screensaver started: fade out and pause where it was. */
    void pause() {
        wanted = false;
        handler.removeCallbacks(startRunnable);
        fadeTo(0f);
    }

    void release() {
        wanted = false;
        handler.removeCallbacks(startRunnable);
        handler.removeCallbacks(fade);
        abandonFocus();
        if (current != null) current.release();
        if (next != null) next.release();
        current = next = null;
    }

    private final Runnable startRunnable = this::start;

    private void start() {
        if (!wanted || level == 0 || file == null) return;
        if (current == null || !current.isPlaying()) {
            // Someone else's music (a cast, an app playing in the background): leave the room to it.
            if (audio != null && audio.isMusicActive()) return;
            if (!requestFocus()) return;
            lostForGood = false;
            if (current == null) {
                current = player(false);
                if (current == null) return;
                next = player(true);
            }
            setVolume(volume);
            current.start();
        }
        fadeTo(LEVELS[level]);
    }

    /**
     * A player for the file. The next one in the loop is prepared off the main thread (prepareAsync) and handed to
     * the current one when ready: preparing it at the loop's end on the main thread made the menu and wave hitch.
     */
    private MediaPlayer player(boolean async) {
        try {
            MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(attributes());
            p.setDataSource(file.getAbsolutePath());
            p.setOnCompletionListener(this::handOver);
            if (async) {
                p.setOnPreparedListener(mp -> {
                    mp.setVolume(volume, volume);
                    if (mp == next && current != null) current.setNextMediaPlayer(mp);
                });
                p.prepareAsync();
            } else {
                p.prepare();
                p.setVolume(volume, volume);
            }
            return p;
        } catch (Exception e) {
            Log.w(TAG, "cannot play " + file + ": " + e);
            return null;
        }
    }

    /** The finished player hands over to the next one, which Android already started without a gap. */
    private void handOver(MediaPlayer done) {
        if (done != current) return;
        done.release();
        current = next;
        next = player(true);
    }

    private static AudioAttributes attributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    }

    /** Home is hidden and memory is wanted: let the players go (the loop starts again from its top next time). */
    void releasePlayers() {
        if (wanted) return;
        handler.removeCallbacks(fade);
        abandonFocus();
        if (current != null) current.release();
        if (next != null) next.release();
        current = next = null;
        volume = 0f;
    }

    private void fadeTo(float to) {
        target = to;
        handler.removeCallbacks(fade);
        handler.post(fade);
    }

    private final Runnable fade = new Runnable() {
        @Override
        public void run() {
            float step = LEVELS[LEVELS.length - 1] * STEP_MS / (target > volume ? FADE_IN_MS : FADE_OUT_MS);
            volume = target > volume ? Math.min(target, volume + step) : Math.max(target, volume - step);
            setVolume(volume);
            if (volume != target) {
                handler.postDelayed(this, STEP_MS);
            } else if (volume == 0f && current != null && current.isPlaying()) {
                current.pause();
                // A moment's interruption keeps the request, so Android hands the sound back (AUDIOFOCUS_GAIN).
                if (!wanted || lostForGood) abandonFocus();
            }
        }
    };

    private void setVolume(float v) {
        if (current != null) current.setVolume(v, v);
        if (next != null) next.setVolume(v, v);
    }

    private boolean requestFocus() {
        if (audio == null) return true;
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes())
                    .setOnAudioFocusChangeListener(change -> {
                        // Anything else that wants to sound (the assistant, a cast, an alert) has the room.
                        if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                            fadeTo(LEVELS[level] * 0.3f);
                        } else if (change < 0) {
                            lostForGood = change == AudioManager.AUDIOFOCUS_LOSS;
                            handler.removeCallbacks(startRunnable);
                            fadeTo(0f);
                        } else if (change == AudioManager.AUDIOFOCUS_GAIN && wanted) {
                            start();
                        }
                    }, handler)
                    .build();
        }
        return audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (audio != null && focus != null) audio.abandonAudioFocusRequest(focus);
    }
}
