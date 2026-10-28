package com.paiagent.service.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 怎么启动一个 stdio MCP 服务：命令、参数、环境变量。同样的三元组复用同一个进程，
 * 任何一项变了（比如换了 API Key）就是另一个进程。
 */
public record McpServerSpec(String command, List<String> args, Map<String, String> env) {

    public McpServerSpec {
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
    }

    /** 会话缓存的键。环境变量里有密钥，所以只用摘要，不把原文留在 Map 键或日志里。 */
    public String key() {
        StringBuilder canonical = new StringBuilder(command == null ? "" : command).append('\u0000');
        args.forEach(arg -> canonical.append(arg).append('\u0000'));
        new TreeMap<>(env).forEach((name, value) -> canonical.append(name).append('=').append(value).append('\u0000'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
