package com.zhixiaogang.service.adjustment;

import com.zhixiaogang.entity.ScheduleAdjustment;
import com.zhixiaogang.service.notification.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 调课通知联动：按调课记录组装「调课已批准 / 未通过 / 已撤销 / 已撤回」文案并交给 NotificationService。
 * 文案与拆分前完全一致；写入失败由 NotificationService 内部兜底，不阻断业务。
 */
@Service
public class AdjustmentNotifier {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 批准通知：教师本人「调课已批准」+ 班级全部在读学生「调课通知」 */
    void notifyApproved(ScheduleAdjustment a) {
        String teacher = teacherLabel(a);
        String origText = AdjustmentSupport.weekdayText(a.getOrigWeekDay()) + " "
                + AdjustmentSupport.periodText(a.getOrigStartPeriod(), a.getOrigEndPeriod());
        String targetText = AdjustmentSupport.weekdayText(a.getTargetWeekDay()) + " "
                + AdjustmentSupport.periodText(a.getTargetStartPeriod(), a.getTargetEndPeriod());
        String teacherContent = "你申请的《" + AdjustmentSupport.str(a.getCourseName()) + "》调课（" + origText + " 调整为 " + targetText
                + "）已被管理员批准，课表已生效";
        String studentContent = "你的班级《" + AdjustmentSupport.str(a.getCourseName()) + "》课程已调课：" + origText + " 调整为 "
                + targetText + "（" + teacher + "），请留意课表变化";
        String substituteName = AdjustmentSupport.str(a.getSubstituteTeacherName());
        if (!substituteName.isEmpty()) {
            studentContent = "你的班级《" + AdjustmentSupport.str(a.getCourseName()) + "》课程已安排代课教师：" + substituteName
                    + "（" + targetText + "，原授课教师：" + teacher + "），请留意课表变化";
        }
        notificationService.onAdjustmentApproved(a.getId(), a.getTeacherId(), a.getTeacherName(),
                a.getClassId(), teacherContent, studentContent);
        // 通知代课教师本人
        if (!substituteName.isEmpty() && a.getSubstituteTeacherId() != null && !a.getSubstituteTeacherId().isEmpty()) {
            Long subUserId = notificationService.findUserId(a.getSubstituteTeacherId());
            if (subUserId != null) {
                notificationService.create(subUserId, "adjustment", "代课安排已批准",
                        "你被安排为《" + AdjustmentSupport.str(a.getCourseName()) + "》的代课教师（" + targetText
                                + "，原授课教师：" + teacher + "），请按时到课",
                        a.getId());
            }
        }
    }

    /** 拒绝通知：仅教师本人「调课申请未通过」（含拒绝原因），学生不收通知 */
    void notifyRejected(ScheduleAdjustment a, String reason) {
        if (isSwap(a)) {
            notifySwapRejected(a, reason);
            return;
        }
        String origText = AdjustmentSupport.weekdayText(a.getOrigWeekDay()) + " "
                + AdjustmentSupport.periodText(a.getOrigStartPeriod(), a.getOrigEndPeriod());
        String targetText = AdjustmentSupport.weekdayText(a.getTargetWeekDay()) + " "
                + AdjustmentSupport.periodText(a.getTargetStartPeriod(), a.getTargetEndPeriod());
        String content = "你申请的《" + AdjustmentSupport.str(a.getCourseName()) + "》调课（" + origText + " 调整为 " + targetText
                + "）未通过，原因：" + AdjustmentSupport.str(reason);
        notificationService.onAdjustmentRejected(a.getId(), a.getTeacherId(), a.getTeacherName(), content);
    }

    /** 撤销通知：教师本人与班级学生各收「调课已撤销」，课程恢复原时段 */
    void notifyCancelled(ScheduleAdjustment a) {
        if (isSwap(a)) {
            notifySwapCancelled(a);
            return;
        }
        String teacher = teacherLabel(a);
        String origText = AdjustmentSupport.weekdayText(a.getOrigWeekDay()) + " "
                + AdjustmentSupport.periodText(a.getOrigStartPeriod(), a.getOrigEndPeriod());
        String targetText = AdjustmentSupport.weekdayText(a.getTargetWeekDay()) + " "
                + AdjustmentSupport.periodText(a.getTargetStartPeriod(), a.getTargetEndPeriod());
        String teacherContent = "你申请的《" + AdjustmentSupport.str(a.getCourseName()) + "》调课（" + origText + " 调整为 " + targetText
                + "）已被管理员撤销，课程恢复原时段";
        String studentContent = "你班级《" + AdjustmentSupport.str(a.getCourseName()) + "》课程的调课已撤销：" + targetText
                + " 的课程恢复至 " + origText + "（" + teacher + "），请留意课表变化";
        notificationService.onAdjustmentCancelled(a.getId(), a.getTeacherId(), a.getTeacherName(),
                a.getClassId(), teacherContent, studentContent);
    }

    /** 教师自行撤回通知：仅教师本人收「调课已撤回」，学生不收；互换时通知对方教师 */
    void notifyWithdrawn(ScheduleAdjustment a) {
        String origText = AdjustmentSupport.weekdayText(a.getOrigWeekDay()) + " "
                + AdjustmentSupport.periodText(a.getOrigStartPeriod(), a.getOrigEndPeriod());
        String targetText = AdjustmentSupport.weekdayText(a.getTargetWeekDay()) + " "
                + AdjustmentSupport.periodText(a.getTargetStartPeriod(), a.getTargetEndPeriod());
        String content = "你申请的《" + AdjustmentSupport.str(a.getCourseName()) + "》调课（" + origText + " 调整为 " + targetText
                + "）已自行撤回";
        notificationService.onAdjustmentWithdrawn(a.getId(), a.getTeacherId(), a.getTeacherName(), content);
        if (isSwap(a)) {
            Long peerUserId = peerUserId(a);
            if (peerUserId != null) {
                notificationService.create(peerUserId, "adjustment", "教师互换已撤回",
                        teacherLabel(a) + " 已撤回与你换课的申请，课程安排保持不变", a.getId());
            }
        }
    }

    // ==================== 教师互换通知 ====================

    /** 互换发起：通知对方教师确认 */
    void notifySwapRequest(ScheduleAdjustment a) {
        Long peerUserId = peerUserId(a);
        if (peerUserId == null) {
            return;
        }
        Map<String, Object> peer = peerSchedule(a);
        String content = teacherLabel(a) + " 申请与你互换课程：TA 的《" + AdjustmentSupport.str(a.getCourseName())
                + "》（" + mySlotText(a) + "）与你的《" + AdjustmentSupport.str(peer.get("course_name"))
                + "》（" + peerSlotText(peer) + "）。请到教师端「我的调课」确认。";
        notificationService.create(peerUserId, "adjustment", "教师互换待确认", content, a.getId());
    }

    /** 互换批准：两位教师本人 + 两个班学生全部通知 */
    void notifySwapApproved(ScheduleAdjustment a) {
        Map<String, Object> peer = peerSchedule(a);
        String myTeacher = teacherLabel(a);
        String peerTeacher = peerTeacherLabel(a);
        String myCourse = AdjustmentSupport.str(a.getCourseName());
        String peerCourse = AdjustmentSupport.str(peer.get("course_name"));
        String mySlot = mySlotText(a);
        String peerSlot = peerSlotText(peer);

        Long myUserId = notificationService.findUserId(a.getTeacherId());
        if (myUserId != null) {
            notificationService.create(myUserId, "adjustment", "教师互换已批准",
                    "你与 " + peerTeacher + " 的课程互换已生效：你的《" + myCourse + "》（" + mySlot + "）改由 "
                            + peerTeacher + " 授课，你将于 " + peerSlot + " 为《" + peerCourse + "》授课",
                    a.getId());
        }
        Long peerUserId = peerUserId(a);
        if (peerUserId != null) {
            notificationService.create(peerUserId, "adjustment", "教师互换已批准",
                    "你与 " + myTeacher + " 的课程互换已生效：你的《" + peerCourse + "》（" + peerSlot + "）改由 "
                            + myTeacher + " 授课，你将于 " + mySlot + " 为《" + myCourse + "》授课",
                    a.getId());
        }
        notificationService.notifyClassStudents(a.getClassId(), "adjustment", "课程教师调整",
                "你的班级《" + myCourse + "》授课教师由 " + myTeacher + " 调整为 " + peerTeacher
                        + "（与另一门课程互换，上课时间与教室不变）", a.getId());
        Long peerClassId = longVal(peer.get("class_id"));
        if (peerClassId != null) {
            notificationService.notifyClassStudents(peerClassId, "adjustment", "课程教师调整",
                    "你的班级《" + peerCourse + "》授课教师由 " + peerTeacher + " 调整为 " + myTeacher
                            + "（与另一门课程互换，上课时间与教室不变）", a.getId());
        }
    }

    /** 互换被拒绝：两位教师本人均通知 */
    private void notifySwapRejected(ScheduleAdjustment a, String reason) {
        String content = "你与 " + peerTeacherLabel(a) + " 的教师互换申请未通过，原因："
                + AdjustmentSupport.str(reason);
        notificationService.onAdjustmentRejected(a.getId(), a.getTeacherId(), a.getTeacherName(), content);
        Long peerUserId = peerUserId(a);
        if (peerUserId != null) {
            notificationService.create(peerUserId, "adjustment", "教师互换未通过",
                    "你与 " + teacherLabel(a) + " 的教师互换申请未通过，原因：" + AdjustmentSupport.str(reason), a.getId());
        }
    }

    /** 互换撤销/紧急回滚：两位教师 + 两个班学生恢复原教师 */
    private void notifySwapCancelled(ScheduleAdjustment a) {
        Map<String, Object> peer = peerSchedule(a);
        String myTeacher = teacherLabel(a);
        String peerTeacher = peerTeacherLabel(a);
        String myCourse = AdjustmentSupport.str(a.getCourseName());
        String peerCourse = AdjustmentSupport.str(peer.get("course_name"));
        String reason = AdjustmentSupport.str(a.getEmergencyReason());
        if (reason.isEmpty()) {
            reason = AdjustmentSupport.str(a.getRejectReason());
        }
        Long myUserId = notificationService.findUserId(a.getTeacherId());
        if (myUserId != null) {
            notificationService.create(myUserId, "adjustment", "教师互换已撤销",
                    "你与 " + peerTeacher + " 的课程互换已撤销，两门课程恢复原授课教师"
                            + (reason.isEmpty() ? "" : "。原因：" + reason), a.getId());
        }
        Long peerUserId = peerUserId(a);
        if (peerUserId != null) {
            notificationService.create(peerUserId, "adjustment", "教师互换已撤销",
                    "你与 " + myTeacher + " 的课程互换已撤销，两门课程恢复原授课教师"
                            + (reason.isEmpty() ? "" : "。原因：" + reason), a.getId());
        }
        notificationService.notifyClassStudents(a.getClassId(), "adjustment", "课程教师恢复",
                "你的班级《" + myCourse + "》授课教师恢复为 " + myTeacher + "（教师互换已撤销）", a.getId());
        Long peerClassId = longVal(peer.get("class_id"));
        if (peerClassId != null) {
            notificationService.notifyClassStudents(peerClassId, "adjustment", "课程教师恢复",
                    "你的班级《" + peerCourse + "》授课教师恢复为 " + peerTeacher + "（教师互换已撤销）", a.getId());
        }
    }

    private boolean isSwap(ScheduleAdjustment a) {
        return a != null && a.getSwapFlag() != null && a.getSwapFlag() == 1;
    }

    private Long peerUserId(ScheduleAdjustment a) {
        if (a.getPeerTeacherId() == null || a.getPeerTeacherId().isEmpty()) {
            return null;
        }
        return notificationService.findUserId(a.getPeerTeacherId());
    }

    /** 对方课表行：课程名、班级、时段（教师互换通知用） */
    private Map<String, Object> peerSchedule(ScheduleAdjustment a) {
        if (a.getPeerScheduleId() == null) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT s.class_id, s.week_day, s.start_period, s.end_period, " +
                        "COALESCE(c.course_name, '') AS course_name, COALESCE(cl.class_name, '') AS class_name " +
                        "FROM schedule s LEFT JOIN course c ON s.course_id = c.id " +
                        "LEFT JOIN class_info cl ON s.class_id = cl.id WHERE s.id = ?",
                a.getPeerScheduleId());
        return rows.isEmpty() ? Collections.emptyMap() : rows.get(0);
    }

    private String mySlotText(ScheduleAdjustment a) {
        return AdjustmentSupport.weekdayText(a.getOrigWeekDay()) + " "
                + AdjustmentSupport.periodText(a.getOrigStartPeriod(), a.getOrigEndPeriod());
    }

    private String peerSlotText(Map<String, Object> peer) {
        if (peer.isEmpty()) {
            return "";
        }
        return AdjustmentSupport.weekdayText(intVal(peer.get("week_day"))) + " "
                + AdjustmentSupport.periodText(peer.get("start_period"), peer.get("end_period"));
    }

    private String peerTeacherLabel(ScheduleAdjustment a) {
        String name = AdjustmentSupport.str(a.getPeerTeacherName());
        String id = AdjustmentSupport.str(a.getPeerTeacherId());
        if (name.isEmpty()) {
            return id;
        }
        return id.isEmpty() ? name : name + "（" + id + "）";
    }

    private static int intVal(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static Long longVal(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    /** 撤销申请被管理员拒绝：调课保持生效，仅通知发起教师 */
    void notifyCancelRejected(ScheduleAdjustment a, String reason) {
        String origText = AdjustmentSupport.weekdayText(a.getOrigWeekDay()) + " "
                + AdjustmentSupport.periodText(a.getOrigStartPeriod(), a.getOrigEndPeriod());
        String targetText = AdjustmentSupport.weekdayText(a.getTargetWeekDay()) + " "
                + AdjustmentSupport.periodText(a.getTargetStartPeriod(), a.getTargetEndPeriod());
        String content = "你申请撤销的《" + AdjustmentSupport.str(a.getCourseName()) + "》调课（" + origText + " 调整为 " + targetText
                + "）未通过，原因：" + AdjustmentSupport.str(reason) + "。调课保持生效。";
        notificationService.onAdjustmentCancelRejected(a.getId(), a.getTeacherId(), a.getTeacherName(), content);
    }

    /** 授课教师展示标签：姓名（工号），工号缺失时仅姓名 */
    private String teacherLabel(ScheduleAdjustment a) {
        String name = AdjustmentSupport.str(a.getTeacherName());
        String tid = a.getTeacherId() == null ? "" : a.getTeacherId();
        if (name.isEmpty()) {
            return tid;
        }
        return tid.isEmpty() ? name : name + "（" + tid + "）";
    }
}
