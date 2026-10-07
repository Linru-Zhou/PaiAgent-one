package com.paiagent.engine;

/**
 * 执行状态冲突：目标执行记录当前不处于允许该操作的状态（例如已有另一个请求正在续跑同一次执行）。
 */
public class ExecutionConflictException extends RuntimeException {

    public ExecutionConflictException(String message) {
        super(message);
    }
}
