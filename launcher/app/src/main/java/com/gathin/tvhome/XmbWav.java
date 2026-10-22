package com.gathin.tvhome;

/**
 * Brings a quiet menu sound up to full level: a 16-bit PCM WAV file is scaled so its loudest sample sits just
 * under full scale. Menu sounds made for a desktop browser peak 20 to 30 dB below that, and a TV playing them
 * next to films and shows mastered near full scale makes them hard to hear at all. Pure byte work, no Android
 * classes, so it is tested on the JVM (XmbWavTest).
 */
final class XmbWav {
    /** -1 dBFS: the loudest sample after normalising. */
    static final float TARGET_PEAK = 0.891f;
    /** At most 30 dB louder, so a file that is almost silence does not turn its noise into a hiss. */
    static final float MAX_GAIN = 31.6f;

    private XmbWav() {
    }

    /**
     * The file with its samples scaled up to {@link #TARGET_PEAK}, or null when it is not a 16-bit PCM WAV,
     * is silent, or is already that loud (then the original plays as it is). Never makes a file quieter.
     */
    static byte[] normalize(byte[] wav) {
        if (wav == null || wav.length < 44 || !tag(wav, 0, "RIFF") || !tag(wav, 8, "WAVE")) return null;
        int format = -1, bits = -1, dataStart = -1, dataLength = 0;
        int pos = 12;
        while (pos + 8 <= wav.length) {
            int size = le32(wav, pos + 4);
            if (size < 0) return null;
            int body = pos + 8;
            // A chunk running past the end is malformed, except the data chunk, whose size streaming writers
            // leave at a placeholder: that one is read up to the end of the file.
            if (size > wav.length - body && !tag(wav, pos, "data")) return null;
            if (tag(wav, pos, "fmt ") && size >= 16 && body + 16 <= wav.length) {
                format = le16(wav, body);
                bits = le16(wav, body + 14);
            } else if (tag(wav, pos, "data")) {
                dataStart = body;
                dataLength = Math.min(size, wav.length - body) & ~1;
                break;
            }
            pos = body + size + (size & 1);   // chunks are padded to an even length
        }
        if (format != 1 || bits != 16 || dataStart < 0 || dataLength == 0) return null;
        int peak = 0;
        for (int i = dataStart; i < dataStart + dataLength; i += 2) peak = Math.max(peak, Math.abs((short) le16(wav, i)));
        if (peak == 0) return null;
        float gain = Math.min(MAX_GAIN, TARGET_PEAK * 32767f / peak);
        if (gain <= 1.01f) return null;
        byte[] out = wav.clone();
        for (int i = dataStart; i < dataStart + dataLength; i += 2) {
            int v = Math.round((short) le16(wav, i) * gain);
            v = Math.max(-32768, Math.min(32767, v));
            out[i] = (byte) v;
            out[i + 1] = (byte) (v >> 8);
        }
        return out;
    }

    private static boolean tag(byte[] b, int at, String name) {
        if (at + 4 > b.length) return false;
        for (int i = 0; i < 4; i++) if (b[at + i] != name.charAt(i)) return false;
        return true;
    }

    private static int le16(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) | ((b[at + 2] & 0xFF) << 16) | ((b[at + 3] & 0xFF) << 24);
    }
}
