package com.zhixiaogang.service.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 通知收件人解析：按学号/工号、班级、教师姓名等维度定位 user.id。
 * 所有查询失败均静默降级（返回 null / 空列表），不阻断通知主流程。
 */
@Service
public class NotificationRecipientService {

    private static final Logger log = LoggerFactory.getLogger(NotificationRecipientService.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 按学号/工号查 user.id */
    public Long findUserId(String studentId) {
        if (studentId == null || studentId.isEmpty()) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject("SELECT id FROM user WHERE student_id = ?", Long.class, studentId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 班级全部在读学生的 user.id（含退班待审核，与课表可见口径一致） */
    public List<Long> classStudentUserIds(Long classId) {
        if (classId == null) {
            return List.of();
        }
        try {
            return jdbcTemplate.queryForList(
                    "SELECT u.id FROM class_member cm JOIN user u ON u.student_id = cm.student_id " +
                    "WHERE cm.class_id = ? AND cm.status IN ('approved','withdraw_pending')",
                    Long.class, classId);
        } catch (Exception e) {
            log.warn("班级学生查询失败（不影响业务）: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 按姓名查 user.id（仅教师通知兜底用，工号缺失时才走到）。
     * 匹配到多条同名教师时不取第一条，记录告警并返回 null（本次通知不发送），避免发错人。
     */
    public Long findUserIdByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM user WHERE name = ? AND role = 'teacher'", Long.class, name);
            if (ids.size() == 1) {
                return ids.get(0);
            }
            if (ids.size() > 1) {
                log.warn("按姓名匹配到多名同名教师，本次通知不发送：name={}, matchedUserIds={}", name, ids);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 班级管理教师账号（class_info.teacher_id → user.id），无教师或解析不到账号返回 null */
    public Long classTeacherUserId(Long classId) {
        if (classId == null) {
            return null;
        }
        try {
            String teacherStudentId = jdbcTemplate.queryForObject(
                    "SELECT teacher_id FROM class_info WHERE id = ?", String.class, classId);
            return findUserId(teacherStudentId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 班级名称（查不到返回空串） */
    public String classNameOf(Long classId) {
        try {
            return nz(jdbcTemplate.queryForObject("SELECT class_name FROM class_info WHERE id = ?", String.class, classId));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 该教师是否已存在「同一申请记录 + 同一类别（加入/退出）」的未读班级申请提醒（幂等去重）。
     * 通过文案前缀区分类别：入班提醒以「有学生申请加入你的班级」开头，退班以「有学生申请退出你的班级」开头，
     * 因此同一 class_member 记录先后发生入班 / 退班时互不影响，各自只提醒一次。
     */
    public boolean hasUnreadClassApplication(Long userId, Long applicationId, String joinPrefix, String withdrawPrefix,
                                             boolean withdraw) {
        if (applicationId == null) {
            return false;
        }
        String prefix = withdraw ? withdrawPrefix : joinPrefix;
        try {
            Long cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'class_application' " +
                    "AND is_read = 0 AND related_id = ? AND content LIKE CONCAT(?, '%')",
                    Long.class, userId, applicationId, prefix);
            return cnt != null && cnt > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
