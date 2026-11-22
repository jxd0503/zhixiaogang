package com.zhixiaogang.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败计数与短时锁定：按「入口:账号」维度在内存中累计失败次数，
 * 连续失败达到阈值后锁定一段时间，成功登录即清零。
 * 单机内存实现，应用重启后计数清空，不影响账号本身。
 */
@Service
public class LoginAttemptService {

    /** 连续失败多少次后锁定 */
    private static final int MAX_FAILURES = 5;
    /** 锁定时长（毫秒） */
    private static final long LOCK_MILLIS = 5 * 60 * 1000L;
    /** 计数条目过期时间，避免长期占用内存 */
    private static final long ENTRY_TTL_MILLIS = 30 * 60 * 1000L;

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /** 是否处于锁定期 */
    public boolean isLocked(String key) {
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return false;
        }
        if (attempt.lockUntil > System.currentTimeMillis()) {
            return true;
        }
        if (attempt.lockUntil > 0) {
            attempts.remove(key);
        } else if (System.currentTimeMillis() - attempt.lastFailure > ENTRY_TTL_MILLIS) {
            attempts.remove(key);
        }
        return false;
    }

    /** 剩余锁定秒数，未锁定返回 0 */
    public long remainingLockSeconds(String key) {
        Attempt attempt = attempts.get(key);
        if (attempt == null || attempt.lockUntil <= System.currentTimeMillis()) {
            return 0;
        }
        return Math.max(1, (attempt.lockUntil - System.currentTimeMillis() + 999) / 1000);
    }

    /** 记录一次失败，达到阈值后进入锁定期 */
    public void recordFailure(String key) {
        long now = System.currentTimeMillis();
        attempts.compute(key, (k, old) -> {
            Attempt attempt = old == null ? new Attempt() : old;
            attempt.failures++;
            attempt.lastFailure = now;
            if (attempt.failures >= MAX_FAILURES) {
                attempt.lockUntil = now + LOCK_MILLIS;
                attempt.failures = 0;
            }
            return attempt;
        });
    }

    /** 登录成功后清空计数 */
    public void reset(String key) {
        attempts.remove(key);
    }

    private static final class Attempt {
        private int failures;
        private long lockUntil;
        private long lastFailure;
    }
}
