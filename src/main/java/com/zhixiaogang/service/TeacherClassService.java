package com.zhixiaogang.service;

import com.zhixiaogang.service.notification.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 教师班级管理服务：查询所辖班级/学生名单/加入与退班申请，审核申请
 */
@Service
public class TeacherClassService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NotificationService notificationService;

    /**
     * 当前教师管理的班级列表（class_info.teacher_id = 当前教师工号）
     * 返回班级名、年级、系部、人数（approved 数量）
     */
    public Map<String, Object> getMyClasses(String teacherId) {
        Map<String, Object> result = new HashMap<>();
        if (teacherId == null || teacherId.isEmpty()) {
            result.put("success", false);
            result.put("message", "教师信息缺失");
            return result;
        }
        try {
            String sql = "SELECT ci.id, ci.class_name, ci.grade, ci.department, ci.teacher_id, " +
                    "(SELECT COUNT(*) FROM class_member WHERE class_id = ci.id AND status IN ('approved','withdraw_pending')) AS member_count " +
                    "FROM class_info ci WHERE ci.teacher_id = ? ORDER BY ci.id";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, teacherId);

            List<Map<String, Object>> data = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", row.get("id"));
                item.put("className", row.get("class_name"));
                item.put("grade", row.get("grade"));
                item.put("department", row.get("department"));
                item.put("teacherId", row.get("teacher_id"));
                item.put("memberCount", row.get("member_count"));
                data.add(item);
            }
            result.put("success", true);
            result.put("data", data);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "查询班级失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 班级学生名单：class_member join user，status=approved，按姓名排序
     * 返回学号、姓名、专业
     */
    public Map<String, Object> getStudents(Long classId, String teacherId) {
        Map<String, Object> result = new HashMap<>();
        if (!ownsClass(classId, teacherId)) {
            result.put("success", false);
            result.put("message", "无权管理该班级");
            return result;
        }
        try {
            String sql = "SELECT u.student_id, u.name, u.major, u.grade " +
                    "FROM class_member cm JOIN user u ON cm.student_id = u.student_id " +
                    "WHERE cm.class_id = ? AND cm.status IN ('approved','withdraw_pending') " +
                    "ORDER BY CONVERT(u.name USING gbk)";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, classId);

            List<Map<String, Object>> data = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> item = new HashMap<>();
                item.put("studentId", row.get("student_id"));
                item.put("name", row.get("name"));
                item.put("major", row.get("major"));
                item.put("grade", row.get("grade"));
                data.add(item);
            }
            result.put("success", true);
            result.put("data", data);
            result.put("classId", classId);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "查询学生名单失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 班级申请列表：加入申请（status=pending）与退班申请（status=withdraw_pending）
     * 返回学生信息与申请时间
     */
    public Map<String, Object> getApplications(Long classId, String teacherId) {
        Map<String, Object> result = new HashMap<>();
        if (!ownsClass(classId, teacherId)) {
            result.put("success", false);
            result.put("message", "无权管理该班级");
            return result;
        }
        try {
            String sql = "SELECT cm.id, cm.student_id, u.name, u.major, cm.status, cm.create_time, cm.update_time " +
                    "FROM class_member cm JOIN user u ON cm.student_id = u.student_id " +
                    "WHERE cm.class_id = ? AND cm.status IN ('pending', 'withdraw_pending') " +
                    "ORDER BY cm.create_time";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, classId);

            List<Map<String, Object>> joinApps = new ArrayList<>();
            List<Map<String, Object>> withdrawApps = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", row.get("id"));
                item.put("studentId", row.get("student_id"));
                item.put("name", row.get("name"));
                item.put("major", row.get("major"));
                item.put("status", row.get("status"));
                item.put("createTime", row.get("create_time"));
                item.put("updateTime", row.get("update_time"));
                if ("pending".equals(row.get("status"))) {
                    joinApps.add(item);
                } else {
                    withdrawApps.add(item);
                }
            }
            result.put("success", true);
            result.put("joinApplications", joinApps);
            result.put("withdrawApplications", withdrawApps);
            result.put("classId", classId);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "查询申请失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 通过加入申请：pending → approved
     */
    public Map<String, Object> approveApplication(Long applicationId, String teacherId) {
        return updateApplicationStatus(applicationId, teacherId, "pending", "approved",
                "已通过加入申请", "加入申请不存在或已处理",
                "入班申请已通过", "你申请加入的《%s》已通过审核，现在可以查看班级课表");
    }

    /**
     * 拒绝加入申请：pending → rejected
     */
    public Map<String, Object> rejectApplication(Long applicationId, String teacherId) {
        return updateApplicationStatus(applicationId, teacherId, "pending", "rejected",
                "已拒绝加入申请", "加入申请不存在或已处理",
                "入班申请被拒绝", "你申请加入的《%s》未通过审核");
    }

    /**
     * 同意退班：withdraw_pending → withdrawn
     */
    public Map<String, Object> confirmWithdraw(Long applicationId, String teacherId) {
        return updateApplicationStatus(applicationId, teacherId, "withdraw_pending", "withdrawn",
                "已同意退班", "退班申请不存在或已处理",
                "退班申请已通过", "你的退班申请已通过，《%s》已移出你的班级列表");
    }

    /**
     * 踢出学生：approved/withdraw_pending → withdrawn（强制退班，保留记录，学生可重新申请加入）
     */
    public Map<String, Object> removeStudent(Long classId, String studentId, String teacherId) {
        Map<String, Object> result = new HashMap<>();
        if (studentId == null || studentId.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "学号缺失");
            return result;
        }
        if (!ownsClass(classId, teacherId)) {
            result.put("success", false);
            result.put("message", "无权管理该班级");
            return result;
        }
        try {
            int n = jdbcTemplate.update(
                    "UPDATE class_member SET status = 'withdrawn', update_time = NOW() " +
                            "WHERE class_id = ? AND student_id = ? AND status IN ('approved','withdraw_pending')",
                    classId, studentId.trim());
            if (n == 0) {
                result.put("success", false);
                result.put("message", "该学生不在班级名单中或已被移出");
                return result;
            }
            // 仅新增通知写入，不改变移除结果
            Long memberId = null;
            try {
                List<Long> ids = jdbcTemplate.queryForList(
                        "SELECT id FROM class_member WHERE class_id = ? AND student_id = ? " +
                                "ORDER BY id DESC LIMIT 1", Long.class, classId, studentId.trim());
                if (!ids.isEmpty()) {
                    memberId = ids.get(0);
                }
            } catch (Exception ignore) {
                memberId = null;
            }
            notifyApplicationResult(studentId.trim(), "已移出班级", className(classId),
                    "你已被移出《%s》", memberId);
            result.put("success", true);
            result.put("message", "已将学生移出班级");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "操作失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 通用申请状态流转：校验归属 + 原状态守卫，更新为新状态；
     * 成功后向申请学生发送审核结果通知（通知失败不影响审核结果与返回文案）。
     */
    private Map<String, Object> updateApplicationStatus(Long applicationId, String teacherId,
                                                         String fromStatus, String toStatus,
                                                         String okMsg, String errMsg,
                                                         String notifTitle, String notifContentFmt) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 查询该申请所属班级与申请学生
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT class_id, student_id FROM class_member WHERE id = ?", applicationId);
            if (rows.isEmpty()) {
                result.put("success", false);
                result.put("message", "申请不存在");
                return result;
            }
            Long classId = toLong(rows.get(0).get("class_id"));
            String studentId = rows.get(0).get("student_id") == null ? null : rows.get(0).get("student_id").toString();
            if (!ownsClass(classId, teacherId)) {
                result.put("success", false);
                result.put("message", "无权处理该班级的申请");
                return result;
            }
            // 带状态守卫的更新
            int n = jdbcTemplate.update(
                    "UPDATE class_member SET status = ?, update_time = NOW() WHERE id = ? AND status = ?",
                    toStatus, applicationId, fromStatus);
            if (n == 0) {
                result.put("success", false);
                result.put("message", errMsg);
                return result;
            }
            // 仅新增通知写入，不改变审核结果
            notifyApplicationResult(studentId, notifTitle, className(classId), notifContentFmt, applicationId);
            result.put("success", true);
            result.put("message", okMsg);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "操作失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 向申请学生发送审核结果通知：class_member.id 作为 relatedId，班级名填入内容占位符。
     */
    private void notifyApplicationResult(String studentId, String title, String className,
                                         String contentFmt, Long relatedId) {
        if (studentId == null || studentId.isEmpty()) {
            return;
        }
        String content = String.format(contentFmt, className);
        notificationService.onClassApplicationResult(studentId, title, content, relatedId);
    }

    /**
     * 班级名称，查询异常时以"该班级"兜底，保证通知文案完整
     */
    private String className(Long classId) {
        if (classId == null) {
            return "该班级";
        }
        try {
            String name = jdbcTemplate.queryForObject(
                    "SELECT class_name FROM class_info WHERE id = ?", String.class, classId);
            return name == null || name.isEmpty() ? "该班级" : name;
        } catch (Exception e) {
            return "该班级";
        }
    }

    /** 结果集中的数值列（COUNT/整数）安全转 Long */
    private Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.valueOf(v.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 校验班级是否由当前教师管理
     */
    private boolean ownsClass(Long classId, String teacherId) {
        if (classId == null || teacherId == null) return false;
        try {
            List<String> tids = jdbcTemplate.queryForList(
                    "SELECT teacher_id FROM class_info WHERE id = ?", String.class, classId);
            return !tids.isEmpty() && teacherId.equals(tids.get(0));
        } catch (Exception e) {
            return false;
        }
    }
}
