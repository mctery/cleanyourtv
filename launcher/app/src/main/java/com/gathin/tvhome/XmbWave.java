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
            + "void main() {\n"
            + "  float u = clamp(vUvYDown.y, 0.0, 1.0);\n"
            + "  float g = u * u * (3.0 - 2.0 * u);\n"
            // A dark gradient over 1080 lines has fewer 8-bit steps than lines and shows bands; half a step of
            // noise per pixel hides them.
            // Interleaved gradient noise: no sine per pixel, which over two million pixels a frame a small GPU feels.
            + "  float n = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) - 0.5;\n"
            + "  oColor = vec4(mix(uColorStart, uColorEnd, g) + n / 255.0, 1.0);\n"
            + "}\n";
    private static final String WAVE_VS = "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 aPos;\n"
            + "uniform sampler2D uSplineTex;\n"
            + "uniform float uTime, flowSpeed, tension, damping, uLength, spacing, perturbation, perturbationScale;\n"
            + "uniform float timeStep, waveCosAmp, waveBias, waveHeightScale, waveSoftClip, ffdYAmp, ffdZAmp, zDetailScale;\n"
            + "uniform vec3 ffdScale1, ffdScale2, ffdOffset;\n"
            + "out vec3 vPos;\n"
            + "void main() {\n"
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
            + "out vec4 oColor;\n"
            + "uniform float opacity, brightness, fresnelPower, fresnelScale;\n"
            + "void main() {\n"
            + "  vec3 N = normalize(cross(dFdx(vPos), dFdy(vPos)));\n"
            + "  float F = fresnelScale * pow(1.0 + dot(vec3(0.0, 0.0, -1.0), N), fresnelPower);\n"
            + "  oColor = vec4(vec3(1.0), F * opacity * brightness);\n"
            + "}\n";
    private static final String PT_VS = "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec3 aSeed;\n"
            + "uniform float uTime, flowSpeed, ratio, ptSizeBase, ptSizeVar;\n"
            + "out float vAlpha;\n"
            + "void main() {\n"
            + "  gl_PointSize = aSeed.z * ptSizeVar + ptSizeBase;\n"
            + "  float time = uTime * flowSpeed;\n"
            + "  float x = fract(time * (aSeed.x - 0.5) / 15.0 + aSeed.y * 50.0) * 2.0 - 1.0;\n"
            + "  float y = sin(sign(aSeed.y) * time * (aSeed.y + 1.5) / 4.0 + aSeed.x * 100.0)\n"
            + "          / ((6.0 - aSeed.x * 4.0 * aSeed.y) / ratio);\n"
            + "  float opVar = mix(sin(time * (aSeed.x + 0.5) * 12.0 + aSeed.y * 10.0),\n"
            + "                    sin(time * (aSeed.y + 1.5) * 6.0 + aSeed.x * 4.0), y * 0.5 + 0.5) * aSeed.x + aSeed.y;\n"
            + "  vAlpha = opVar * opVar * (1.0 - fract(aSeed.x + time * 0.00285));\n"
            + "  gl_Position = vec4(x, y, 0.0, 1.0);\n"
            + "}\n";
    private static final String PT_FS = "#version 300 es\n"
            + "precision highp float;\n"
            + "in float vAlpha;\n"
            + "out vec4 oColor;\n"
            + "uniform float particleOpacity;\n"
            + "void main() {\n"
            + "  vec2 c = gl_PointCoord * 2.0 - 1.0;\n"
            + "  float d = dot(c, c);\n"
            + "  if (d > 1.0) discard;\n"
            + "  float sparkle = (1.0 - d) * (1.0 - d);\n"
            + "  oColor = vec4(vec3(vAlpha * particleOpacity * sparkle), 1.0);\n"
            + "}\n";

    private final XmbSpline spline = new XmbSpline();
    private final XmbSpline.Settings s = new XmbSpline.Settings();
    private final float particleOpacity;
    private final float[] heights = new float[TEX_W * TEX_H];
    private final FloatBuffer heightBuf = ByteBuffer.allocateDirect(TEX_W * TEX_H * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();

    /** 0xRRGGBB, written by the UI thread. */
    private volatile int color = 0x2559B3;
    private boolean ok;
    private int width = 1, height = 1;
    private int bgProg, waveProg, ptProg, bgVao, gridVao, ptVao, gridIndexCount, splineTex;
    private float tSpline, tPart = new Random().nextFloat() * 1000f;
    private long lastNanos;
    private int frames;
    private int uBgStart, uBgEnd, uWaveTime, uPtTime;

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
        color = rgb & 0xFFFFFF;
    }

    /** The next frame continues from now: a pause (app in front, idle) must not jump the wave. */
    void resetClock() {
        lastNanos = 0;
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

    @Override
    public void onDrawFrame(GL10 unused) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.016f : (now - lastNanos) / 1e9f;
        lastNanos = now;
        if (dt > 0.1f || dt < 0) dt = 0.016f;   // clamp across pauses
        tSpline += dt;
        tPart += dt;

        int c = color;
        float r = ((c >> 16) & 0xFF) / 255f, g = ((c >> 8) & 0xFF) / 255f, b = (c & 0xFF) / 255f;
        if (!ok) {
            GLES30.glClearColor(r * 0.3f, g * 0.3f, b * 0.3f, 1f);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            return;
        }
        // The heights change slowly: recompute them on every other frame (15 a second); the shader's own time terms
        // still move every frame, so the motion stays smooth while the CPU does half the work.
        boolean newHeights = (frames++ & 1) == 0;
        if (newHeights) {
            spline.write(s, tSpline, heights, TEX_W, TEX_H);
            heightBuf.position(0);
            heightBuf.put(heights).position(0);
        }

        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glUseProgram(bgProg);
        GLES30.glUniform3f(uBgStart, r * 0.09f, g * 0.09f, b * 0.09f * 1.2f);
        GLES30.glUniform3f(uBgEnd, r * 0.62f, g * 0.62f, b * 0.62f);
        GLES30.glBindVertexArray(bgVao);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUseProgram(waveProg);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, splineTex);
        if (newHeights) {
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, TEX_W, TEX_H, GLES30.GL_RED, GLES30.GL_FLOAT, heightBuf);
        }
        GLES30.glUniform1f(uWaveTime, tSpline);   // the rest were set once, in onSurfaceCreated
        GLES30.glBindVertexArray(gridVao);
        GLES30.glDrawElements(GLES30.GL_TRIANGLE_STRIP, gridIndexCount, GLES30.GL_UNSIGNED_SHORT, 0);

        // Sparkles add light.
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE);
        GLES30.glUseProgram(ptProg);
        GLES30.glUniform1f(uPtTime, tPart);
        GLES30.glBindVertexArray(ptVao);
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, PARTICLES);
        GLES30.glBindVertexArray(0);
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
        arrayBuffer(verts);
        int loc = attrib(program, "aPos");
        GLES30.glEnableVertexAttribArray(loc);
        GLES30.glVertexAttribPointer(loc, 2, GLES30.GL_FLOAT, false, 8, 0);
        int[] ib = new int[1];
        GLES30.glGenBuffers(1, ib, 0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ib[0]);
        ShortBuffer sb = ByteBuffer.allocateDirect(idx.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        sb.put(idx).position(0);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.length * 2, sb, GLES30.GL_STATIC_DRAW);
        GLES30.glBindVertexArray(0);
        return vao;
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
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16F, TEX_W, TEX_H, 0, GLES30.GL_RED, GLES30.GL_FLOAT, null);
        return id[0];
    }
}
