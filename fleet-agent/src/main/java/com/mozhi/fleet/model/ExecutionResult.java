package com.mozhi.fleet.model;

import java.util.Objects;

/** 执行器对一个步骤的实际反馈；result 应描述进度、已发生的效果或失败原因。 */
public record ExecutionResult(Step step, Status status, String result) {
    public enum Status { RUNNING, WAITING, SUCCEEDED, FAILED }

    public ExecutionResult {
        Objects.requireNonNull(step, "执行步骤");
        Objects.requireNonNull(status, "执行状态");
        if (result == null || result.isBlank()) throw new IllegalArgumentException("执行结果说明不能为空");
    }
}
