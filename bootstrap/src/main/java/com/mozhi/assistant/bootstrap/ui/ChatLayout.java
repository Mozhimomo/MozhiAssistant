package com.mozhi.assistant.bootstrap.ui;

import java.awt.Rectangle;

/** 对话、状态和计划共用的响应式布局，坐标使用游戏逻辑像素。 */
record ChatLayout(int width, int height, boolean split, Rectangle conversation, Rectangle overview, Rectangle plan) {
    static ChatLayout fit(int screenWidth, int screenHeight) {
        int w = Math.max(280, Math.min(1560, screenWidth - 48));
        int h = Math.max(360, Math.min(980, screenHeight - 48));
        boolean split = w >= 1080;
        int top = split ? 110 : 150;
        int side = Math.min(520, Math.max(360, Math.round(w * .35f)));
        int bodyWidth = split ? w - side - 82 : w - 60;
        Rectangle conversation = new Rectangle(30, top, bodyWidth, h - top - 24);
        Rectangle overview = split ? new Rectangle(w - side - 24, top, side, 296)
                : new Rectangle(24, top, w - 48, h - top - 24);
        Rectangle plan = split ? new Rectangle(overview.x, top + 312, side, h - top - 336)
                : new Rectangle(overview);
        // 矮屏保留可滚动的计划高度，改用标签避免上下区域挤压。
        if (split && plan.height < 180) return new ChatLayout(w, h, false,
                new Rectangle(30, 150, w - 60, h - 174),
                new Rectangle(24, 150, w - 48, h - 174), new Rectangle(24, 150, w - 48, h - 174));
        return new ChatLayout(w, h, split, conversation, overview, plan);
    }
}
