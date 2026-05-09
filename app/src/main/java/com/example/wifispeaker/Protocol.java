package com.example.wifispeaker;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class Protocol {
    static final int PORT = 45777;
    static final int CONTROL_PORT = 45779;
    private static final byte[] MAGIC = "WSPK0001".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONTROL_MAGIC = "WSPKCTRL".getBytes(StandardCharsets.US_ASCII);
    private static final int CONTROL_TYPE_VOLUME = 1;
    static final int PCM_16_BIT = 16;

    private Protocol() {}

    static void writeHeader(DataOutputStream out, int sampleRate, int channelCount, int bitsPerSample) throws IOException {
        out.write(MAGIC);
        out.writeInt(sampleRate);
        out.writeInt(channelCount);
        out.writeInt(bitsPerSample);
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
        if (sampleRate < 8000 || sampleRate > 192000) {
            throw new IOException("Unsupported sample rate: " + sampleRate);
        }
        if (channelCount != 1 && channelCount != 2) {
            throw new IOException("Unsupported channel count: " + channelCount);
        }
        if (bitsPerSample != PCM_16_BIT) {
            throw new IOException("Unsupported PCM depth: " + bitsPerSample);
        }
        return new Header(sampleRate, channelCount, bitsPerSample);
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

    static final class Header {
        final int sampleRate;
        final int channelCount;
        final int bitsPerSample;

        Header(int sampleRate, int channelCount, int bitsPerSample) {
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.bitsPerSample = bitsPerSample;
        }
    }
}
