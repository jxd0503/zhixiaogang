package com.zhixiaogang.service.notification;

import com.zhixiaogang.entity.Notification;
import com.zhixiaogang.mapper.NotificationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 通知写入：单条/批量写入与每用户保留上限清理。
 * 全部写操作内部捕获异常仅记录日志，保证通知失败不阻断原业务操作。
 */
@Service
public class NotificationDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);

    /** 每用户保留的最大通知条数 */
    private static final int MAX_PER_USER = 50;

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NotificationRecipientService recipientService;

    /** 写单条通知（失败仅记日志，不抛异常），返回是否成功 */
    public boolean create(Long userId, String type, String title, String content, Long relatedId) {
        if (userId == null) {
            return false;
        }
        try {
            Notification n = new Notification();
            n.setUserId(userId);
            n.setType(type);
            n.setTitle(title);
            n.setContent(content);
            n.setRelatedId(relatedId);
            n.setIsRead(0);
            notificationMapper.insert(n);
            trimOld(userId);
            return true;
        } catch (Exception e) {
            log.warn("通知写入失败（不影响业务）: {}", e.getMessage());
            return false;
        }
    }

    /** 批量发送：一次批量插入后统一清理（仅对通知数超上限的用户各清理一次），返回成功条数 */
    public int notifyUsers(List<Long> userIds, String type, String title, String content, Long relatedId) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        try {
            jdbcTemplate.batchUpdate(
                    "INSERT INTO notifications (user_id, type, title, content, related_id, is_read, create_time) " +
                    "VALUES (?, ?, ?, ?, ?, 0, NOW())",
                    userIds, userIds.size(),
                    (ps, uid) -> {
                        ps.setLong(1, uid);
                        ps.setString(2, type);
                        ps.setString(3, title);
                        ps.setString(4, content);
                        if (relatedId == null) {
                            ps.setNull(5, java.sql.Types.BIGINT);
                        } else {
                            ps.setLong(5, relatedId);
                        }
                    });
            trimOverLimit(userIds);
            return userIds.size();
        } catch (Exception e) {
            // 批量写入失败回退逐条写入，保证不丢通知
            log.warn("批量通知写入失败，回退逐条写入: {}", e.getMessage());
            int count = 0;
            for (Long uid : userIds) {
                if (create(uid, type, title, content, relatedId)) {
                    count++;
                }
            }
            return count;
        }
    }

    /** 给班级全部在读学生发通知，返回成功条数 */
    public int notifyClassStudents(Long classId, String type, String title, String content, Long relatedId) {
        return notifyUsers(recipientService.classStudentUserIds(classId), type, title, content, relatedId);
    }

    /** 每用户仅保留最近 MAX_PER_USER 条，超出删除最旧记录 */
    private void trimOld(Long userId) {
        try {
            jdbcTemplate.update(
                    "DELETE FROM notifications WHERE user_id = ? AND id NOT IN " +
                    "(SELECT id FROM (SELECT id FROM notifications WHERE user_id = ? ORDER BY create_time DESC, id DESC LIMIT ?) t)",
                    userId, userId, MAX_PER_USER);
        } catch (Exception e) {
            log.warn("通知清理失败（不影响业务）: {}", e.getMessage());
        }
    }

    /** 批量发送后统一清理：仅对通知总数超过上限的用户各执行一次清理 */
    private void trimOverLimit(List<Long> userIds) {
        try {
            String placeholders = String.join(",", Collections.nCopies(userIds.size(), "?"));
            List<Map<String, Object>> over = jdbcTemplate.queryForList(
                    "SELECT user_id FROM notifications WHERE user_id IN (" + placeholders + ") " +
                    "GROUP BY user_id HAVING COUNT(*) > " + MAX_PER_USER,
                    userIds.toArray());
            for (Map<String, Object> row : over) {
                Object uid = row.get("user_id");
                if (uid instanceof Number) {
                    trimOld(((Number) uid).longValue());
                }
            }
        } catch (Exception e) {
            log.warn("批量通知清理失败（不影响业务）: {}", e.getMessage());
        }
    }
}
