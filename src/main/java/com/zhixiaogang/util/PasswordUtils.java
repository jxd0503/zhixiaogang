package com.zhixiaogang.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 口令哈希工具：新口令统一使用 PBKDF2WithHmacSHA256 加盐哈希，
 * 同时兼容历史数据中的无盐 MD5（32 位十六进制），用于登录校验与自动升级。
 *
 * 存储格式：pbkdf2$迭代次数$Base64(salt)$Base64(hash)
 */
public final class PasswordUtils {

    private static final String PREFIX = "pbkdf2";
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    /** 随机初始口令字符集，去掉了易混淆的 0/O、1/l/I */
    private static final char[] RANDOM_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private static final int RANDOM_LENGTH = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtils() {
    }

    /** 使用加盐 PBKDF2 生成可入库的哈希串 */
    public static String encode(String rawPassword) {
        if (rawPassword == null) {
            throw new IllegalArgumentException("口令不能为空");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(rawPassword.toCharArray(), salt, ITERATIONS);
        Base64.Encoder encoder = Base64.getEncoder();
        return PREFIX + "$" + ITERATIONS + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(hash);
    }

    /**
     * 校验口令。兼容历史无盐 MD5：数据库里已有的 32 位十六进制口令仍可登录。
     */
    public static boolean matches(String rawPassword, String storedPassword) {
        if (rawPassword == null || storedPassword == null) {
            return false;
        }
        String stored = storedPassword.trim();
        if (stored.startsWith(PREFIX + "$")) {
            return matchesPbkdf2(rawPassword, stored);
        }
        if (stored.matches("(?i)[0-9a-f]{32}")) {
            return MessageDigest.isEqual(
                    md5Hex(rawPassword).getBytes(StandardCharsets.US_ASCII),
                    stored.toLowerCase().getBytes(StandardCharsets.US_ASCII));
        }
        return false;
    }

    /** 是否为需要升级的历史格式（非 PBKDF2） */
    public static boolean needsUpgrade(String storedPassword) {
        return storedPassword == null || !storedPassword.trim().startsWith(PREFIX + "$");
    }

    /** 生成随机初始口令（用于种子账号与自动创建账号） */
    public static String generateRandomPassword() {
        StringBuilder sb = new StringBuilder(RANDOM_LENGTH);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            sb.append(RANDOM_CHARS[RANDOM.nextInt(RANDOM_CHARS.length)]);
        }
        return sb.toString();
    }

    private static boolean matchesPbkdf2(String rawPassword, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 4) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(rawPassword.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("口令哈希失败", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static String md5Hex(String rawPassword) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(rawPassword.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("MD5 计算失败", e);
        }
    }
}
