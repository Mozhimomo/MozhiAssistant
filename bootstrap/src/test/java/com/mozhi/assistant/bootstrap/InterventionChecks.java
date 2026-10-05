package com.mozhi.assistant.bootstrap;

import com.mozhi.assistant.bridge.AgentBridge;
import com.mozhi.assistant.bridge.AgentStreamListener;
import com.mozhi.assistant.bridge.GameThreadAccess;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class InterventionChecks {
    public static void main(String[] args) throws Exception {
        detectionAndDelivery();
        busyAndStale();
        failureAndClose();
        tankerCapacityNotice();
        completedNotice();
        planningFailureFacts();
        System.out.println("人工介入队列与聊天消息投递检查通过");
    }
    private static void planningFailureFacts() {
        var data = Map.<String, Object>of("state", Map.of("mode", "BLOCKED", "reason", "规划失败：模型达到输出上限",
                "mission", Map.of("id", "planning", "status", "BLOCKED", "originalGoal", "跑商到50万，初始资金216800"),
                "plan", Map.of("id", "plan", "currentStep", 0, "steps", List.of(Map.of("description", "购买16重型机械",
                        "status", "FAILED", "result", "实际库存不足：请求16，实际15.8；未成交")))));
        String snapshot = FleetIntervention.from(data).snapshot();
        require(snapshot.contains("实际库存不足") && snapshot.contains("已记录动作状态：FAILED") && snapshot.contains("规划失败：模型达到输出上限"), "保留执行失败和后续规划失败两项独立事实");
        require(snapshot.contains("不能推断资金未变") && !snapshot.contains("受阻步骤："), "当前步骤和初始目标不能证明资金余额或此前零交易");
    }

    private static void detectionAndDelivery() throws Exception {
        require(FleetIntervention.from(view("PLANNING", "a")) == null, "自动重规划不应发送消息");
        require(FleetIntervention.from(Map.of("error", "未初始化")) == null, "初始化不是舰队执行事件");
        AtomicReference<Map<String, Object>> state = new AtomicReference<>(view("BLOCKED", "a"));
        FakeAgent agent = new FakeAgent();
        Thread owner = Thread.currentThread();
        try (AgentSession session = new AgentSession(agent, () -> {
            require(Thread.currentThread() == owner, "必须在游戏线程采集快照"); return state.get();
        })) {
            session.setDraft("继续处理这个任务");
            await(session, () -> agent.notices.get() == 1 && !session.busy());
            require(session.history().size() == 1 && session.history().get(0).speaker().equals("墨汁"), "通知不得伪造玩家消息");
            require(session.history().get(0).text().equals("舰长，补给库存不足，请确认是否减少购买数量。"), "生成的回复投递到聊天界面");
            require(agent.snapshot.contains("购买补给") && agent.snapshot.contains("库存不足"), "快照提供目标与失败信息");
            require(session.draft().equals("继续处理这个任务"), "通知保留输入草稿");
            Thread.sleep(550); session.poll();
            require(agent.notices.get() == 1 && !session.busy(), "持续失败只通知一次");
            state.set(view("EXECUTING", "a")); Thread.sleep(550); session.poll();
            state.set(view("BLOCKED", "a"));
            await(session, () -> agent.notices.get() == 2 && !session.busy());
            state.set(view("BLOCKED", "b"));
            await(session, () -> agent.notices.get() == 3 && !session.busy());
        }
    }

    private static void busyAndStale() throws Exception {
        AtomicReference<Map<String, Object>> state = new AtomicReference<>(view("BLOCKED", "a"));
        FakeAgent agent = new FakeAgent(); agent.chatGate = new CountDownLatch(1);
        try (AgentSession session = new AgentSession(agent, state::get)) {
            require(session.send("你好", false), "玩家对话轮次启动");
            require(agent.chatEntered.await(2, TimeUnit.SECONDS), "聊天工作线程已启动");
            session.poll();
            require(agent.notices.get() == 0, "通知等待已有回复完成");
            agent.chatGate.countDown();
            await(session, () -> agent.notices.get() == 1 && !session.busy());
            require(session.history().size() == 3, "一轮玩家对话之后发送一条主动回复");
        }
        FakeAgent staleAgent = new FakeAgent(); staleAgent.chatGate = new CountDownLatch(1);
        try (AgentSession session = new AgentSession(staleAgent, state::get)) {
            session.send("更改任务", false); staleAgent.chatEntered.await(2, TimeUnit.SECONDS); session.poll();
            state.set(view("EXECUTING", "new")); staleAgent.chatGate.countDown();
            await(session, () -> !session.busy());
            session.poll();
            require(staleAgent.notices.get() == 0 && !session.busy(), "已解决的排队事件在发送前丢弃");
        }
    }

    private static void failureAndClose() throws Exception {
        FakeAgent agent = new FakeAgent(); agent.fail = true;
        try (AgentSession session = new AgentSession(agent, () -> view("BLOCKED", "a"))) {
            await(session, () -> agent.notices.get() == 1 && !session.busy());
            require(session.history().stream().anyMatch(message -> message.speaker().equals("系统") && message.text().contains("库存不足")), "模型调用失败时保留事实错误信息");
            Thread.sleep(550); session.poll(); require(agent.notices.get() == 1, "模型失败后不逐帧重试");
        }
        FakeAgent waiting = new FakeAgent(); waiting.chatGate = new CountDownLatch(1);
        AgentSession session = new AgentSession(waiting, () -> view("BLOCKED", "a"));
        session.send("正在对话", false); waiting.chatEntered.await(2, TimeUnit.SECONDS); session.poll(); session.close();
        require(waiting.interrupted.await(2, TimeUnit.SECONDS), "存档或会话重置取消旧工作线程");
        require(waiting.notices.get() == 0, "已关闭会话不能发送排队通知");
    }

    private static void tankerCapacityNotice() throws Exception {
        String reason = "加满燃料仍不足 15 光年，请购买油船并编入墨汁舰队";
        var state = Map.<String, Object>of("state", Map.of("mode", "BLOCKED", "mission", Map.of("id", "tanker-task", "status", "BLOCKED", "originalGoal", "跑商",
                "reviewReason", reason), "plan", Map.of("steps", List.of())));
        FakeAgent agent = new FakeAgent();
        try (AgentSession session = new AgentSession(agent, () -> state)) {
            await(session, () -> agent.notices.get() == 1 && !session.busy());
            require(agent.snapshot.contains("油船") && agent.snapshot.contains("15 光年"), "即使没有失败步骤，容量阻塞事件也能传递给对话智能体");
            require(session.history().size() == 1, "需要玩家介入时生成聊天消息");
        }
    }

    private static void completedNotice() throws Exception {
        var state = Map.<String, Object>of("state", Map.of("mode", "COMPLETED", "awaitingReturnConfirmation", true,
                "reason", "舰队星币已达到100万", "mission", Map.of("id", "trade-task", "status", "COMPLETED", "originalGoal", "跑商到100万")));
        FakeAgent agent = new FakeAgent();
        try (AgentSession session = new AgentSession(agent, () -> state)) {
            await(session, () -> agent.notices.get() == 1 && !session.busy());
            require(agent.snapshot.contains("任务完成") && agent.snapshot.contains("询问是否返航") && !agent.snapshot.contains("异常原因"),
                    "完成任务后主动询问返航权限，不显示失败");
            Thread.sleep(550); session.poll();
            require(agent.notices.get() == 1, "等待玩家时，已完成任务只通知一次");
        }
        require(FleetIntervention.from(Map.of("state", Map.of("mode", "MERGED", "mission", Map.of("status", "COMPLETED")))) == null,
                "已合并舰队不再询问是否返航");
    }

    private static Map<String, Object> view(String status, String task) {
        return Map.of("state", Map.of("mode", status, "reason", "库存不足", "mission", Map.of("id", task, "status", status, "originalGoal", "购买补给"),
                "plan", Map.of("id", "plan", "currentStep", 0, "steps", List.of(Map.of("description", "购买 100 补给", "status", "FAILED")))));
    }
    private static void await(AgentSession session, BooleanSupplier done) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        do { session.poll(); if (done.getAsBoolean()) return; Thread.sleep(10); } while (System.nanoTime() < end);
        throw new AssertionError("异步请求超时");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final class FakeAgent implements AgentBridge {
        final AtomicInteger notices = new AtomicInteger();
        final CountDownLatch chatEntered = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        CountDownLatch chatGate;
        volatile String snapshot;
        boolean fail;
        public void initialize(String config, GameThreadAccess gameThread) {}
        public String chat(String message) {
            chatEntered.countDown();
            try { if (chatGate != null && !chatGate.await(3, TimeUnit.SECONDS)) throw new AssertionError("等待聊天超时"); }
            catch (InterruptedException e) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return "收到舰长的消息。";
        }
        public String notifyFleetIntervention(String snapshot, AgentStreamListener listener) {
            this.snapshot = snapshot; notices.incrementAndGet();
            if (fail) throw new IllegalStateException("模拟模型不可用");
            listener.onPartialText("舰长，补给库存不足");
            return "舰长，补给库存不足，请确认是否减少购买数量。";
        }
        public String diagnostics() { return ""; }
        public String toolTrace() { return ""; }
    }
}
