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

/** 独立验证状态转换与输入，并使用正式绘制器生成预览。 */
public final class FleetUiChecks {
    public static void main(String[] args) throws Exception {
        notifications();
        input();
        layoutAndResources();
        preview(new File(args[0]));
        windowPreview(new File(args[0]).getParentFile(), 1920, 1080, "chat-desktop", 0);
        windowPreview(new File(args[0]).getParentFile(), 1366, 768, "chat-laptop", 0);
        for (int tab = 0; tab < 3; tab++) windowPreview(new File(args[0]).getParentFile(), 1024, 768, "chat-compact-" + tab, tab);
        System.out.println("舰队界面检查通过；预览：" + args[0]);
    }

    private static void notifications() {
        FleetNotice notice = new FleetNotice();
        var running = sample("one", "EXECUTING");
        notice.update(running, false);
        require(notice.badge() == FleetNotice.Badge.NONE, "运行中不显示提示");
        notice.update(sample("one", "COMPLETED"), false);
        require(notice.badge() == FleetNotice.Badge.SUCCESS, "完成时显示通知");
        notice.acknowledge();
        notice.update(sample("one", "COMPLETED"), false);
        require(notice.badge() == FleetNotice.Badge.NONE, "已读的完成通知保持安静");
        notice.update(sample("two", "COMPLETED"), false);
        require(notice.badge() == FleetNotice.Badge.SUCCESS, "新任务即使状态相同也会通知");
        notice.update(sample("three", "BLOCKED"), false);
        require(notice.badge() == FleetNotice.Badge.ATTENTION, "需要人工介入时显示通知");
        notice.update(sample("three", "BLOCKED"), true);
        require(notice.badge() == FleetNotice.Badge.ATTENTION, "打开对话不会解除任务阻塞");
        notice.acknowledge();
        notice.update(sample("three", "BLOCKED"), false);
        require(notice.badge() == FleetNotice.Badge.ATTENTION, "关闭对话后保留未解决的警告");
        var replanning = FleetPresentation.from(Map.of("state", Map.of("mission", Map.of("id", "four", "status", "PLANNING"),
                "plan", Map.of("steps", List.of(Map.of("status", "FAILED", "action", "BUY", "result", "市场库存不足"))))));
        notice.update(replanning, false);
        require(notice.badge() == FleetNotice.Badge.ATTENTION, "重规划期间仍显示失败步骤提示");
        require(replanning.label().equals("异常 · 重规划中") && replanning.detail().equals("市场库存不足"), "恢复期间错误提示仍可见");
        notice.update(replanning, true);
        require(notice.badge() == FleetNotice.Badge.ATTENTION, "阅读失败信息不会解决失败");
        notice.update(sample("four", "EXECUTING"), false);
        require(notice.badge() == FleetNotice.Badge.NONE, "替换计划清除已解决的警告");
        notice.update(sample("four", "BLOCKED"), false);
        require(notice.badge() == FleetNotice.Badge.ATTENTION, "同一任务之后再次失败时重新通知");
        notice.update(new FleetPresentation("four", replanning.planId(), replanning.goal(), "CANCELLED", "", replanning.steps()), false);
        require(notice.badge() == FleetNotice.Badge.NONE, "已取消任务忽略旧失败步骤");
        notice.update(replanning, false);
        notice.update(FleetPresentation.from(Map.of("state", Map.of("mission", Map.of("id", "five", "status", "PLANNING")))), false);
        require(notice.badge() == FleetNotice.Badge.NONE, "新任务规划不属于错误");
        notice.update(sample("five", "COMPLETED"), false);
        require(notice.badge() == FleetNotice.Badge.SUCCESS, "成功状态替换警告");
        notice.update(sample("five", "COMPLETED"), true);
        require(notice.badge() == FleetNotice.Badge.NONE, "完成通知仍可标记已读");
        var blocked = FleetPresentation.from(Map.of("state", Map.of("mode", "BLOCKED", "reason", "finishReason=LENGTH, outputTokens=8192")));
        require(!blocked.detail().contains("Tokens"), "隐藏技术诊断信息");
        require(FleetPresentation.from(Map.of("state", Map.of("mode", "MERGED"))).success(), "合并视为成功");
        var reviewing = FleetPresentation.from(Map.of("state", Map.of("mode", "MERGED", "mission", Map.of("id", "review", "status", "REVIEWING"))));
        notice.update(reviewing, false);
        require(reviewing.label().equals("验收中") && !reviewing.success() && notice.badge() == FleetNotice.Badge.NONE, "等待验收时，合并不等于目标完成");
        var uncertain = FleetPresentation.from(Map.of("state", Map.of("mode", "MERGED", "mission", Map.of("id", "review", "status", "BLOCKED", "reviewReason", "请玩家检查目标"))));
        notice.update(uncertain, false);
        require(uncertain.attention() && !uncertain.success() && notice.badge() == FleetNotice.Badge.ATTENTION, "合并后的验收无法确认时显示人工介入标记");
        notice.reset();
        require(notice.badge() == FleetNotice.Badge.NONE, "存档重置清除旧状态");
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
            require(button.input(down) && down.isConsumed(), "按下按钮不会移动舰队");
            InputEventAPI up = event("Up", 1710, 930);
            require(button.input(up) && up.isConsumed() && opened.get() == 1, "一次点击只打开一次");
            require(!button.input(event("Down", 100, 100)), "按钮外按下事件正常传递");
            button.input(event("Up", 1710, 930));
            require(opened.get() == 1, "没有按下按钮时，松开事件不打开窗口");
            button.input(event("Down", 1710, 930));
            require(button.input(event("Move", 100, 100)), "拖动期间持续捕获输入");
            require(button.input(event("Up", 100, 100)) && opened.get() == 1, "按钮外松开取消点击");
            button.input(event("Down", 1710, 930));
            button.hidden();
            button.input(event("Up", 1710, 930));
            require(opened.get() == 1, "隐藏入口按钮时取消按下状态");
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

    private static Map<String, Object> fleetSnapshot() {
        return Map.of("fleetId", "preview", "location", "Corvus 星系 · Jangala", "ships", List.of("a", "b"),
                "logistics", Map.of("credits", 953689, "supplies", 1476.6, "supplyDays", 886,
                        "fuel", 259, "fuelCapacity", 800, "fuelRangeLy", 12.3, "crew", 179,
                        "requiredCrew", 800, "readiness", .65, "cargoSpaceLeft", 1902));
    }

    private static void layoutAndResources() {
        for (int[] size : new int[][]{{1920,1080},{1366,768},{1024,768},{800,600},{640,480}}) {
            ChatLayout layout = ChatLayout.fit(size[0], size[1]);
            require(layout.width() <= size[0] && layout.height() <= size[1], "窗口不能超出屏幕");
            Rectangle window = new Rectangle(0, 0, layout.width(), layout.height());
            for (Rectangle area : List.of(layout.conversation(), layout.overview(), layout.plan()))
                require(area.width > 0 && area.height > 0 && window.contains(area), "区域保持可见：" + area);
            if (layout.split()) require(!layout.conversation().intersects(layout.plan())
                    && !layout.conversation().intersects(layout.overview()) && !layout.overview().intersects(layout.plan()), "三块区域不能重叠");
        }
        require(ChatLayout.fit(1920,1080).width() == 1560 && ChatLayout.fit(1920,1080).height() == 980, "扩大桌面窗口");
        require(!ChatLayout.fit(1024,768).split(), "窄屏使用三个标签");
        var overview = FleetOverview.from(fleetSnapshot());
        require(overview.present() && overview.credits().equals("953,689"), "展示实时资金");
        require(overview.metrics().get(1).warning() && overview.metrics().get(2).warning()
                && !overview.metrics().get(0).warning(), "燃料和船员短缺仅在状态区提示");
        require(!FleetOverview.from(Map.of()).present() && FleetOverview.from(Map.of()).credits().equals("—"), "未派遣不显示虚假的零余额");
        require(FleetOverview.from(Map.of("state",Map.of("mode","MERGED"))).empty().contains("合并"), "合并后明确说明状态");
    }

    /** 使用真实窗口绘制路径输出离线预览，不启动模型或 OpenGL。 */
    private static void windowPreview(File output, int screenWidth, int screenHeight, String name, int tab) throws Exception {
        SettingsAPI previous = Global.getSettings();
        try {
            Global.setSettings((SettingsAPI) Proxy.newProxyInstance(SettingsAPI.class.getClassLoader(), new Class<?>[]{SettingsAPI.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getScreenWidth" -> (float) screenWidth;
                        case "getScreenHeight" -> (float) screenHeight;
                        case "openStream" -> new java.io.FileInputStream(String.valueOf(arguments[0]));
                        default -> throw new AssertionError(method.getName());
                    }));
            ChatWindow window = new ChatWindow(() -> {});
            field(window, "messages").set(window, List.of(
                    new com.mozhi.assistant.bootstrap.AgentSession.ChatMessage(1, "舰长", "让分舰队继续跑商，直到拥有 100 万星币。完成后通知我，先不要返航。"),
                    new com.mozhi.assistant.bootstrap.AgentSession.ChatMessage(2, "墨汁", "收到，舰长。目标是分舰队资金达到 100 万星币，完成后等你决定是否返航。\n\n目前有两项后勤需要优先处理：\n• 船员 179 人，最低需求 800 人。\n• 燃料剩余航程约 12.3 光年。\n\n我会先安排补充，再计算下一轮跑商路线。你可以在右侧查看实时状态与执行进度。"),
                    new com.mozhi.assistant.bootstrap.AgentSession.ChatMessage(3, "舰长", "好的，补充完成之后继续。")));
            field(window, "status").set(window, "准备就绪");
            field(window, "activeTab").setInt(window, tab);
            ((FleetStatusPanel) field(window, "fleetPanel").get(window)).setModel(new FleetPresentation("preview", "route", "自主跑商，直到分舰队拥有 100 万星币；完成后通知舰长，未经同意不返航。", "EXECUTING", "正在前往 Jangala，先补充人员和燃料。",
                    List.of(new FleetPresentation.Step("查询附近可采购的市场", "COMPLETED"), new FleetPresentation.Step("前往 Jangala 并进入环绕轨道", "RUNNING"),
                            new FleetPresentation.Step("购买 621 名船员", "PENDING"), new FleetPresentation.Step("补充燃料", "PENDING"), new FleetPresentation.Step("计算并执行下一轮跑商路线", "PENDING"))));
            ((FleetOverviewPanel) field(window, "overviewPanel").get(window)).setModel(FleetOverview.from(fleetSnapshot()));
            var layoutMessages = ChatWindow.class.getDeclaredMethod("layoutMessages"); layoutMessages.setAccessible(true); layoutMessages.invoke(window);
            ChatLayout layout = ChatLayout.fit(screenWidth, screenHeight);
            BufferedImage image = new BufferedImage(layout.width(), layout.height(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            try {
                ChatText.configure(g);
                var paint = ChatWindow.class.getDeclaredMethod("paint", Graphics2D.class); paint.setAccessible(true); paint.invoke(window, g);
            } finally { g.dispose(); }
            ImageIO.write(image, "png", new File(output, name + ".png"));
        } finally { Global.setSettings(previous); }
    }

    private static java.lang.reflect.Field field(ChatWindow window, String name) throws Exception {
        var field = ChatWindow.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
