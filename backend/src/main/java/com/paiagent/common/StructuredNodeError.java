package com.paiagent.common;

import java.util.Map;

/**
 * 带机器可读错误码的节点异常。NodeRunner 会把错误码和细节放进节点结果，
 * 前端据此决定交互（比如弹窗让用户做选择），不必去解析随时可能改动的错误文案。
 */
public interface StructuredNodeError {

    String errorCode();

    Map<String, Object> errorDetail();
}
