package com.paiagent.engine.llm;

/** 某个大模型供应商的并发名额已满，等待超时后仍拿不到名额。 */
public class LlmBusyException extends RuntimeException {

    public LlmBusyException(String message) {
        super(message);
    }
}
