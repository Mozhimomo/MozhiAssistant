package com.mozhi.assistant.bootstrap.ui;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.lwjgl.input.Keyboard;

/** 独立于游戏 TextFieldAPI 的多行编辑器；UTF-16 光标偏移始终位于码点边界。 */
final class ChatEditor {
    static final int LIMIT = 8000;
    private String text = "";
    private int caret;
    private int anchor;
    private final Deque<State> undo = new ArrayDeque<>();
    private final Deque<State> redo = new ArrayDeque<>();
    private record State(String text, int caret, int anchor) { }

    String text() { return text; }
    int caret() { return caret; }
    int selectionStart() { return Math.min(caret, anchor); }
    int selectionEnd() { return Math.max(caret, anchor); }

    void setText(String value) {
        text = clean(value);
        caret = anchor = text.length();
        undo.clear();
        redo.clear();
    }

    void insert(String value) {
        value = clean(value);
        int available = LIMIT - (text.length() - (selectionEnd() - selectionStart()));
        if (value.length() > available) value = value.substring(0, safeOffset(value, available));
        if (value.isEmpty() && selectionStart() == selectionEnd()) return;
        remember();
        int start = selectionStart();
        text = text.substring(0, start) + value + text.substring(selectionEnd());
        caret = anchor = start + value.length();
    }

    void moveTo(int offset, boolean extend) {
        caret = safeOffset(text, Math.max(0, Math.min(text.length(), offset)));
        if (!extend) anchor = caret;
    }

    void selectAll() { anchor = 0; caret = text.length(); }

    boolean key(int key, boolean ctrl, boolean shift, List<ChatText.Line> lines) {
        if (ctrl) {
            switch (key) {
                case Keyboard.KEY_A -> { selectAll(); return true; }
                case Keyboard.KEY_C -> { copySelection(); return true; }
                case Keyboard.KEY_X -> { if (copySelection()) insert(""); return true; }
                case Keyboard.KEY_V -> { String value = readClipboard(); if (value != null) insert(value); return true; }
                case Keyboard.KEY_Z -> { restore(shift ? redo : undo, shift ? undo : redo); return true; }
                case Keyboard.KEY_Y -> { restore(redo, undo); return true; }
            }
        }
        switch (key) {
            case Keyboard.KEY_LEFT, Keyboard.KEY_RIGHT -> {
                boolean right = key == Keyboard.KEY_RIGHT;
                int target = !shift && caret != anchor ? (right ? selectionEnd() : selectionStart())
                        : ctrl ? wordBoundary(right) : step(caret, right);
                moveTo(target, shift);
            }
            case Keyboard.KEY_HOME, Keyboard.KEY_END -> {
                ChatText.Line line = lines.get(ChatText.lineAt(lines, caret));
                moveTo(ctrl ? (key == Keyboard.KEY_HOME ? 0 : text.length())
                        : (key == Keyboard.KEY_HOME ? line.start() : line.end()), shift);
            }
            case Keyboard.KEY_UP, Keyboard.KEY_DOWN -> {
                int row = ChatText.lineAt(lines, caret);
                int next = Math.max(0, Math.min(lines.size() - 1, row + (key == Keyboard.KEY_UP ? -1 : 1)));
                moveTo(lines.get(next).hit(lines.get(row).x(caret)), shift);
            }
            case Keyboard.KEY_BACK, Keyboard.KEY_DELETE -> {
                if (caret == anchor) anchor = ctrl ? wordBoundary(key == Keyboard.KEY_DELETE)
                        : step(caret, key == Keyboard.KEY_DELETE);
                insert("");
            }
            default -> { return false; }
        }
        return true;
    }

    private int step(int from, boolean right) {
        if (right && from < text.length()) return text.offsetByCodePoints(from, 1);
        if (!right && from > 0) return text.offsetByCodePoints(from, -1);
        return from;
    }

    private int wordBoundary(boolean right) {
        int offset = caret;
        while (offset > 0 && offset < text.length()
                && Character.isWhitespace(text.codePointAt(right ? offset : step(offset, false)))) {
            offset = step(offset, right);
        }
        while (right ? offset < text.length() : offset > 0) {
            int next = right ? offset : step(offset, false);
            if (Character.isWhitespace(text.codePointAt(next))) break;
            offset = step(offset, right);
        }
        return offset;
    }

    private boolean copySelection() {
        return selectionStart() != selectionEnd() && copy(text.substring(selectionStart(), selectionEnd()));
    }

    static boolean copy(String value) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
            return true;
        } catch (Exception unavailable) { return false; }
    }

    private static String readClipboard() {
        try {
            Object value = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            return value instanceof String ? (String) value : null;
        } catch (Exception unavailable) { return null; }
    }

    private void remember() {
        undo.push(new State(text, caret, anchor));
        if (undo.size() > 80) undo.removeLast();
        redo.clear();
    }

    private void restore(Deque<State> source, Deque<State> destination) {
        if (source.isEmpty()) return;
        destination.push(new State(text, caret, anchor));
        State state = source.pop();
        text = state.text(); caret = state.caret(); anchor = state.anchor();
    }

    private static int safeOffset(String value, int offset) {
        return offset > 0 && offset < value.length() && Character.isLowSurrogate(value.charAt(offset))
                && Character.isHighSurrogate(value.charAt(offset - 1)) ? offset - 1 : offset;
    }

    private static String clean(String value) {
        value = value.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ");
        StringBuilder result = new StringBuilder();
        value.codePoints().filter(c -> c == '\n' || !Character.isISOControl(c)).forEach(result::appendCodePoint);
        return result.substring(0, safeOffset(result.toString(), Math.min(LIMIT, result.length())));
    }
}