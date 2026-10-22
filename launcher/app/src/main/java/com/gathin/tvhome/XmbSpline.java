package com.gathin.tvhome;

/**
 * The CPU half of the XMB wave: synthesises PS3-like spline control data into the height texture the wave mesh
 * samples ({@link XmbWave}). A Java port of spline-reverse.js from PlayStation-3-XMB, the renderer KittiwanaNotify's
 * admin uses. The numbers are the same; the work per frame is not: every sine whose angle is a fixed part plus a
 * per-frame part is expanded (sin(a + b) = sin a cos b + cos a sin b) with the fixed parts computed once, which takes
 * a frame from about 15,000 trigonometric calls to about 650 (the browser did all of them on a desktop CPU; a TV box
 * spent most of a core on it).
 *
 * PlayStation-3-XMB, MIT License, Copyright (c) 2025 Mart (https://github.com/linkev/PlayStation-3-XMB).
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions: The above
 * copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT
 * SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */
final class XmbSpline {

    /** The tuning knobs of spline-settings.js, at their defaults. */
    static final class Settings {
        float flowSpeed = 0.18f, tension = 0.12f, damping = 0.0001f, length = 0.306001f, spacing = 407.658f, timeStep = 1f;
        float bandAmplitude = 0.2f, bandSecondaryFreq = 7f, bandSecondaryAmp = 0.025f;
        float travelSpeed1 = 0.25f, travelAmp1 = 0.014f, travelSpeed2 = 0.15f, travelAmp2 = 0.008f;
        float perturbation = 0.0998587f, perturbationScale = 0.07f, waveCosAmp = 0.09f, waveBias = -0.1f;
        float waveHeightScale = 0.5f, waveSoftClip = 0.22f;
        float rePipelineBlend = 0.45f, reDescriptorStrength = 0.7f, reSyntheticDescriptorMotion = 0.65f;
        int reSyntheticDescriptorSeed = 1337;
        float reKernelGain = 0.04f, reNormalizeGain = 0.08f, reKernelPhaseStep = 0.45f, reIndexJitter = 0.006f;
        float reTemporalSmooth = 0.84f;
        float fresnelPower = 4f, fresnelScale = 0.5f, opacity = 0.7f, brightness = 0.98f, zDetailScale = 0.08f;
        float ffdScale1X = 5.67726f, ffdScale1Y = 1.00077f, ffdScale1Z = 1f;
        float ffdScale2X = 2.82755f, ffdScale2Y = 1.27579f, ffdScale2Z = 2.88782f;
        float ffdOffsetX = 0f, ffdOffsetY = -0.469999f, ffdOffsetZ = 0f, ffdYAmp = 0.05f, ffdZAmp = 0.06f;
    }

    private static final int B380_SIZE = 0x2200;
    private static final int TABLE = 0x169;
    private static final int LOOP_ITERS = 8;
    private static final int STRIDE_FLOATS = 0x400 >> 2;
    private static final int[] STORE_OFFSETS = {0x000, 0x100, 0x200, 0x300, 0x010, 0x110, 0x210, 0x310};
    private static final float[] NORM_A = {0.39584f, -0.0052389996f, -0.58664495f, 0.189007f};
    private static final float[] NORM_B = {-0.003751f, -0.57536095f, 0.161975f, 0.417137f};
    private static final int[] R37_WORDS = {0x00, 0x11, 0x22, 0x33};

    private final byte[] b380 = new byte[B380_SIZE];
    private int lastSeed = Integer.MIN_VALUE;
    private final float[] b300 = new float[16];
    private final float[] coeffs = new float[TABLE * 16];
    private final float[] table = new float[TABLE * 4];
    private final float[] kernel = new float[LOOP_ITERS * STRIDE_FLOATS];
    private final float[] kernelPacked = new float[LOOP_ITERS * 8 * 4];
    private final float[] kernelPackedPrev = new float[LOOP_ITERS * 8 * 4];
    private final float[] controlPoints = new float[28];
    private final float[][] v = new float[4][4];
    private final float[][] stores = new float[8][4];
    private final float[] idxFloat = new float[4];
    // decodeCoefficients: the descriptor bytes as -1..1 (fixed per seed) and the harmonic's fixed angle, expanded.
    private final float[] centered = new float[TABLE * 16];
    private final float[] harmSin = new float[TABLE * 16], harmCos = new float[TABLE * 16];
    private boolean harmReady;
    // write(): the per-control-point angles, fixed for given settings.
    private final double[] sinBand = new double[28], cosBand = new double[28], sinSec = new double[28], cosSec = new double[28];
    private final double[] sinT1 = new double[28], cosT1 = new double[28], sinT2 = new double[28], cosT2 = new double[28];
    private final double[] sinPert = new double[28], cosPert = new double[28];
    private boolean cpReady;
    // write(): each output column's spline segment and its four B-spline weights, fixed for a width.
    private int basisWidth = -1;
    private int[] basisSeg;
    private float[] basisW;

    private static float clamp01(double x) {
        return (float) Math.max(0, Math.min(1, x));
    }

    private static double fract(double x) {
        return x - Math.floor(x);
    }

    private static double hash01(double x) {
        return fract(Math.sin(x) * 43758.5453123);
    }

    private static int tableIndexFromWord(int word) {
        int hi = (word >> 4) & 0xff, lo = word & 0xf;
        return (19 * hi + lo) % TABLE;
    }

    private static float wrapIndex(float x, int len) {
        float out = x % len;
        return out < 0 ? out + len : out;
    }

    private void sampleTable(float idx, float[] out) {
        int i0 = (int) Math.floor(idx) % TABLE, i1 = (i0 + 1) % TABLE;
        float t = (float) (idx - Math.floor(idx));
        for (int k = 0; k < 4; k++) out[k] = table[i0 * 4 + k] * (1 - t) + table[i1 * 4 + k] * t;
    }

    private static void mix(float[] a, float[] b, float t, float[] out) {
        for (int k = 0; k < 4; k++) out[k] = a[k] * (1 - t) + b[k] * t;
    }

    private void buildB380(Settings s) {
        if (s.reSyntheticDescriptorSeed == lastSeed) return;
        lastSeed = s.reSyntheticDescriptorSeed;
        harmReady = false;   // the decoded descriptor bytes follow the seed
        for (int i = 0; i < B380_SIZE; i++) {
            double f = (double) i / B380_SIZE;
            double wave = Math.sin((f * 97.0) * (0.3 + s.length)) * 0.5
                    + Math.sin((f * 211.0) * (0.15 + s.tension * 3.0)) * 0.3
                    + Math.sin((f * 17.0) * (0.2 + s.perturbationScale * 2.0)) * 0.2;
            double noise = hash01(i * 13.37 + lastSeed * 0.01) * 2.0 - 1.0;
            b380[i] = (byte) (int) (clamp01((wave * s.reDescriptorStrength + noise * 0.35 + 1.0) * 0.5) * 255);
        }
    }

    private void decodeCoefficients(Settings s, float time) {
        if (!harmReady) {
            for (int entry = 0; entry < TABLE; entry++) {
                int descBase = (entry * 0x130) % B380_SIZE;
                for (int blk = 0; blk < 4; blk++) {
                    for (int lane = 0; lane < 4; lane++) {
                        int k = entry * 16 + blk * 4 + lane;
                        int byteIdx = (descBase + blk * 0x10 + lane * 4 + (entry % 7)) % B380_SIZE;
                        centered[k] = (float) ((b380[byteIdx] & 0xFF) / 255.0 * 2 - 1);
                        double a = entry * 0.07 + blk * 0.91 + lane * 1.37;
                        harmSin[k] = (float) Math.sin(a);
                        harmCos[k] = (float) Math.cos(a);
                    }
                }
            }
            harmReady = true;
        }
        double phase = time * s.flowSpeed * s.timeStep * s.reSyntheticDescriptorMotion;
        float sp = (float) Math.sin(phase * 0.23), cp = (float) Math.cos(phase * 0.23);
        for (int k = 0; k < coeffs.length; k++) {
            // sin(a + phase * 0.23)
            coeffs[k] = centered[k] * 0.75f + (harmSin[k] * cp + harmCos[k] * sp) * 0.25f;
        }
    }

    private void buildTable(Settings s, float time) {
        b300[0] = s.damping; b300[1] = s.length; b300[2] = s.tension; b300[3] = s.spacing * 0.001f;
        buildB380(s);
        decodeCoefficients(s, time);
        for (int i = 0; i < TABLE; i++) {
            for (int lane = 0; lane < 4; lane++) {
                float raw = coeffs[i * 16 + lane] * b300[0] + coeffs[i * 16 + 4 + lane] * b300[1]
                        + coeffs[i * 16 + 8 + lane] * b300[2] + coeffs[i * 16 + 12 + lane] * b300[3];
                float denomAbs = Math.max(Math.abs(NORM_B[lane]), 0.05f);
                float denom = NORM_B[lane] < 0 ? -denomAbs : denomAbs;
                table[i * 4 + lane] = (float) Math.tanh((raw - NORM_A[lane]) / denom * s.reNormalizeGain);
            }
        }
    }

    private void runKernel(Settings s, float time) {
        for (int iter = 0; iter < LOOP_ITERS; iter++) {
            int blockBase = iter * STRIDE_FLOATS;
            double phase = time * s.reKernelPhaseStep + iter * 0.37;
            for (int lane = 0; lane < 4; lane++) {
                int baseIdx = tableIndexFromWord((R37_WORDS[lane] + iter * 0x13) & 0xff);
                float jitter = (float) (Math.sin(phase + lane * 0.77) * s.reIndexJitter * TABLE);
                idxFloat[lane] = wrapIndex(baseIdx + jitter, TABLE);
            }
            for (int k = 0; k < 4; k++) sampleTable(idxFloat[k], v[k]);
            float mixA = (float) (0.5 + 0.5 * Math.sin(phase * 0.7));
            float mixB = (float) (0.5 + 0.5 * Math.cos(phase * 0.9));
            mix(v[0], v[1], mixA, stores[0]);
            mix(v[1], v[2], mixB, stores[1]);
            mix(v[2], v[3], mixA, stores[2]);
            mix(v[3], v[0], mixB, stores[3]);
            for (int k = 0; k < 4; k++) {
                stores[4][k] = v[1][k] - v[0][k];
                stores[5][k] = v[2][k] - v[1][k];
                stores[6][k] = v[3][k] - v[2][k];
                stores[7][k] = v[0][k] - v[3][k];
            }
            for (int st = 0; st < 8; st++) {
                System.arraycopy(stores[st], 0, kernel, blockBase + (STORE_OFFSETS[st] >> 2), 4);
                System.arraycopy(stores[st], 0, kernelPacked, (iter * 8 + st) * 4, 4);
            }
        }
        float temporal = Math.max(0, Math.min(0.999f, s.reTemporalSmooth));
        for (int i = 0; i < kernelPacked.length; i++) {
            float smoothed = kernelPackedPrev[i] * temporal + kernelPacked[i] * (1 - temporal);
            kernelPacked[i] = smoothed;
            kernelPackedPrev[i] = smoothed;
        }
    }

    private void prepareControlPoints(Settings s) {
        int n = controlPoints.length;
        double pert = (4.0 + s.length * 2.0) * (s.spacing * 0.01);
        for (int i = 0; i < n; i++) {
            double x = (double) i / Math.max(1, n - 1);
            sinBand[i] = Math.sin(x * 6.2);
            cosBand[i] = Math.cos(x * 6.2);
            sinSec[i] = Math.sin(x * 4.8);
            cosSec[i] = Math.cos(x * 4.8);
            sinT1[i] = Math.sin(x * Math.PI * 1.3);
            cosT1[i] = Math.cos(x * Math.PI * 1.3);
            sinT2[i] = Math.sin(x * Math.PI * 2.8);
            cosT2[i] = Math.cos(x * Math.PI * 2.8);
            sinPert[i] = Math.sin(x * pert);
            cosPert[i] = Math.cos(x * pert);
        }
        cpReady = true;
    }

    /** Fills out (width x height, row-major) with the wave heights for this time in seconds. */
    void write(Settings s, float time, float[] out, int width, int height) {
        buildTable(s, time);
        runKernel(s, time);
        if (!cpReady) prepareControlPoints(s);
        if (basisWidth != width) prepareBasis(width);
        float[] cp = controlPoints;
        int cpCount = cp.length, kernelVecs = LOOP_ITERS * 8;
        double flow = time * s.flowSpeed * s.timeStep;
        double blend = s.rePipelineBlend, pertAmp = s.perturbation * s.perturbationScale, spacing = s.spacing * 0.01;
        for (int row = 0; row < height; row++) {
            double z = (double) row / Math.max(1, height - 1) * 2 - 1;
            // Each angle below is (fixed per control point) + (this row and frame); see the class comment.
            double band = flow * 0.25 + z * 1.7;
            double sb = Math.sin(band), cb = Math.cos(band);
            double sec = z * s.bandSecondaryFreq + flow * 0.09;
            double ss = Math.sin(sec), cs = Math.cos(sec);
            double t1 = z * 0.8 - flow * s.travelSpeed1;
            double st1 = Math.sin(t1), ct1 = Math.cos(t1);
            double t2 = -z * 1.2 + flow * s.travelSpeed2;
            double st2 = Math.sin(t2), ct2 = Math.cos(t2);
            double pz = (z * 4.0 - flow * 0.6) * spacing;
            double sp = Math.sin(pz), cpz = Math.cos(pz);
            for (int i = 0; i < cpCount; i++) {
                double kvf = row * 0.93 + i * 0.61 + flow * 0.35;
                int kv0 = (int) (((long) Math.floor(kvf) % kernelVecs + kernelVecs) % kernelVecs);
                int kv1 = (kv0 + 1) % kernelVecs;
                float kt = (float) (kvf - Math.floor(kvf));
                int k0 = kv0 * 4, k1 = kv1 * 4;
                float kx = kernelPacked[k0] * (1 - kt) + kernelPacked[k1] * kt;
                float ky = kernelPacked[k0 + 1] * (1 - kt) + kernelPacked[k1 + 1] * kt;
                float kz = kernelPacked[k0 + 2] * (1 - kt) + kernelPacked[k1 + 2] * kt;
                float kw = kernelPacked[k0 + 3] * (1 - kt) + kernelPacked[k1 + 3] * kt;
                double reCore = (kx * 0.45 + ky * 0.25 + kz * 0.2 + kw * 0.1) * s.reKernelGain
                        + (sinBand[i] * cb + cosBand[i] * sb) * s.bandAmplitude          // sin(band + x*6.2)
                        + (cosSec[i] * cs - sinSec[i] * ss) * s.bandSecondaryAmp;       // cos(sec + x*4.8)
                double legacy = (sinT1[i] * ct1 + cosT1[i] * st1) * s.travelAmp1 * s.tension
                        + (sinT2[i] * ct2 + cosT2[i] * st2) * s.travelAmp2
                        + pertAmp * (sinPert[i] * cpz + cosPert[i] * sp);
                cp[i] = (float) (reCore * blend + legacy * (1.0 - blend));
            }
            int rowBase = row * width;
            for (int xi = 0; xi < width; xi++) {
                int seg = basisSeg[xi], w = xi * 4;
                out[rowBase + xi] = basisW[w] * cp[seg] + basisW[w + 1] * cp[seg + 1]
                        + basisW[w + 2] * cp[seg + 2] + basisW[w + 3] * cp[seg + 3];
            }
        }
    }

    /** evalSpline() split in two: where each column falls on the curve depends only on the width, so do it once. */
    private void prepareBasis(int width) {
        int n = controlPoints.length - 3;
        basisSeg = new int[width];
        basisW = new float[width * 4];
        for (int xi = 0; xi < width; xi++) {
            float u = (float) xi / Math.max(1, width - 1);
            float sv = Math.max(0, Math.min(u * n, n - 1e-6f));
            int seg = (int) Math.floor(sv);
            float t = sv - seg, t2 = t * t, t3 = t2 * t;
            basisSeg[xi] = seg;
            basisW[xi * 4] = (1 - 3 * t + 3 * t2 - t3) / 6;
            basisW[xi * 4 + 1] = (4 - 6 * t2 + 3 * t3) / 6;
            basisW[xi * 4 + 2] = (1 + 3 * t + 3 * t2 - 3 * t3) / 6;
            basisW[xi * 4 + 3] = t3 / 6;
        }
        basisWidth = width;
    }
}
