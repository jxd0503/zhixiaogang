package com.zhixiaogang.service.classroom;

import com.zhixiaogang.entity.ClassroomReservation;
import com.zhixiaogang.entity.ScheduleAdjustment;
import com.zhixiaogang.service.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 教室释放提醒：调课撤销/紧急回滚、预约撤销导致教室空出时，通知曾在该教室该时段预约被拒的学生；
 * 没有匹配对象时给管理员一条汇总提示。仅做通知，不改变业务状态，异常不影响主流程。
 */
@Service
public class ClassroomReleaseNoticeService {

    private static final Logger log = LoggerFactory.getLogger(ClassroomReleaseNoticeService.class);

    private static final String TITLE_STUDENT = "教室时段已释放";
    private static final String TITLE_ADMIN = "教室释放提醒";

    private static final String[] WEEKDAY_NAMES = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NotificationService notificationService;

    /** 调课撤销/紧急回滚：目标时段的教室空出，匹配未来 7 天内同教室同星期同时段被拒的预约 */
    public void onAdjustmentReleased(ScheduleAdjustment a) {
        if (a == null) {
            return;
        }
        try {
            Long roomId = a.getTargetClassroomId() != null ? a.getTargetClassroomId() : scheduleClassroom(a.getScheduleId());
            if (roomId == null) {
                return;
            }
            int weekDay = num(a.getTargetWeekDay());
            int start = num(a.getTargetStartPeriod());
            int end = num(a.getTargetEndPeriod());
            if (weekDay < 1 || weekDay > 7 || start < 1 || end < start) {
                return;
            }
            String roomNo = classroomNo(roomId);
            String slot = WEEKDAY_NAMES[weekDay] + " 第" + start + "-" + end + "节";

            List<Map<String, Object>> candidates = jdbcTemplate.queryForList(
                    "SELECT r.student_id, MIN(r.reservation_date) AS reservation_date FROM classroom_reservation r " +
                            "WHERE r.classroom_id = ? AND r.status = 'rejected' " +
                            "AND r.reservation_date BETWEEN CURDATE() AND DATE_ADD(CURDATE(), INTERVAL 7 DAY) " +
                            "AND DAYOFWEEK(r.reservation_date) = ? " +
                            "AND r.start_period <= ? AND r.end_period >= ? " +
                            "GROUP BY r.student_id ORDER BY reservation_date",
                    roomId, weekDay % 7 + 1, end, start);

            for (Map<String, Object> c : candidates) {
                String studentId = str(c.get("student_id"));
                Long userId = findUserId(studentId);
                String date = str(c.get("reservation_date"));
                if (userId == null || !markOnce(userId, a.getId(), TITLE_STUDENT)) {
                    continue;
                }
                notificationService.create(userId, "classroom", TITLE_STUDENT,
                        "教室 " + roomNo + " 于 " + slot + "（" + date + "）已释放（调课撤销/回滚），"
                                + "你此前在该时段的预约被拒，可重新预约。", a.getId());
            }
            if (candidates.isEmpty()) {
                notifyAdmins(a.getId(), "教室 " + roomNo + " " + slot + " 已释放（调课撤销/回滚），暂无曾预约被拒的学生可通知。");
            }
        } catch (Exception e) {
            log.warn("教室释放提醒（调课）发送失败: adjustmentId={}", a.getId(), e);
        }
    }

    /** 预约撤销：该日期该教室时段空出，匹配同日同教室同时段被拒的其他学生 */
    public void onReservationReleased(ClassroomReservation r) {
        if (r == null) {
            return;
        }
        try {
            Long roomId = r.getClassroomId();
            if (roomId == null || r.getReservationDate() == null) {
                return;
            }
            int start = num(r.getStartPeriod());
            int end = num(r.getEndPeriod());
            if (start < 1 || end < start) {
                return;
            }
            String roomNo = classroomNo(roomId);
            String slot = segmentPeriodText(start, end);

            List<Map<String, Object>> candidates = jdbcTemplate.queryForList(
                    "SELECT DISTINCT student_id FROM classroom_reservation " +
                            "WHERE classroom_id = ? AND reservation_date = ? AND status = 'rejected' " +
                            "AND start_period <= ? AND end_period >= ? AND student_id <> ?",
                    roomId, r.getReservationDate(), end, start, str(r.getStudentId()));

            for (Map<String, Object> c : candidates) {
                Long userId = findUserId(str(c.get("student_id")));
                if (userId == null || !markOnce(userId, r.getId(), TITLE_STUDENT)) {
                    continue;
                }
                notificationService.create(userId, "classroom", TITLE_STUDENT,
                        "教室 " + roomNo + " 于 " + r.getReservationDate() + " " + slot + " 已释放（预约撤销），"
                                + "你此前在该时段的预约被拒，可重新预约。", r.getId());
            }
            if (candidates.isEmpty()) {
                notifyAdmins(r.getId(), "教室 " + roomNo + " 于 " + r.getReservationDate() + " " + slot
                        + " 已释放（预约撤销），暂无曾预约被拒的学生可通知。");
            }
        } catch (Exception e) {
            log.warn("教室释放提醒（预约）发送失败: reservationId={}", r.getId(), e);
        }
    }

    private Long scheduleClassroom(Long scheduleId) {
        if (scheduleId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT classroom_id FROM schedule WHERE id = ?", scheduleId);
        if (rows.isEmpty() || rows.get(0).get("classroom_id") == null) {
            return null;
        }
        return ((Number) rows.get(0).get("classroom_id")).longValue();
    }

    private String classroomNo(Long roomId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT classroom_no FROM classroom WHERE id = ?", roomId);
        String no = rows.isEmpty() ? "" : str(rows.get(0).get("classroom_no"));
        return no.isEmpty() ? ("#" + roomId) : no;
    }

    private Long findUserId(String studentId) {
        return notificationService.findUserId(studentId);
    }

    /** 同一事件对同一用户只提醒一次，避免重复撤销/回滚造成骚扰 */
    private boolean markOnce(Long userId, Long relatedId, String title) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND related_id = ? AND title = ?",
                Integer.class, userId, relatedId, title);
        return exists == null || exists == 0;
    }

    private void notifyAdmins(Long relatedId, String content) {
        List<Long> adminIds = jdbcTemplate.queryForList(
                "SELECT id FROM user WHERE role = 'admin' AND status = 1", Long.class);
        for (Long adminId : new ArrayList<>(adminIds)) {
            if (markOnce(adminId, relatedId, TITLE_ADMIN)) {
                notificationService.create(adminId, "classroom", TITLE_ADMIN, content, relatedId);
            }
        }
    }

    /** 预约段号(1-5)换算为节次文本，与现有预约通知口径一致 */
    private static String segmentPeriodText(int segStart, int segEnd) {
        int ps = (segStart - 1) * 2 + 1;
        int pe = segEnd * 2;
        if (segStart == segEnd) {
            return "第" + ps + "-" + pe + "节";
        }
        int es = (segEnd - 1) * 2 + 1;
        int ee = segEnd * 2;
        return "第" + ps + "-" + (ps + 1) + "至" + es + "-" + ee + "节";
    }

    private static int num(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }
}
