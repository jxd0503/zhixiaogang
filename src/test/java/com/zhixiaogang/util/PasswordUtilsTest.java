package com.zhixiaogang.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 口令工具单测：新 PBKDF2 格式校验、历史 MD5 兼容、升级判定。
 */
class PasswordUtilsTest {

    @Test
    void encodeThenMatches() {
        String encoded = PasswordUtils.encode("Test@123456");
        assertTrue(encoded.startsWith("pbkdf2$"));
        assertTrue(PasswordUtils.matches("Test@123456", encoded));
        assertFalse(PasswordUtils.matches("wrong-password", encoded));
        assertFalse(PasswordUtils.needsUpgrade(encoded));
    }

    @Test
    void encodeUsesRandomSalt() {
        assertNotEquals(PasswordUtils.encode("same-password"), PasswordUtils.encode("same-password"));
    }

    @Test
    void legacyMd5StillMatchesAndNeedsUpgrade() {
        // "123456" 的历史无盐 MD5
        String legacy = "e10adc3949ba59abbe56e057f20f883e";
        assertTrue(PasswordUtils.matches("123456", legacy));
        assertFalse(PasswordUtils.matches("1234567", legacy));
        assertTrue(PasswordUtils.needsUpgrade(legacy));
    }

    @Test
    void invalidStoredPasswordNeverMatches() {
        assertFalse(PasswordUtils.matches("123456", null));
        assertFalse(PasswordUtils.matches(null, "abc"));
        assertFalse(PasswordUtils.matches("123456", "not-a-hash"));
        assertFalse(PasswordUtils.matches("123456", "pbkdf2$bad$format"));
    }
}
