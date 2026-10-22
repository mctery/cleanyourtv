package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * XmbSpline expands its sines for speed; this checks it against a line-by-line transcription of spline-reverse.js
 * (PlayStation-3-XMB, MIT, Copyright (c) 2025 Mart) that does every call the long way, frame after frame.
 */
public class XmbSplineTest {
    private static final int W = 256, H = 64;

    @Test public void matchesTheOriginalAlgorithm() {
        XmbSpline fast = new XmbSpline();
        Reference slow = new Reference();
        XmbSpline.Settings s = new XmbSpline.Settings();
        float[] a = new float[W * H], b = new float[W * H];
        double worst = 0;
        // Several consecutive frames: the kernel's temporal smoothing carries state from one to the next.
        for (int frame = 0; frame < 40; frame++) {
            float t = 3.7f + frame / 30f;
            fast.write(s, t, a, W, H);
            slow.write(s, t, b, W, H);
            for (int i = 0; i < a.length; i++) worst = Math.max(worst, Math.abs(a[i] - b[i]));
        }
        assertTrue("largest difference " + worst, worst < 1e-4);
    }

    @Test public void heightsAreSmallAndVary() {
        XmbSpline sp = new XmbSpline();
        float[] out = new float[W * H];
        sp.write(new XmbSpline.Settings(), 10f, out, W, H);
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : out) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        assertTrue(max - min > 0.01f);
        assertTrue(Math.abs(min) < 1f && Math.abs(max) < 1f);
        assertEquals(out[0], out[0], 0f);
    }

    /** spline-reverse.js, transcribed without the speed-ups. */
    private static final class Reference {
        static final int TABLE = 0x169, LOOP = 8, STRIDE = 0x400 >> 2, B380 = 0x2200;
        static final int[] STORE = {0x000, 0x100, 0x200, 0x300, 0x010, 0x110, 0x210, 0x310};
        static final float[] NA = {0.39584f, -0.0052389996f, -0.58664495f, 0.189007f};
        static final float[] NB = {-0.003751f, -0.57536095f, 0.161975f, 0.417137f};
        static final int[] R37 = {0x00, 0x11, 0x22, 0x33};
        final byte[] b380 = new byte[B380];
        int lastSeed = Integer.MIN_VALUE;
        final float[] coeffs = new float[TABLE * 16], table = new float[TABLE * 4];
        final float[] packed = new float[LOOP * 32], prev = new float[LOOP * 32], cp = new float[28];

        static double fract(double v) { return v - Math.floor(v); }

        float spline(float[] c, float u) {
            int n = c.length - 3;
            float sv = Math.max(0, Math.min(u * n, n - 1e-6f));
            int seg = (int) Math.floor(sv);
            float t = sv - seg, t2 = t * t, t3 = t2 * t;
            return (1 - 3 * t + 3 * t2 - t3) / 6 * c[seg] + (4 - 6 * t2 + 3 * t3) / 6 * c[seg + 1]
                    + (1 + 3 * t + 3 * t2 - 3 * t3) / 6 * c[seg + 2] + t3 / 6 * c[seg + 3];
        }

        void write(XmbSpline.Settings s, float time, float[] out, int width, int height) {
            if (s.reSyntheticDescriptorSeed != lastSeed) {
                lastSeed = s.reSyntheticDescriptorSeed;
                for (int i = 0; i < B380; i++) {
                    double f = (double) i / B380;
                    double wave = Math.sin((f * 97.0) * (0.3 + s.length)) * 0.5
                            + Math.sin((f * 211.0) * (0.15 + s.tension * 3.0)) * 0.3
                            + Math.sin((f * 17.0) * (0.2 + s.perturbationScale * 2.0)) * 0.2;
                    double noise = fract(Math.sin(i * 13.37 + lastSeed * 0.01) * 43758.5453123) * 2.0 - 1.0;
                    double v = Math.max(0, Math.min(1, (wave * s.reDescriptorStrength + noise * 0.35 + 1.0) * 0.5));
                    b380[i] = (byte) (int) ((float) v * 255);
                }
            }
            double phase = time * s.flowSpeed * s.timeStep * s.reSyntheticDescriptorMotion;
            for (int e = 0; e < TABLE; e++) {
                int base = (e * 0x130) % B380;
                for (int blk = 0; blk < 4; blk++) for (int lane = 0; lane < 4; lane++) {
                    int idx = (base + blk * 0x10 + lane * 4 + (e % 7)) % B380;
                    double c = (b380[idx] & 0xFF) / 255.0 * 2 - 1;
                    coeffs[e * 16 + blk * 4 + lane] = (float) (c * 0.75
                            + Math.sin(e * 0.07 + blk * 0.91 + lane * 1.37 + phase * 0.23) * 0.25);
                }
            }
            float[] b300 = {s.damping, s.length, s.tension, s.spacing * 0.001f};
            for (int i = 0; i < TABLE; i++) for (int lane = 0; lane < 4; lane++) {
                float raw = coeffs[i * 16 + lane] * b300[0] + coeffs[i * 16 + 4 + lane] * b300[1]
                        + coeffs[i * 16 + 8 + lane] * b300[2] + coeffs[i * 16 + 12 + lane] * b300[3];
                float d = Math.max(Math.abs(NB[lane]), 0.05f);
                table[i * 4 + lane] = (float) Math.tanh((raw - NA[lane]) / (NB[lane] < 0 ? -d : d) * s.reNormalizeGain);
            }
            for (int iter = 0; iter < LOOP; iter++) {
                double ph = time * s.reKernelPhaseStep + iter * 0.37;
                float[][] v = new float[4][4];
                for (int lane = 0; lane < 4; lane++) {
                    int w = (R37[lane] + iter * 0x13) & 0xff;
                    int bi = (19 * ((w >> 4) & 0xff) + (w & 0xf)) % TABLE;
                    float x = bi + (float) (Math.sin(ph + lane * 0.77) * s.reIndexJitter * TABLE);
                    x = x % TABLE;
                    if (x < 0) x += TABLE;
                    int i0 = (int) Math.floor(x) % TABLE, i1 = (i0 + 1) % TABLE;
                    float t = (float) (x - Math.floor(x));
                    for (int k = 0; k < 4; k++) v[lane][k] = table[i0 * 4 + k] * (1 - t) + table[i1 * 4 + k] * t;
                }
                float ma = (float) (0.5 + 0.5 * Math.sin(ph * 0.7)), mb = (float) (0.5 + 0.5 * Math.cos(ph * 0.9));
                float[][] st = new float[8][4];
                for (int k = 0; k < 4; k++) {
                    st[0][k] = v[0][k] * (1 - ma) + v[1][k] * ma;
                    st[1][k] = v[1][k] * (1 - mb) + v[2][k] * mb;
                    st[2][k] = v[2][k] * (1 - ma) + v[3][k] * ma;
                    st[3][k] = v[3][k] * (1 - mb) + v[0][k] * mb;
                    st[4][k] = v[1][k] - v[0][k];
                    st[5][k] = v[2][k] - v[1][k];
                    st[6][k] = v[3][k] - v[2][k];
                    st[7][k] = v[0][k] - v[3][k];
                }
                for (int j = 0; j < 8; j++) System.arraycopy(st[j], 0, packed, (iter * 8 + j) * 4, 4);
            }
            float temporal = Math.max(0, Math.min(0.999f, s.reTemporalSmooth));
            for (int i = 0; i < packed.length; i++) {
                packed[i] = prev[i] * temporal + packed[i] * (1 - temporal);
                prev[i] = packed[i];
            }
            double flow = time * s.flowSpeed * s.timeStep;
            int kv = LOOP * 8;
            for (int row = 0; row < height; row++) {
                double z = (double) row / Math.max(1, height - 1) * 2 - 1;
                double rowPhase = flow * 0.25 + z * 1.7;
                for (int i = 0; i < cp.length; i++) {
                    double x = (double) i / Math.max(1, cp.length - 1);
                    double kvf = row * 0.93 + i * 0.61 + flow * 0.35;
                    int k0 = (int) (((long) Math.floor(kvf) % kv + kv) % kv), k1 = (k0 + 1) % kv;
                    float kt = (float) (kvf - Math.floor(kvf));
                    float kx = packed[k0 * 4] * (1 - kt) + packed[k1 * 4] * kt;
                    float ky = packed[k0 * 4 + 1] * (1 - kt) + packed[k1 * 4 + 1] * kt;
                    float kz = packed[k0 * 4 + 2] * (1 - kt) + packed[k1 * 4 + 2] * kt;
                    float kw = packed[k0 * 4 + 3] * (1 - kt) + packed[k1 * 4 + 3] * kt;
                    double re = (kx * 0.45 + ky * 0.25 + kz * 0.2 + kw * 0.1) * s.reKernelGain
                            + Math.sin(rowPhase + x * 6.2) * s.bandAmplitude
                            + Math.cos(z * s.bandSecondaryFreq + x * 4.8 + flow * 0.09) * s.bandSecondaryAmp;
                    double legacy = Math.sin((x * Math.PI * 1.3 + z * 0.8) - flow * s.travelSpeed1) * s.travelAmp1 * s.tension
                            + Math.sin((x * Math.PI * 2.8 - z * 1.2) + flow * s.travelSpeed2) * s.travelAmp2
                            + s.perturbation * s.perturbationScale
                            * Math.sin((x * (4.0 + s.length * 2.0) + z * 4.0 - flow * 0.6) * (s.spacing * 0.01));
                    cp[i] = (float) (re * s.rePipelineBlend + legacy * (1.0 - s.rePipelineBlend));
                }
                for (int xi = 0; xi < width; xi++) out[row * width + xi] = spline(cp, (float) xi / Math.max(1, width - 1));
            }
        }
    }
}
