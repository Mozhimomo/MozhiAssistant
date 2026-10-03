package com.mozhi.fleet.model;

import java.util.Objects;

/** 执行器对一个步骤的实际反馈；result 应描述进度、已发生的效果或失败原因。 */
public record ExecutionResult(Step step, Status status, String result, Plan generatedPlan, TradeReceipt tradeReceipt) {
    public enum Status { RUNNING, WAITING, SUCCEEDED, FAILED }

    public ExecutionResult(Step step, Status status, String result) { this(step, status, result, null, null); }
    public ExecutionResult(Step step, Status status, String result, Plan generatedPlan) { this(step, status, result, generatedPlan, null); }

    public ExecutionResult {
        Objects.requireNonNull(step, "执行步骤");
        Objects.requireNonNull(status, "执行状态");
        if (result == null || result.isBlank()) throw new IllegalArgumentException("执行结果说明不能为空");
        if (generatedPlan != null && status != Status.SUCCEEDED) throw new IllegalArgumentException("只有成功的决策步骤可以返回计划");
        if (tradeReceipt != null && (status != Status.SUCCEEDED || !step.action().equals("BUY") && !step.action().equals("SELL")))
            throw new IllegalArgumentException("只有成功的买卖步骤可以返回交易收据");
        if (tradeReceipt != null && (step.action().equals("BUY") ? tradeReceipt.creditsReceived() != 0 : tradeReceipt.creditsSpent() != 0))
            throw new IllegalArgumentException("交易收支方向与动作不符");
    }
}
