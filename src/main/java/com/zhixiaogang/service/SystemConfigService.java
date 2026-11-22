package com.zhixiaogang.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统配置读写（system_config 表），带内存缓存，供操作日志保留策略等开关使用。
 */
@Service
public class SystemConfigService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Map<String, String> cache = new HashMap<>();

    /** 读取配置，不存在时返回默认值（同时写入缓存） */
    public String get(String key, String defaultValue) {
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            List<String> values = jdbcTemplate.queryForList(
                    "SELECT config_value FROM system_config WHERE config_key = ?", String.class, key);
            String value = values.isEmpty() || values.get(0) == null ? defaultValue : values.get(0);
            cache.put(key, value);
            return value;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)).trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return "1".equals(get(key, defaultValue ? "1" : "0"));
    }

    /** 写入配置并刷新缓存 */
    public void set(String key, String value, String description) {
        jdbcTemplate.update(
                "INSERT INTO system_config (config_key, config_value, description) VALUES (?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE config_value = VALUES(config_value), description = VALUES(description)",
                key, value, description);
        cache.put(key, value);
    }

    public void setInt(String key, int value, String description) {
        set(key, String.valueOf(value), description);
    }
}
