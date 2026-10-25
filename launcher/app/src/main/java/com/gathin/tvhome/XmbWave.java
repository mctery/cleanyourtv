package com.gathin.tvhome;

import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The XMB background: a vertical gradient in the month colour, the PS3 wave (a 100x100 mesh displaced by
 * {@link XmbSpline}, lit by a fresnel term) and drifting sparkles. A port of spline.js and particles.js from
 * PlayStation-3-XMB, the renderer KittiwanaNotify's admin uses, at the same calm "subtle" level that admin runs at.
 * Changed for the TV: the height texture is R16F, which GLES 3.0 filters without OES_texture_float_linear (the
 * Mi Box's Mali-G31 has no such extension, and an unfilterable R32F texture would flatten the wave); the gradient
 * is dithered for the full 1080-line surface; the sparkle size follows the surface height.
 *
 * PlayStation-3-XMB, MIT License, Copyright (c) 2025 Mart (https://github.com/linkev/PlayStation-3-XMB); the full
 * notice is in {@link XmbSpline}.
 */
final class XmbWave implements GLSurfaceView.Renderer {
    private static final String TAG = "TvHome.XmbWave";
    // The browser's 100x100. Every row of the mesh lands on the same band of the screen (that stacking is what draws
    // the wave's many fine lines), so each extra row is another full layer of blended pixels: at 1080 lines on the
    // Mi Box's Mali-G31 a frame took about 17 ms with 100 rows and 22 ms with 160, which started dropping frames.
    private static final int GRID = 100;
    private static final int TEX_W = 256, TEX_H = 64;
    private static final int PARTICLES = 1500;

    private static final String BG_VS = "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 aPos;\n"
            + "out vec2 vUvYDown;\n"
            + "void main() {\n"
            + "  vUvYDown = vec2(aPos.x * 0.5 + 0.5, 1.0 - (aPos.y * 0.5 + 0.5));\n"
            + "  gl_Position = vec4(aPos, 0.0, 1.0);\n"
            + "}\n";
    private static final String BG_FS = "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 vUvYDown;\n"
            + "out vec4 oColor;\n"
            + "uniform vec3 uColorStart;\n"
            + "uniform vec3 uColorEnd;\n"
            // The boot animation (all at no-op values otherwise): how far the colour reaches from the middle band
            // towards the top and bottom edges, through a wide soft edge (99: everywhere), how bright it has come up,
            // and a faint white haze along the middle behind the lines being drawn (uReveal: their front, screen x).
            + "uniform float uBloom, uExpose, uHaze, uReveal;\n"
            + "void main() {\n"
            + "  float u = clamp(vUvYDown.y, 0.0, 1.0);\n"
            + "  float g = u * u * (3.0 - 2.0 * u);\n"
            + "  float dy = abs(u - 0.5) * 2.0;\n"
            + "  float m = (1.0 - smoothstep(uBloom - 0.8, uBloom, dy)) * uExpose;\n"
            + "  float haze = uHaze * exp(-dy * dy * 30.0) * clamp((uReveal - (vUvYDown.x * 2.0 - 1.0)) / 0.5, 0.0, 1.0);\n"
            // A dark gradient over 1080 lines has fewer 8-bit steps than lines and shows bands; half a step of
            // noise per pixel hides them.
            // Interleaved gradient noise: no sine per pixel, which over two million pixels a frame a small GPU feels.
            + "  float n = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) - 0.5;\n"
            + "  oColor = vec4((mix(uColorStart, uColorEnd, g) + n / 255.0) * m + haze, 1.0);\n"
            + "}\n";
    private static final String WAVE_VS = "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 aPos;\n"
            + "uniform sampler2D uSplineTex;\n"
            + "uniform float uTime, flowSpeed, tension, damping, uLength, spacing, perturbation, perturbationScale;\n"
            + "uniform float timeStep, waveCosAmp, waveBias, waveHeightScale, waveSoftClip, ffdYAmp, ffdZAmp, zDetailScale;\n"
            + "uniform vec3 ffdScale1, ffdScale2, ffdOffset;\n"
            + "out vec3 vPos;\n"
            // How far back this row's head runs in the boot animation, 0 to 1, varying smoothly from row to row.
            + "out float vLag;\n"
            + "void main() {\n"
            + "  vLag = 0.5 + 0.3 * sin(aPos.y * 9.0 + 0.7) + 0.2 * sin(aPos.y * 23.0 + 2.1);\n"
            + "  vec3 p = vec3(aPos.x, 0.0, aPos.y);\n"
            + "  vec2 uv = (aPos + 1.0) * 0.5;\n"
            + "  p.y = texture(uSplineTex, uv).r;\n"
            + "  vec3 ffd1 = p * ffdScale1 + ffdOffset;\n"
            + "  vec3 ffd2 = p * ffdScale2 + ffdOffset;\n"
            + "  p.y += sin(ffd1.x + uTime * flowSpeed) * ffdYAmp;\n"
            + "  p.z += cos(ffd2.z + uTime * flowSpeed) * ffdZAmp;\n"
            + "  float baseWave = cos(p.x * 2.0 - uTime * 0.5 * timeStep) * waveCosAmp + waveBias;\n"
            + "  baseWave *= (1.0 - damping);\n"
            + "  baseWave += tension * sin(p.x * uLength + uTime * flowSpeed * timeStep * 0.25);\n"
            + "  float structured = perturbation * perturbationScale * (\n"
            + "    sin((p.x * uLength * 6.0 + p.z * 0.5) * spacing * 0.01 + uTime * flowSpeed * timeStep * 0.7) * 0.5 +\n"
            + "    sin((p.x * uLength * 10.0 - p.z * 0.8) * spacing * 0.005 - uTime * flowSpeed * timeStep * 0.35) * 0.25);\n"
            + "  float totalWave = (baseWave + structured) * waveHeightScale;\n"
            + "  totalWave = waveSoftClip * tanh(totalWave / max(waveSoftClip, 1e-4));\n"
            + "  p.y -= totalWave;\n"
            + "  vec2 uv2 = uv;\n"
            + "  uv2.x = fract(uv2.x - uTime * flowSpeed * 0.04 * timeStep);\n"
            + "  p.z -= texture(uSplineTex, uv2).r * zDetailScale;\n"
            + "  gl_Position = vec4(p, 1.0);\n"
            + "  vPos = p;\n"
            + "}\n";
    private static final String WAVE_FS = "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec3 vPos;\n"
            + "in float vLag;\n"
            + "out vec4 oColor;\n"
            + "uniform float opacity, brightness, fresnelPower, fresnelScale;\n"
            // The boot animation: the lines are drawn as far as their front (uReveal, screen x; each row's head
            // uSpread * vLag behind it; 99 when no animation runs), uBoost times as bright, a light at each head
            // (uTip) and a trail behind it that cools down (uTrail).
            + "uniform float uReveal, uSpread, uBoost, uTrail, uTip;\n"
            // How bright the rows of this draw are: 1, but for the boot animation's second half of them, fading in.
            + "uniform float uRows;\n"
            + "void main() {\n"
            + "  vec3 N = normalize(cross(dFdx(vPos), dFdy(vPos)));\n"
            + "  float F = fresnelScale * pow(1.0 + dot(vec3(0.0, 0.0, -1.0), N), fresnelPower);\n"
            + "  float a = opacity * brightness;\n"
            // Plain arithmetic, no exp(): at 60 frames a second every fragment of the hundred overlapping rows runs it.
            + "  if (uReveal < 50.0) {\n"
            + "    float d = uReveal - uSpread * vLag - vPos.x;\n"
            + "    if (d < -0.02) discard;\n"   // ahead of its head: not drawn yet, nothing to blend
            + "    float shown = clamp(d / 0.04 + 0.5, 0.0, 1.0);\n"
            + "    float t = clamp(1.0 - abs(d) / 0.06, 0.0, 1.0);\n"
            + "    float tip = uTip * t * t * (3.0 - 2.0 * t);\n"
            + "    float w = clamp(1.0 - max(d, 0.0) * 1.4, 0.0, 1.0);\n"
            + "    a = (a * uBoost * (1.0 + uTrail * w * w) + tip) * shown;\n"
            + "  }\n"
            + "  oColor = vec4(vec3(1.0), F * a * uRows);\n"
            + "}\n";
    private static final String PT_VS = "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec3 aSeed;\n"
            + "uniform float uTime, flowSpeed, ratio, ptSizeBase, ptSizeVar;\n"
            + "out float vAlpha;\n"
            + "out float vX;\n"
            + "void main() {\n"
            + "  gl_PointSize = aSeed.z * ptSizeVar + ptSizeBase;\n"
            + "  float time = uTime * flowSpeed;\n"
            + "  float x = fract(time * (aSeed.x - 0.5) / 15.0 + aSeed.y * 50.0) * 2.0 - 1.0;\n"
            + "  float y = sin(sign(aSeed.y) * time * (aSeed.y + 1.5) / 4.0 + aSeed.x * 100.0)\n"
            + "          / ((6.0 - aSeed.x * 4.0 * aSeed.y) / ratio);\n"
            + "  float opVar = mix(sin(time * (aSeed.x + 0.5) * 12.0 + aSeed.y * 10.0),\n"
            + "                    sin(time * (aSeed.y + 1.5) * 6.0 + aSeed.x * 4.0), y * 0.5 + 0.5) * aSeed.x + aSeed.y;\n"
            + "  vAlpha = opVar * opVar * (1.0 - fract(aSeed.x + time * 0.00285));\n"
            + "  vX = x;\n"
            + "  gl_Position = vec4(x, y, 0.0, 1.0);\n"
            + "}\n";
    private static final String PT_FS = "#version 300 es\n"
            + "precision highp float;\n"
            + "in float vAlpha;\n"
            + "in float vX;\n"
            + "out vec4 oColor;\n"
            + "uniform float particleOpacity;\n"
            // The boot animation: sparkles come up behind the lines' front, a little brighter (uBoost) at first.
            + "uniform float uReveal, uBoost;\n"
            + "void main() {\n"
            + "  vec2 c = gl_PointCoord * 2.0 - 1.0;\n"
            + "  float d = dot(c, c);\n"
            + "  if (d > 1.0) discard;\n"
            + "  float sparkle = (1.0 - d) * (1.0 - d);\n"
            + "  float shown = clamp((uReveal - 0.3 - vX) / 0.4, 0.0, 1.0);\n"
            + "  oColor = vec4(vec3(vAlpha * particleOpacity * sparkle * uBoost * shown), 1.0);\n"
            + "}\n";

    private final XmbSpline spline = new XmbSpline();
    private final XmbSpline.Settings s = new XmbSpline.Settings();
    private final float particleOpacity;
    private final float[] heights = new float[TEX_W * TEX_H];
    /**
     * The heights as half floats, the texture's own format. Handed over as floats, the driver converted them on
     * this thread at every upload: 3 to 5 ms of CPU each time on the Mi Box S, more than working out the heights.
     */
    private final short[] halves = new short[TEX_W * TEX_H];
    private final ShortBuffer heightBuf = ByteBuffer.allocateDirect(TEX_W * TEX_H * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer();

    /** 0xRRGGBB, written by the UI thread, and how fast to go to it. */
    private volatile int color = 0x2559B3;
    private volatile float colorTau;
    /** The colour drawn, on its way to color (GL thread); red below 0 before the first frame. */
    private float drawnR = -1f, drawnG, drawnB;
    private boolean ok;
    private int width = 1, height = 1;
    private int bgProg, waveProg, ptProg, bgVao, gridVao, ptVao, gridIndexCount, splineTex;
    /** The grid's rows in two halves, every other row each, for the boot animation: one VAO and count per half. */
    private final int[] halfVao = new int[2], halfCount = new int[2];
    private float tSpline, tPart = new Random().nextFloat() * 1000f;
    private long lastNanos;
    private int frames;
    private int uBgStart, uBgEnd, uWaveTime, uPtTime, uBgBloom, uBgExpose, uBgHaze, uBgReveal, uWaveReveal, uWaveSpread,
            uWaveBoost, uWaveTrail, uWaveTip, uWaveRows, uPtReveal, uPtBoost;

    /**
     * The wave's GL context at low priority, where the GPU has priorities (EGL_IMG_context_priority, the Mi Box's Mali
     * has it): the menu's frames, drawn by Android at normal priority, go first. The wave has a whole 33 ms for each
     * of its frames, the menu 16.7 ms. Measured on the Mi Box S while the menu moved: the menu's frames were done 7 ms
     * after their vsync instead of 13 (Android's own count of frames over 16.7 ms halved), so a key shows sooner;
     * what the display showed was 60 frames a second either way. Without the extension, an ordinary GLES 3 context.
     */
    static final class ContextFactory implements GLSurfaceView.EGLContextFactory {
        private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098, EGL_CONTEXT_PRIORITY_LEVEL_IMG = 0x3100,
                EGL_CONTEXT_PRIORITY_LOW_IMG = 0x3103;

        @Override
        public javax.microedition.khronos.egl.EGLContext createContext(javax.microedition.khronos.egl.EGL10 egl,
                javax.microedition.khronos.egl.EGLDisplay display, EGLConfig config) {
            String ext = egl.eglQueryString(display, javax.microedition.khronos.egl.EGL10.EGL_EXTENSIONS);
            if (ext != null && ext.contains("EGL_IMG_context_priority")) {
                int[] low = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_LOW_IMG,
                        javax.microedition.khronos.egl.EGL10.EGL_NONE};
                javax.microedition.khronos.egl.EGLContext c = egl.eglCreateContext(display, config,
                        javax.microedition.khronos.egl.EGL10.EGL_NO_CONTEXT, low);
                if (c != null && c != javax.microedition.khronos.egl.EGL10.EGL_NO_CONTEXT) {
                    Log.i(TAG, "wave context at low GPU priority");
                    return c;
                }
            }
            int[] plain = {EGL_CONTEXT_CLIENT_VERSION, 3, javax.microedition.khronos.egl.EGL10.EGL_NONE};
            return egl.eglCreateContext(display, config, javax.microedition.khronos.egl.EGL10.EGL_NO_CONTEXT, plain);
        }

        @Override
        public void destroyContext(javax.microedition.khronos.egl.EGL10 egl,
                                   javax.microedition.khronos.egl.EGLDisplay display,
                                   javax.microedition.khronos.egl.EGLContext context) {
            egl.eglDestroyContext(display, context);
        }
    }

    /** RGB888 with 4x multisampling when the GPU offers it (cheap on a tile-based Mali), else the first RGB888. */
    static final class ConfigChooser implements GLSurfaceView.EGLConfigChooser {
        private static final int EGL_OPENGL_ES3_BIT = 0x40;

        @Override
        public EGLConfig chooseConfig(javax.microedition.khronos.egl.EGL10 egl, javax.microedition.khronos.egl.EGLDisplay display) {
            EGLConfig c = pick(egl, display, 4);
            return c != null ? c : pick(egl, display, 0);
        }

        private static EGLConfig pick(javax.microedition.khronos.egl.EGL10 egl,
                                      javax.microedition.khronos.egl.EGLDisplay display, int samples) {
            int[] attrs = {
                    javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8,
                    javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                    javax.microedition.khronos.egl.EGL10.EGL_SAMPLE_BUFFERS, samples > 0 ? 1 : 0,
                    javax.microedition.khronos.egl.EGL10.EGL_SAMPLES, samples,
                    javax.microedition.khronos.egl.EGL10.EGL_NONE};
            int[] count = new int[1];
            EGLConfig[] configs = new EGLConfig[1];
            if (!egl.eglChooseConfig(display, attrs, configs, 1, count) || count[0] == 0) return null;
            return configs[0];
        }
    }

    XmbWave() {
        // KittiwanaNotify's background runs at the "subtle" level: half the opacity, a little less bright.
        s.opacity *= 0.5f;
        s.brightness *= 0.85f;
        particleOpacity = 0.75f * 0.5f;
    }

    void setColor(int rgb) {
        setColor(rgb, 0f);
    }

    /**
     * The colour to go to, eased in over about three times tau seconds rather than switched: the hour's and the
     * month's changes drift in slowly, a colour being picked follows quickly; 0 at once.
     */
    void setColor(int rgb, float tau) {
        colorTau = tau;
        color = rgb & 0xFFFFFF;
    }

    /** The next frame continues from now: a pause (app in front, idle) must not jump the wave. */
    void resetClock() {
        lastNanos = 0;
        // A float clock loses steps after days of drawing (the wave would stutter, then freeze). Wrapped here, where
        // Home has just come back and a new shape is not noticed.
        if (tSpline > 7200f) tSpline = 0f;
        if (tPart > 7200f) tPart = 0f;
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        try {
            bgProg = link(BG_VS, BG_FS);
            waveProg = link(WAVE_VS, WAVE_FS);
            ptProg = link(PT_VS, PT_FS);
            bgVao = quad(bgProg);
            gridVao = grid(waveProg);
            ptVao = particles(ptProg);
            splineTex = texture();
            setConstantUniforms();
            String ext = GLES30.glGetString(GLES30.GL_EXTENSIONS);
            timers = ext != null && ext.contains("GL_EXT_disjoint_timer_query");
            if (timers) GLES30.glGenQueries(queries.length, queries, 0);
            java.util.Arrays.fill(queued, false);
            frames = 0;
            ok = true;
        } catch (RuntimeException e) {
            // A GPU that refuses the shaders still gets the plain month gradient from the clear colour.
            Log.w(TAG, "wave disabled: " + e.getMessage());
            ok = false;
        }
        lastNanos = 0;
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
        GLES30.glViewport(0, 0, width, height);
        if (!ok) return;
        // Sparkle size is in surface pixels: scale it from the 1080-line canvas it was tuned on.
        float px = height / 1080f, aspect = (float) width / height;
        GLES30.glUseProgram(ptProg);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(ptProg, "ratio"), Math.max(1f, Math.min(aspect, 2f)) * 0.375f);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(ptProg, "ptSizeBase"), 2.6f * px);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(ptProg, "ptSizeVar"), 1.5f * px);
    }

    /** Everything but the colour and the clocks is fixed, and a program keeps its uniforms: set them once. */
    private void setConstantUniforms() {
        uBgStart = GLES30.glGetUniformLocation(bgProg, "uColorStart");
        uBgEnd = GLES30.glGetUniformLocation(bgProg, "uColorEnd");
        uWaveTime = GLES30.glGetUniformLocation(waveProg, "uTime");
        uPtTime = GLES30.glGetUniformLocation(ptProg, "uTime");
        uBgBloom = GLES30.glGetUniformLocation(bgProg, "uBloom");
        uBgExpose = GLES30.glGetUniformLocation(bgProg, "uExpose");
        uBgHaze = GLES30.glGetUniformLocation(bgProg, "uHaze");
        uBgReveal = GLES30.glGetUniformLocation(bgProg, "uReveal");
        uWaveReveal = GLES30.glGetUniformLocation(waveProg, "uReveal");
        uWaveSpread = GLES30.glGetUniformLocation(waveProg, "uSpread");
        uWaveBoost = GLES30.glGetUniformLocation(waveProg, "uBoost");
        uWaveTrail = GLES30.glGetUniformLocation(waveProg, "uTrail");
        uWaveTip = GLES30.glGetUniformLocation(waveProg, "uTip");
        uWaveRows = GLES30.glGetUniformLocation(waveProg, "uRows");
        uPtReveal = GLES30.glGetUniformLocation(ptProg, "uReveal");
        uPtBoost = GLES30.glGetUniformLocation(ptProg, "uBoost");
        GLES30.glUseProgram(waveProg);
        set1i(waveProg, "uSplineTex", 0);
        set1f(waveProg, "flowSpeed", s.flowSpeed);
        set1f(waveProg, "tension", s.tension);
        set1f(waveProg, "damping", s.damping);
        set1f(waveProg, "uLength", s.length);   // renamed: "length" is a GLSL built-in
        set1f(waveProg, "spacing", s.spacing);
        set1f(waveProg, "perturbation", s.perturbation);
        set1f(waveProg, "perturbationScale", s.perturbationScale);
        set1f(waveProg, "timeStep", s.timeStep);
        set1f(waveProg, "waveCosAmp", s.waveCosAmp);
        set1f(waveProg, "waveBias", s.waveBias);
        set1f(waveProg, "waveHeightScale", s.waveHeightScale);
        set1f(waveProg, "waveSoftClip", s.waveSoftClip);
        GLES30.glUniform3f(GLES30.glGetUniformLocation(waveProg, "ffdScale1"), s.ffdScale1X, s.ffdScale1Y, s.ffdScale1Z);
        GLES30.glUniform3f(GLES30.glGetUniformLocation(waveProg, "ffdScale2"), s.ffdScale2X, s.ffdScale2Y, s.ffdScale2Z);
        GLES30.glUniform3f(GLES30.glGetUniformLocation(waveProg, "ffdOffset"), s.ffdOffsetX, s.ffdOffsetY, s.ffdOffsetZ);
        set1f(waveProg, "ffdYAmp", s.ffdYAmp);
        set1f(waveProg, "ffdZAmp", s.ffdZAmp);
        set1f(waveProg, "zDetailScale", s.zDetailScale);
        set1f(waveProg, "opacity", s.opacity);
        set1f(waveProg, "brightness", s.brightness);
        set1f(waveProg, "fresnelPower", s.fresnelPower);
        set1f(waveProg, "fresnelScale", s.fresnelScale);
        GLES30.glUseProgram(ptProg);
        set1f(ptProg, "flowSpeed", 0.18f);
        set1f(ptProg, "particleOpacity", particleOpacity);
    }

    private static void set1f(int program, String name, float v) {
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, name), v);
    }

    private static void set1i(int program, String name, int v) {
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, name), v);
    }

    // ---- the boot animation ---------------------------------------------------------------------------------

    /** Told on the main thread as the boot animation reaches its marks. */
    interface BootListener {
        /** LINES: the lines start to be drawn; MENU: time for the menu; DONE: the wave is its own self again. */
        void bootMark(int mark);
    }

    static final int LINES = 1, MENU = 2, DONE = 3;
    private final XmbBoot boot = new XmbBoot();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile BootListener bootListener;
    /** Set by the UI thread: start the animation at the next frame / run what is left of it five times as fast. */
    private volatile boolean bootRequested, bootSkip, bootCancel;
    /** Black until the boot animation starts (Home hidden before the TV went to standby). */
    private volatile boolean holdBlack;
    /**
     * The boot animation wants every frame the display shows (Home's pacing reads it): while its lines sweep across.
     * Drawn then with every other row of the wave, about 9 ms of the GPU instead of 16 to 18 at its brightest, so it
     * keeps to 60 frames a second; the other rows fade in as the lines meet the right edge, at 30 again.
     */
    volatile boolean fullRate;
    /** Seconds into the boot animation, or -1 when none runs (only the GL thread touches it). */
    private float bootClock = -1f;
    private int bootMarks;

    /**
     * Plays the boot animation with the wave itself: black, then its lines drawn in white from the left edge to the
     * right, each row's head a little behind the next with its own light and a trail, sparkles left behind, then the
     * colour spreading from the middle band to the top and bottom while the lines settle into the wave as it always
     * is. Timed from its first frame (XmbBoot, XmbStyle.BOOT_*); Home draws it at the full frame rate.
     */
    void startBoot(BootListener listener) {
        bootListener = listener;
        fullRate = true;
        bootSkip = false;
        bootCancel = false;
        bootRequested = true;
    }

    /** A key: what is left of the animation runs five times as fast. */
    void skipBoot() {
        bootSkip = true;
    }

    /** No animation now: the wave as it always is, at once (Home went behind an app). */
    void endBoot() {
        bootRequested = false;
        fullRate = false;
        holdBlack = false;
        bootCancel = true;
    }

    /** Black from the next frame until startBoot (or endBoot). */
    void holdBlack() {
        holdBlack = true;
    }

    private void mark(int which) {
        BootListener l = bootListener;
        if (l != null) main.post(() -> l.bootMark(which));
    }

    /** Frames drawn so far (the diagnostics panel shows the rate). */
    volatile int framesDrawn;
    /**
     * For the diagnostics panel, while measuring: the CPU time of a frame on this thread and of the heights in it,
     * and the GPU's time for the whole frame (a timer query, where the GPU has them: GL_EXT_disjoint_timer_query;
     * -1 elsewhere), each averaged over the last second. One query round the whole frame: on a tile-based GPU such
     * as the Mali, a query between two draws splits the frame in two and times that, not the draws.
     */
    volatile boolean measuring;
    volatile float cpuMs, splineMs, gpuMs = -1;
    private long cpuSum, splineSum, gpuSum;
    private int measured, gpuFrames;
    private static final int GL_TIME_ELAPSED = 0x88BF, GL_GPU_DISJOINT = 0x8FBB, RING = 4;
    private boolean timers;
    private final int[] queries = new int[RING];
    private final boolean[] queued = new boolean[RING];
    private int queryFrame;

    @Override
    public void onDrawFrame(GL10 unused) {
        long now = System.nanoTime();
        long cpu0 = measuring ? android.os.Debug.threadCpuTimeNanos() : 0, splineNanos = 0;
        float dt = lastNanos == 0 ? 0.016f : (now - lastNanos) / 1e9f;
        framesDrawn++;
        // A frame far later than the next one should have been: what the eye sees as the wave jumping.
        if (lastNanos != 0 && dt > 0.12f && dt < 5f) XmbDebug.log(R.string.xmb_log_wave_late, (int) (dt * 1000 - 33));
        lastNanos = now;
        if (dt > 0.1f || dt < 0) dt = 0.016f;   // clamp across pauses
        tSpline += dt;
        tPart += dt;

        // The boot animation's controls for this frame; no-op values (everything shown, as bright as ever) without one.
        float reveal = 99f, spread = 0f, boost = 1f, trail = 0f, tip = 0f, spark = 1f, bloom = 99f, expose = 1f, haze = 0f,
                rows2 = 1f;
        if (bootCancel) {
            bootCancel = false;
            if (bootClock >= 0) mark(DONE);
            bootClock = -1f;
        }
        if (bootRequested) {
            bootRequested = false;
            holdBlack = false;
            bootClock = 0f;   // this frame is its start
            bootMarks = 0;
        } else if (bootClock >= 0) {
            bootClock += bootSkip ? dt * 5f : dt;
        }
        if (bootClock >= 0) {
            float ms = bootClock * 1000f;
            if (ms >= XmbStyle.BOOT_LINES_AT_MS && (bootMarks & 1) == 0) {
                bootMarks |= 1;
                mark(LINES);
            }
            if (ms >= XmbStyle.BOOT_MENU_AT_MS && (bootMarks & 2) == 0) {
                bootMarks |= 2;
                mark(MENU);
            }
            boot.at(ms);
            fullRate = boot.fast;
            if (boot.done) {
                bootClock = -1f;
                mark(DONE);
            } else {
                rows2 = boot.rows2;
                reveal = boot.reveal;
                spread = boot.spread;
                boost = boot.boost;
                trail = boot.trail;
                tip = boot.tip;
                spark = boot.spark;
                bloom = boot.bloom;
                expose = boot.expose;
                haze = boot.haze;
            }
        } else if (holdBlack) {
            reveal = -9f;
            bloom = 0f;
            expose = 0f;
        }

        int c = color;
        float tr = ((c >> 16) & 0xFF) / 255f, tg = ((c >> 8) & 0xFF) / 255f, tb = (c & 0xFF) / 255f, tau = colorTau;
        if (drawnR < 0f || tau <= 0f) {
            drawnR = tr;
            drawnG = tg;
            drawnB = tb;
        } else {
            float k = 1f - (float) Math.exp(-dt / tau);
            drawnR += (tr - drawnR) * k;
            drawnG += (tg - drawnG) * k;
            drawnB += (tb - drawnB) * k;
        }
        float r = drawnR, g = drawnG, b = drawnB;
        if (!ok) {
            GLES30.glClearColor(r * 0.3f, g * 0.3f, b * 0.3f, 1f);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            return;
        }
        // The heights change slowly: recompute them on every other frame (15 a second); the shader's own time terms
        // still move every frame, so the motion stays smooth while the CPU does half the work.
        boolean newHeights = (frames++ & 1) == 0;
        if (newHeights) {
            long t0 = measuring ? android.os.Debug.threadCpuTimeNanos() : 0;
            spline.write(s, tSpline, heights, TEX_W, TEX_H);
            for (int i = 0; i < halves.length; i++) halves[i] = half(heights[i]);
            heightBuf.position(0);
            heightBuf.put(halves).position(0);
            if (measuring) splineNanos = android.os.Debug.threadCpuTimeNanos() - t0;
        }

        boolean timed = measuring && timers;
        int slot = queryFrame % RING;
        if (timed) GLES30.glBeginQuery(GL_TIME_ELAPSED, queries[slot]);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glUseProgram(bgProg);
        GLES30.glUniform3f(uBgStart, r * 0.09f, g * 0.09f, b * 0.09f * 1.2f);
        GLES30.glUniform3f(uBgEnd, r * 0.62f, g * 0.62f, b * 0.62f);
        GLES30.glUniform1f(uBgBloom, bloom);
        GLES30.glUniform1f(uBgExpose, expose);
        GLES30.glUniform1f(uBgHaze, haze);
        GLES30.glUniform1f(uBgReveal, reveal);
        GLES30.glBindVertexArray(bgVao);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUseProgram(waveProg);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, splineTex);
        if (newHeights) {
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, TEX_W, TEX_H, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, heightBuf);
        }
        GLES30.glUniform1f(uWaveTime, tSpline);   // the rest were set once, in onSurfaceCreated
        GLES30.glUniform1f(uWaveReveal, reveal);
        GLES30.glUniform1f(uWaveSpread, spread);
        GLES30.glUniform1f(uWaveBoost, boost);
        GLES30.glUniform1f(uWaveTrail, trail);
        GLES30.glUniform1f(uWaveTip, tip);
        if (rows2 >= 1f) {
            GLES30.glUniform1f(uWaveRows, 1f);
            GLES30.glBindVertexArray(gridVao);
            GLES30.glDrawElements(GLES30.GL_TRIANGLE_STRIP, gridIndexCount, GLES30.GL_UNSIGNED_SHORT, 0);
        } else {
            // The boot animation: every other row, the rest fading in (blending white over white is the same in any
            // order, so the halves drawn one after the other look as the whole grid does).
            GLES30.glUniform1f(uWaveRows, 1f);
            GLES30.glBindVertexArray(halfVao[0]);
            GLES30.glDrawElements(GLES30.GL_TRIANGLE_STRIP, halfCount[0], GLES30.GL_UNSIGNED_SHORT, 0);
            if (rows2 > 0f) {
                GLES30.glUniform1f(uWaveRows, rows2);
                GLES30.glBindVertexArray(halfVao[1]);
                GLES30.glDrawElements(GLES30.GL_TRIANGLE_STRIP, halfCount[1], GLES30.GL_UNSIGNED_SHORT, 0);
            }
        }

        // Sparkles add light.
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE);
        GLES30.glUseProgram(ptProg);
        GLES30.glUniform1f(uPtTime, tPart);
        GLES30.glUniform1f(uPtReveal, reveal);
        GLES30.glUniform1f(uPtBoost, spark);
        GLES30.glBindVertexArray(ptVao);
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, PARTICLES);
        GLES30.glBindVertexArray(0);
        if (timed) {
            GLES30.glEndQuery(GL_TIME_ELAPSED);
            queued[slot] = true;
            queryFrame++;
            collect(queryFrame % RING);   // the oldest frame in the ring, RING - 1 frames back: done by now
        }
        if (measuring) measure(cpu0, splineNanos);
    }

    /** Adds a finished frame's GPU time, if its result is in (never waits for it). */
    private void collect(int slot) {
        if (!queued[slot]) return;
        int[] v = new int[1];
        GLES30.glGetQueryObjectuiv(queries[slot], GLES30.GL_QUERY_RESULT_AVAILABLE, v, 0);
        if (v[0] == 0) return;
        queued[slot] = false;
        GLES30.glGetIntegerv(GL_GPU_DISJOINT, v, 0);
        if (v[0] != 0) return;   // the GPU's clock jumped (a frequency change): this frame's time means nothing
        GLES30.glGetQueryObjectuiv(queries[slot], GLES30.GL_QUERY_RESULT, v, 0);
        gpuSum += v[0] & 0xFFFFFFFFL;
        gpuFrames++;
    }

    /** The frame's CPU time on this thread (not the time it waited for a buffer) and the heights', averaged a second. */
    private void measure(long cpu0, long splineNanos) {
        cpuSum += android.os.Debug.threadCpuTimeNanos() - cpu0;
        splineSum += splineNanos;
        if (++measured < 30) return;
        cpuMs = cpuSum / 1e6f / measured;
        splineMs = splineSum / 1e6f / measured;
        cpuSum = splineSum = 0;
        measured = 0;
        if (gpuFrames > 0) gpuMs = gpuSum / 1e6f / gpuFrames;
        gpuSum = 0;
        gpuFrames = 0;
    }

    /**
     * A float as a half float (IEEE binary16), rounded to nearest; values too small for a normal half become 0 and
     * values too large the largest. Written out for the heights (16384 of them, 15 times a second): Android's
     * Half.toHalf took about 1 ms for them on the Mi Box S, this about a fifth of that.
     */
    static short half(float f) {
        int bits = Float.floatToRawIntBits(f);
        int sign = (bits >>> 16) & 0x8000;
        int exp = ((bits >>> 23) & 0xFF) - 112;          // rebiased: 127 - 15
        if (exp <= 0) return (short) sign;               // below 2^-14, or zero: 0 (a wave height that small is flat)
        if (exp >= 31) return (short) (sign | 0x7BFF);   // the largest half (no infinity or NaN comes from the spline)
        int h = sign | (exp << 10) | ((bits & 0x7FFFFF) >> 13);
        if ((bits & 0x1000) != 0 && ((bits & 0x2FFF) != 0)) h++;   // round half to even, a carry may lift the exponent
        return (short) Math.min(h & 0xFFFF, sign | 0x7BFF);
    }

    // ---- setup ---------------------------------------------------------------------------------------

    private static int compile(String src, int type) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, src);
        GLES30.glCompileShader(shader);
        int[] status = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String info = GLES30.glGetShaderInfoLog(shader);
            GLES30.glDeleteShader(shader);
            throw new RuntimeException("shader: " + info);
        }
        return shader;
    }

    private static int link(String vs, String fs) {
        int program = GLES30.glCreateProgram();
        int v = compile(vs, GLES30.GL_VERTEX_SHADER), f = compile(fs, GLES30.GL_FRAGMENT_SHADER);
        GLES30.glAttachShader(program, v);
        GLES30.glAttachShader(program, f);
        GLES30.glLinkProgram(program);
        GLES30.glDeleteShader(v);
        GLES30.glDeleteShader(f);
        int[] status = new int[1];
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            String info = GLES30.glGetProgramInfoLog(program);
            GLES30.glDeleteProgram(program);
            throw new RuntimeException("link: " + info);
        }
        return program;
    }

    private static FloatBuffer floats(float[] data) {
        FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data).position(0);
        return fb;
    }

    private static int attrib(int program, String name) {
        int loc = GLES30.glGetAttribLocation(program, name);
        if (loc < 0) throw new RuntimeException("missing attribute " + name);
        return loc;
    }

    private static int newVao() {
        int[] id = new int[1];
        GLES30.glGenVertexArrays(1, id, 0);
        GLES30.glBindVertexArray(id[0]);
        return id[0];
    }

    private static void arrayBuffer(float[] data) {
        int[] id = new int[1];
        GLES30.glGenBuffers(1, id, 0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id[0]);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.length * 4, floats(data), GLES30.GL_STATIC_DRAW);
    }

    private static int quad(int program) {
        int vao = newVao();
        arrayBuffer(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
        int loc = attrib(program, "aPos");
        GLES30.glEnableVertexAttribArray(loc);
        GLES30.glVertexAttribPointer(loc, 2, GLES30.GL_FLOAT, false, 0, 0);
        GLES30.glBindVertexArray(0);
        return vao;
    }

    /** The wave mesh: GRID x GRID vertices as triangle strips joined by degenerate triangles. */
    private int grid(int program) {
        int strips = GRID - 1, perStrip = GRID * 2;
        float[] verts = new float[strips * perStrip * 2];
        int vi = 0;
        for (int y = 0; y < strips; y++) {
            for (int x = 0; x < GRID; x++) {
                float fx = (float) x / (GRID - 1) * 2 - 1;
                verts[vi++] = fx;
                verts[vi++] = (float) (y + 1) / (GRID - 1) * 2 - 1;
                verts[vi++] = fx;
                verts[vi++] = (float) y / (GRID - 1) * 2 - 1;
            }
        }
        short[] idx = new short[strips * (perStrip + 2) - 2];
        int ii = 0, base = 0;
        for (int st = 0; st < strips; st++) {
            if (st > 0) {
                idx[ii++] = (short) (base - 1);
                idx[ii++] = (short) base;
            }
            for (int i = 0; i < perStrip; i++) idx[ii++] = (short) (base + i);
            base += perStrip;
        }
        gridIndexCount = ii;
        int vao = newVao();
        int[] vb = new int[1];
        arrayBuffer(verts);
        GLES30.glGetIntegerv(GLES30.GL_ARRAY_BUFFER_BINDING, vb, 0);
        int loc = attrib(program, "aPos");
        GLES30.glEnableVertexAttribArray(loc);
        GLES30.glVertexAttribPointer(loc, 2, GLES30.GL_FLOAT, false, 8, 0);
        elements(idx, ii);
        GLES30.glBindVertexArray(0);
        // The same vertices, every other row in each half.
        for (int half = 0; half < 2; half++) {
            short[] h = new short[idx.length];
            int hi = 0;
            for (int st = half, b = half * perStrip; st < strips; st += 2, b += 2 * perStrip) {
                if (hi > 0) {   // a degenerate join: the last vertex again, then the next row's first
                    short last = h[hi - 1];
                    h[hi++] = last;
                    h[hi++] = (short) b;
                }
                for (int i = 0; i < perStrip; i++) h[hi++] = (short) (b + i);
            }
            halfCount[half] = hi;
            halfVao[half] = newVao();
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vb[0]);
            GLES30.glEnableVertexAttribArray(loc);
            GLES30.glVertexAttribPointer(loc, 2, GLES30.GL_FLOAT, false, 8, 0);
            elements(h, hi);
            GLES30.glBindVertexArray(0);
        }
        return vao;
    }

    /** An element buffer with the first count indices, bound to the VAO being set up. */
    private static void elements(short[] idx, int count) {
        int[] ib = new int[1];
        GLES30.glGenBuffers(1, ib, 0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ib[0]);
        ShortBuffer sb = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        sb.put(idx, 0, count).position(0);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, count * 2, sb, GLES30.GL_STATIC_DRAW);
    }

    private static int particles(int program) {
        Random rnd = new Random();
        float[] seeds = new float[PARTICLES * 3];
        for (int i = 0; i < PARTICLES; i++) {
            seeds[i * 3] = rnd.nextFloat();
            seeds[i * 3 + 1] = rnd.nextFloat();
            seeds[i * 3 + 2] = (float) Math.pow(rnd.nextFloat(), 8) + 0.1f;
        }
        int vao = newVao();
        arrayBuffer(seeds);
        int loc = attrib(program, "aSeed");
        GLES30.glEnableVertexAttribArray(loc);
        GLES30.glVertexAttribPointer(loc, 3, GLES30.GL_FLOAT, false, 0, 0);
        GLES30.glBindVertexArray(0);
        return vao;
    }

    private static int texture() {
        int[] id = new int[1];
        GLES30.glGenTextures(1, id, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16F, TEX_W, TEX_H, 0, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, null);
        return id[0];
    }
}
