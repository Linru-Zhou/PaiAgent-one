package com.paiagent.security;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * 敏感字段（API Key）的加解密与脱敏。
 *
 * <ul>
 *   <li>落库：AES-256-GCM，密文形如 {@code enc:v1:<base64(iv||ciphertext+tag)>}，每次加密随机 IV；</li>
 *   <li>读库：带前缀才解密，不带前缀按明文透传，所以历史明文数据不会读不出来；</li>
 *   <li>对外：接口只返回脱敏值（前 3 位 + ******** + 后 4 位），保存时把"原样回传的脱敏值"还原成库里的真实值。</li>
 * </ul>
 *
 * 密钥来源：{@code paiagent.security.encryption-key}（环境变量 PAIAGENT_ENCRYPTION_KEY，生产必须显式配置）；
 * 未配置时回退到本机密钥文件（默认 ~/.paiagent/secret.key，首次启动自动生成，权限 600），仅适合本地开发。
 */
@Slf4j
@Component
public class SecretCodec {

    public static final String PREFIX = "enc:v1:";
    static final String MASK_CORE = "********";
    private static final Pattern MASKED = Pattern.compile("^.{0,3}\\*{8}.{0,4}$");
    /** flow_data 里按名称识别的敏感字段。 */
    private static final Set<String> SECRET_FIELDS = Set.of("apiKey", "api_key", "secretKey");
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static volatile SecretCodec instance;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCodec(@Value("${paiagent.security.encryption-key:}") String configuredKey,
                       @Value("${paiagent.security.key-file:}") String keyFile) {
        this.key = resolveKey(configuredKey, keyFile);
        instance = this;
    }

    /** MyBatis 的 TypeHandler 由 MyBatis 自己实例化，拿不到 Spring 注入，通过这里取容器里的实例。 */
    public static SecretCodec instance() {
        SecretCodec codec = instance;
        if (codec == null) {
            throw new IllegalStateException("SecretCodec 尚未初始化");
        }
        return codec;
    }

    // ---------------------------------------------------------------- 单个值

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty() || isEncrypted(plain)) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            throw new IllegalStateException("敏感字段加密失败", e);
        }
    }

    /**
     * 解密。无前缀视为历史明文原样返回；解密失败（密钥被换/密文被改）返回空串并记录错误，
     * 避免一条坏数据让整个列表接口 500。
     */
    public String decrypt(String stored) {
        if (!isEncrypted(stored)) {
            return stored;
        }
        try {
            byte[] packed = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(packed, IV_BYTES, packed.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("敏感字段解密失败，请确认 PAIAGENT_ENCRYPTION_KEY / 密钥文件没有变更: {}", e.getClass().getSimpleName());
            return "";
        }
    }

    public static String mask(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        if (plain.length() < 12) {
            return MASK_CORE;
        }
        return plain.substring(0, 3) + MASK_CORE + plain.substring(plain.length() - 4);
    }

    public static boolean isMasked(String value) {
        return value != null && MASKED.matcher(value).matches();
    }

    /**
     * 保存时的还原：如果前端把脱敏值原样传回，换回库里的真实值。
     * 找不到可还原的真实值时返回 null（调用方决定报错还是清空），绝不把脱敏值当成密钥写进库。
     */
    public static String restoreIfMasked(String incoming, String existingPlain) {
        if (!isMasked(incoming)) {
            return incoming;
        }
        if (existingPlain != null && !existingPlain.isEmpty() && !isMasked(existingPlain)
                && (incoming.equals(mask(existingPlain)))) {
            return existingPlain;
        }
        return null;
    }

    // ---------------------------------------------------------------- flow_data（JSON 里的 apiKey 字段）

    public String encryptFlowData(String flowData) {
        return transformFlowData(flowData, this::encrypt);
    }

    public String decryptFlowData(String flowData) {
        return transformFlowData(flowData, this::decrypt);
    }

    public String maskFlowData(String flowData) {
        return transformFlowData(flowData, v -> isEncrypted(v) ? mask(decrypt(v)) : mask(v));
    }

    private String transformFlowData(String flowData, UnaryOperator<String> fn) {
        if (flowData == null || SECRET_FIELDS.stream().noneMatch(flowData::contains)) {
            return flowData;
        }
        return transformSecrets(flowData, fn, SECRET_FIELDS::contains);
    }

    // ---------------------------------------------------------------- MCP env（环境变量 JSON，按变量名识别密钥）

    /** 环境变量名里带 KEY / SECRET / TOKEN / PASSWORD 的视为密钥。 */
    public static boolean isSecretEnvKey(String name) {
        if (name == null) {
            return false;
        }
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.contains("KEY") || upper.contains("SECRET") || upper.contains("TOKEN") || upper.contains("PASSWORD");
    }

    public String encryptEnv(String envJson) {
        return transformSecrets(envJson, this::encrypt, SecretCodec::isSecretEnvKey);
    }

    public String decryptEnv(String envJson) {
        return transformSecrets(envJson, this::decrypt, SecretCodec::isSecretEnvKey);
    }

    /**
     * 保存工作流前调用：把前端回传的脱敏 apiKey 还原为库里的真实值。
     * 优先按节点 id 对应；节点 id 变了（复制节点）则按脱敏串在本工作流已有密钥里查找；
     * 仍然找不到就清空，让用户重新填写，而不是把 "sk-0********a1ba" 存成密钥。
     *
     * @param existingPlainFlowData 库里当前（已解密）的 flow_data，新建工作流时为 null
     */
    public String restoreMaskedFlowData(String incomingFlowData, String existingPlainFlowData) {
        if (incomingFlowData == null || !incomingFlowData.contains(MASK_CORE)) {
            return incomingFlowData;
        }
        Map<String, String> keyByNodeId = new HashMap<>();
        Map<String, String> keyByMask = new HashMap<>();
        collectExisting(existingPlainFlowData, keyByNodeId, keyByMask);

        Object parsed = JSON.parse(incomingFlowData);
        boolean[] changed = {false};
        restoreWalk(parsed, null, keyByNodeId, keyByMask, changed);
        return changed[0] ? JSON.toJSONString(parsed) : incomingFlowData;
    }

    private void collectExisting(String existingPlain, Map<String, String> byNodeId, Map<String, String> byMask) {
        if (existingPlain == null || existingPlain.isBlank()) {
            return;
        }
        Object parsed = JSON.parse(existingPlain);
        if (!(parsed instanceof JSONObject root) || !(root.get("nodes") instanceof JSONArray nodes)) {
            return;
        }
        for (Object n : nodes) {
            if (!(n instanceof JSONObject node) || !(node.get("data") instanceof JSONObject data)) {
                continue;
            }
            for (String field : SECRET_FIELDS) {
                Object v = data.get(field);
                if (v instanceof String s && !s.isEmpty() && !isMasked(s)) {
                    if (node.get("id") != null) {
                        byNodeId.put(node.getString("id") + "/" + field, s);
                    }
                    byMask.put(mask(s), s);
                }
            }
        }
    }

    private void restoreWalk(Object value, String nodeId, Map<String, String> byNodeId,
                             Map<String, String> byMask, boolean[] changed) {
        if (value instanceof JSONArray array) {
            for (Object item : array) {
                restoreWalk(item, nodeId, byNodeId, byMask, changed);
            }
        } else if (value instanceof JSONObject object) {
            String currentNodeId = object.get("id") instanceof String id && object.get("data") instanceof JSONObject
                    ? id : nodeId;
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                Object v = entry.getValue();
                if (SECRET_FIELDS.contains(entry.getKey()) && v instanceof String s && isMasked(s)) {
                    String restored = null;
                    if (currentNodeId != null) {
                        restored = restoreIfMasked(s, byNodeId.get(currentNodeId + "/" + entry.getKey()));
                    }
                    if (restored == null) {
                        restored = byMask.get(s);
                    }
                    entry.setValue(restored == null ? "" : restored);
                    changed[0] = true;
                } else {
                    restoreWalk(v, currentNodeId, byNodeId, byMask, changed);
                }
            }
        }
    }

    /** 递归处理 JSON 中名为 apiKey 等的字符串字段；没有任何变化时返回原字符串，保证无敏感字段的数据逐字节不变。 */
    private String transformSecrets(String json, UnaryOperator<String> fn, Predicate<String> isSecretField) {
        if (json == null || json.isBlank()) {
            return json;
        }
        Object parsed;
        try {
            parsed = JSON.parse(json);
        } catch (Exception e) {
            return json;
        }
        boolean[] changed = {false};
        walk(parsed, fn, isSecretField, changed);
        return changed[0] ? JSON.toJSONString(parsed) : json;
    }

    private void walk(Object value, UnaryOperator<String> fn, Predicate<String> isSecretField, boolean[] changed) {
        if (value instanceof JSONArray array) {
            for (Object item : array) {
                walk(item, fn, isSecretField, changed);
            }
        } else if (value instanceof JSONObject object) {
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                Object v = entry.getValue();
                if (isSecretField.test(entry.getKey()) && v instanceof String s && !s.isEmpty()) {
                    String next = fn.apply(s);
                    if (!s.equals(next)) {
                        entry.setValue(next);
                        changed[0] = true;
                    }
                } else {
                    walk(v, fn, isSecretField, changed);
                }
            }
        }
    }

    // ---------------------------------------------------------------- 密钥加载

    private SecretKey resolveKey(String configured, String keyFile) {
        if (configured != null && !configured.isBlank()) {
            if (configured.length() < 16) {
                log.warn("paiagent.security.encryption-key 少于 16 个字符，强度不足，建议使用 32 字节随机值的 Base64");
            }
            return new SecretKeySpec(deriveBytes(configured.trim()), "AES");
        }
        Path path = Path.of(keyFile == null || keyFile.isBlank()
                ? System.getProperty("user.home") + "/.paiagent/secret.key" : keyFile);
        try {
            if (Files.exists(path)) {
                return new SecretKeySpec(deriveBytes(Files.readString(path).trim()), "AES");
            }
            byte[] fresh = new byte[32];
            random.nextBytes(fresh);
            Files.createDirectories(path.getParent());
            Files.writeString(path, Base64.getEncoder().encodeToString(fresh));
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // 非 POSIX 文件系统（Windows）无法设置权限
            }
            log.warn("未配置 PAIAGENT_ENCRYPTION_KEY，已生成本机密钥文件 {}（仅适合本地开发，生产请显式配置密钥并妥善备份）", path);
            return new SecretKeySpec(fresh, "AES");
        } catch (IOException e) {
            throw new IllegalStateException("无法读取或创建加密密钥文件: " + path, e);
        }
    }

    /** 32 字节的 Base64 直接使用；其他任意字符串按口令处理（SHA-256 派生）。 */
    private static byte[] deriveBytes(String material) {
        try {
            byte[] decoded = Base64.getDecoder().decode(material);
            if (decoded.length == 32) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // 不是 Base64，按口令处理
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
