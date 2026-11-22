package com.zhixiaogang.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * API Key 加密单测：密文往返、历史明文兼容、错误密钥不泄露明文。
 */
class ApiKeyCipherTest {

    @Test
    void encryptAndDecryptRoundTrip() {
        ApiKeyCipher cipher = new ApiKeyCipher("unit-test-secret");
        String encrypted = cipher.encrypt("sk-1234567890abcdef");
        assertTrue(encrypted.startsWith("enc:v1:"));
        assertNotEquals("sk-1234567890abcdef", encrypted);
        assertEquals("sk-1234567890abcdef", cipher.decrypt(encrypted));
        assertEquals("sk-1****cdef", cipher.mask(encrypted));
    }

    @Test
    void legacyPlaintextPassesThrough() {
        ApiKeyCipher cipher = new ApiKeyCipher("unit-test-secret");
        assertEquals("plain-key", cipher.decrypt("plain-key"));
    }

    @Test
    void differentSecretCannotDecrypt() {
        String encrypted = new ApiKeyCipher("secret-a").encrypt("sk-secret-value");
        assertEquals("", new ApiKeyCipher("secret-b").decrypt(encrypted));
    }
}
