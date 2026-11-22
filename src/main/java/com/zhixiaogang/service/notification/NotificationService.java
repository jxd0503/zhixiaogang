package com.zhixiaogang.service.notification;

import com.zhixiaogang.entity.ClassroomReservation;
import com.zhixiaogang.entity.NotificationSendLog;
import com.zhixiaogang.mapper.NotificationSendLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知服务门面：对上层业务保持统一入口，内部按职责拆分为
 * {@link NotificationDeliveryService}（写入与保留上限）、{@link NotificationQueryService}（用户端读取）、
 * {@link NotificationRecipientService}（收件人解析）与 {@link NotificationEventService}（业务事件通知）。
 * system 自动通知与管理端自定义通知共用 notifications 表；通知失败不阻断原业务。
 * 本类保留课表发布聚合通知与管理端自定义群发两类跨模块编排逻辑。
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    @Autowired
    private NotificationDeliveryService deliveryService;

    @Autowired
    private NotificationQueryService queryService;

    @Autowired
    private NotificationRecipientService recipientService;

    @Autowired
    private NotificationEventService eventService;

    @Autowired
    private NotificationSendLogMapper sendLogMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================== 基础写入 / 收件人 ====================

    /** 写单条通知（失败仅记日志，不抛异常），返回是否成功 */
    public boolean create(Long userId, String type, String title, String content, Long relatedId) {
        return deliveryService.create(userId, type, title, content, relatedId);
    }

    /** 批量发送：一次批量插入后统一清理，返回成功条数 */
    public int notifyUsers(List<Long> userIds, String type, String title, String content, Long relatedId) {
        return deliveryService.notifyUsers(userIds, type, title, content, relatedId);
    }

    /** 给班级全部在读学生发通知，返回成功条数 */
    public int notifyClassStudents(Long classId, String type, String title, String content, Long relatedId) {
        return deliveryService.notifyClassStudents(classId, type, title, content, relatedId);
    }

    /** 按学号/工号查 user.id */
    public Long findUserId(String studentId) {
        return recipientService.findUserId(studentId);
    }

    /** 班级全部在读学生的 user.id */
    public List<Long> classStudentUserIds(Long classId) {
        return recipientService.classStudentUserIds(classId);
    }

    /** 班级管理教师账号（class_info.teacher_id → user.id） */
    public Long classTeacherUserId(Long classId) {
        return recipientService.classTeacherUserId(classId);
    }

    // ==================== 业务事件通知 ====================

    /** 调课批准 */
    public void onAdjustmentApproved(Long relatedId, String teacherStudentId, String teacherName,
                                     Long classId, String teacherTitle, String studentTitle) {
        eventService.onAdjustmentApproved(relatedId, teacherStudentId, teacherName, classId, teacherTitle, studentTitle);
    }

    /** 调课被拒绝 */
    public void onAdjustmentRejected(Long relatedId, String teacherStudentId, String teacherName, String teacherTitle) {
        eventService.onAdjustmentRejected(relatedId, teacherStudentId, teacherName, teacherTitle);
    }

    /** 教师自行撤回调课申请 */
    public void onAdjustmentWithdrawn(Long relatedId, String teacherStudentId, String teacherName, String teacherTitle) {
        eventService.onAdjustmentWithdrawn(relatedId, teacherStudentId, teacherName, teacherTitle);
    }

    /** 调课撤销 */
    public void onAdjustmentCancelled(Long relatedId, String teacherStudentId, String teacherName,
                                      Long classId, String teacherTitle, String studentTitle) {
        eventService.onAdjustmentCancelled(relatedId, teacherStudentId, teacherName, classId, teacherTitle, studentTitle);
    }

    /** 撤销调课申请被管理员拒绝 */
    public void onAdjustmentCancelRejected(Long relatedId, String teacherStudentId, String teacherName, String teacherTitle) {
        eventService.onAdjustmentCancelRejected(relatedId, teacherStudentId, teacherName, teacherTitle);
    }

    /** 教室预约批准 */
    public void onReservationApproved(ClassroomReservation r) {
        eventService.onReservationApproved(r);
    }

    /** 教室预约被拒 */
    public void onReservationRejected(ClassroomReservation r, String reason) {
        eventService.onReservationRejected(r, reason);
    }

    /** 已批准预约被管理端撤销 */
    public void onReservationCancelled(ClassroomReservation r, String reason) {
        eventService.onReservationCancelled(r, reason);
    }

    /** 班级申请审核结果 */
    public void onClassApplicationResult(String studentId, String title, String content, Long relatedId) {
        eventService.onClassApplicationResult(studentId, title, content, relatedId);
    }

    /** 学生提交入班 / 退班申请 */
    public void onClassApplicationSubmitted(Long classId, String studentName, boolean withdraw, Long applicationId) {
        eventService.onClassApplicationSubmitted(classId, studentName, withdraw, applicationId);
    }

    // ==================== 课表发布通知 ====================

    /** 课表发布通知条目：由调用方在课表写入成功后组装（失败条目不传入） */
    public static class SchedulePub {
        /** 所属班级 id（通知聚合维度，同时作为学生的 relatedId） */
        public Long classId;
        /** 所属班级名 */
        public String className;
        /** 课程名 */
        public String courseName;
        /** 星期 1-7 */
        public Integer weekDay;
        /** 开始节次 */
        public Integer startPeriod;
        /** 结束节次 */
        public Integer endPeriod;
        /** 教室编号（无教室课程为空） */
        public String classroomNo;
        /** 授课教师工号（用于解析教师账号，可为空） */
        public String teacherId;
    }

    private static final String[] SCHEDULE_WEEKDAY_CN = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    /**
     * 新增课表发布通知：按班级合并通知班内学生「新课表已发布」，按授课教师合并通知教师「课表已更新」。
     * 同一批写入中同一班级 / 同一教师的多条课表合并为一条（含「已发布 N 条课表」），避免逐条轰炸。
     * 仅对成功写入的条目调用；授课教师工号解析不到账号时静默跳过，不报错。
     */
    public void onSchedulePublished(List<SchedulePub> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        // 按班级合并 → 通知班内学生，relatedId 取班级 id 便于聚合
        Map<Long, List<SchedulePub>> byClass = new LinkedHashMap<>();
        for (SchedulePub it : items) {
            if (it == null || it.classId == null) {
                continue;
            }
            byClass.computeIfAbsent(it.classId, k -> new ArrayList<>()).add(it);
        }
        for (Map.Entry<Long, List<SchedulePub>> e : byClass.entrySet()) {
            deliveryService.notifyClassStudents(e.getKey(), "schedule", "新课表已发布",
                    schedulePublishContent(e.getValue()), e.getKey());
        }
        // 按授课教师合并 → 通知教师账号（无账号则跳过）
        Map<String, List<SchedulePub>> byTeacher = new LinkedHashMap<>();
        for (SchedulePub it : items) {
            if (it == null || it.teacherId == null || it.teacherId.isEmpty()) {
                continue;
            }
            byTeacher.computeIfAbsent(it.teacherId, k -> new ArrayList<>()).add(it);
        }
        for (Map.Entry<String, List<SchedulePub>> e : byTeacher.entrySet()) {
            Long uid = recipientService.findUserId(e.getKey());
            if (uid == null) {
                continue;
            }
            List<SchedulePub> group = e.getValue();
            deliveryService.create(uid, "schedule", "课表已更新", schedulePublishContent(group), group.get(0).classId);
        }
    }

    /** 课表发布通知内容：单条含课程/班级/星期节次/教室；多条合并为「已发布 N 条课表：…」 */
    private String schedulePublishContent(List<SchedulePub> group) {
        if (group.size() == 1) {
            SchedulePub s = group.get(0);
            return "《" + nz(s.courseName) + "》已排入 " + nz(s.className) + " " + scheduleWeekDayCn(s.weekDay)
                    + " 第" + s.startPeriod + "-" + s.endPeriod + "节" + scheduleRoomPart(s.classroomNo)
                    + "，请查看课表";
        }
        StringBuilder sb = new StringBuilder("已发布 ").append(group.size()).append(" 条课表：");
        for (int i = 0; i < group.size(); i++) {
            SchedulePub s = group.get(i);
            if (i > 0) {
                sb.append("；");
            }
            sb.append("《").append(nz(s.courseName)).append("》").append(nz(s.className)).append(" ")
                    .append(scheduleWeekDayCn(s.weekDay)).append(" 第").append(s.startPeriod).append("-")
                    .append(s.endPeriod).append("节").append(scheduleRoomPart(s.classroomNo));
        }
        sb.append("，请查看课表");
        return sb.toString();
    }

    private static String scheduleRoomPart(String classroomNo) {
        if (classroomNo == null || classroomNo.trim().isEmpty()) {
            return "";
        }
        return "（" + classroomNo.trim() + "）";
    }

    private static String scheduleWeekDayCn(Integer weekDay) {
        if (weekDay == null) {
            return "";
        }
        if (weekDay >= 1 && weekDay <= 7) {
            return SCHEDULE_WEEKDAY_CN[weekDay];
        }
        return "周" + weekDay;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ==================== 管理端自定义通知 ====================

    /**
     * 发送自定义通知：scope = all_users（全部学生+教师）/ all_students（全部学生）/ class（指定班级全部学生）。
     * 发送后写发送记录，返回 {success, count, message}。
     */
    public Map<String, Object> sendCustom(String title, String content, String scope, Long classId, String sender) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (title == null || title.trim().isEmpty()) {
                result.put("success", false);
                result.put("message", "请填写通知标题");
                return result;
            }
            if (content == null || content.trim().isEmpty()) {
                result.put("success", false);
                result.put("message", "请填写通知内容");
                return result;
            }
            if (title.trim().length() > 200) {
                result.put("success", false);
                result.put("message", "通知标题不能超过 200 字");
                return result;
            }
            if (content.trim().length() > 1000) {
                result.put("success", false);
                result.put("message", "通知内容不能超过 1000 字");
                return result;
            }
            title = title.trim();
            content = content.trim();

            List<Long> userIds;
            String scopeText;
            Long logClassId = null;
            String logClassName = null;
            if ("all_users".equals(scope)) {
                userIds = jdbcTemplate.queryForList(
                        "SELECT id FROM user WHERE role IN ('student','teacher') AND status = 1", Long.class);
                scopeText = "全部用户";
            } else if ("all_students".equals(scope)) {
                userIds = jdbcTemplate.queryForList(
                        "SELECT id FROM user WHERE role = 'student' AND status = 1", Long.class);
                scopeText = "全部学生";
            } else if ("class".equals(scope)) {
                if (classId == null) {
                    result.put("success", false);
                    result.put("message", "请选择目标班级");
                    return result;
                }
                userIds = recipientService.classStudentUserIds(classId);
                scopeText = "指定班级";
                logClassId = classId;
                try {
                    logClassName = jdbcTemplate.queryForObject(
                            "SELECT class_name FROM class_info WHERE id = ?", String.class, classId);
                } catch (Exception ignore) {
                    logClassName = null;
                }
            } else {
                result.put("success", false);
                result.put("message", "接收范围不合法");
                return result;
            }

            int count = deliveryService.notifyUsers(userIds, "custom", title, content, null);

            NotificationSendLog sendLog = new NotificationSendLog();
            sendLog.setTitle(title);
            sendLog.setContent(content);
            sendLog.setScope(scope);
            sendLog.setClassId(logClassId);
            sendLog.setClassName(logClassName);
            sendLog.setReceiverCount(count);
            sendLog.setSender(sender);
            try {
                sendLogMapper.insert(sendLog);
            } catch (Exception e) {
                log.warn("通知发送记录写入失败（不影响通知本身）: {}", e.getMessage());
            }

            result.put("success", true);
            result.put("count", count);
            result.put("message", "通知发送成功，共发送 " + count + " 条（" + scopeText
                    + ("class".equals(scope) && logClassName != null ? "：" + logClassName : "") + "）");
        } catch (Exception e) {
            log.error("自定义通知发送失败", e);
            result.put("success", false);
            result.put("message", "通知发送失败，请稍后重试");
        }
        return result;
    }

    // ==================== 用户端读取 ====================

    /** 本人通知列表（最新在前） */
    public Map<String, Object> listForUser(Long userId) {
        return queryService.listForUser(userId);
    }

    /** 本人未读数量 */
    public Map<String, Object> unreadCount(Long userId) {
        return queryService.unreadCount(userId);
    }

    /** 标记单条已读（仅本人通知） */
    public Map<String, Object> markRead(Long userId, Long id) {
        return queryService.markRead(userId, id);
    }

    /** 全部标记已读 */
    public Map<String, Object> markReadAll(Long userId) {
        return queryService.markReadAll(userId);
    }
}
