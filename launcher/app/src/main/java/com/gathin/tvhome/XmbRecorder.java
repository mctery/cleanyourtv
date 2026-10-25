package com.gathin.tvhome;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Records the screen for a set time, for chasing display problems (Settings > Diagnostics): the video goes to
 * Movies/XMB on the TV's storage, where adb can pull it (adb pull /sdcard/Movies/XMB). Android asks the owner once per
 * recording ("start recording?") and shows a notification while it runs; the status row shows a red camera.
 *
 * A foreground service of type mediaProjection, as Android 10 and later require for screen capture. 1280x720 at
 * 30 frames a second, about 6 Mbit/s, no sound.
 */
public class XmbRecorder extends Service {
    private static final String TAG = "TvHome.Recorder";
    static final String EXTRA_CODE = "code", EXTRA_DATA = "data", EXTRA_SECONDS = "seconds";
    private static final String CHANNEL = "xmb_recording";
    private static final int W = 1280, H = 720;

    /** A recording is running (the status row shows it). */
    static volatile boolean recording;
    /** Told when a recording starts or ends, with the file's place or an error; on the main thread. */
    interface Listener {
        void recorderChanged(boolean running, String message);
    }

    static Listener listener;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaRecorder recorder;
    private ParcelFileDescriptor out;
    private Uri video;

    /** The intent that asks the owner for permission; its result goes to {@link #start}. */
    static Intent permissionIntent(Context ctx) {
        return ctx.getSystemService(MediaProjectionManager.class).createScreenCaptureIntent();
    }

    static void start(Context ctx, int resultCode, Intent data, int seconds) {
        Intent i = new Intent(ctx, XmbRecorder.class).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_SECONDS, seconds);
        ctx.startForegroundService(i);
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, XmbRecorder.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || recording) return START_NOT_STICKY;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.xmb_diag_recording),
                    NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_xmb_videocam).setContentTitle(getString(R.string.xmb_diag_recording))
                .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
        int seconds = Math.max(5, Math.min(600, intent.getIntExtra(EXTRA_SECONDS, 30)));
        try {
            begin(intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED), intent.getParcelableExtra(EXTRA_DATA));
            recording = true;
            tell(true, null);
            main.postDelayed(this::stopSelf, seconds * 1000L);
        } catch (Exception e) {
            Log.w(TAG, "could not record: " + e);
            cleanUp(false);
            tell(false, getString(R.string.xmb_diag_record_failed));
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void begin(int code, Intent data) throws Exception {
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) throw new IllegalStateException("no projection");
        // Android 14 wants a callback before any capture starts; it also tells us when the owner stops it.
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                main.post(XmbRecorder.this::stopSelf);
            }
        }, main);

        String name = "xmb-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".mp4";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/XMB");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
        }
        ContentResolver cr = getContentResolver();
        video = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        if (video == null) throw new IllegalStateException("no place for the video");
        out = cr.openFileDescriptor(video, "w");

        recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? new MediaRecorder(this) : new MediaRecorder();
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(W, H);
        recorder.setVideoFrameRate(30);
        recorder.setVideoEncodingBitRate(6_000_000);
        recorder.setOutputFile(out.getFileDescriptor());
        recorder.prepare();

        DisplayMetrics dm = getResources().getDisplayMetrics();
        display = projection.createVirtualDisplay("xmb-recording", W, H, dm.densityDpi * W / Math.max(1, dm.widthPixels),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.getSurface(), null, main);
        recorder.start();
        Log.i(TAG, "recording to Movies/XMB/" + name);
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        boolean was = recording;
        boolean saved = cleanUp(true);
        recording = false;
        if (was) tell(false, saved ? getString(R.string.xmb_diag_record_saved) : getString(R.string.xmb_diag_record_failed));
        super.onDestroy();
    }

    /** Stops and releases everything; true when a video was finished and kept. */
    private boolean cleanUp(boolean keep) {
        boolean ok = false;
        if (recorder != null) {
            try {
                recorder.stop();
                ok = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "stop failed: " + e);   // nothing was recorded yet
            }
            recorder.release();
            recorder = null;
        }
        if (display != null) {
            display.release();
            display = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        if (out != null) {
            try {
                out.close();
            } catch (Exception ignored) {
            }
            out = null;
        }
        if (video != null) {
            ContentResolver cr = getContentResolver();
            if (ok && keep) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Video.Media.IS_PENDING, 0);
                    cr.update(video, v, null, null);
                }
            } else {
                cr.delete(video, null, null);
            }
            video = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        return ok && keep;
    }

    private void tell(boolean running, String message) {
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.recorderChanged(running, message);
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
