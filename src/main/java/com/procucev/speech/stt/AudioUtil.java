package com.procucev.speech.stt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class AudioUtil {
    private AudioUtil() {}

    /** Wraps raw 16-bit little-endian mono PCM in a WAV container. */
    public static byte[] pcmToWav(byte[] pcm, int sampleRate) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(pcm.length).put(pcm);
        return b.array();
    }

    /** Normalised RMS energy (0..1) of 16-bit little-endian PCM. */
    public static double rms(byte[] pcm) {
        int n = pcm.length / 2;
        if (n == 0) return 0;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            short s = (short) ((pcm[2 * i + 1] << 8) | (pcm[2 * i] & 0xff));
            double v = s / 32768.0;
            sum += v * v;
        }
        return Math.sqrt(sum / n);
    }
}
