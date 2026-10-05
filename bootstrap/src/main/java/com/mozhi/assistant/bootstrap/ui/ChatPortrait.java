package com.mozhi.assistant.bootstrap.ui;

import com.fs.starfarer.api.Global;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** 每个窗口只加载一次头像，保留透明度与宽高比。 */
final class ChatPortrait {
    private static final String PATH = "graphics/portraits/SOD_portrait_mozhi.png";
    private final BufferedImage image = load();

    private static BufferedImage load() {
        try (InputStream source = Global.getSettings().openStream(PATH)) {
            if (source == null) throw new java.io.IOException("未找到头像：" + PATH);
            // 避免在游戏脚本加载器中使用 ImageIO 默认的临时文件缓存。
            try (MemoryCacheImageInputStream stream = new MemoryCacheImageInputStream(source)) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new java.io.IOException("不支持的头像格式：" + PATH);
                var reader = readers.next();
                try {
                    reader.setInput(stream);
                    return reader.read(0);
                } finally { reader.dispose(); }
            }
        } catch (Exception error) {
            Global.getLogger(ChatPortrait.class).warn("无法加载墨汁头像：" + PATH, error);
            return null;
        }
    }

    void draw(Graphics2D graphics, int x, int y, int size) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.clip(ChatFrame.outline(x, y, size, size, Math.min(8, size / 8)));
            g.setColor(new Color(15, 29, 34)); g.fillRect(x, y, size, size);
            if (size >= 80) {
                g.setColor(new Color(24, 43, 48));
                for (int i = 0; i < size; i += 16) {
                    g.drawLine(x + i, y, x + i, y + size);
                    g.drawLine(x, y + i, x + size, y + i);
                }
            }
            if (image != null) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                float scale = (float) size / Math.max(image.getWidth(), image.getHeight());
                int w = Math.round(image.getWidth() * scale), h = Math.round(image.getHeight() * scale);
                g.drawImage(image, x + (size - w) / 2, y + (size - h) / 2, w, h, null);
            }
        } finally { g.dispose(); }
        graphics.setColor(ChatFrame.BORDER);
        graphics.draw(ChatFrame.outline(x, y, size, size, Math.min(8, size / 8)));
        if (size >= 80) ChatFrame.brackets(graphics, x - 3, y - 3, size + 6, size + 6, ChatText.ACCENT);
    }
}