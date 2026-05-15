package com.example.wifispeaker;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class Protocol {
    static final int PORT = 45777;
    static final int CONTROL_PORT = 45779;
    static final int PCM_16_BIT = 16;
    static final int FORMAT_PCM_S16LE = 1;
    static final int DEFAULT_FRAME_MS = 10;

    private static final byte[] MAGIC = "WSPK0003".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONTROL_MAGIC = "WSPKCTRL".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FRAME_MAGIC = "AFRM".getBytes(StandardCharsets.US_ASCII);
    private static final int CONTROL_TYPE_VOLUME = 1;
    private static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    private Protocol() {}

    static void writeHeader(DataOutputStream out, int sampleRate, int channelCount, int bitsPerSample, int frameDurationMs) throws IOException {
        out.write(MAGIC);
        out.writeInt(sampleRate);
        out.writeInt(channelCount);
        out.writeInt(bitsPerSample);
        out.writeInt(FORMAT_PCM_S16LE);
        out.writeInt(frameDurationMs);
        out.flush();
    }

    static Header readHeader(DataInputStream in) throws IOException {
        byte[] magic = new byte[MAGIC.length];
        in.readFully(magic);
        for (int i = 0; i < MAGIC.length; i++) {
            if (magic[i] != MAGIC[i]) {
                throw new IOException("Bad stream magic");
            }
        }
        int sampleRate = in.readInt();
        int channelCount = in.readInt();
        int bitsPerSample = in.readInt();
        int formatCode = in.readInt();
        int frameDurationMs = in.readInt();
        if (sampleRate < 8000 || sampleRate > 192000) {
            throw new IOException("Unsupported sample rate: " + sampleRate);
        }
        if (channelCount != 1 && channelCount != 2) {
            throw new IOException("Unsupported channel count: " + channelCount);
        }
        if (bitsPerSample != PCM_16_BIT || formatCode != FORMAT_PCM_S16LE) {
            throw new IOException("Unsupported PCM format: bits=" + bitsPerSample + ", format=" + formatCode);
        }
        if (frameDurationMs < 1 || frameDurationMs > 100) {
            throw new IOException("Unsupported frame duration: " + frameDurationMs);
        }
        return new Header(sampleRate, channelCount, bitsPerSample, formatCode, frameDurationMs);
    }

    static byte[] buildAudioFrame(long sequence, long presentationTimeNs, int durationFrames, byte[] pcm, int pcmBytes) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(FRAME_MAGIC.length + 4 + 8 + 8 + 4 + pcmBytes);
        DataOutputStream out = new DataOutputStream(baos);
        out.write(FRAME_MAGIC);
        out.writeInt(pcmBytes);
        out.writeLong(sequence);
        out.writeLong(presentationTimeNs);
        out.writeInt(durationFrames);
        out.write(pcm, 0, pcmBytes);
        out.flush();
        return baos.toByteArray();
    }

    static AudioFrame readAudioFrame(DataInputStream in) throws IOException {
        byte[] magic = new byte[FRAME_MAGIC.length];
        in.readFully(magic);
        for (int i = 0; i < FRAME_MAGIC.length; i++) {
            if (magic[i] != FRAME_MAGIC[i]) {
                throw new IOException("Bad frame magic");
            }
        }
        int payloadBytes = in.readInt();
        if (payloadBytes <= 0 || payloadBytes > MAX_PAYLOAD_BYTES) {
            throw new IOException("Bad frame payload size: " + payloadBytes);
        }
        long sequence = in.readLong();
        long presentationTimeNs = in.readLong();
        int durationFrames = in.readInt();
        if (durationFrames <= 0 || durationFrames > 192000) {
            throw new IOException("Bad frame duration frames: " + durationFrames);
        }
        byte[] payload = new byte[payloadBytes];
        in.readFully(payload);
        return new AudioFrame(sequence, presentationTimeNs, durationFrames, payload);
    }

    static void writeVolumeCommand(DataOutputStream out, int volumePercent) throws IOException {
        int clamped = clampVolume(volumePercent);
        out.write(CONTROL_MAGIC);
        out.writeInt(CONTROL_TYPE_VOLUME);
        out.writeInt(clamped);
        out.flush();
    }

    static int readVolumeCommand(DataInputStream in) throws IOException {
        byte[] magic = new byte[CONTROL_MAGIC.length];
        in.readFully(magic);
        for (int i = 0; i < CONTROL_MAGIC.length; i++) {
            if (magic[i] != CONTROL_MAGIC[i]) {
                throw new IOException("Bad control magic");
            }
        }
        int type = in.readInt();
        if (type != CONTROL_TYPE_VOLUME) {
            throw new IOException("Unsupported control type: " + type);
        }
        return clampVolume(in.readInt());
    }

    static int clampVolume(int volumePercent) {
        if (volumePercent < 0) return 0;
        if (volumePercent > 100) return 100;
        return volumePercent;
    }

    static int bytesPerFrame(int channelCount, int bitsPerSample) {
        return channelCount * (bitsPerSample / 8);
    }

    static int frameBytesForDuration(int sampleRate, int channelCount, int bitsPerSample, int frameMs) {
        int frames = Math.max(1, sampleRate * frameMs / 1000);
        return frames * bytesPerFrame(channelCount, bitsPerSample);
    }

    static List<String> parseHosts(String text) {
        Set<String> unique = new LinkedHashSet<>();
        if (text != null) {
            String[] parts = text.split("[,;\\s]+");
            for (String part : parts) {
                String host = part == null ? "" : part.trim();
                if (!host.isEmpty()) unique.add(host);
            }
        }
        return new ArrayList<>(unique);
    }

    static String joinHosts(List<String> hosts) {
        StringBuilder sb = new StringBuilder();
        if (hosts == null) return "";
        for (String host : hosts) {
            String value = host == null ? "" : host.trim();
            if (value.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(value);
        }
        return sb.toString();
    }

    static String normalizeHosts(String text) {
        return joinHosts(parseHosts(text));
    }

    static final class Header {
        final int sampleRate;
        final int channelCount;
        final int bitsPerSample;
        final int formatCode;
        final int frameDurationMs;

        Header(int sampleRate, int channelCount, int bitsPerSample, int formatCode, int frameDurationMs) {
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.bitsPerSample = bitsPerSample;
            this.formatCode = formatCode;
            this.frameDurationMs = frameDurationMs;
        }
    }

    static final class AudioFrame {
        final long sequence;
        final long presentationTimeNs;
        final int durationFrames;
        final byte[] payload;

        AudioFrame(long sequence, long presentationTimeNs, int durationFrames, byte[] payload) {
            this.sequence = sequence;
            this.presentationTimeNs = presentationTimeNs;
            this.durationFrames = durationFrames;
            this.payload = payload;
        }
    }
}
