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
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;
import java.nio.ByteBuffer;
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
    private MediaCodec vCodec, aCodec;
    private MediaMuxer muxer;
    private AudioRecord audioRec;
    private Thread vThread, aThread;
    private final Object lock = new Object();
    private int vTrack = -1, aTrack = -1, tracksAdded = 0, expectedTracks = 1;
    private boolean muxStarted = false, aborted = false;
    private volatile boolean audioRunning = false;
    private long t0 = 0, lastV = -1, lastA = -1;
    private int samples = 0;
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
        int res = p.getInt("res2", 480);       // короткая сторона, 0 = оригинал
        int fps = p.getInt("fps2", 30);
        int quality = p.getInt("quality2", 1); // 0 низкое, 1 среднее, 2 высокое
        int orient = p.getInt("orient", 0);    // 0 авто, 1 горизонт, 2 вертикаль
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
        double bpp = quality == 0 ? 0.06 : quality == 2 ? 0.18 : 0.10;
        int bitrate = (int) Math.max(1_500_000, Math.min(30_000_000, w * (double) h * fps * bpp));

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
        muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // Видео: аппаратный кодер с НИЗКИМ приоритетом (не отбирает ресурсы у игры)
        String mime = hevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        MediaFormat vf = MediaFormat.createVideoFormat(mime, w, h);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        vf.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        vf.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        vf.setInteger(MediaFormat.KEY_PRIORITY, 1);            // 1 = не реальное время, уступает игре
        vf.setInteger("max-fps-to-encoder", fps);              // лишние кадры отбрасываются до кодера
        vCodec = MediaCodec.createEncoderByType(mime);
        vCodec.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface input = vCodec.createInputSurface();
        vCodec.start();

        expectedTracks = 1;
        t0 = System.nanoTime() / 1000;
        if (mic) {
            try {
                startAudio();
                expectedTracks = 2;
            } catch (Throwable t) {
                audioRunning = false;
                toast("Микрофон недоступен: пишу без звука");
            }
        }

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
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, input, null, handler);

        running = true;
        vThread = new Thread(this::drainVideo, "rec-video");
        vThread.setPriority(Thread.MIN_PRIORITY);
        vThread.start();
        toast("Запись началась (" + w + "×" + h + ", " + fps + " к/с)");
    }

    private void startAudio() throws Exception {
        int rate = 44100;
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioRec = new AudioRecord(android.media.MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 16384));
        if (audioRec.getState() != AudioRecord.STATE_INITIALIZED) throw new Exception("AudioRecord");
        MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1);
        af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        af.setInteger(MediaFormat.KEY_BIT_RATE, 96_000);
        af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192);
        aCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        aCodec.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        aCodec.start();
        audioRec.startRecording();
        audioRunning = true;
        aThread = new Thread(this::audioLoop, "rec-audio");
        aThread.setPriority(Thread.MIN_PRIORITY);
        aThread.start();
    }

    private void addTrack(boolean video, MediaFormat fmt) {
        synchronized (lock) {
            int idx = muxer.addTrack(fmt);
            if (video) vTrack = idx; else aTrack = idx;
            tracksAdded++;
            if (tracksAdded >= expectedTracks && !muxStarted) {
                muxer.start();
                muxStarted = true;
                lock.notifyAll();
            }
        }
    }

    private boolean waitStarted() {
        synchronized (lock) {
            while (!muxStarted && !aborted) {
                try { lock.wait(100); } catch (InterruptedException e) { return false; }
            }
            return muxStarted && !aborted;
        }
    }

    private void write(boolean video, ByteBuffer b, MediaCodec.BufferInfo bi) {
        synchronized (lock) {
            long pts = bi.presentationTimeUs;
            if (video) { if (pts <= lastV) pts = lastV + 1; lastV = pts; }
            else { if (pts <= lastA) pts = lastA + 1; lastA = pts; }
            bi.presentationTimeUs = pts;
            b.position(bi.offset);
            b.limit(bi.offset + bi.size);
            try {
                muxer.writeSampleData(video ? vTrack : aTrack, b, bi);
                samples++;
            } catch (Throwable ignored) { }
        }
    }

    private void drainVideo() {
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        try {
            while (!aborted) {
                int idx = vCodec.dequeueOutputBuffer(bi, 20_000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    addTrack(true, vCodec.getOutputFormat());
                } else if (idx >= 0) {
                    ByteBuffer b = vCodec.getOutputBuffer(idx);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bi.size = 0;
                    if (bi.size > 0 && b != null && waitStarted()) {
                        bi.presentationTimeUs = Math.max(0, bi.presentationTimeUs - t0);
                        write(true, b, bi);
                    }
                    vCodec.releaseOutputBuffer(idx, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } catch (Throwable ignored) { }
    }

    private void audioLoop() {
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        try {
            while (audioRunning) {
                int in = aCodec.dequeueInputBuffer(10_000);
                if (in >= 0) {
                    ByteBuffer ib = aCodec.getInputBuffer(in);
                    ib.clear();
                    int n = audioRec.read(ib, Math.min(ib.remaining(), 4096));
                    long pts = Math.max(0, System.nanoTime() / 1000 - t0 - (Math.max(n, 0) / 2) * 1_000_000L / 44100);
                    aCodec.queueInputBuffer(in, 0, Math.max(n, 0), pts, 0);
                }
                drainAudio(bi, false);
            }
            int in = aCodec.dequeueInputBuffer(100_000);
            if (in >= 0) aCodec.queueInputBuffer(in, 0, 0, Math.max(0, System.nanoTime() / 1000 - t0), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drainAudio(bi, true);
        } catch (Throwable ignored) { }
    }

    private void drainAudio(MediaCodec.BufferInfo bi, boolean untilEos) {
        int guard = 0;
        while (guard++ < 200) {
            int idx = aCodec.dequeueOutputBuffer(bi, untilEos ? 20_000 : 0);
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                addTrack(false, aCodec.getOutputFormat());
            } else if (idx >= 0) {
                ByteBuffer b = aCodec.getOutputBuffer(idx);
                if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bi.size = 0;
                if (bi.size > 0 && b != null && waitStarted()) write(false, b, bi);
                aCodec.releaseOutputBuffer(idx, false);
                if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            } else if (!untilEos) {
                return;
            }
        }
    }

    private synchronized void stopRecording() {
        if (stopping) return;
        stopping = true;
        boolean wasRunning = running;
        running = false;
        audioRunning = false;
        // сначала отключаем захват экрана, чтобы игра сразу перестала тратить ресурсы
        try { if (display != null) display.release(); } catch (Throwable ignored) { }
        try { if (vCodec != null && wasRunning) vCodec.signalEndOfInputStream(); } catch (Throwable ignored) { }
        try { if (vThread != null) vThread.join(2500); } catch (Throwable ignored) { }
        try { if (aThread != null) aThread.join(2500); } catch (Throwable ignored) { }
        aborted = true;
        synchronized (lock) { lock.notifyAll(); }
        boolean ok = false;
        try {
            if (muxer != null && muxStarted && samples > 0) {
                muxer.stop();
                ok = true;
            }
        } catch (Throwable ignored) { }
        cleanup(!ok);
        if (ok) toast("Запись сохранена: Фильмы/Enotik Recorder");
    }

    private void cleanup(boolean deleteFile) {
        try { if (display != null) display.release(); } catch (Throwable ignored) { }
        try { if (vCodec != null) vCodec.release(); } catch (Throwable ignored) { }
        try { if (aCodec != null) aCodec.release(); } catch (Throwable ignored) { }
        try { if (audioRec != null) { audioRec.stop(); audioRec.release(); } } catch (Throwable ignored) { }
        try { if (muxer != null) muxer.release(); } catch (Throwable ignored) { }
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
        display = null; vCodec = null; aCodec = null; audioRec = null; muxer = null; projection = null; pfd = null; outUri = null;
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
