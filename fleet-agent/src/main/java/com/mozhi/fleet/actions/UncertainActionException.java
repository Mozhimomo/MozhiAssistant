package com.mozhi.fleet.actions;

/** 动作失败且资产恢复不能确认；Executor 必须阻止后续动作，等待核实实际资产。 */
public final class UncertainActionException extends IllegalStateException {
    public UncertainActionException(String message, Throwable cause) { super(message, cause); }
}
