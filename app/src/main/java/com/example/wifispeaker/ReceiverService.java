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

    private static final String TAG = "WifiSpeakerReceiver";
    private static final int NOTIFICATION_ID = 2002;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;
    private Thread discoveryWorker;
    private ServerSocket serverSocket;
    private DatagramSocket discoverySocket;
    private Socket clientSocket;
    private AudioTrack audioTrack;
    private WifiManager.MulticastLock multicastLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;

        startForegroundNow("等待连接：" + WifiUtils.getLikelyLanIpAddress() + ":" + Protocol.PORT);
        stopCurrentWorker();
        running.set(true);
        acquireMulticastLock();
        discoveryWorker = new Thread(this::runDiscoveryResponder, "wifi-speaker-discovery-responder");
        discoveryWorker.start();
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
                startForegroundNow("已连接：" + socket.getInetAddress().getHostAddress());
                handleClient(socket);
                closeClientQuietly();
                if (running.get()) {
                    startForegroundNow("等待连接：" + WifiUtils.getLikelyLanIpAddress() + ":" + Protocol.PORT);
                }
            }
        } catch (Exception e) {
            if (running.get()) Log.e(TAG, "Receiver failed", e);
        } finally {
            running.set(false);
            closeQuietly();
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
        int bufferBytes = Math.max(minBytes * 4, 32 * 1024);

        AudioFormat outputFormat = new AudioFormat.Builder()
                .setSampleRate(header.sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(channelOutMask)
                .build();

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(outputFormat)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        audioTrack.play();
        byte[] buffer = new byte[16 * 1024];
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            int n = in.read(buffer);
            if (n < 0) break;
            if (n > 0) {
                int written = 0;
                while (written < n && running.get()) {
                    int ret = audioTrack.write(buffer, written, n - written, AudioTrack.WRITE_BLOCKING);
                    if (ret < 0) throw new IllegalStateException("AudioTrack write failed: " + ret);
                    written += ret;
                }
            }
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

    private void stopCurrentWorker() {
        running.set(false);
        if (worker != null) worker.interrupt();
        if (discoveryWorker != null) discoveryWorker.interrupt();
        closeQuietly();
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
            if (discoverySocket != null) discoverySocket.close();
        } catch (Exception ignored) {}
        discoverySocket = null;
        releaseMulticastLock();
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
