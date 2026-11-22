package com.zhixiaogang.service.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理端通知历史：把 notifications 表按「同一批发送」聚合为历史记录。
 * notifications 为每用户一行的明细表，系统自动通知（调课 / 课表 / 班级申请 / 排课要求）
 * 与管理端自定义通知都写入该表，因此历史列表以该表为准，不再依赖只记录自定义通知的发送日志表。
 * 同一批发送的特征：type、title、content、related_id 相同且写入时间落在同一秒。
 */
@Service
public class NotificationHistoryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationHistoryService.class);

    /** 历史列表最多返回的批次数（默认展示最近记录） */
    private static final int MAX_BATCHES = 100;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 管理端：历史发送记录（按批次聚合，最新在前） */
    public Map<String, Object> history() {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map<String, Object>> batches = jdbcTemplate.queryForList(
                    "SELECT MIN(id) AS batch_id, type, title, content, related_id, " +
                    "DATE_FORMAT(MIN(create_time), '%Y-%m-%d %H:%i:%s') AS create_time, " +
                    "COUNT(*) AS receiver_count, " +
                    "SUM(CASE WHEN is_read = 1 THEN 1 ELSE 0 END) AS read_count, " +
                    "DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS batch_key " +
                    "FROM notifications " +
                    "GROUP BY type, title, content, related_id, batch_key " +
                    "ORDER BY MIN(create_time) DESC, MIN(id) DESC LIMIT " + MAX_BATCHES);

            Map<Long, String> classNames = resolveClassNames(batches);
            Map<String, Map<String, Object>> sendLogs = loadSendLogs();

            List<Map<String, Object>> list = new ArrayList<>();
            for (Map<String, Object> row : batches) {
                String type = str(row.get("type"));
                String title = str(row.get("title"));
                String content = str(row.get("content"));
                String createTime = str(row.get("create_time"));
                int receiverCount = num(row.get("receiver_count"));
                int readCount = num(row.get("read_count"));
                String className = classNames.getOrDefault(longVal(row.get("related_id")), "");

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", row.get("batch_id"));
                item.put("type", type);
                item.put("title", title);
                item.put("content", content);
                item.put("createTime", createTime);
                item.put("receiverCount", receiverCount);
                item.put("readCount", readCount);
                item.put("unreadCount", receiverCount - readCount);
                item.put("className", className);

                if ("custom".equals(type)) {
                    Map<String, Object> logEntry = matchSendLog(sendLogs, title, content);
                    String scope = logEntry == null ? "" : str(logEntry.get("scope"));
                    item.put("scope", scope);
                    item.put("scopeText", customScopeText(scope,
                            logEntry == null ? "" : str(logEntry.get("class_name"))));
                    item.put("sender", logEntry == null ? "管理员" : str(logEntry.get("sender")));
                } else {
                    item.put("scope", type);
                    item.put("scopeText", systemScopeText(type, className));
                    item.put("sender", "系统");
                }
                list.add(item);
            }
            result.put("success", true);
            result.put("history", list);
        } catch (Exception e) {
            log.warn("加载通知历史失败: {}", e.getMessage());
            result.put("success", false);
            result.put("message", "加载发送记录失败：" + e.getMessage());
            result.put("history", List.of());
        }
        return result;
    }

    /**
     * 批量解析通知关联的班级名，返回「related_id → 班级名」。
     * 课表通知的 related_id 即班级 id；调课通知的 related_id 是调课记录 id，需再查 schedule_adjustment 取班级 id。
     */
    private Map<Long, String> resolveClassNames(List<Map<String, Object>> batches) {
        Set<Long> classIds = new HashSet<>();
        Set<Long> adjustmentIds = new HashSet<>();
        for (Map<String, Object> row : batches) {
            Long relatedId = longVal(row.get("related_id"));
            if (relatedId == null) {
                continue;
            }
            String type = str(row.get("type"));
            if ("schedule".equals(type)) {
                classIds.add(relatedId);
            } else if ("adjustment".equals(type)) {
                adjustmentIds.add(relatedId);
            }
        }

        Map<Long, Long> adjustmentClass = new HashMap<>();
        if (!adjustmentIds.isEmpty()) {
            String sql = "SELECT id, class_id FROM schedule_adjustment WHERE id IN ("
                    + placeholders(adjustmentIds.size()) + ")";
            for (Map<String, Object> row : jdbcTemplate.queryForList(sql, adjustmentIds.toArray())) {
                Long adjustmentId = longVal(row.get("id"));
                Long classId = longVal(row.get("class_id"));
                if (adjustmentId != null && classId != null) {
                    adjustmentClass.put(adjustmentId, classId);
                    classIds.add(classId);
                }
            }
        }

        Map<Long, String> classNames = new HashMap<>();
        if (!classIds.isEmpty()) {
            String sql = "SELECT id, class_name FROM class_info WHERE id IN ("
                    + placeholders(classIds.size()) + ")";
            for (Map<String, Object> row : jdbcTemplate.queryForList(sql, classIds.toArray())) {
                classNames.put(longVal(row.get("id")), str(row.get("class_name")));
            }
        }

        Map<Long, String> byRelatedId = new HashMap<>();
        for (Map<String, Object> row : batches) {
            Long relatedId = longVal(row.get("related_id"));
            if (relatedId == null) {
                continue;
            }
            Long classId = "schedule".equals(str(row.get("type")))
                    ? relatedId : adjustmentClass.get(relatedId);
            if (classId != null) {
                byRelatedId.put(relatedId, classNames.getOrDefault(classId, ""));
            }
        }
        return byRelatedId;
    }

    /** 读取自定义通知发送日志，按「标题 + 内容」建立索引（保留最新一条） */
    private Map<String, Map<String, Object>> loadSendLogs() {
        Map<String, Map<String, Object>> index = new HashMap<>();
        try {
            List<Map<String, Object>> logs = jdbcTemplate.queryForList(
                    "SELECT title, content, scope, class_name, sender, create_time " +
                    "FROM notification_send_log ORDER BY id DESC");
            for (Map<String, Object> row : logs) {
                index.putIfAbsent(logKey(str(row.get("title")), str(row.get("content"))), row);
            }
        } catch (Exception e) {
            log.warn("读取通知发送日志失败（不影响历史列表）: {}", e.getMessage());
        }
        return index;
    }

    /** 自定义通知批次匹配发送日志：标题与内容一致即视为同一次发送 */
    private Map<String, Object> matchSendLog(Map<String, Map<String, Object>> logs,
                                             String title, String content) {
        return logs.get(logKey(title, content));
    }

    private static String logKey(String title, String content) {
        return title + "\u0001" + content;
    }

    /** 自定义通知范围文案 */
    private static String customScopeText(String scope, String className) {
        if ("all_users".equals(scope)) {
            return "全部用户";
        }
        if ("all_students".equals(scope)) {
            return "全部学生";
        }
        if ("class".equals(scope)) {
            return className.isEmpty() ? "指定班级" : "指定班级：" + className;
        }
        return "自定义通知";
    }

    /** 系统自动通知范围文案：按通知类型与关联班级给出可读范围 */
    private static String systemScopeText(String type, String className) {
        if ("schedule".equals(type)) {
            return className.isEmpty() ? "指定班级学生与教师" : "指定班级：" + className;
        }
        if ("adjustment".equals(type)) {
            return className.isEmpty() ? "调课相关师生" : "指定班级：" + className;
        }
        if ("class_application".equals(type)) {
            return "班级申请相关用户";
        }
        if ("schedule_constraint".equals(type)) {
            return "指定教师";
        }
        if ("reservation".equals(type)) {
            return "预约发起人";
        }
        return "系统通知";
    }

    private static String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int num(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    private static Long longVal(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : null;
    }
}
