package com.mozhi.assistant.bootstrap.ui;

import java.awt.*;
import java.util.Map;

/** 独立资源面板，健康提示仅使用颜色和文字，不阻止游戏操作。 */
final class FleetOverviewPanel {
    private FleetOverview model = FleetOverview.from(Map.of());
    private int scroll, maxScroll;
    void scroll(int delta) { scroll = Math.max(0, Math.min(maxScroll, scroll + delta * 40)); }
    boolean setModel(FleetOverview next) {
        if (model.equals(next)) return false;
        model = next; return true;
    }
    void paint(Graphics2D graphics, Rectangle area) {
        ChatFrame.panel(graphics, area.x, area.y, area.width, area.height, 8, ChatFrame.SURFACE, ChatFrame.BORDER);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.clip(area);
            maxScroll = Math.max(0, 296 - area.height);
            scroll = Math.min(scroll, maxScroll);
            g.translate(0, -scroll);
            int x = area.x + 18, y = area.y, w = area.width - 36;
            ChatText.label(g, "分舰队状态", ChatText.BODY, ChatText.TEXT, x, y + 29);
            ChatText.label(g, "实时", ChatText.SMALL, ChatText.ACCENT, x + w - 28, y + 28);
            if (!model.present()) {
                FleetStatusPanel.wrapped(g, model.empty(), ChatText.BODY, ChatText.MUTED, x, y + 64, w, 4, 27);
                return;
            }
            FleetStatusPanel.wrapped(g, model.location(), ChatText.SMALL, ChatText.MUTED, x, y + 40, w, 1, 21);
            ChatText.label(g, "星币", ChatText.SMALL, ChatText.MUTED, x, y + 86);
            FleetStatusPanel.wrapped(g, model.credits(), ChatText.TITLE, ChatText.TEXT, x + 72, y + 64, w - 72, 1, 30);
            int cellWidth = (w - 12) / 2;
            for (int i = 0; i < model.metrics().size(); i++) {
                var metric = model.metrics().get(i);
                int cx = x + i % 2 * (cellWidth + 12), cy = y + 104 + i / 2 * 78;
                g.setColor(new Color(12, 25, 30)); g.fillRoundRect(cx, cy, cellWidth, 70, 8, 8);
                Color color = metric.warning() ? FleetStatusPanel.WARNING : ChatText.TEXT;
                ChatText.label(g, metric.title() + (metric.warning() ? " · 偏低" : ""), ChatText.SMALL,
                        metric.warning() ? color : ChatText.MUTED, cx + 10, cy + 18);
                FleetStatusPanel.wrapped(g, metric.value(), ChatText.BODY, color, cx + 10, cy + 23, cellWidth - 20, 1, 23);
                FleetStatusPanel.wrapped(g, metric.detail(), ChatText.SMALL, ChatText.MUTED, cx + 10, cy + 48, cellWidth - 20, 1, 18);
            }
            FleetStatusPanel.wrapped(g, model.footer(), ChatText.SMALL, ChatText.MUTED, x, y + 267, w, 1, 20);
        } finally { g.dispose(); }
    }
}
