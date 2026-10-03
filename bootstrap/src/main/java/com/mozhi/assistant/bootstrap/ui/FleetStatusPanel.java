package com.mozhi.assistant.bootstrap.ui;

import com.mozhi.assistant.bridge.FleetAgentAccess;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 只读舰队面板：主线程轮询桥接数据，独立滚动，不触发模型或游戏操作。 */
final class FleetStatusPanel {
    private static final Color ERROR = new Color(235,139,133);
    private Map<String,Object> data = Map.of();
    private Rectangle bounds = new Rectangle();
    private long nextPoll;
    private int scroll, contentHeight;
    private String planId = "";
    private int stepIndex = -1;

    boolean update() {
        long now = System.nanoTime();
        if (now < nextPoll) return false;
        nextPoll = now + 500_000_000L;
        Map<String,Object> next = FleetAgentAccess.view();
        if (next.equals(data)) return false;
        Map<?,?> plan = map(map(next.get("state")).get("plan"));
        String id = text(plan.get("id"));
        int step = number(plan.get("currentStep")).intValue();
        if (!id.equals(planId) || step != stepIndex) scroll = 0;
        planId = id; stepIndex = step; data = next;
        return true;
    }
    boolean contains(int x,int y) {return bounds.contains(x,y);}
    void scroll(int delta) {scroll=Math.max(0,Math.min(maxScroll(),scroll+delta*54));}
    private int maxScroll() {return Math.max(0,contentHeight-Math.max(1,bounds.height-65));}

    void paint(Graphics2D g, Rectangle area) {
        bounds = area;
        ChatFrame.panel(g,area.x,area.y,area.width,area.height,8,ChatFrame.SURFACE,ChatFrame.BORDER);
        ChatText.label(g,"远征舰队",ChatText.BODY,ChatText.ACCENT,area.x+16,area.y+28);
        ChatText.label(g,"状态 / 执行计划",ChatText.SMALL,ChatText.MUTED,area.x+16,area.y+48);
        g.setColor(ChatFrame.BORDER);g.drawLine(area.x+14,area.y+57,area.x+area.width-14,area.y+57);
        List<Row> rows = rows();
        List<List<ChatText.Line>> lines = new ArrayList<>();
        contentHeight = 8;
        for(Row row:rows) {
            List<ChatText.Line> wrapped=ChatText.wrap(row.text(),row.heading()?ChatText.BODY:ChatText.SMALL,area.width-42);
            lines.add(wrapped);contentHeight+=wrapped.size()*22+(row.heading()?14:7);
        }
        scroll=Math.min(scroll,maxScroll());
        Shape old=g.getClip();g.clipRect(area.x+10,area.y+63,area.width-20,Math.max(1,area.height-73));
        int y=area.y+66-scroll;
        for(int i=0;i<rows.size();i++) {
            Row row=rows.get(i);
            ChatText.drawLines(g,lines.get(i),area.x+16,y,22,row.color());
            y+=lines.get(i).size()*22+(row.heading()?14:7);
        }
        g.setClip(old);
        if(maxScroll()>0) {
            int track=area.height-79;
            int thumb=Math.max(22,track*track/contentHeight);
            int top=area.y+65+(int)((float)scroll/maxScroll()*(track-thumb));
            g.setColor(ChatText.ACCENT);g.fillRect(area.x+area.width-8,top,2,thumb);
        }
    }
    private record Row(String text,Color color,boolean heading) {}
    private List<Row> rows() {
        List<Row> rows=new ArrayList<>();
        if(data.containsKey("error")) {
            rows.add(new Row(text(data.get("error")),ERROR,false));return rows;
        }
        Map<?,?> state=map(data.get("state"));
        Map<?,?> mission=map(state.get("mission"));
        if(!mission.isEmpty()) {
            rows.add(new Row("舰队子任务",ChatText.ACCENT,true));
            rows.add(new Row(text(mission.get("originalGoal")),ChatText.TEXT,false));
            rows.add(new Row(label(text(mission.get("status")))+" · 重规划 "+number(mission.get("replanCount")).intValue()+" 次",ChatText.MUTED,false));
            if(!text(mission.get("reviewReason")).isBlank())
                rows.add(new Row(text(mission.get("reviewReason")),ChatText.MUTED,false));
        }
        String fleet=text(state.get("fleetId"));
        if(fleet.isBlank()) {
            rows.add(new Row(switch(text(state.get("mode"))) {
                case "MERGED" -> "已返航合并";
                case "LOST" -> "舰队已覆灭或移除";
                default -> "尚未派出舰队";
            },ChatText.TEXT,true));
            rows.add(new Row("通过与墨汁对话分配舰船和物资。",ChatText.MUTED,false));
        } else {
            rows.add(new Row(text(data.get("location"))+" · "+label(text(state.get("mode"))),ChatText.TEXT,true));
            Map<?,?> logistics=map(data.get("logistics"));
            int ships=list(data.get("ships")).size();
            rows.add(new Row(String.format(java.util.Locale.ROOT,"%d 艘舰船   ·   信用点 %,.0f",ships,number(logistics.get("credits")).doubleValue()),ChatText.TEXT,false));
            rows.add(new Row(String.format(java.util.Locale.ROOT,"补给 %.0f（约 %.1f 日）\n燃料 %.0f / %.0f\n船员 %.0f / 最低 %.0f   ·   CR %.0f%%",
                    n(logistics,"supplies"),n(logistics,"supplyDays"),n(logistics,"fuel"),n(logistics,"fuelCapacity"),
                    n(logistics,"crew"),n(logistics,"requiredCrew"),n(logistics,"readiness")*100),ChatText.TEXT,false));
            rows.add(new Row("当前指令",ChatText.ACCENT,true));
            rows.add(new Row(text(state.get("order")),ChatText.TEXT,false));
            if(!text(state.get("reason")).isBlank())rows.add(new Row(text(state.get("reason")),ChatText.MUTED,false));
        }
        Map<?,?> plan=map(state.get("plan"));
        rows.add(new Row("执行计划",ChatText.ACCENT,true));
        if(plan.isEmpty()) rows.add(new Row(text(state.get("plannerStatus")).isBlank()?"等待制定计划":text(state.get("plannerStatus")),ChatText.MUTED,false));
        else {
            List<?> steps=list(plan.get("steps"));
            long completed=steps.stream().filter(s->"COMPLETED".equals(text(map(s).get("status")))).count();
            String status=text(plan.get("status"));
            rows.add(new Row(label(status)+"   "+completed+" / "+steps.size()+" 步",color(status),false));
            rows.add(new Row(text(plan.get("goal")),ChatText.TEXT,false));
            if(!text(plan.get("feedback")).isBlank() && ("FAILED".equals(status)||"PAUSED".equals(status)||"CANCELLED".equals(status)))
                rows.add(new Row(text(plan.get("feedback")),color(status),false));
            for(int i=0;i<steps.size();i++) {
                Map<?,?> step=map(steps.get(i));
                String stepStatus=text(step.get("status"));
                rows.add(new Row((i+1)+". "+label(stepStatus)+" · "+text(step.get("description")),color(stepStatus),false));
                if("FOLLOW_PLAYER".equals(text(step.get("action"))))
                    rows.add(new Row(n(step,"durationDays")==0?"持续跟随":String.format(java.util.Locale.ROOT,"时长：%.2f / %.2f 游戏日",n(step,"progressDays"),n(step,"durationDays")),ChatText.MUTED,false));
                if(!text(step.get("destination")).isBlank())
                    rows.add(new Row("目的地："+text(step.get("destination")),ChatText.MUTED,false));
                if("BUY".equals(text(step.get("action"))) || "SELL".equals(text(step.get("action"))))
                    rows.add(new Row(label(text(step.get("action")))+"："+text(step.get("item"))+" × "+number(step.get("quantity")).intValue()
                            +(text(step.get("submarket")).isBlank()?"":" · "+text(step.get("submarket"))),ChatText.MUTED,false));
                if(!text(step.get("result")).isBlank())rows.add(new Row(text(step.get("result")),ChatText.MUTED,false));
            }
            String planner=text(state.get("plannerStatus"));
            if(!planner.isBlank()) rows.add(new Row(planner,ChatText.MUTED,false));
        }
        List<?> log=list(state.get("log"));
        if(!log.isEmpty()) {
            rows.add(new Row("最近动态",ChatText.ACCENT,true));
            for(int i=Math.max(0,log.size()-4);i<log.size();i++)rows.add(new Row(text(log.get(i)),ChatText.MUTED,false));
        }
        return rows;
    }
    private static Color color(String status) {
        return switch(status) {
            case "RUNNING","COMPLETED" -> ChatText.ACCENT;
            case "FAILED","BLOCKED" -> ERROR;
            case "PAUSED" -> ChatFrame.GOLD;
            default -> ChatText.MUTED;
        };
    }
    private static String label(String value) {
        return switch(value) {
            case "FOLLOW_PLAYER" -> "跟随玩家"; case "RETURN" -> "返航合并";
            case "MOVE_TO" -> "前往目的地"; case "BUY" -> "购买"; case "SELL" -> "出售"; case "ORBIT" -> "环绕待命";
            case "REVIEWING" -> "检查原始目标"; case "REPLANNING" -> "重新规划"; case "BLOCKED" -> "需要处理"; case "EXECUTING" -> "执行中";
            case "IDLE" -> "等待指令"; case "PLANNING" -> "规划中";
            case "READY" -> "准备执行"; case "RUNNING" -> "执行中"; case "PENDING" -> "待执行";
            case "COMPLETED" -> "已完成"; case "FAILED" -> "失败"; case "PAUSED" -> "暂停执行";
            case "CANCELLED" -> "已取消"; default -> value;
        };
    }
    private static Map<?,?> map(Object value) {return value instanceof Map<?,?> m?m:Map.of();}
    private static List<?> list(Object value) {return value instanceof List<?> l?l:List.of();}
    private static String text(Object value) {return value==null?"":String.valueOf(value);}
    private static Number number(Object value) {return value instanceof Number n?n:0;}
    private static double n(Map<?,?> values,String key) {return number(values.get(key)).doubleValue();}
}
