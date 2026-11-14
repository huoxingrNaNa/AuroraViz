package com.operit.audioviz;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 把 Bitmap 通过 OpenGL 渲染到 MediaCodec 的输入 Surface。
 * MediaCodec 的 input Surface 不能 lockCanvas，必须走 GL。
 */
public class GlBlitter {
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;
    private int program;
    private int texture;
    private int aPos, aUV, uTex;
    private int width, height;
    private boolean textureReady = false;

    private static final float[] POS = {
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
    };

    // 纹理上下翻转（Bitmap 首行在底部）
    private static final float[] UV = {
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
    };

    private static final String VERT =
            "attribute vec4 aPos;\n" +
                    "attribute vec2 aUV;\n" +
                    "varying vec2 vUV;\n" +
                    "void main() {\n" +
                    "  gl_Position = aPos;\n" +
                    "  vUV = aUV;\n" +
                    "}\n";

    private static final String FRAG =
            "precision mediump float;\n" +
                    "varying vec2 vUV;\n" +
                    "uniform sampler2D uTex;\n" +
                    "void main() {\n" +
                    "  gl_FragColor = texture2D(uTex, vUV);\n" +
                    "}\n";

    public boolean init(Surface encoderSurface, int w, int h) {
        width = w;
        height = h;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) return false;
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false;

            int[] cfgAttr = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] num = new int[1];
            if (!EGL14.eglChooseConfig(display, cfgAttr, 0, configs, 0, 1, num, 0) || num[0] == 0) {
                return false;
            }

            int[] ctxAttr = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
            if (context == EGL14.EGL_NO_CONTEXT) return false;

            int[] sAttr = {EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE};
            surface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, sAttr, 0);
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
                // 部分驱动不支持 RECORDABLE 属性，退回普通 surface
                surface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface,
                        new int[]{EGL14.EGL_NONE}, 0);
            }
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) return false;
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return false;

            // ---- program ----
            int vs = compile(GLES20.GL_VERTEX_SHADER, VERT);
            int fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAG);
            if (vs == 0 || fs == 0) return false;
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vs);
            GLES20.glAttachShader(program, fs);
            GLES20.glLinkProgram(program);
            int[] link = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, link, 0);
            GLES20.glDeleteShader(vs);
            GLES20.glDeleteShader(fs);
            if (link[0] != GLES20.GL_TRUE) return false;

            aPos = GLES20.glGetAttribLocation(program, "aPos");
            aUV = GLES20.glGetAttribLocation(program, "aUV");
            uTex = GLES20.glGetUniformLocation(program, "uTex");

            int[] texs = new int[1];
            GLES20.glGenTextures(1, texs, 0);
            texture = texs[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glViewport(0, 0, width, height);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 渲染一帧并把时间戳交给编码器 */
    public void draw(Bitmap bmp, long presentationTimeNs) {
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        if (!textureReady) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
            textureReady = true;
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp);
        }
        GLES20.glUniform1i(uTex, 0);

        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, buffer(POS));
        GLES20.glEnableVertexAttribArray(aUV);
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 0, buffer(UV));
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aUV);

        EGLExt.eglPresentationTimeANDROID(display, surface, presentationTimeNs);
        EGL14.eglSwapBuffers(display, surface);
    }

    public void release() {
        try {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                EGL14.eglTerminate(display);
            }
        } catch (Throwable ignored) {
        }
        display = EGL14.EGL_NO_DISPLAY;
        context = EGL14.EGL_NO_CONTEXT;
        surface = EGL14.EGL_NO_SURFACE;
    }

    private static FloatBuffer buffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data).position(0);
        return fb;
    }

    private static int compile(int type, String src) {
        int sh = GLES20.glCreateShader(type);
        GLES20.glShaderSource(sh, src);
        GLES20.glCompileShader(sh);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] != GLES20.GL_TRUE) {
            GLES20.glDeleteShader(sh);
            return 0;
        }
        return sh;
    }
}