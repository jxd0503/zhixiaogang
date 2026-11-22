package com.zhixiaogang.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * API Key 静态加密工具：使用 AES-256-GCM 加密数据库中的敏感配置（如 ai_provider.api_key）。
 *
 * 密钥来源优先级：配置项 ai.key.secret → 环境变量 AI_KEY_SECRET → 本地密钥文件 config/ai-key.secret。
 * 未配置时首次启动自动生成随机密钥并持久化到本地文件，后续启动复用，不再降级明文；
 * 文件路径只打印在日志中，密钥内容不写日志且不进入版本库。
 * 历史明文数据无需迁移即可读取，下一次保存会自动升级为密文。
 *
 * 密文格式：enc:v1:Base64(12 字节 IV || 密文+认证标签)
 */
@Component
public class ApiKeyCipher {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyCipher.class);

    private static final String PREFIX = "enc:v1:";
    private static final String ENV_NAME = "AI_KEY_SECRET";
    private static final String KEY_FILE = "config/ai-key.secret";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final boolean enabled;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyCipher(@Value("${ai.key.secret:}") String configuredSecret) {
        String secret = resolveSecret(configuredSecret);
        this.key = new SecretKeySpec(sha256(secret), "AES");
        this.enabled = true;
    }

    /** 外部配置 > 环境变量 > 本地密钥文件（不存在则生成并持久化） */
    private String resolveSecret(String configuredSecret) {
        String secret = configuredSecret == null ? "" : configuredSecret.trim();
        if (!secret.isEmpty()) {
            return secret;
        }
        String env = System.getenv(ENV_NAME);
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        Path path = Path.of(KEY_FILE);
        try {
            if (Files.exists(path)) {
                String stored = Files.readString(path, StandardCharsets.UTF_8).trim();
                if (!stored.isEmpty()) {
                    log.info("使用本地 API Key 加密密钥文件: {}", path.toAbsolutePath());
                    return stored;
                }
            }
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String generated = HexFormat.of().formatHex(bytes);
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, generated, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("未配置 {}，已生成 API Key 加密密钥并保存到: {}（密钥内容不写入日志）",
                    ENV_NAME, path.toAbsolutePath());
            return generated;
        } catch (IOException e) {
            throw new IllegalStateException("无法读取或创建 API Key 加密密钥文件: " + path.toAbsolutePath(), e);
        }
    }

    /** 是否已启用加密（当前始终为 true，保留给已有调用方） */
    public boolean isEnabled() {
        return enabled;
    }

    /** 是否为密文格式 */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /** 加密；空值或已是密文时原样返回 */
    public String encrypt(String plainText) {
        if (!enabled || plainText == null || plainText.isEmpty() || isEncrypted(plainText)) {
            return plainText;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("API Key 加密失败", e);
        }
    }

    /** 解密；历史明文原样返回；无法解密时返回空串并记录告警，避免影响主流程 */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty() || !isEncrypted(stored)) {
            return stored;
        }
        if (!enabled) {
            log.warn("检测到加密的 API Key，但未配置解密密钥，已按空值处理");
            return "";
        }
        try {
            byte[] payload = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (payload.length <= IV_BYTES) {
                return "";
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(payload, 0, iv, 0, IV_BYTES);
            byte[] encrypted = new byte[payload.length - IV_BYTES];
            System.arraycopy(payload, IV_BYTES, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("API Key 解密失败，已按空值处理: {}", e.getClass().getSimpleName());
            return "";
        }
    }

    /** 掩码展示：保留前 4 位与后 4 位；长度不足 10 位时统一显示 **** */
    public String mask(String value) {
        String plain = decrypt(value);
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        if (plain.length() <= 10) {
            return "****";
        }
        return plain.substring(0, 4) + "****" + plain.substring(plain.length() - 4);
    }

    private static byte[] sha256(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("密钥派生失败", e);
        }
    }
}
