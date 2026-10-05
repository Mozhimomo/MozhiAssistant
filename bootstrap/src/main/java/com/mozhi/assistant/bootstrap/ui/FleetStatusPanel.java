package com.mozhi.assistant.bootstrap.ui;

import java.awt.*;
import java.util.Map;

/** 目标、执行摘要和可独立滚动的步骤清单；资源由独立状态面板展示。 */
final class FleetStatusPanel {
    static final Color WARNING = new Color(232, 186, 109);
    private FleetPresentation model = FleetPresentation.from(Map.of());
    private Rectangle bounds = new Rectangle();
    private int scroll, contentHeight;
    boolean setModel(FleetPresentation next) {
        if (next.equals(model)) return false;
        if (!next.taskId().equals(model.taskId()) || !next.planId().equals(model.planId())) scroll = 0;
        model = next; return true;
    }
    boolean contains(int x, int y) { return bounds.contains(x, y); }
    void scroll(int delta) { scroll = Math.max(0, Math.min(maxScroll(), scroll + delta * 54)); }
    private int maxScroll() { return Math.max(0, contentHeight - Math.max(1, bounds.height - 70)); }
    void paint(Graphics2D g, Rectangle area) {
        bounds = area;
        ChatFrame.panel(g, area.x, area.y, area.width, area.height, 8, ChatFrame.SURFACE, ChatFrame.BORDER);
        int x = area.x + 18, width = area.width - 36;
        boolean compact = area.height < 380;
        ChatText.label(g, "目标与执行计划", ChatText.BODY, ChatText.TEXT, x, area.y + 32);
        g.setColor(ChatFrame.BORDER); g.drawLine(x, area.y + 51, x + width, area.y + 51);
        Graphics2D body = (Graphics2D) g.create();
        try {
            body.clipRect(x, area.y + 62, width, Math.max(1, area.height - 74));
            int top = area.y + 62 - scroll, y = top;
            if (!compact) { ChatText.label(body, "目标", ChatText.SMALL, ChatText.MUTED, x, y + 15); y += 27; }
            y += wrapped(body, model.goal(), compact ? ChatText.SMALL : ChatText.BODY, ChatText.TEXT, x, y, width,
                    Integer.MAX_VALUE, compact ? 20 : 25) + (compact ? 10 : 20);
            if (!compact) { ChatText.label(body, "执行情况", ChatText.SMALL, ChatText.MUTED, x, y + 15); y += 29; }
            Color statusColor = model.attention() ? WARNING : ChatText.ACCENT;
            ChatText.label(body, model.label(), ChatText.BODY, statusColor, x, y + 18);
            String count = model.steps().isEmpty() ? "" : model.completed() + " / " + model.steps().size();
            float countWidth = (float) ChatText.SMALL.getStringBounds(count, ChatText.METRICS).getWidth();
            ChatText.label(body, count, ChatText.SMALL, ChatText.MUTED, x + width - countWidth, y + 17); y += compact ? 25 : 32;
            if (!model.steps().isEmpty()) {
                body.setColor(new Color(34, 53, 58)); body.fillRoundRect(x, y, width, 3, 3, 3);
                body.setColor(statusColor); body.fillRoundRect(x, y, Math.round(width * (float) model.completed() / model.steps().size()), 3, 3, 3); y += 14;
            }
            y += wrapped(body, model.detail(), ChatText.SMALL, ChatText.MUTED, x, y, width, compact ? 1 : 3, 21) + (compact ? 8 : 27);
            if (!compact) { ChatText.label(body, "计划清单", ChatText.SMALL, ChatText.MUTED, x, y + 15); y += 30; }
            if (model.steps().isEmpty()) y += wrapped(body, model.status().equals("PLANNING") ? "计划制定后会显示在这里" : "暂无待执行步骤", ChatText.SMALL, ChatText.MUTED, x, y, width, 2, 22);
            for (int i = 0; i < model.steps().size(); i++) {
                var step = model.steps().get(i);
                boolean done = step.status().equals("COMPLETED") || step.status().equals("SUCCEEDED");
                boolean running = step.status().equals("RUNNING") || step.status().equals("WAITING");
                boolean failed = step.status().equals("FAILED");
                Color color = failed ? WARNING : done || running ? ChatText.ACCENT : ChatText.MUTED;
                int titleHeight = Math.min(2, ChatText.wrap(step.title(), ChatText.BODY, width - 46).size()) * 24;
                int rowHeight = titleHeight + 37;
                if (running || failed) { body.setColor(running ? new Color(24, 46, 49) : new Color(48, 42, 32)); body.fillRoundRect(x, y, width, rowHeight, 8, 8); }
                body.setColor(color); body.drawOval(x + 9, y + 12, 19, 19);
                if (done) checkMark(body, x + 13, y + 18, color);
                else ChatText.label(body, Integer.toString(i + 1), ChatText.SMALL, color, x + (i < 9 ? 15 : 11), y + 27);
                wrapped(body, step.title(), ChatText.BODY, done ? ChatText.MUTED : ChatText.TEXT, x + 39, y + 8, width - 46, 2, 24);
                ChatText.label(body, FleetPresentation.label(step.status()), ChatText.SMALL, color, x + 39, y + titleHeight + 28);
                y += rowHeight + 9;
            }
            contentHeight = y - top + 12;
        } finally { body.dispose(); }
        scroll = Math.min(scroll, maxScroll());
        if (maxScroll() > 0) {
            int track = Math.max(1, area.height - 82), thumb = Math.max(22, track * track / contentHeight);
            int top = area.y + 65 + (int) ((float) scroll / maxScroll() * (track - thumb));
            g.setColor(ChatFrame.BORDER); g.fillRoundRect(area.x + area.width - 7, top, 2, thumb, 2, 2);
        }
    }
    static int wrapped(Graphics2D g, String text, Font font, Color color, int x, int y, int width, int limit, int spacing) {
        var lines = ChatText.wrap(text, font, width);
        if (lines.size() > limit) {
            int end = lines.get(limit - 1).end();
            String cut = text.substring(0, Math.max(0, end - 1)) + "…";
            while (ChatText.wrap(cut, font, width).size() > limit && cut.length() > 1) cut = cut.substring(0, cut.length() - 2) + "…";
            lines = ChatText.wrap(cut, font, width);
        }
        ChatText.drawLines(g, lines, x, y, spacing, color); return lines.size() * spacing;
    }
    static void checkMark(Graphics2D g, int x, int y, Color color) {
        Stroke old = g.getStroke(); g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(color); g.drawLine(x, y + 3, x + 4, y + 7); g.drawLine(x + 4, y + 7, x + 11, y - 1); g.setStroke(old);
    }
}
