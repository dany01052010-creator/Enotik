package com.enotik.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Display;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Запись экрана через MediaProjection + аппаратный кодек (MediaRecorder).
 * Кодирование делает отдельный аппаратный блок телефона, поэтому нагрузка на процессор и игру минимальна.
 */
public class RecorderService extends Service {
    public static final String ACTION_START = "com.enotik.recorder.START";
    public static final String ACTION_STOP = "com.enotik.recorder.STOP";
    private static final String CHANNEL = "rec";

    /** Идёт ли запись (читает MainActivity). */
    public static volatile boolean running = false;

    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaRecorder recorder;
    private ParcelFileDescriptor pfd;
    private Uri outUri;
    private HandlerThread thread;
    private Handler handler;
    private boolean stopping = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            stopRecording();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Запись экрана", NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, RecorderService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_rec)
                .setContentTitle("Идёт запись экрана")
                .setContentText("Нажми «Остановить», когда закончишь")
                .setOngoing(true)
                .setUsesChronometer(true)
                .setWhen(System.currentTimeMillis())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_rec), "Остановить", pi).build())
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        try {
            begin(intent);
        } catch (Throwable t) {
            toast("Не удалось начать запись: " + t.getMessage());
            cleanup(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void begin(Intent i) throws Exception {
        SharedPreferences p = getSharedPreferences("rec", MODE_PRIVATE);
        int res = p.getInt("res", 720);       // короткая сторона, 0 = оригинал
        int fps = p.getInt("fps", 30);
        int quality = p.getInt("quality", 1); // 0 низкое, 1 среднее, 2 высокое
        int orient = p.getInt("orient", 0);   // 0 авто, 1 горизонт, 2 вертикаль
        boolean mic = p.getBoolean("mic", false);
        boolean hevc = p.getBoolean("hevc", false);

        DisplayManager dm = getSystemService(DisplayManager.class);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        int longS = Math.max(m.widthPixels, m.heightPixels);
        int shortS = Math.min(m.widthPixels, m.heightPixels);
        boolean land = orient == 1 || (orient == 0 && m.widthPixels > m.heightPixels);
        int sShort = res == 0 ? shortS : Math.min(res, shortS);
        int sLong = Math.round((float) longS * sShort / shortS);
        sShort &= ~1;
        sLong &= ~1;
        int w = land ? sLong : sShort;
        int h = land ? sShort : sLong;
        double bpp = quality == 0 ? 0.08 : quality == 2 ? 0.25 : 0.15;
        int bitrate = (int) Math.max(2_000_000, Math.min(40_000_000, w * (double) h * fps * bpp));

        // Файл в «Фильмы/Enotik Recorder» через MediaStore (разрешения на память не нужны)
        String name = "Enotik_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Enotik Recorder");
        cv.put(MediaStore.Video.Media.IS_PENDING, 1);
        outUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
        if (outUri == null) throw new Exception("не удалось создать файл");
        pfd = getContentResolver().openFileDescriptor(outUri, "w");

        recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
        if (mic) recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setVideoEncoder(hevc ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264);
        if (mic) {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128_000);
            recorder.setAudioSamplingRate(44_100);
        }
        recorder.setVideoSize(w, h);
        recorder.setVideoFrameRate(fps);
        recorder.setVideoEncodingBitRate(bitrate);
        recorder.setOutputFile(pfd.getFileDescriptor());
        recorder.prepare();

        thread = new HandlerThread("rec-cb", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        handler = new Handler(thread.getLooper());

        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        Intent data = i.getParcelableExtra("data");
        projection = mpm.getMediaProjection(i.getIntExtra("code", 0), data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopRecording();
                stopSelf();
            }
        }, handler);
        display = projection.createVirtualDisplay("EnotikRec", w, h, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.getSurface(), null, handler);
        recorder.start();
        running = true;
        toast("Запись началась (" + w + "×" + h + ", " + fps + " к/с)");
    }

    private synchronized void stopRecording() {
        if (stopping) return;
        stopping = true;
        boolean ok = false;
        if (recorder != null && running) {
            try {
                recorder.stop();
                ok = true;
            } catch (RuntimeException e) {
                ok = false; // запись была слишком короткой
            }
        }
        running = false;
        cleanup(!ok);
        if (ok) toast("Запись сохранена: Фильмы/Enotik Recorder");
    }

    private void cleanup(boolean deleteFile) {
        try { if (display != null) display.release(); } catch (Throwable ignored) { }
        try { if (recorder != null) recorder.release(); } catch (Throwable ignored) { }
        try { if (projection != null) projection.stop(); } catch (Throwable ignored) { }
        try { if (pfd != null) pfd.close(); } catch (Throwable ignored) { }
        if (outUri != null) {
            try {
                if (deleteFile) {
                    getContentResolver().delete(outUri, null, null);
                } else {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(outUri, cv, null, null);
                    getSharedPreferences("rec", MODE_PRIVATE).edit().putString("last", outUri.toString()).apply();
                }
            } catch (Throwable ignored) { }
        }
        display = null; recorder = null; projection = null; pfd = null; outUri = null;
        if (thread != null) { thread.quitSafely(); thread = null; }
        running = false;
    }

    private void toast(String s) {
        new Handler(getMainLooper()).post(() -> Toast.makeText(getApplicationContext(), s, Toast.LENGTH_LONG).show());
    }

    @Override
    public void onDestroy() {
        stopRecording();
        super.onDestroy();
    }
}
