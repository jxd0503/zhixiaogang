package com.zhixiaogang.service.adjustment;

import com.zhixiaogang.service.SystemConfigService;
import com.zhixiaogang.service.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 调课审批时限提醒：待审批超过系统配置 {@code adjustment.sla_hours}（默认 48 小时）时，
 * 用现有通知机制提醒全部管理员；同一申请 24 小时内不重复提醒（last_remind_time 去重）。
 * 管理端列表的挂起时长由查询实时计算，本任务只负责提醒。
 */
@Service
public class AdjustmentSlaReminderService {

    private static final Logger log = LoggerFactory.getLogger(AdjustmentSlaReminderService.class);

    /** 默认 SLA 阈值（小时），可由 system_config 覆盖 */
    private static final int DEFAULT_SLA_HOURS = 48;
    /** 同一申请重复提醒间隔（小时） */
    private static final int REMIND_INTERVAL_HOURS = 24;
    /** 单次提醒最多列出的申请条数（避免通知内容超长） */
    private static final int MAX_ITEMS_IN_CONTENT = 5;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private SystemConfigService systemConfigService;

    /** 每小时扫描一次超时待审批申请，提醒管理员（cron 可通过配置覆盖，便于冒烟验证） */
    @Scheduled(cron = "${adjustment.sla-reminder-cron:0 10 * * * ?}")
    public void remindOverdue() {
        try {
            int slaHours = Math.max(1, systemConfigService.getInt("adjustment.sla_hours", DEFAULT_SLA_HOURS));
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT a.id, a.course_name, ci.class_name, a.teacher_name, a.apply_time " +
                            "FROM schedule_adjustment a LEFT JOIN class_info ci ON a.class_id = ci.id " +
                            "WHERE a.status = 'pending' AND a.apply_time IS NOT NULL " +
                            "AND a.apply_time <= TIMESTAMPADD(HOUR, ?, NOW()) " +
                            "AND (a.last_remind_time IS NULL OR a.last_remind_time <= TIMESTAMPADD(HOUR, ?, NOW())) " +
                            "ORDER BY a.apply_time ASC, a.id ASC LIMIT 50",
                    -slaHours, -REMIND_INTERVAL_HOURS);
            if (rows.isEmpty()) {
                return;
            }
            List<Long> adminIds = jdbcTemplate.queryForList(
                    "SELECT id FROM user WHERE role = 'admin' AND status = 1", Long.class);
            if (adminIds != null && !adminIds.isEmpty()) {
                String title = "调课审批超时提醒（超过 " + slaHours + " 小时）";
                String content = buildContent(rows, slaHours);
                Long relatedId = rows.get(0).get("id") instanceof Number n ? n.longValue() : null;
                for (Long uid : new ArrayList<>(adminIds)) {
                    notificationService.create(uid, "adjustment", title, content, relatedId);
                }
            }
            // 标记本次已提醒，24 小时内不再重复；仍为 pending 才更新，避免覆盖审批结果
            for (Map<String, Object> row : rows) {
                jdbcTemplate.update(
                        "UPDATE schedule_adjustment SET last_remind_time = NOW() WHERE id = ? AND status = 'pending'",
                        row.get("id"));
            }
        } catch (Exception e) {
            log.warn("调课超时提醒任务执行失败（不影响业务）", e);
        }
    }

    private String buildContent(List<Map<String, Object>> rows, int slaHours) {
        StringBuilder sb = new StringBuilder();
        sb.append("有 ").append(rows.size()).append(" 条调课申请待审批超过 ").append(slaHours).append(" 小时：");
        int shown = Math.min(rows.size(), MAX_ITEMS_IN_CONTENT);
        for (int i = 0; i < shown; i++) {
            Map<String, Object> r = rows.get(i);
            if (i > 0) {
                sb.append("；");
            }
            sb.append("《").append(str(r.get("course_name"))).append("》")
                    .append(str(r.get("class_name"))).append("（")
                    .append(str(r.get("teacher_name"))).append("）");
            if (r.get("apply_time") != null) {
                long hours = Math.max(0, Duration.between(toLocalDateTime(r.get("apply_time")), LocalDateTime.now()).toHours());
                sb.append(" 已挂起 ").append(hours).append(" 小时");
            }
        }
        if (rows.size() > shown) {
            sb.append(" 等 ").append(rows.size()).append(" 条");
        }
        sb.append("，请及时处理。");
        // notifications.content 上限 1000 字
        return sb.length() > 1000 ? sb.substring(0, 997) + "…" : sb.toString();
    }

    private static String str(Object v) {
        return v == null ? "-" : String.valueOf(v);
    }

    /** 统一把 JDBC 返回的时间值转为 LocalDateTime（不同驱动类型不一致） */
    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toLocalDateTime();
        }
        if (value instanceof java.util.Date date) {
            return new Timestamp(date.getTime()).toLocalDateTime();
        }
        return LocalDateTime.now();
    }
}
