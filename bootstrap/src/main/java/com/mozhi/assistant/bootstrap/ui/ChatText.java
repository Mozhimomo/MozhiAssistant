package com.mozhi.assistant.bootstrap.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextHitInfo;
import java.awt.font.TextLayout;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;

/** 绘制、换行、光标定位和鼠标选择共用的文本度量。 */
final class ChatText {
    static final Color TEXT = new Color(224, 237, 236);
    static final Color MUTED = new Color(145, 169, 174);
    static final Color ACCENT = new Color(119, 203, 197);
    static final Font BODY = font(17, false);
    static final Font SMALL = font(13, false);
    static final Font TITLE = font(26, true);
    static final FontRenderContext METRICS = new FontRenderContext(null, true, true);

    private static Font font(int size, boolean bold) {
        Font candidate = new Font("Microsoft YaHei UI", bold ? Font.BOLD : Font.PLAIN, size);
        return candidate.canDisplay('墨') ? candidate : new Font(Font.DIALOG, bold ? Font.BOLD : Font.PLAIN, size);
    }

    static void configure(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    static void label(Graphics2D g, String text, Font font, Color color, float x, float baseline) {
        g.setFont(font);
        g.setColor(color);
        g.drawString(text, x, baseline);
    }

    record Line(int start, int end, TextLayout layout) {
        float x(int offset) {
            return layout == null ? 0 : layout.getCaretInfo(TextHitInfo.leading(
                    Math.max(0, Math.min(end - start, offset - start))))[0];
        }
        int hit(float x) {
            return layout == null ? start : start + layout.hitTestChar(x, 0).getInsertionIndex();
        }
    }

    static List<Line> wrap(String text, Font font, float width) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                lines.add(new Line(start, start, null));
            } else {
                AttributedString attributed = new AttributedString(paragraph);
                attributed.addAttribute(TextAttribute.FONT, font);
                LineBreakMeasurer measurer = new LineBreakMeasurer(attributed.getIterator(), METRICS);
                while (measurer.getPosition() < paragraph.length()) {
                    int from = measurer.getPosition();
                    TextLayout layout = measurer.nextLayout(Math.max(20, width));
                    lines.add(new Line(start + from, start + measurer.getPosition(), layout));
                }
            }
            start += paragraph.length() + 1;
        }
        return lines;
    }

    static int lineAt(List<Line> lines, int caret) {
        for (int i = 0; i < lines.size() - 1; i++) {
            if (caret < lines.get(i + 1).start()) return i;
        }
        return lines.size() - 1;
    }

    static void drawLines(Graphics2D g, List<Line> lines, float x, float top, int spacing, Color color) {
        g.setColor(color);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).layout() != null) lines.get(i).layout().draw(g, x, top + 19 + i * spacing);
        }
    }
}