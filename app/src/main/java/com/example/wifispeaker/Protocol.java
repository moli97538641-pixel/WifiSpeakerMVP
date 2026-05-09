package com.example.wifispeaker;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class Protocol {
    static final int PORT = 45777;
    private static final byte[] MAGIC = "WSPK0001".getBytes(StandardCharsets.US_ASCII);
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
