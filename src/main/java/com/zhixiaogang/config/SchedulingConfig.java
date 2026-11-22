package com.zhixiaogang.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启定时任务：操作日志每晚清理、文件日志过期清理等。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
