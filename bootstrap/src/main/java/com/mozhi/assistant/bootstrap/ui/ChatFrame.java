package com.mozhi.assistant.bootstrap.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;

/** 简洁的仪表面板样式，不添加装饰性文字或模拟遥测数据。 */
final class ChatFrame {
    static final Color BACKGROUND = new Color(10, 19, 23);
    static final Color SURFACE = new Color(17, 31, 36);
    static final Color BORDER = new Color(54, 87, 92);
    static final Color GOLD = new Color(207, 191, 143);

    static Shape outline(int x, int y, int width, int height, int cut) {
        return new Polygon(new int[]{x + cut, x + width, x + width, x + width - cut, x, x},
                new int[]{y, y, y + height - cut, y + height, y + height, y + cut}, 6);
    }

    static void panel(Graphics2D g, int x, int y, int width, int height, int cut, Color fill, Color border) {
        Shape shape = outline(x, y, width, height, cut);
        g.setColor(fill); g.fill(shape);
        g.setColor(border); g.draw(shape);
    }

    static void brackets(Graphics2D g, int x, int y, int width, int height, Color color) {
        int arm = Math.min(15, Math.min(width, height) / 4);
        g.setColor(color);
        g.drawLine(x, y + arm, x, y); g.drawLine(x, y, x + arm, y);
        g.drawLine(x + width - arm, y, x + width, y); g.drawLine(x + width, y, x + width, y + arm);
        g.drawLine(x, y + height - arm, x, y + height); g.drawLine(x, y + height, x + arm, y + height);
        g.drawLine(x + width - arm, y + height, x + width, y + height);
        g.drawLine(x + width, y + height, x + width, y + height - arm);
    }
}