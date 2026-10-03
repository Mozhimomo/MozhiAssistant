package com.mozhi.assistant.bootstrap.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/** Standalone transition/input checks and a preview rendered by the production painter. */
public final class FleetUiChecks {
    public static void main(String[] args) throws Exception {
        notifications();
        input();
        preview(new File(args[0]));
        System.out.println("Fleet UI checks passed; preview: " + args[0]);
    }

    private static void notifications() {
        FleetNotice notice = new FleetNotice();
        var running = sample("one", "EXECUTING");
        notice.update(running, false);
        require(notice.badge() == FleetNotice.Badge.NONE, "running is quiet");
        notice.update(sample("one", "COMPLETED"), false);
        require(notice.badge() == FleetNotice.Badge.SUCCESS, "completion is announced");
        notice.acknowledge();
        notice.update(sample("one", "COMPLETED"), false);
        require(notice.badge() == FleetNotice.Badge.NONE, "read completion stays quiet");
        notice.update(sample("two", "COMPLETED"), false);
        require(notice.badge() == FleetNotice.Badge.SUCCESS, "new task announces same status");
        notice.update(sample("three", "BLOCKED"), false);
        require(notice.badge() == FleetNotice.Badge.ATTENTION, "manual intervention is announced");
        notice.update(sample("three", "BLOCKED"), true);
        require(notice.badge() == FleetNotice.Badge.ATTENTION, "open conversation does not resolve a blocked task");
        notice.acknowledge();
        notice.update(sample("three", "BLOCKED"), false);
        require(notice.badge() == FleetNotice.Badge.ATTENTION, "closing conversation retains unresolved warning");
        var replanning = FleetPresentation.from(Map.of("state", Map.of("mission", Map.of("id", "four", "status", "PLANNING"),
                "plan", Map.of("steps", List.of(Map.of("status", "FAILED", "action", "BUY", "result", "市场库存不足"))))));
        notice.update(replanning, false);
        require(notice.badge() == FleetNotice.Badge.ATTENTION, "failed step is announced during replanning");
        require(replanning.label().equals("异常 · 重规划中") && replanning.detail().equals("市场库存不足"), "recovery keeps error visible");
        notice.update(replanning, true);
        require(notice.badge() == FleetNotice.Badge.ATTENTION, "reading a failure does not resolve it");
        notice.update(sample("four", "EXECUTING"), false);
        require(notice.badge() == FleetNotice.Badge.NONE, "replacement plan clears resolved warning");
        notice.update(sample("four", "BLOCKED"), false);
        require(notice.badge() == FleetNotice.Badge.ATTENTION, "later failure in same task is announced again");
        notice.update(new FleetPresentation("four", replanning.planId(), replanning.goal(), "CANCELLED", "", replanning.steps()), false);
        require(notice.badge() == FleetNotice.Badge.NONE, "cancelled task ignores old failed step");
        notice.update(replanning, false);
        notice.update(FleetPresentation.from(Map.of("state", Map.of("mission", Map.of("id", "five", "status", "PLANNING")))), false);
        require(notice.badge() == FleetNotice.Badge.NONE, "fresh planning is not an error");
        notice.update(sample("five", "COMPLETED"), false);
        require(notice.badge() == FleetNotice.Badge.SUCCESS, "success replaces warning");
        notice.update(sample("five", "COMPLETED"), true);
        require(notice.badge() == FleetNotice.Badge.NONE, "completion can still be acknowledged");
        var blocked = FleetPresentation.from(Map.of("state", Map.of("mode", "BLOCKED", "reason", "finishReason=LENGTH, outputTokens=8192")));
        require(!blocked.detail().contains("Tokens"), "technical diagnostics hidden");
        require(FleetPresentation.from(Map.of("state", Map.of("mode", "MERGED"))).success(), "merge is success");
        var reviewing = FleetPresentation.from(Map.of("state", Map.of("mode", "MERGED", "mission", Map.of("id", "review", "status", "REVIEWING"))));
        notice.update(reviewing, false);
        require(reviewing.label().equals("验收中") && !reviewing.success() && notice.badge() == FleetNotice.Badge.NONE, "Merge is not goal completion while review is pending");
        var uncertain = FleetPresentation.from(Map.of("state", Map.of("mode", "MERGED", "mission", Map.of("id", "review", "status", "BLOCKED", "reviewReason", "请玩家检查目标"))));
        notice.update(uncertain, false);
        require(uncertain.attention() && !uncertain.success() && notice.badge() == FleetNotice.Badge.ATTENTION, "Uncertain review after merge shows intervention badge");
        notice.reset();
        require(notice.badge() == FleetNotice.Badge.NONE, "save reset clears old state");
    }

    private static void input() {
        SettingsAPI previous = Global.getSettings();
        try {
            Global.setSettings((SettingsAPI) Proxy.newProxyInstance(SettingsAPI.class.getClassLoader(), new Class<?>[]{SettingsAPI.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getScreenWidth" -> 1920f;
                        case "getScreenHeight" -> 1080f;
                        default -> throw new AssertionError(method.getName());
                    }));
            AtomicInteger opened = new AtomicInteger();
            CampaignChatButton button = new CampaignChatButton(opened::incrementAndGet);
            InputEventAPI down = event("Down", 1710, 930);
            require(button.input(down) && down.isConsumed(), "button press cannot move fleet");
            InputEventAPI up = event("Up", 1710, 930);
            require(button.input(up) && up.isConsumed() && opened.get() == 1, "click opens once");
            require(!button.input(event("Down", 100, 100)), "outside press passes through");
            button.input(event("Up", 1710, 930));
            require(opened.get() == 1, "release without button press does not open");
            button.input(event("Down", 1710, 930));
            require(button.input(event("Move", 100, 100)), "drag stays captured");
            require(button.input(event("Up", 100, 100)) && opened.get() == 1, "outside release cancels click");
            button.input(event("Down", 1710, 930));
            button.hidden();
            button.input(event("Up", 1710, 930));
            require(opened.get() == 1, "hidden launcher cancels press");
        } finally { Global.setSettings(previous); }
    }

    private static InputEventAPI event(String type, int x, int y) {
        boolean[] consumed = {false};
        return (InputEventAPI) Proxy.newProxyInstance(InputEventAPI.class.getClassLoader(), new Class<?>[]{InputEventAPI.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "getX" -> x; case "getY" -> y; case "getEventValue" -> 0;
                case "consume" -> { consumed[0] = true; yield null; }
                case "isConsumed" -> consumed[0]; case "isMouseEvent" -> true;
                default -> method.getName().equals("isMouse" + type + "Event");
            };
        });
    }

    private static FleetPresentation sample(String id, String status) {
        return new FleetPresentation(id, "plan", "前往贾加拉购买 100 单位补给，随后返回玩家舰队", status,
                status.equals("BLOCKED") ? "市场补给库存不足，请调整购买数量" : status.equals("COMPLETED") ? "已回归，舰队资产已合并" : "按计划执行中",
                List.of(new FleetPresentation.Step("前往贾加拉", "COMPLETED"),
                        new FleetPresentation.Step("购买 100 单位补给", status.equals("BLOCKED") ? "FAILED" : status.equals("COMPLETED") ? "COMPLETED" : "RUNNING"),
                        new FleetPresentation.Step("返回玩家并合并", status.equals("COMPLETED") ? "COMPLETED" : "PENDING")));
    }

    private static void preview(File output) throws Exception {
        BufferedImage image = new BufferedImage(1040, 820, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            ChatText.configure(g);
            g.setColor(ChatFrame.BACKGROUND); g.fillRect(0, 0, image.getWidth(), image.getHeight());
            String[] states = {"EXECUTING", "COMPLETED", "BLOCKED"};
            FleetNotice.Badge[] badges = {FleetNotice.Badge.NONE, FleetNotice.Badge.SUCCESS, FleetNotice.Badge.ATTENTION};
            for (int i = 0; i < states.length; i++) {
                FleetPresentation model = sample("sample", states[i]);
                FleetStatusPanel panel = new FleetStatusPanel(); panel.setModel(model);
                panel.paint(g, new Rectangle(30 + i * 340, 24, 300, 660));
                Graphics2D hud = (Graphics2D) g.create(70 + i * 340, 708, CampaignChatButton.WIDTH, CampaignChatButton.HEIGHT);
                try { CampaignChatButton.paint(hud, model, badges[i], i == 0, false); } finally { hud.dispose(); }
            }
        } finally { g.dispose(); }
        ImageIO.write(image, "png", output);
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
