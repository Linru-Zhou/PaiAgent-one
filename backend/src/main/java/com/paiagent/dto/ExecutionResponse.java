package com.paiagent.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 工作流执行响应 DTO
 */
@Data
public class ExecutionResponse {
    
    private Long executionId;
    private String status;
    private String inputData;
    private List<NodeResult> nodeResults;
    private String outputData;
    private Integer duration;
    private String errorMessage;
    
    @Data
    public static class NodeResult {
        private String nodeId;
        private String nodeName;
        private String status;
        private String input;
        private String output;
        private Integer duration;
        private String error;
        /** 机器可读的错误码，只有部分错误才有；前端据此决定交互，不要去解析 error 文案 */
        private String errorCode;
        /** 错误码对应的结构化细节 */
        private Map<String, Object> errorDetail;
    }
}
