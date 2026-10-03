package com.mozhi.assistant.bootstrap.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.input.InputEventAPI;
import com.mozhi.assistant.bridge.FleetAgentAccess;
import java.awt.Color;
import java.awt.Graphics2D;

/** 战役画面上的通讯入口；没有遮罩，不暂停游戏，只有点击入口才打开交流。 */
public final class CampaignChatButton {
    static final int WIDTH = 208, HEIGHT = 82;
    private final ChatTexture texture = new ChatTexture();
    private final FleetNotice notice = new FleetNotice();
    private final Runnable open;
    private FleetPresentation model = FleetPresentation.from(java.util.Map.of());
    private long nextPoll;
    private boolean dirty = true, hovered, pressed;

    public CampaignChatButton(Runnable open) { this.open = open; }
    public void reset() { notice.reset(); nextPoll = 0; dirty = true; hovered = pressed = false; }
    public void acknowledge() { notice.acknowledge(); dirty = true; }
    public void update(boolean conversationOpen) {
        long now = System.nanoTime();
        if (now < nextPoll) return;
        nextPoll = now + 500_000_000L;
        FleetPresentation next = FleetPresentation.from(FleetAgentAccess.view());
        var before = notice.badge(); notice.update(next, conversationOpen);
        if (!next.equals(model) || before != notice.badge()) dirty = true;
        model = next;
    }
    private float left() { return Math.max(8, Global.getSettings().getScreenWidth() - WIDTH - 20); }
    private float bottom() { return Math.max(8, Global.getSettings().getScreenHeight() - 200); }
    public boolean input(InputEventAPI event) {
        if (!event.isMouseEvent()) return false;
        float x = event.getX() - left(), y = HEIGHT - (event.getY() - bottom());
        boolean inside = x >= 0 && x < WIDTH && y >= 0 && y < 66;
        if (hovered != inside) { hovered = inside; dirty = true; }
        if (event.isMouseDownEvent() && event.getEventValue() == 0 && inside) { pressed = true; dirty = true; event.consume(); return true; }
        if (event.isMouseUpEvent() && event.getEventValue() == 0 && pressed) {
            pressed = false; dirty = true; event.consume();
            if (inside) open.run();
            return true;
        }
        if (pressed && event.isMouseMoveEvent()) { event.consume(); return true; }
        return false;
    }
    public void render() {
        texture.draw(Global.getSettings().getScreenWidth(), Global.getSettings().getScreenHeight(), left(), bottom(), WIDTH, HEIGHT,
                Global.getSettings().getScreenScaleMult(), dirty, false, g -> paint(g, model, notice.badge(), hovered, pressed));
        dirty = false;
    }
    public void hidden() { if (hovered || pressed) dirty = true; hovered = pressed = false; }

    static void paint(Graphics2D g, FleetPresentation model, FleetNotice.Badge badge, boolean hover, boolean pressed) {
        Color border = hover ? ChatText.ACCENT : ChatFrame.BORDER;
        ChatFrame.panel(g, 1, 10, 193, 54, 8, pressed ? new Color(28, 53, 57) : new Color(12, 25, 30, 242), border);
        g.setColor(new Color(33, 58, 63)); g.fillRoundRect(12, 20, 34, 34, 8, 8);
        ChatText.label(g, "墨", ChatText.BODY, ChatText.ACCENT, 20, 43);
        ChatText.label(g, "与墨汁交流", ChatText.BODY, ChatText.TEXT, 57, 33);
        String status = model.label();
        if (!model.steps().isEmpty() && !model.attention() && !model.success()) status += "  " + model.completed() + "/" + model.steps().size();
        ChatText.label(g, status, ChatText.SMALL, model.attention() ? FleetStatusPanel.WARNING : ChatText.MUTED, 58, 52);
        if (badge != FleetNotice.Badge.NONE) {
            Color color = badge == FleetNotice.Badge.SUCCESS ? ChatText.ACCENT : FleetStatusPanel.WARNING;
            g.setColor(new Color(9, 20, 24)); g.fillOval(176, 0, 30, 30);
            g.setColor(color); g.fillOval(179, 3, 24, 24);
            if (badge == FleetNotice.Badge.SUCCESS) FleetStatusPanel.checkMark(g, 185, 12, ChatFrame.BACKGROUND);
            else ChatText.label(g, "!", ChatText.BODY.deriveFont(java.awt.Font.BOLD, 20f), ChatFrame.BACKGROUND, 187, 22);
        }
        if (hover) ChatText.label(g, "点击交流 · Ctrl + Shift + M", ChatText.SMALL, ChatText.MUTED, 12, 80);
    }
}
