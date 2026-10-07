package com.paiagent.engine;

/** 执行线程池已满（队列也满了），当前请求被拒绝，调用方应提示稍后重试。 */
public class ExecutionRejectedException extends RuntimeException {

    public ExecutionRejectedException(String message) {
        super(message);
    }
}
