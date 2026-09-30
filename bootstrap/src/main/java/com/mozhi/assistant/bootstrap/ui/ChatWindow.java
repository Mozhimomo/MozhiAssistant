package com.mozhi.assistant.bootstrap.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.input.InputEventAPI;
import com.mozhi.assistant.bootstrap.AgentSession;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.input.Keyboard;

/** An independent chat canvas. Game widgets are not involved in rendering or editing text. */
public final class ChatWindow {
    private static final Color BACKGROUND = ChatFrame.BACKGROUND;
    private static final Color SURFACE = ChatFrame.SURFACE;
    private static final Color BORDER = ChatFrame.BORDER;
    private static final int LINE_HEIGHT = 27;
    private final ChatTexture texture = new ChatTexture();
    private final ChatEditor editor = new ChatEditor();
    private final ChatPortrait portrait = new ChatPortrait();
    private final Runnable close;
    private final List<Hit> hits = new ArrayList<>();
    private final Map<Long, MessageBlock> blocks = new HashMap<>();
    private AgentSession session;
    private List<AgentSession.ChatMessage> messages = List.of();
    private List<ChatText.Line> inputLines = List.of();
    private String laidOutInput = "";
    private String status = "";
    private String notice = "";
    private long noticeUntil;
    private long confirmUntil;
    private boolean busy;
    private boolean dirty = true;
    private boolean focused = true;
    private boolean selecting;
    private boolean draggingScroll;
    private boolean followLatest = true;
    private boolean caretVisible;
    private String hovered = "";
    private int width, height, sidebar, bodyX, bodyWidth;
    private float left, bottom;
    private Rectangle history = new Rectangle();
    private Rectangle input = new Rectangle();
    private Rectangle scrollbar = new Rectangle();
    private float scroll;
    private int contentHeight;
    private int inputFirstLine;
    private int scrollGrab;
    private long lastPaint;

    private record Hit(String id, Rectangle area, Runnable action) { }
    private record MessageBlock(String text, List<ChatText.Line> lines, int height) { }

    public ChatWindow(Runnable close) {
        this.close = close;
        update();
    }

    public void update() {
        resize();
        AgentSession current = AgentSession.current();
        if (session != current) {
            session = current;
            editor.setText(session.draft());
            messages = List.of(); blocks.clear();
            scroll = 0; followLatest = true;
            dirty = true;
        }
        List<AgentSession.ChatMessage> next = session.history();
        if (!next.equals(messages)) {
            messages = next;
            layoutMessages();
            dirty = true;
        }
        if (busy != session.busy() || !status.equals(session.status())) {
            busy = session.busy(); status = session.status(); dirty = true;
        }
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        if (blink != caretVisible) { caretVisible = blink; if (focused) dirty = true; }
        if (!notice.isEmpty() && System.currentTimeMillis() > noticeUntil) { notice = ""; dirty = true; }
        if (confirmUntil != 0 && System.currentTimeMillis() > confirmUntil) { confirmUntil = 0; dirty = true; }
        layoutInput();
        session.setDraft(editor.text());
    }

    private void resize() {
        int w = Math.max(320, Math.min(1120, (int) Global.getSettings().getScreenWidth() - 64));
        int h = Math.max(360, Math.min(800, (int) Global.getSettings().getScreenHeight() - 64));
        left = (Global.getSettings().getScreenWidth() - w) / 2;
        bottom = (Global.getSettings().getScreenHeight() - h) / 2;
        if (w == width && h == height) return;
        width = w; height = h; sidebar = width >= 960 ? 218 : 0;
        bodyX = sidebar + 30; bodyWidth = width - bodyX - 30;
        history = new Rectangle(bodyX, 110, bodyWidth, height - 346);
        input = new Rectangle(bodyX + 17, height - 178, bodyWidth - 34, 81);
        blocks.clear();
        layoutMessages();
        laidOutInput = null;
        dirty = true;
    }

    private void layoutMessages() {
        contentHeight = 10;
        for (AgentSession.ChatMessage message : messages) {
            MessageBlock block = blocks.get(message.id());
            if (block == null || !block.text().equals(message.text())) {
                List<ChatText.Line> lines = ChatText.wrap(message.text(), ChatText.BODY, bodyWidth - 106);
                block = new MessageBlock(message.text(), lines, lines.size() * LINE_HEIGHT + 72);
                blocks.put(message.id(), block);
            }
            contentHeight += block.height() + 18;
        }
        blocks.keySet().removeIf(id -> messages.stream().noneMatch(message -> message.id() == id));
        scroll = followLatest ? maxScroll() : Math.min(scroll, maxScroll());
    }

    private void layoutInput() {
        if (!editor.text().equals(laidOutInput)) {
            inputLines = ChatText.wrap(editor.text(), ChatText.BODY, input.width - 8);
            laidOutInput = editor.text(); dirty = true;
        }
        int caretRow = ChatText.lineAt(inputLines, editor.caret());
        inputFirstLine = Math.max(0, Math.min(inputFirstLine, Math.max(0, inputLines.size() - 3)));
        if (selecting) ensureCaretVisible(caretRow);
    }

    private void ensureCaretVisible(int row) {
        if (row < inputFirstLine) inputFirstLine = row;
        if (row >= inputFirstLine + 3) inputFirstLine = row - 2;
    }

    private float maxScroll() { return Math.max(0, contentHeight - history.height); }

    public void render() {
        update();
        long now = System.nanoTime();
        boolean repaint = dirty && now - lastPaint >= 33_000_000L;
        texture.draw(Global.getSettings().getScreenWidth(), Global.getSettings().getScreenHeight(),
                left, bottom, width, height, Global.getSettings().getScreenScaleMult(), repaint, this::paint);
        if (repaint) { dirty = false; lastPaint = now; }
    }

    /** Called only by the render listener, including when disposing a closed window. */
    public void dispose() { texture.dispose(); }

    private void paint(Graphics2D g) {
        hits.clear();
        ChatFrame.panel(g, 1, 1, width - 3, height - 3, 15, BACKGROUND, BORDER);
        ChatFrame.brackets(g, 7, 7, width - 15, height - 15, ChatText.ACCENT);
        if (sidebar > 0) {
            paintContact(g);
            ChatText.label(g, "通讯记录", ChatText.TITLE, ChatText.TEXT, bodyX, 53);
        } else {
            portrait.draw(g, bodyX, 25, 48);
            ChatText.label(g, "墨汁", ChatText.TITLE, ChatText.TEXT, bodyX + 62, 57);
        }
        button(g, "new", new Rectangle(width - 202, 29, 130, 36),
                confirmUntil == 0 ? "新对话" : "再次点击清空", false, this::newConversation);
        button(g, "close", new Rectangle(width - 60, 29, 32, 36), "×", false, close);
        g.setColor(BORDER); g.drawLine(bodyX, 94, width - 30, 94);
        g.setColor(ChatText.ACCENT); g.fillRect(bodyX, 93, 48, 2);
        if (messages.isEmpty()) paintEmptyHistory(g); else paintMessages(g);
        paintComposer(g);
    }

    private void paintContact(Graphics2D g) {
        g.setColor(new Color(13, 26, 31)); g.fillRect(17, 17, sidebar - 18, height - 34);
        g.setColor(BORDER); g.drawLine(sidebar, 24, sidebar, height - 24);
        ChatText.label(g, "通讯对象", ChatText.SMALL, ChatText.MUTED, 29, 55);
        ChatText.label(g, "墨汁", ChatText.TITLE, ChatText.TEXT, 29, 93);
        portrait.draw(g, 29, 122, sidebar - 58);
        int separatorY = 122 + sidebar - 58 + 22;
        g.setColor(BORDER); g.drawLine(29, separatorY, sidebar - 29, separatorY);
    }

    private void paintEmptyHistory(Graphics2D g) {
        String text = "暂无消息";
        float textWidth = (float) ChatText.SMALL.getStringBounds(text, ChatText.METRICS).getWidth();
        int centerY = history.y + history.height / 2;
        ChatText.label(g, text, ChatText.SMALL, ChatText.MUTED,
                bodyX + (bodyWidth - textWidth) / 2, centerY + 5);
        g.setColor(BORDER);
        int centerX = bodyX + bodyWidth / 2;
        g.drawLine(centerX - 80, centerY, centerX - 44, centerY);
        g.drawLine(centerX + 44, centerY, centerX + 80, centerY);
    }

    private void paintMessages(Graphics2D g) {
        Shape oldClip = g.getClip();
        g.clip(history);
        int y = history.y + 10 - Math.round(scroll);
        for (AgentSession.ChatMessage message : messages) {
            MessageBlock block = blocks.get(message.id());
            int h = block.height();
            boolean user = "舰长".equals(message.speaker());
            boolean error = "系统".equals(message.speaker());
            int x = bodyX + (user ? 58 : 0);
            int w = bodyWidth - 64;
            if (y + h >= history.y && y < history.y + history.height) {
                Color senderColor = user ? ChatFrame.GOLD : error ? new Color(235, 139, 133) : ChatText.ACCENT;
                ChatFrame.panel(g, x, y, w, h, 7,
                        user ? new Color(29, 35, 33) : error ? new Color(45, 29, 31) : SURFACE, BORDER);
                g.setColor(senderColor); g.fillRect(x, y + 12, 2, 24);
                boolean assistant = "墨汁".equals(message.speaker());
                if (assistant) portrait.draw(g, x + 17, y + 8, 32);
                ChatText.label(g, message.speaker(), ChatText.SMALL, senderColor,
                        x + (assistant ? 60 : 20), y + 29);
                button(g, "copy-" + message.id(), new Rectangle(x + w - 66, y + 8, 54, 26), "复制", false,
                        () -> notify(ChatEditor.copy(message.text()) ? "已复制消息" : "剪贴板暂不可用"));
                ChatText.drawLines(g, block.lines(), x + 20, y + 43, LINE_HEIGHT, ChatText.TEXT);
            }
            y += h + 18;
        }
        g.setClip(oldClip);
        if (maxScroll() > 0) {
            int thumbHeight = Math.max(32, history.height * history.height / contentHeight);
            int thumbY = history.y + Math.round(scroll / maxScroll() * (history.height - thumbHeight));
            scrollbar = new Rectangle(width - 35, thumbY, 5, thumbHeight);
            g.setColor(new Color(81, 124, 128));
            g.fillRect(scrollbar.x, scrollbar.y, scrollbar.width, scrollbar.height);
        } else scrollbar = new Rectangle();
        if (!followLatest && maxScroll() > 0) {
            button(g, "latest", new Rectangle(bodyX + bodyWidth / 2 - 68, history.y + history.height - 42, 136, 34),
                    "↓ 回到最新回复", true, () -> { followLatest = true; scroll = maxScroll(); dirty = true; });
        }
    }

    private void paintComposer(Graphics2D g) {
        int y = height - 201;
        g.setColor(busy ? ChatText.ACCENT : ChatText.MUTED); g.fillRect(bodyX + 2, y - 17, 6, 6);
        String caption = notice.isEmpty() ? messages.isEmpty() && !busy ? "准备就绪" : status : notice;
        Shape clip = g.getClip();
        g.clipRect(bodyX + 16, y - 29, bodyWidth - 20, 25);
        ChatText.label(g, caption, ChatText.SMALL, ChatText.MUTED, bodyX + 17, y - 10);
        g.setClip(clip);
        g.setStroke(new BasicStroke(1f));
        ChatFrame.panel(g, bodyX, y, bodyWidth, 152, 8, new Color(12, 25, 30),
                focused ? new Color(83, 137, 140) : BORDER);
        g.setColor(BORDER); g.drawLine(bodyX + 14, y + 99, bodyX + bodyWidth - 14, y + 99);
        g.clip(input);
        if (editor.text().isEmpty()) {
            ChatText.label(g, "输入消息…", ChatText.BODY, ChatText.MUTED, input.x, input.y + 20);
        }
        for (int row = inputFirstLine; row < Math.min(inputLines.size(), inputFirstLine + 4); row++) {
            ChatText.Line line = inputLines.get(row);
            int top = input.y + (row - inputFirstLine) * LINE_HEIGHT;
            int start = Math.max(line.start(), editor.selectionStart());
            int end = Math.min(line.end(), editor.selectionEnd());
            if (end > start) {
                g.setColor(new Color(45, 87, 95));
                g.fillRect(input.x + (int) line.x(start), top, Math.max(2, (int) (line.x(end) - line.x(start))), LINE_HEIGHT);
            }
            g.setColor(ChatText.TEXT);
            if (line.layout() != null) line.layout().draw(g, input.x, top + 20);
        }
        int caretRow = ChatText.lineAt(inputLines, editor.caret());
        if (focused && caretVisible) {
            int x = input.x + Math.round(inputLines.get(caretRow).x(editor.caret()));
            int top = input.y + (caretRow - inputFirstLine) * LINE_HEIGHT;
            g.setColor(ChatText.ACCENT); g.fillRect(x, top + 1, 2, 23);
        }
        g.setClip(clip);
        ChatText.label(g, editor.text().length() + " / " + ChatEditor.LIMIT, ChatText.SMALL, ChatText.MUTED,
                bodyX + 17, y + 133);
        boolean canSend = !busy && !editor.text().isBlank();
        button(g, "send", new Rectangle(width - 153, y + 106, 106, 34), busy ? "回复中…" : "发送", canSend,
                this::send);
        ChatText.label(g, "Enter 发送 · Shift+Enter 换行 · Ctrl+V 粘贴 · Esc 收起", ChatText.SMALL,
                ChatText.MUTED, bodyX + 2, height - 22);
    }

    private void button(Graphics2D g, String id, Rectangle r, String text, boolean accent, Runnable action) {
        boolean enabled = !"send".equals(id) || (!busy && !editor.text().isBlank());
        boolean hover = enabled && id.equals(hovered);
        Color border = accent || hover ? ChatText.ACCENT : BORDER;
        Color fill = accent || hover ? new Color(30, 62, 66) : new Color(19, 35, 40);
        ChatFrame.panel(g, r.x, r.y, r.width, r.height, 5, fill, border);
        float tw = (float) ChatText.SMALL.getStringBounds(text, ChatText.METRICS).getWidth();
        ChatText.label(g, text, ChatText.SMALL, !enabled ? ChatText.MUTED : accent ? ChatText.ACCENT : ChatText.TEXT,
                r.x + (r.width - tw) / 2, r.y + r.height / 2f + 5);
        Rectangle active = id.startsWith("copy-") ? r.intersection(history) : r;
        if (enabled) hits.add(new Hit(id, active, action));
    }

    public void input(InputEventAPI event) {
        if (event.isKeyDownEvent()) { key(event); return; }
        if (!event.isMouseEvent()) return;
        int x = Math.round(event.getX() - left);
        int y = Math.round(height - (event.getY() - bottom));
        if (event.isMouseScrollEvent()) {
            int delta = -Integer.signum(event.getEventValue());
            if (input.contains(x, y)) {
                inputFirstLine = Math.max(0, Math.min(Math.max(0, inputLines.size() - 3), inputFirstLine + delta * 2));
            } else {
                scroll = Math.max(0, Math.min(maxScroll(), scroll + delta * 70));
                followLatest = scroll >= maxScroll() - 8;
            }
            dirty = true;
        }
        if (event.isLMBDownEvent()) {
            if (input.contains(x, y)) {
                focused = selecting = true;
                editor.moveTo(hitEditor(x, y), event.isShiftDown());
                if (event.isDoubleClick()) editor.selectAll();
                dirty = true;
            } else if (x >= width - 43 && x <= width - 20 && history.contains(x, y) && maxScroll() > 0) {
                draggingScroll = true;
                scrollGrab = scrollbar.contains(x, y) ? y - scrollbar.y : scrollbar.height / 2;
                dragScrollbar(y);
            } else {
                for (int i = hits.size() - 1; i >= 0; i--) {
                    if (hits.get(i).area().contains(x, y)) { hits.get(i).action().run(); dirty = true; break; }
                }
            }
        }
        if (event.isMouseMoveEvent()) {
            if (selecting) { editor.moveTo(hitEditor(x, y), true); ensureCaretVisible(ChatText.lineAt(inputLines, editor.caret())); dirty = true; }
            if (draggingScroll) dragScrollbar(y);
            String hover = "";
            for (Hit hit : hits) if (hit.area().contains(x, y)) hover = hit.id();
            if (!hover.equals(hovered)) { hovered = hover; dirty = true; }
        }
        if (event.isLMBUpEvent()) { selecting = false; draggingScroll = false; }
    }

    private int hitEditor(int x, int y) {
        int row = Math.max(0, Math.min(inputLines.size() - 1, inputFirstLine + Math.floorDiv(y - input.y, LINE_HEIGHT)));
        return inputLines.get(row).hit(x - input.x);
    }

    private void dragScrollbar(int y) {
        scroll = Math.max(0, Math.min(maxScroll(), (float) (y - history.y - scrollGrab)
                / Math.max(1, history.height - scrollbar.height) * maxScroll()));
        followLatest = scroll >= maxScroll() - 8; dirty = true;
    }

    private void key(InputEventAPI event) {
        int key = event.getEventValue();
        if (key == Keyboard.KEY_ESCAPE || (key == Keyboard.KEY_M && event.isCtrlDown() && event.isShiftDown())) {
            close.run(); return;
        }
        if (key == Keyboard.KEY_PRIOR || key == Keyboard.KEY_NEXT) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll + (key == Keyboard.KEY_PRIOR ? -1 : 1) * history.height * .8f));
            followLatest = scroll >= maxScroll() - 8; dirty = true; return;
        }
        if (!focused) return;
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            if (event.isShiftDown()) editor.insert("\n"); else if (!event.isRepeat()) send();
        } else if (!editor.key(key, event.isCtrlDown(), event.isShiftDown(), inputLines)
                && !event.isCtrlDown() && !event.isAltDown() && !Character.isISOControl(event.getEventChar())) {
            editor.insert(String.valueOf(event.getEventChar()));
        }
        layoutInput(); ensureCaretVisible(ChatText.lineAt(inputLines, editor.caret()));
        session.setDraft(editor.text()); dirty = true;
    }

    private void send() {
        if (session.send(editor.text().strip(), false)) {
            editor.setText(""); session.setDraft(""); inputFirstLine = 0;
            followLatest = true; focused = true; update(); dirty = true;
        }
    }

    private void newConversation() {
        if ((!messages.isEmpty() || !editor.text().isEmpty()) && confirmUntil == 0) {
            confirmUntil = System.currentTimeMillis() + 4000;
            notify("再次点击以清空当前对话，长期记忆保留。"); return;
        }
        AgentSession.reset(); confirmUntil = 0; notice = ""; inputFirstLine = 0;
        update(); dirty = true;
    }

    private void notify(String value) {
        notice = value; noticeUntil = System.currentTimeMillis() + 4000; dirty = true;
    }
}