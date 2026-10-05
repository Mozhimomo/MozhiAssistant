package com.mozhi.assistant.bootstrap.ui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.IntBuffer;
import java.util.function.Consumer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;

/** 仅在内容变化时上传 CPU 排版结果；所有 OpenGL 操作均在战役渲染回调中执行。 */
final class ChatTexture {
    private BufferedImage image;
    private IntBuffer pixels;
    private int texture;
    private int width;
    private int height;
    private double rasterScale;

    void draw(float screenWidth, float screenHeight, float x, float y, int w, int h,
              float pixelScale, boolean dirty, Consumer<Graphics2D> paint) {
        draw(screenWidth, screenHeight, x, y, w, h, pixelScale, dirty, true, paint);
    }

    void draw(float screenWidth, float screenHeight, float x, float y, int w, int h,
              float pixelScale, boolean dirty, boolean dimBackground, Consumer<Graphics2D> paint) {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
        int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, screenWidth, 0, screenHeight, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            if (dimBackground) {
                GL11.glColor4f(0.015f, 0.025f, 0.05f, 0.80f);
                quad(0, 0, screenWidth, screenHeight);
            }

            double scale = Math.min(Math.max(1, pixelScale),
                    (double) GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE) / Math.max(w, h));
            int pw = (int) Math.ceil(w * scale), ph = (int) Math.ceil(h * scale);
            boolean resized = image == null || pw != width || ph != height;
            if (resized) {
                width = pw; height = ph; rasterScale = scale;
                image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                pixels = BufferUtils.createIntBuffer(width * height);
            }
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            if (texture == 0) texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
            if (dirty || resized) {
                Graphics2D g = image.createGraphics();
                try {
                    g.setComposite(java.awt.AlphaComposite.Clear);
                    g.fillRect(0, 0, width, height);
                    g.setComposite(java.awt.AlphaComposite.SrcOver);
                    g.scale(rasterScale, rasterScale);
                    ChatText.configure(g);
                    paint.accept(g);
                } finally { g.dispose(); }
                pixels.clear();
                pixels.put(((DataBufferInt) image.getRaster().getDataBuffer()).getData()).flip();
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
                GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
                if (resized) {
                    GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                            GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
                } else {
                    GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
                            GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
                }
            }
            GL11.glColor4f(1, 1, 1, 1); // 不继承原版面板的透明度或文字淡出效果。
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(0, 1); GL11.glVertex2f(x, y);
            GL11.glTexCoord2f(1, 1); GL11.glVertex2f(x + w, y);
            GL11.glTexCoord2f(1, 0); GL11.glVertex2f(x + w, y + h);
            GL11.glTexCoord2f(0, 0); GL11.glVertex2f(x, y + h);
            GL11.glEnd();
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glPopMatrix();
            GL11.glMatrixMode(matrixMode);
            GL11.glPopClientAttrib();
            GL11.glPopAttrib();
        }
    }

    private static void quad(float x, float y, float w, float h) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y); GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h); GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }

    void dispose() {
        if (texture != 0) GL11.glDeleteTextures(texture);
        texture = 0;
        image = null;
        pixels = null;
    }
}
