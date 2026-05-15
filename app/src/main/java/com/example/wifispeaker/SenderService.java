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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SenderService extends Service {
    static final String ACTION_START = "com.example.wifispeaker.action.START_SENDER";
    static final String ACTION_STOP = "com.example.wifispeaker.action.STOP_SENDER";
    static final String ACTION_STATE = "com.example.wifispeaker.action.SENDER_STATE";
    static final String EXTRA_HOST = "host";
    static final String EXTRA_HOSTS = "hosts";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";
    static final String EXTRA_RUNNING = "running";
    static final String EXTRA_STREAMING = "streaming";
    static final String EXTRA_MESSAGE = "message";

    private static final String TAG = "WifiSpeakerSender";
    private static final int NOTIFICATION_ID = 2001;
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_COUNT = 2;
    private static final int FRAME_DURATION_MS = Protocol.DEFAULT_FRAME_MS;
    private static final int RECONNECT_DELAY_MS = 800;
    private static final int CLIENT_QUEUE_FRAMES = 6;

    static volatile boolean sRunning = false;
    static volatile boolean sStreaming = false;
    static volatile String sHost = "";
    static volatile String sMessage = "未启动";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<ClientSender> clients = new ArrayList<>();
    private Thread worker;
    private AudioRecord recorder;
    private MediaProjection projection;
    private List<String> targetHosts = new ArrayList<>();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            publishState(false, false, sHost, "已停止推送");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;

        String hostsText = intent.getStringExtra(EXTRA_HOSTS);
        if (hostsText == null || hostsText.trim().isEmpty()) {
            hostsText = intent.getStringExtra(EXTRA_HOST);
        }
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        List<String> hosts = Protocol.parseHosts(hostsText);
        if (hosts.isEmpty() || resultData == null) {
            publishState(false, false, "", "启动失败：缺少接收端地址或系统授权");
            stopSelf();
            return START_NOT_STICKY;
        }

        String hostSummary = Protocol.joinHosts(hosts);
        startForegroundNow("准备发送到 " + hosts.size() + " 台接收端");
        stopCurrentWorker(false);
        targetHosts = new ArrayList<>(hosts);
        publishState(true, false, hostSummary, "准备发送到 " + hosts.size() + " 台接收端：" + hostSummary);
        running.set(true);
        worker = new Thread(() -> runCaptureLoop(hosts, resultCode, resultData), "wifi-speaker-sender");
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

    private synchronized void publishState(boolean isRunning, boolean isStreaming, String hosts, String message) {
        sRunning = isRunning;
        sStreaming = isStreaming;
        sHost = hosts == null ? "" : hosts;
        sMessage = message == null ? "" : message;
        Intent intent = new Intent(ACTION_STATE);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_RUNNING, sRunning);
        intent.putExtra(EXTRA_STREAMING, sStreaming);
        intent.putExtra(EXTRA_HOST, sHost);
        intent.putExtra(EXTRA_MESSAGE, sMessage);
        sendBroadcast(intent);
    }

    private void runCaptureLoop(List<String> hosts, int resultCode, Intent resultData) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        long sequence = 0;
        long capturedFrames = 0;
        try {
            prepareAudioCapture(resultCode, resultData);
            startClients(hosts);
            waitForInitialConnections();
            byte[] buffer = new byte[getFrameBytes()];
            recorder.startRecording();
            publishAggregateState("同步音频采集中，正在向接收端推送...");

            while (running.get() && !Thread.currentThread().isInterrupted()) {
                int n = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (n > 0) {
                    int bytesPerFrame = Protocol.bytesPerFrame(CHANNEL_COUNT, Protocol.PCM_16_BIT);
                    int alignedBytes = n - (n % bytesPerFrame);
                    if (alignedBytes <= 0) continue;
                    int durationFrames = alignedBytes / bytesPerFrame;
                    long presentationTimeNs = capturedFrames * 1000000000L / SAMPLE_RATE;
                    byte[] packet = Protocol.buildAudioFrame(sequence++, presentationTimeNs, durationFrames, buffer, alignedBytes);
                    capturedFrames += durationFrames;
                    synchronized (clients) {
                        for (ClientSender client : clients) {
                            client.offer(packet);
                        }
                    }
                } else if (n < 0) {
                    Log.w(TAG, "AudioRecord read returned " + n);
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Sender failed", e);
            publishState(false, false, Protocol.joinHosts(hosts), "发送端异常停止：" + e.getClass().getSimpleName());
        } finally {
            running.set(false);
            closeQuietly();
            publishState(false, false, Protocol.joinHosts(hosts), "已停止推送");
            stopSelf();
        }
    }

    private void startClients(List<String> hosts) {
        synchronized (clients) {
            clients.clear();
            for (String host : hosts) {
                ClientSender client = new ClientSender(host);
                clients.add(client);
                client.start();
            }
        }
    }

    private void waitForInitialConnections() {
        long deadline = System.currentTimeMillis() + 1200;
        while (running.get() && System.currentTimeMillis() < deadline) {
            int connected = 0;
            int total;
            synchronized (clients) {
                total = clients.size();
                for (ClientSender client : clients) {
                    if (client.connected) connected++;
                }
            }
            publishState(true, connected > 0, Protocol.joinHosts(targetHosts), "等待初始连接：" + connected + "/" + total);
            if (total > 0 && connected == total) break;
            sleepQuietly(80);
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
                publishState(false, false, sHost, "系统音频采集授权已结束");
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
                .setBufferSizeInBytes(getCaptureBufferBytes())
                .build();
    }

    private int getFrameBytes() {
        return Protocol.frameBytesForDuration(SAMPLE_RATE, CHANNEL_COUNT, Protocol.PCM_16_BIT, FRAME_DURATION_MS);
    }

    private int getCaptureBufferBytes() {
        int minBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        return Math.max(minBytes, getFrameBytes() * 4);
    }

    private void publishAggregateState(String fallback) {
        int connected = 0;
        int total;
        List<String> failed = new ArrayList<>();
        synchronized (clients) {
            total = clients.size();
            for (ClientSender client : clients) {
                if (client.connected) connected++;
                else failed.add(client.host);
            }
        }
        String hostSummary = Protocol.joinHosts(targetHosts);
        String message;
        if (total <= 0) {
            message = fallback;
        } else if (connected == total) {
            message = "同步推送中：" + connected + "/" + total + " 台接收端";
        } else if (connected > 0) {
            message = "正在向 " + connected + "/" + total + " 台接收端推送；其余正在重连：" + Protocol.joinHosts(failed);
        } else {
            message = "未连接到接收端，正在重连：" + hostSummary;
        }
        updateNotification(message);
        publishState(true, connected > 0, hostSummary, message);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void stopCurrentWorker(boolean publishStopped) {
        running.set(false);
        if (worker != null) worker.interrupt();
        closeQuietly();
        if (publishStopped) publishState(false, false, sHost, "已停止推送");
    }

    private void closeQuietly() {
        synchronized (clients) {
            for (ClientSender client : clients) {
                client.stop();
            }
            clients.clear();
        }

        try {
            if (recorder != null) {
                try { recorder.stop(); } catch (Exception ignored) {}
                recorder.release();
            }
        } catch (Exception ignored) {}
        recorder = null;

        MediaProjection p = projection;
        projection = null;
        try {
            if (p != null) p.stop();
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        stopCurrentWorker(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final class ClientSender {
        final String host;
        final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(CLIENT_QUEUE_FRAMES);
        final AtomicBoolean active = new AtomicBoolean(true);
        volatile boolean connected = false;
        Thread thread;
        Socket socket;
        DataOutputStream out;

        ClientSender(String host) {
            this.host = host;
        }

        void start() {
            thread = new Thread(this::run, "wifi-speaker-client-" + host);
            thread.start();
        }

        void offer(byte[] frame) {
            if (!active.get()) return;
            if (!queue.offer(frame)) {
                // 队列满时只丢最旧的一帧，再放入最新帧。
                // 之前直接清空队列会让慢设备频繁吃空，表现为声音断断续续。
                // 这里保留一个 40~60ms 的小缓冲，在连续性和低延迟之间取平衡。
                queue.poll();
                queue.offer(frame);
            }
        }

        void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            while (running.get() && active.get() && !Thread.currentThread().isInterrupted()) {
                if (out == null) {
                    connect();
                    if (out == null) {
                        sleepQuietly(RECONNECT_DELAY_MS);
                        continue;
                    }
                }
                try {
                    byte[] frame = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (frame == null) continue;
                    out.write(frame);
                    out.flush();
                } catch (Exception e) {
                    Log.w(TAG, "Write failed for " + host + ", will reconnect", e);
                    disconnect();
                    publishAggregateState("连接断开，正在重连");
                    sleepQuietly(RECONNECT_DELAY_MS);
                }
            }
            disconnect();
        }

        void connect() {
            disconnect();
            Socket s = new Socket();
            try {
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(host, Protocol.PORT), 3000);
                DataOutputStream output = new DataOutputStream(s.getOutputStream());
                s.setSendBufferSize(32 * 1024);
                Protocol.writeHeader(output, SAMPLE_RATE, CHANNEL_COUNT, Protocol.PCM_16_BIT, FRAME_DURATION_MS);
                socket = s;
                out = output;
                connected = true;
                queue.clear();
                publishAggregateState("已连接接收端");
            } catch (Exception e) {
                try { s.close(); } catch (Exception ignored) {}
                connected = false;
                out = null;
                socket = null;
                Log.w(TAG, "Connect failed, will retry: " + host, e);
                publishAggregateState("连接失败，正在重试");
            }
        }

        void disconnect() {
            connected = false;
            queue.clear();
            try {
                if (out != null) out.flush();
            } catch (Exception ignored) {}
            out = null;
            try {
                if (socket != null) socket.close();
            } catch (Exception ignored) {}
            socket = null;
        }

        void stop() {
            active.set(false);
            if (thread != null) thread.interrupt();
            disconnect();
        }
    }
}
