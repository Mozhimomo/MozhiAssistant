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
        System.out.println("Intervention queue and chat delivery checks passed");
    }

    private static void detectionAndDelivery() throws Exception {
        require(FleetIntervention.from(view("PLANNING", "a")) == null, "automatic replan must not send a message");
        require(FleetIntervention.from(Map.of("error", "未初始化")) == null, "initialization is not a fleet execution event");
        AtomicReference<Map<String, Object>> state = new AtomicReference<>(view("BLOCKED", "a"));
        FakeAgent agent = new FakeAgent();
        Thread owner = Thread.currentThread();
        try (AgentSession session = new AgentSession(agent, () -> {
            require(Thread.currentThread() == owner, "snapshot must be taken on game thread"); return state.get();
        })) {
            session.setDraft("继续处理这个任务");
            await(session, () -> agent.notices.get() == 1 && !session.busy());
            require(session.history().size() == 1 && session.history().get(0).speaker().equals("墨汁"), "notification must not forge player message");
            require(session.history().get(0).text().equals("舰长，补给库存不足，请确认是否减少购买数量。"), "generated response delivered to chat");
            require(agent.snapshot.contains("购买补给") && agent.snapshot.contains("库存不足"), "snapshot supplies goal and failure");
            require(session.draft().equals("继续处理这个任务"), "notification preserves draft");
            Thread.sleep(550); session.poll();
            require(agent.notices.get() == 1 && !session.busy(), "persistent failure sends once");
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
            require(session.send("你好", false), "user turn starts");
            require(agent.chatEntered.await(2, TimeUnit.SECONDS), "chat worker started");
            session.poll();
            require(agent.notices.get() == 0, "notification waits for existing reply");
            agent.chatGate.countDown();
            await(session, () -> agent.notices.get() == 1 && !session.busy());
            require(session.history().size() == 3, "one user turn followed by one proactive response");
        }
        FakeAgent staleAgent = new FakeAgent(); staleAgent.chatGate = new CountDownLatch(1);
        try (AgentSession session = new AgentSession(staleAgent, state::get)) {
            session.send("更改任务", false); staleAgent.chatEntered.await(2, TimeUnit.SECONDS); session.poll();
            state.set(view("EXECUTING", "new")); staleAgent.chatGate.countDown();
            await(session, () -> !session.busy());
            session.poll();
            require(staleAgent.notices.get() == 0 && !session.busy(), "resolved queued event is discarded before sending");
        }
    }

    private static void failureAndClose() throws Exception {
        FakeAgent agent = new FakeAgent(); agent.fail = true;
        try (AgentSession session = new AgentSession(agent, () -> view("BLOCKED", "a"))) {
            await(session, () -> agent.notices.get() == 1 && !session.busy());
            require(session.history().stream().anyMatch(message -> message.speaker().equals("系统") && message.text().contains("库存不足")), "model failure retains factual error");
            Thread.sleep(550); session.poll(); require(agent.notices.get() == 1, "model failure does not retry every frame");
        }
        FakeAgent waiting = new FakeAgent(); waiting.chatGate = new CountDownLatch(1);
        AgentSession session = new AgentSession(waiting, () -> view("BLOCKED", "a"));
        session.send("正在对话", false); waiting.chatEntered.await(2, TimeUnit.SECONDS); session.poll(); session.close();
        require(waiting.interrupted.await(2, TimeUnit.SECONDS), "save/session reset cancels old worker");
        require(waiting.notices.get() == 0, "closed session cannot dispatch queued notice");
    }

    private static Map<String, Object> view(String status, String task) {
        return Map.of("state", Map.of("mode", status, "reason", "库存不足", "mission", Map.of("id", task, "status", status, "originalGoal", "购买补给"),
                "plan", Map.of("id", "plan", "currentStep", 0, "steps", List.of(Map.of("description", "购买 100 补给", "status", "FAILED")))));
    }
    private static void await(AgentSession session, BooleanSupplier done) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        do { session.poll(); if (done.getAsBoolean()) return; Thread.sleep(10); } while (System.nanoTime() < end);
        throw new AssertionError("async request timed out");
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
            try { if (chatGate != null && !chatGate.await(3, TimeUnit.SECONDS)) throw new AssertionError("chat wait timed out"); }
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
