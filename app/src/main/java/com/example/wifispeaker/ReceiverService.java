package com.example.wifispeaker;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import java.io.DataInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReceiverService extends Service {
    static final String ACTION_START = "com.example.wifispeaker.action.START_RECEIVER";
    static final String ACTION_STOP = "com.example.wifispeaker.action.STOP_RECEIVER";
    static final String ACTION_STATE = "com.example.wifispeaker.action.RECEIVER_STATE";
    static final String EXTRA_RUNNING = "running";
    static final String EXTRA_CONNECTED = "connected";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_VOLUME_PERCENT = "volume_percent";

    private static final String TAG = "WifiSpeakerReceiver";
    private static final int NOTIFICATION_ID = 2002;

    static volatile boolean sRunning = false;
    static volatile boolean sConnected = false;
    static volatile String sMessage = "未启动";
    static volatile int sVolumePercent = 100;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;
    private Thread discoveryWorker;
    private Thread controlWorker;
    private ServerSocket serverSocket;
    private ServerSocket controlServerSocket;
    private DatagramSocket discoverySocket;
    private Socket clientSocket;
    private AudioTrack audioTrack;
    private WifiManager.MulticastLock multicastLock;
    private volatile int currentVolumePercent = 100;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            publishState(false, false, "已停止接收端");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;

        currentVolumePercent = sVolumePercent;
        String waitMessage = "等待连接：" + WifiUtils.getLikelyLanIpAddress() + ":" + Protocol.PORT;
        startForegroundNow(waitMessage);
        stopCurrentWorker(false);
        publishState(true, false, waitMessage);
        running.set(true);
        acquireMulticastLock();
        discoveryWorker = new Thread(this::runDiscoveryResponder, "wifi-speaker-discovery-responder");
        discoveryWorker.start();
        controlWorker = new Thread(this::runControlServer, "wifi-speaker-volume-control");
        controlWorker.start();
        worker = new Thread(this::runServerLoop, "wifi-speaker-receiver");
        worker.start();
        return START_STICKY;
    }

    private void startForegroundNow(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    Notifications.build(this, "WiFi Speaker 接收端", text),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            );
        } else {
            startForeground(NOTIFICATION_ID, Notifications.build(this, "WiFi Speaker 接收端", text));
        }
    }

    private void publishState(boolean isRunning, boolean isConnected, String message) {
        sRunning = isRunning;
        sConnected = isConnected;
        sMessage = message == null ? "" : message;
        Intent intent = new Intent(ACTION_STATE);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_RUNNING, sRunning);
        intent.putExtra(EXTRA_CONNECTED, sConnected);
        intent.putExtra(EXTRA_MESSAGE, sMessage);
        intent.putExtra(EXTRA_VOLUME_PERCENT, sVolumePercent);
        sendBroadcast(intent);
    }

    private void runServerLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(Protocol.PORT));
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                Socket socket = serverSocket.accept();
                clientSocket = socket;
                socket.setTcpNoDelay(true);
                String connectedMessage = "已连接：" + socket.getInetAddress().getHostAddress();
                startForegroundNow(connectedMessage);
                publishState(true, true, connectedMessage);
                handleClient(socket);
                closeClientQuietly();
                if (running.get()) {
                    String waitMessage = "等待连接：" + WifiUtils.getLikelyLanIpAddress() + ":" + Protocol.PORT;
                    startForegroundNow(waitMessage);
                    publishState(true, false, waitMessage);
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                Log.e(TAG, "Receiver failed", e);
                publishState(false, false, "接收端异常停止：" + e.getClass().getSimpleName());
            }
        } finally {
            running.set(false);
            closeQuietly();
            publishState(false, false, "已停止接收端");
            stopSelf();
        }
    }

    private void handleClient(Socket socket) throws Exception {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        Protocol.Header header = Protocol.readHeader(in);

        int channelOutMask = header.channelCount == 2
                ? AudioFormat.CHANNEL_OUT_STEREO
                : AudioFormat.CHANNEL_OUT_MONO;
        int minBytes = AudioTrack.getMinBufferSize(
                header.sampleRate,
                channelOutMask,
                AudioFormat.ENCODING_PCM_16BIT
        );
        int lowLatencyBytes = bytesForMs(header.sampleRate, header.channelCount, header.bitsPerSample, 90);
        int bufferBytes = Math.max(minBytes, lowLatencyBytes);

        AudioFormat outputFormat = new AudioFormat.Builder()
                .setSampleRate(header.sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(channelOutMask)
                .build();

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        AudioTrack.Builder builder = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(outputFormat)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        }
        audioTrack = builder.build();

        applyPlaybackVolume();
        audioTrack.play();

        long submittedFrames = 0;
        long droppedFrames = 0;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            Protocol.AudioFrame frame = Protocol.readAudioFrame(in);
            long queuedMs = getQueuedAudioMs(audioTrack, submittedFrames, header.sampleRate);
            if (queuedMs > 180) {
                droppedFrames += frame.durationFrames;
                if ((droppedFrames % (header.sampleRate * 2L)) < frame.durationFrames) {
                    Log.w(TAG, "Dropping stale audio to reduce latency, queuedMs=" + queuedMs);
                }
                continue;
            }

            int written = 0;
            byte[] payload = frame.payload;
            while (written < payload.length && running.get()) {
                int ret = audioTrack.write(payload, written, payload.length - written, AudioTrack.WRITE_BLOCKING);
                if (ret < 0) throw new IllegalStateException("AudioTrack write failed: " + ret);
                written += ret;
                submittedFrames += ret / Protocol.bytesPerFrame(header.channelCount, header.bitsPerSample);
            }
        }
    }

    private int bytesForMs(int sampleRate, int channelCount, int bitsPerSample, int ms) {
        return sampleRate * ms / 1000 * Protocol.bytesPerFrame(channelCount, bitsPerSample);
    }

    private long getQueuedAudioMs(AudioTrack track, long submittedFrames, int sampleRate) {
        if (track == null || sampleRate <= 0) return 0;
        long playedFrames = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        long queuedFrames = Math.max(0, submittedFrames - playedFrames);
        return queuedFrames * 1000L / sampleRate;
    }

    private void runControlServer() {
        try {
            controlServerSocket = new ServerSocket();
            controlServerSocket.setReuseAddress(true);
            controlServerSocket.bind(new InetSocketAddress(Protocol.CONTROL_PORT));
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                Socket controlSocket = controlServerSocket.accept();
                try {
                    controlSocket.setSoTimeout(2000);
                    DataInputStream in = new DataInputStream(controlSocket.getInputStream());
                    int volumePercent = Protocol.readVolumeCommand(in);
                    setReceiverVolumePercent(volumePercent, "发送端已设置接收端应用内音量：" + volumePercent + "%");
                } catch (Exception e) {
                    if (running.get()) Log.w(TAG, "Bad volume control command", e);
                } finally {
                    try { controlSocket.close(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            if (running.get()) Log.e(TAG, "Volume control server failed", e);
        } finally {
            try {
                if (controlServerSocket != null) controlServerSocket.close();
            } catch (Exception ignored) {}
            controlServerSocket = null;
        }
    }

    private void setReceiverVolumePercent(int volumePercent, String message) {
        currentVolumePercent = Protocol.clampVolume(volumePercent);
        sVolumePercent = currentVolumePercent;
        applyPlaybackVolume();
        if (message != null) {
            publishState(sRunning, sConnected, message);
        }
    }

    private void applyPlaybackVolume() {
        AudioTrack track = audioTrack;
        if (track == null) return;
        try {
            track.setVolume(currentVolumePercent / 100.0f);
        } catch (Exception e) {
            Log.w(TAG, "Failed to apply playback volume", e);
        }
    }

    private void runDiscoveryResponder() {
        try {
            discoverySocket = new DatagramSocket(null);
            discoverySocket.setReuseAddress(true);
            discoverySocket.bind(new InetSocketAddress(Discovery.DISCOVERY_PORT));
            byte[] buffer = new byte[512];
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                discoverySocket.receive(request);
                if (!Discovery.isRequest(request.getData(), request.getLength())) continue;

                String ip = WifiUtils.getLikelyLanIpAddress();
                String name = Build.MANUFACTURER + " " + Build.MODEL;
                byte[] response = Discovery.responseBytes(ip, name);
                DatagramPacket reply = new DatagramPacket(
                        response,
                        response.length,
                        request.getAddress(),
                        request.getPort()
                );
                discoverySocket.send(reply);
            }
        } catch (Exception e) {
            if (running.get()) Log.e(TAG, "Discovery responder failed", e);
        } finally {
            try {
                if (discoverySocket != null) discoverySocket.close();
            } catch (Exception ignored) {}
            discoverySocket = null;
        }
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                multicastLock = wifiManager.createMulticastLock("wifi-speaker-discovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to acquire multicast lock", e);
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Exception ignored) {}
        multicastLock = null;
    }

    private void stopCurrentWorker(boolean publishStopped) {
        running.set(false);
        if (worker != null) worker.interrupt();
        if (discoveryWorker != null) discoveryWorker.interrupt();
        if (controlWorker != null) controlWorker.interrupt();
        closeQuietly();
        if (publishStopped) publishState(false, false, "已停止接收端");
    }

    private void closeClientQuietly() {
        try {
            if (audioTrack != null) {
                try { audioTrack.stop(); } catch (Exception ignored) {}
                audioTrack.release();
            }
        } catch (Exception ignored) {}
        audioTrack = null;

        try {
            if (clientSocket != null) clientSocket.close();
        } catch (Exception ignored) {}
        clientSocket = null;
    }

    private void closeQuietly() {
        closeClientQuietly();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
        serverSocket = null;

        try {
            if (controlServerSocket != null) controlServerSocket.close();
        } catch (Exception ignored) {}
        controlServerSocket = null;

        try {
            if (discoverySocket != null) discoverySocket.close();
        } catch (Exception ignored) {}
        discoverySocket = null;
        releaseMulticastLock();
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
}
