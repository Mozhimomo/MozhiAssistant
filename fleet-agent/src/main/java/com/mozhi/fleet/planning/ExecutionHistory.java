package com.mozhi.fleet.planning;

import com.mozhi.fleet.model.ExecutionResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 每个任务独立持有的执行历史；详细结果只保留最近 20 步，完成身份单独保留。 */
public final class ExecutionHistory {
    public static final int WINDOW_SIZE = 20;
    private final LinkedHashMap<String, ExecutionResult> recent = new LinkedHashMap<>();
    private final Set<String> completed = new LinkedHashSet<>();

    public ExecutionHistory() {}

    /** 从已保存的快照继续追加记录，保留窗口外的完成身份。 */
    public ExecutionHistory(Snapshot snapshot) {
        restore(snapshot);
    }

    public synchronized void restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "执行历史快照");
        clear();
        for (ExecutionResult result : snapshot.recentResults()) recent.put(result.step().id(), result);
        completed.addAll(snapshot.completedStepIds());
    }

    /** 同一步的多轮反馈更新原记录并移至窗口末尾，不重复占用窗口。 */
    public synchronized void record(ExecutionResult result) {
        Objects.requireNonNull(result, "执行结果");
        String id = result.step().id();
        recent.remove(id);
        recent.put(id, result);
        if (result.status() == ExecutionResult.Status.SUCCEEDED) completed.add(id);
        if (recent.size() > WINDOW_SIZE) recent.remove(recent.keySet().iterator().next());
    }

    /** 在同一把锁下生成不可变快照；后台请求不会看到之后的执行进度。 */
    public synchronized Snapshot snapshot() {
        return new Snapshot(new ArrayList<>(recent.values()), completed);
    }

    /** 新任务开始时清空旧任务的历史。 */
    public synchronized void clear() {
        recent.clear();
        completed.clear();
    }

    /** 也可用于恢复历史；直接构造时同样限制窗口大小，并记录裁剪前已完成的步骤 ID。 */
    public record Snapshot(List<ExecutionResult> recentResults, Set<String> completedStepIds) {
        public Snapshot {
            recentResults = List.copyOf(recentResults);
            Set<String> completed = new LinkedHashSet<>(completedStepIds);
            for (String id : completed) ActionSpec.text(id, "已完成步骤 ID");
            Set<String> ids = new HashSet<>();
            for (ExecutionResult result : recentResults) {
                if (!ids.add(result.step().id())) throw new IllegalArgumentException("历史快照包含重复步骤 ID");
                if (result.status() == ExecutionResult.Status.SUCCEEDED) completed.add(result.step().id());
            }
            recentResults = List.copyOf(recentResults.subList(Math.max(0, recentResults.size() - WINDOW_SIZE), recentResults.size()));
            completedStepIds = Set.copyOf(completed);
        }
    }
}
