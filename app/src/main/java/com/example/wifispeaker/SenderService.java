package com.example.wifispeaker;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

public class SenderService extends Service {
    static final String ACTION_START = "com.example.wifispeaker.action.START_SENDER";
    static final String ACTION_STOP = "com.example.wifispeaker.action.STOP_SENDER";
    static final String EXTRA_HOST = "host";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";

    private static final String TAG = "WifiSpeakerSender";
    private static final int NOTIFICATION_ID = 2001;
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_COUNT = 2;
    private static final int RECONNECT_DELAY_MS = 1200;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;
    private Socket socket;
    private AudioRecord recorder;
    private MediaProjection projection;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;

        String host = intent.getStringExtra(EXTRA_HOST);
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (host == null || host.trim().isEmpty() || resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundNow("准备发送到 " + host + ":" + Protocol.PORT);
        stopCurrentWorker();
        running.set(true);
        worker = new Thread(() -> runCaptureLoop(host.trim(), resultCode, resultData), "wifi-speaker-sender");
        worker.start();
        return START_STICKY;
    }

    private void startForegroundNow(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    Notifications.build(this, "WiFi Speaker 发送端", text),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(NOTIFICATION_ID, Notifications.build(this, "WiFi Speaker 发送端", text));
        }
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, Notifications.build(this, "WiFi Speaker 发送端", text));
            }
        } catch (Exception ignored) {}
    }

    private void runCaptureLoop(String host, int resultCode, Intent resultData) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        DataOutputStream out = null;
        try {
            prepareAudioCapture(resultCode, resultData);
            byte[] buffer = new byte[getBufferBytes()];
            recorder.startRecording();

            while (running.get() && !Thread.currentThread().isInterrupted()) {
                if (out == null) {
                    out = connectAndWriteHeader(host);
                    if (out == null) {
                        sleepQuietly(RECONNECT_DELAY_MS);
                        continue;
                    }
                }

                int n = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (n > 0) {
                    try {
                        out.write(buffer, 0, n);
                    } catch (IOException e) {
                        Log.w(TAG, "Write failed, will reconnect", e);
                        closeSocketQuietly();
                        out = null;
                        updateNotification("连接断开，正在重连 " + host + ":" + Protocol.PORT);
                        sleepQuietly(RECONNECT_DELAY_MS);
                    }
                } else if (n < 0) {
                    Log.w(TAG, "AudioRecord read returned " + n);
                    break;
                }
            }
            if (out != null) {
                try { out.flush(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "Sender failed", e);
        } finally {
            running.set(false);
            closeQuietly();
            stopSelf();
        }
    }

    private void prepareAudioCapture(int resultCode, Intent resultData) {
        MediaProjectionManager mgr = getSystemService(MediaProjectionManager.class);
        projection = mgr.getMediaProjection(resultCode, resultData);
        if (projection == null) throw new IllegalStateException("MediaProjection is null");
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.w(TAG, "MediaProjection stopped by system/user");
                running.set(false);
                stopSelf();
            }
        }, new Handler(Looper.getMainLooper()));

        AudioPlaybackCaptureConfiguration captureConfig = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();

        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();

        recorder = new AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(format)
                .setBufferSizeInBytes(getBufferBytes())
                .build();
    }

    private int getBufferBytes() {
        int minBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        return Math.max(minBytes * 2, 16 * 1024);
    }

    private DataOutputStream connectAndWriteHeader(String host) {
        closeSocketQuietly();
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, Protocol.PORT), 3000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            Protocol.writeHeader(out, SAMPLE_RATE, CHANNEL_COUNT, Protocol.PCM_16_BIT);
            socket = s;
            updateNotification("正在发送到 " + host + ":" + Protocol.PORT);
            return out;
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) {}
            Log.w(TAG, "Connect failed, will retry: " + host, e);
            updateNotification("连接失败，正在重试 " + host + ":" + Protocol.PORT);
            return null;
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void stopCurrentWorker() {
        running.set(false);
        if (worker != null) worker.interrupt();
        closeQuietly();
    }

    private void closeSocketQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
        socket = null;
    }

    private void closeQuietly() {
        try {
            if (recorder != null) {
                try { recorder.stop(); } catch (Exception ignored) {}
                recorder.release();
            }
        } catch (Exception ignored) {}
        recorder = null;

        closeSocketQuietly();

        MediaProjection p = projection;
        projection = null;
        try {
            if (p != null) p.stop();
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        stopCurrentWorker();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
