package com.gathin.tvhome;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class XmbWavTest {
    /** A mono 16-bit PCM WAV with these samples, optionally with an extra chunk before "data". */
    private static byte[] wav(short[] samples, boolean listChunk, int bits, int format) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (short v : samples) {
            data.write(v & 0xFF);
            data.write((v >> 8) & 0xFF);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int listSize = listChunk ? 8 + 4 : 0;
        writeTag(out, "RIFF");
        writeLe32(out, 4 + 24 + listSize + 8 + data.size());
        writeTag(out, "WAVE");
        writeTag(out, "fmt ");
        writeLe32(out, 16);
        writeLe16(out, format);
        writeLe16(out, 1);
        writeLe32(out, 32000);
        writeLe32(out, 64000);
        writeLe16(out, 2);
        writeLe16(out, bits);
        if (listChunk) {
            writeTag(out, "LIST");
            writeLe32(out, 4);
            writeTag(out, "INFO");
        }
        writeTag(out, "data");
        writeLe32(out, data.size());
        out.write(data.toByteArray(), 0, data.size());
        return out.toByteArray();
    }

    private static void writeTag(ByteArrayOutputStream o, String t) {
        for (int i = 0; i < 4; i++) o.write(t.charAt(i));
    }

    private static void writeLe16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    private static void writeLe32(ByteArrayOutputStream o, int v) {
        writeLe16(o, v & 0xFFFF);
        writeLe16(o, (v >>> 16) & 0xFFFF);
    }

    private static short sampleAt(byte[] wav, int index) {
        int at = dataStart(wav) + 2 * index;
        return (short) ((wav[at] & 0xFF) | (wav[at + 1] << 8));
    }

    private static int dataStart(byte[] wav) {
        for (int i = 12; i + 4 <= wav.length; i++) {
            if (wav[i] == 'd' && wav[i + 1] == 'a' && wav[i + 2] == 't' && wav[i + 3] == 'a') return i + 8;
        }
        throw new AssertionError("no data chunk");
    }

    @Test public void aQuietFileIsBroughtUpToMinusOneDbfs() {
        // Peak 2700 (about -21.7 dBFS, like the cursor tic).
        byte[] out = XmbWav.normalize(wav(new short[]{0, 2700, -1350, 500}, false, 16, 1));
        assertNotNull(out);
        assertEquals(Math.round(2700 * (0.891f * 32767f / 2700)), sampleAt(out, 1));
        assertEquals(29195, sampleAt(out, 1), 2);
        assertTrue(sampleAt(out, 2) < -14000);
    }

    @Test public void extraChunksBeforeTheDataAreSkipped() {
        byte[] out = XmbWav.normalize(wav(new short[]{1000, -1000}, true, 16, 1));
        assertNotNull(out);
        assertTrue(sampleAt(out, 0) > 29000);
    }

    @Test public void gainStopsAtThirtyDecibels() {
        byte[] out = XmbWav.normalize(wav(new short[]{10, -10}, false, 16, 1));
        assertNotNull(out);
        assertEquals(Math.round(10 * XmbWav.MAX_GAIN), sampleAt(out, 0));
    }

    @Test public void loudSilentAndOtherFormatsAreLeftAlone() {
        assertNull(XmbWav.normalize(wav(new short[]{32000, -100}, false, 16, 1)));   // already loud
        assertNull(XmbWav.normalize(wav(new short[]{0, 0}, false, 16, 1)));           // silence
        assertNull(XmbWav.normalize(wav(new short[]{100, 200}, false, 8, 1)));        // 8-bit
        assertNull(XmbWav.normalize(wav(new short[]{100, 200}, false, 16, 3)));       // float
        assertNull(XmbWav.normalize(new byte[]{1, 2, 3}));
        assertNull(XmbWav.normalize(null));
    }

    @Test public void aChunkRunningPastTheEndIsRefused() {
        byte[] w = wav(new short[]{100, 200}, true, 16, 1);
        int list = 12 + 24;   // after RIFF header and the fmt chunk
        w[list + 4] = (byte) 0xFF;
        w[list + 5] = (byte) 0x7F;
        assertNull(XmbWav.normalize(w));
    }
}
