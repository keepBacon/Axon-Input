package com.axon.input;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Transparent external-OES video surface with realtime chroma-key filtering. */
final class ChromaKeyVideoSurfaceView extends GLSurfaceView {
    interface SurfaceListener {
        void onSurfaceReady(Surface surface);
    }

    interface ColorSampleListener {
        void onColorSampled(int color);
    }

    private final VideoRenderer videoRenderer;
    private SurfaceListener surfaceListener;
    private boolean lastChromaEnabled;
    private int lastChromaColor = 0xff00ff00;
    private int lastChromaStrength = -1;
    private float lastOpacity = -1f;
    private int lastDisplayWidth = -1;
    private int lastDisplayHeight = -1;

    ChromaKeyVideoSurfaceView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setZOrderMediaOverlay(true);
        setPreserveEGLContextOnPause(true);
        videoRenderer = new VideoRenderer();
        setRenderer(videoRenderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    void setSurfaceListener(SurfaceListener listener) {
        surfaceListener = listener;
        Surface surface = videoRenderer.currentSurface();
        if (listener != null && surface != null && surface.isValid()) {
            post(() -> listener.onSurfaceReady(surface));
        }
    }

    void setChromaKey(boolean enabled, int color, int strengthPercent) {
        int safeColor = 0xff000000 | (color & 0x00ffffff);
        int safeStrength = Math.max(0, Math.min(FloatingMediaStore.CHROMA_STRENGTH_MAX, strengthPercent));
        if (lastChromaEnabled == enabled && lastChromaColor == safeColor
                && lastChromaStrength == safeStrength) return;
        lastChromaEnabled = enabled;
        lastChromaColor = safeColor;
        lastChromaStrength = safeStrength;
        float r = Color.red(safeColor) / 255f;
        float g = Color.green(safeColor) / 255f;
        float b = Color.blue(safeColor) / 255f;
        float strength = safeStrength / 100f;
        queueEvent(() -> videoRenderer.setChroma(enabled, r, g, b, strength));
        requestRender();
    }

    void setVideoOpacity(float opacity) {
        float safe = Math.max(0f, Math.min(1f, opacity));
        if (Math.abs(lastOpacity - safe) < 0.0005f) return;
        lastOpacity = safe;
        queueEvent(() -> videoRenderer.setOpacity(safe));
        requestRender();
    }

    /** Display-space dimensions after rotation metadata has been accounted for. */
    void setVideoDisplaySize(int width, int height) {
        int safeWidth = Math.max(1, width);
        int safeHeight = Math.max(1, height);
        if (lastDisplayWidth == safeWidth && lastDisplayHeight == safeHeight) return;
        lastDisplayWidth = safeWidth;
        lastDisplayHeight = safeHeight;
        queueEvent(() -> videoRenderer.setDisplaySize(safeWidth, safeHeight));
        requestRender();
    }

    void requestColorSample(float x, float y, ColorSampleListener listener) {
        if (listener == null || getWidth() <= 0 || getHeight() <= 0) return;
        float nx = Math.max(0f, Math.min(1f, x / Math.max(1f, getWidth())));
        float ny = Math.max(0f, Math.min(1f, y / Math.max(1f, getHeight())));
        videoRenderer.setSampleRequest(nx, ny, listener);
        requestRender();
    }

    void releaseVideoSurface() {
        queueEvent(videoRenderer::releaseSurface);
    }

    private final class VideoRenderer implements Renderer, SurfaceTexture.OnFrameAvailableListener {
        private final float[] texMatrix = new float[16];
        private final FloatBuffer vertices;
        private final FloatBuffer texCoords;
        private int program;
        private int textureId;
        private int aPosition = -1;
        private int aTexCoord = -1;
        private int uTexMatrix = -1;
        private int uScale = -1;
        private int uKeyColor = -1;
        private int uStrength = -1;
        private int uEnabled = -1;
        private int uOpacity = -1;
        private int uTexture = -1;
        private SurfaceTexture surfaceTexture;
        private Surface surface;
        private int viewportWidth;
        private int viewportHeight;
        private int displayWidth = 16;
        private int displayHeight = 9;
        private float aspectScaleX = 1f;
        private float aspectScaleY = 1f;
        private boolean chromaEnabled;
        private float keyR, keyG = 1f, keyB;
        private float strength = 0.36f;
        private float opacity = 1f;
        private volatile SampleRequest pendingSample;

        VideoRenderer() {
            vertices = floatBuffer(new float[]{
                    -1f, -1f,
                     1f, -1f,
                    -1f,  1f,
                     1f,  1f
            });
            // SurfaceTexture#getTransformMatrix already converts the decoder buffer into
            // OpenGL texture space (including the vertical-origin correction). Do not flip Y
            // here as well, otherwise some videos are rendered upside-down / direction-reversed.
            texCoords = floatBuffer(new float[]{
                    0f, 0f,
                    1f, 0f,
                    0f, 1f,
                    1f, 1f
            });
            Matrix.setIdentityM(texMatrix, 0);
        }

        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                    GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (program != 0) {
                aPosition = GLES20.glGetAttribLocation(program, "aPosition");
                aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
                uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix");
                uScale = GLES20.glGetUniformLocation(program, "uScale");
                uKeyColor = GLES20.glGetUniformLocation(program, "uKeyColor");
                uStrength = GLES20.glGetUniformLocation(program, "uStrength");
                uEnabled = GLES20.glGetUniformLocation(program, "uEnabled");
                uOpacity = GLES20.glGetUniformLocation(program, "uOpacity");
                uTexture = GLES20.glGetUniformLocation(program, "uTexture");
            }
            textureId = createExternalTexture();
            surfaceTexture = new SurfaceTexture(textureId);
            surfaceTexture.setOnFrameAvailableListener(this);
            surface = new Surface(surfaceTexture);
            Surface ready = surface;
            SurfaceListener listener = surfaceListener;
            if (listener != null) post(() -> {
                if (ready.isValid() && surfaceListener == listener) listener.onSurfaceReady(ready);
            });
        }

        @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
            viewportWidth = Math.max(1, width);
            viewportHeight = Math.max(1, height);
            updateAspectFitScale();
            GLES20.glViewport(0, 0, viewportWidth, viewportHeight);
        }

        @Override public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            SurfaceTexture texture = surfaceTexture;
            if (texture == null || program == 0) return;
            try {
                texture.updateTexImage();
                texture.getTransformMatrix(texMatrix);
            } catch (Throwable ignored) {
            }

            GLES20.glUseProgram(program);
            vertices.position(0);
            texCoords.position(0);
            GLES20.glEnableVertexAttribArray(aPosition);
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertices);
            GLES20.glEnableVertexAttribArray(aTexCoord);
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords);
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0);
            GLES20.glUniform2f(uScale, aspectScaleX, aspectScaleY);
            GLES20.glUniform3f(uKeyColor, keyR, keyG, keyB);
            GLES20.glUniform1f(uStrength, strength);
            GLES20.glUniform1f(uOpacity, opacity);
            SampleRequest sample = pendingSample;
            if (sample != null) pendingSample = null;
            // Color picking always samples the original video frame, not the already-keyed result.
            GLES20.glUniform1f(uEnabled, sample != null ? 0f : (chromaEnabled ? 1f : 0f));
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(uTexture, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            if (sample != null) {
                int px = Math.max(0, Math.min(viewportWidth - 1,
                        Math.round(sample.normalizedX * (viewportWidth - 1))));
                int py = Math.max(0, Math.min(viewportHeight - 1,
                        Math.round((1f - sample.normalizedY) * (viewportHeight - 1))));
                // Average a small 5x5 patch instead of trusting one compressed video pixel.
                // Transparent Aspect-Fit bars are ignored, so tapping outside the real frame
                // can no longer accidentally choose black as the key color.
                int radius = 2;
                int left = Math.max(0, px - radius);
                int bottom = Math.max(0, py - radius);
                int right = Math.min(viewportWidth - 1, px + radius);
                int top = Math.min(viewportHeight - 1, py + radius);
                int sampleWidth = right - left + 1;
                int sampleHeight = top - bottom + 1;
                ByteBuffer pixels = ByteBuffer.allocateDirect(sampleWidth * sampleHeight * 4)
                        .order(ByteOrder.nativeOrder());
                GLES20.glReadPixels(left, bottom, sampleWidth, sampleHeight, GLES20.GL_RGBA,
                        GLES20.GL_UNSIGNED_BYTE, pixels);
                long sumR = 0L, sumG = 0L, sumB = 0L, sumWeight = 0L;
                int count = sampleWidth * sampleHeight;
                for (int i = 0; i < count; i++) {
                    int base = i * 4;
                    int alpha = pixels.get(base + 3) & 0xff;
                    if (alpha < 32) continue;
                    sumR += (long) (pixels.get(base) & 0xff) * alpha;
                    sumG += (long) (pixels.get(base + 1) & 0xff) * alpha;
                    sumB += (long) (pixels.get(base + 2) & 0xff) * alpha;
                    sumWeight += alpha;
                }
                if (sumWeight > 0L) {
                    int r = (int) Math.max(0L, Math.min(255L, Math.round(sumR / (double) sumWeight)));
                    int g = (int) Math.max(0L, Math.min(255L, Math.round(sumG / (double) sumWeight)));
                    int b = (int) Math.max(0L, Math.min(255L, Math.round(sumB / (double) sumWeight)));
                    int color = Color.rgb(r, g, b);
                    post(() -> sample.listener.onColorSampled(color));
                }
                if (chromaEnabled) {
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    GLES20.glUniform1f(uEnabled, 1f);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                }
            }

            GLES20.glDisableVertexAttribArray(aPosition);
            GLES20.glDisableVertexAttribArray(aTexCoord);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        }

        @Override public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            requestRender();
        }

        void setChroma(boolean enabled, float r, float g, float b, float value) {
            chromaEnabled = enabled;
            keyR = r;
            keyG = g;
            keyB = b;
            strength = value;
        }

        void setOpacity(float value) {
            opacity = Math.max(0f, Math.min(1f, value));
        }

        void setDisplaySize(int width, int height) {
            displayWidth = Math.max(1, width);
            displayHeight = Math.max(1, height);
            updateAspectFitScale();
        }

        private void updateAspectFitScale() {
            float viewAspect = viewportWidth / (float) Math.max(1, viewportHeight);
            float videoAspect = displayWidth / (float) Math.max(1, displayHeight);
            if (!Float.isFinite(viewAspect) || viewAspect <= 0f) viewAspect = 1f;
            if (!Float.isFinite(videoAspect) || videoAspect <= 0f) videoAspect = 16f / 9f;
            if (videoAspect > viewAspect) {
                aspectScaleX = 1f;
                aspectScaleY = Math.max(0.001f, viewAspect / videoAspect);
            } else {
                aspectScaleX = Math.max(0.001f, videoAspect / viewAspect);
                aspectScaleY = 1f;
            }
        }

        void setSampleRequest(float nx, float ny, ColorSampleListener listener) {
            pendingSample = new SampleRequest(nx, ny, listener);
        }

        Surface currentSurface() {
            return surface;
        }

        void releaseSurface() {
            Surface current = surface;
            surface = null;
            if (current != null) try { current.release(); } catch (Throwable ignored) {}
            SurfaceTexture currentTexture = surfaceTexture;
            surfaceTexture = null;
            if (currentTexture != null) try { currentTexture.release(); } catch (Throwable ignored) {}
        }
    }

    private static final class SampleRequest {
        final float normalizedX;
        final float normalizedY;
        final ColorSampleListener listener;

        SampleRequest(float x, float y, ColorSampleListener listener) {
            normalizedX = x;
            normalizedY = y;
            this.listener = listener;
        }
    }

    private static FloatBuffer floatBuffer(float[] values) {
        ByteBuffer bytes = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder());
        FloatBuffer out = bytes.asFloatBuffer();
        out.put(values).position(0);
        return out;
    }

    private static int createExternalTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int texture = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return texture;
    }

    private static int buildProgram(String vertex, String fragment) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vertex);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment);
        if (vs == 0 || fs == 0) return 0;
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        if (ok[0] != GLES20.GL_TRUE) {
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] != GLES20.GL_TRUE) {
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "uniform vec2 uScale;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main(){\n" +
            "  gl_Position=vec4(aPosition.xy*uScale,0.0,1.0);\n" +
            "  vec4 t=uTexMatrix*vec4(aTexCoord,0.0,1.0);\n" +
            "  vTexCoord=t.xy;\n" +
            "}";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "uniform vec3 uKeyColor;\n" +
            "uniform float uStrength;\n" +
            "uniform float uEnabled;\n" +
            "uniform float uOpacity;\n" +
            "varying vec2 vTexCoord;\n" +
            "vec3 rgbToYcc(vec3 c){\n" +
            "  return vec3(dot(c,vec3(0.299,0.587,0.114)),\n" +
            "              dot(c,vec3(-0.168736,-0.331264,0.5)),\n" +
            "              dot(c,vec3(0.5,-0.418688,-0.081312)));\n" +
            "}\n" +
            "vec3 yccToRgb(vec3 ycc){\n" +
            "  float y=ycc.x; float cb=ycc.y; float cr=ycc.z;\n" +
            "  return vec3(y+1.402*cr, y-0.344136*cb-0.714136*cr, y+1.772*cb);\n" +
            "}\n" +
            "void main(){\n" +
            "  vec4 c=texture2D(uTexture,vTexCoord);\n" +
            "  vec3 ycc=rgbToYcc(c.rgb);\n" +
            "  vec3 keyYcc=rgbToYcc(uKeyColor);\n" +
            "  float keyMax=max(max(uKeyColor.r,uKeyColor.g),uKeyColor.b);\n" +
            "  float keyMin=min(min(uKeyColor.r,uKeyColor.g),uKeyColor.b);\n" +
            "  float keySat=keyMax-keyMin;\n" +
            "  float chromaMode=smoothstep(0.07,0.24,keySat);\n" +
            "  float chromaD=distance(ycc.yz,keyYcc.yz)+abs(ycc.x-keyYcc.x)*0.10;\n" +
            "  float rgbD=distance(c.rgb,uKeyColor)*0.50;\n" +
            "  float d=mix(rgbD,chromaD,chromaMode);\n" +
            "  float strengthBase=min(clamp(uStrength,0.0,2.0),1.0);\n" +
            "  float strengthExtra=max(clamp(uStrength,0.0,2.0)-1.0,0.0);\n" +
            "  float tuned=pow(strengthBase,1.08);\n" +
            "  float threshold=mix(0.012,0.185,tuned)+0.160*strengthExtra;\n" +
            "  float feather=mix(0.012,0.072,0.30+0.70*tuned)+0.055*strengthExtra;\n" +
            "  float keep=smoothstep(threshold,threshold+feather,d);\n" +
            "  float keyLen=length(keyYcc.yz);\n" +
            "  vec2 keyDir=keyYcc.yz/max(keyLen,0.0001);\n" +
            "  float edgeNear=1.0-smoothstep(threshold+feather,threshold+feather*3.2,d);\n" +
            "  float projection=max(0.0,dot(ycc.yz,keyDir));\n" +
            "  float spill=projection*edgeNear*chromaMode*0.62;\n" +
            "  vec2 cleanChroma=ycc.yz-keyDir*spill;\n" +
            "  vec3 cleanRgb=clamp(yccToRgb(vec3(ycc.x,cleanChroma)),0.0,1.0);\n" +
            "  float enabledMask=step(0.5,uEnabled);\n" +
            "  float cleanMix=edgeNear*chromaMode*0.72*enabledMask;\n" +
            "  vec3 outRgb=mix(c.rgb,cleanRgb,cleanMix);\n" +
            "  float keyedAlpha=c.a*keep;\n" +
            "  float a=mix(c.a,keyedAlpha,enabledMask)*uOpacity;\n" +
            "  gl_FragColor=vec4(outRgb,a);\n" +
            "}";
}
