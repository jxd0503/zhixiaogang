package com.zhixiaogang.service.adjustment;

import com.zhixiaogang.entity.ScheduleAdjustment;
import com.zhixiaogang.entity.User;
import com.zhixiaogang.mapper.ScheduleAdjustmentMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 教师互换课程：两位教师对调同一周次范围内的两条课表记录。
 * 发起后需对方教师在教师端确认，管理员批准后两条 schedule 行同步互换授课教师；
 * 复用现有调课审批、通知与审批流水，不新开流程。
 */
@Service
public class AdjustmentSwapService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ScheduleAdjustmentMapper adjustmentMapper;

    @Autowired
    private AdjustmentConflictChecker conflictChecker;

    @Autowired
    private AdjustmentNotifier notifier;

    @Autowired
    private AdjustmentAuditLogger auditLogger;

    /** 可互换的对方课程：同周次范围、不同教师、对方为启用状态教师账号 */
    public Map<String, Object> peerCourses(Long scheduleId, User user) {
        Map<String, Object> result = new HashMap<>();
        if (scheduleId == null) {
            result.put("success", false);
            result.put("message", "请先选择你的课程");
            return result;
        }
        Map<String, Object> mine = AdjustmentSupport.loadOrigSchedule(jdbcTemplate, scheduleId);
        if (mine == null) {
            result.put("success", false);
            result.put("message", "所选课程不存在");
            return result;
        }
        if (!ownedBy(mine, user) && !"admin".equals(user == null ? null : user.getRole())) {
            result.put("success", false);
            result.put("message", "只能互换自己的课程");
            return result;
        }
        String weekRange = mine.get("week_range") == null ? "" : mine.get("week_range").toString();
        String semester = mine.get("semester") == null ? "" : mine.get("semester").toString();
        String myTeacherId = mine.get("teacher_id") == null ? "" : mine.get("teacher_id").toString();
        String myTeacherName = mine.get("teacher_name") == null ? "" : mine.get("teacher_name").toString();

        StringBuilder sql = new StringBuilder(
                "SELECT s.id, s.class_id, s.course_id, s.teacher_id, s.teacher_name, " +
                "s.week_day, s.start_period, s.end_period, s.week_range, s.semester, " +
                "COALESCE(c.course_name, '') AS course_name, COALESCE(ci.class_name, '') AS class_name, " +
                "COALESCE(cr.classroom_no, '') AS classroom_no " +
                "FROM schedule s " +
                "JOIN user u ON u.student_id = s.teacher_id AND u.role = 'teacher' AND u.status = 1 " +
                "LEFT JOIN course c ON s.course_id = c.id " +
                "LEFT JOIN class_info ci ON s.class_id = ci.id " +
                "LEFT JOIN classroom cr ON s.classroom_id = cr.id " +
                "WHERE s.id <> ? AND s.teacher_id IS NOT NULL AND s.teacher_id <> '' " +
                "AND COALESCE(s.week_range, '') = ? ");
        List<Object> args = new ArrayList<>();
        args.add(scheduleId);
        args.add(weekRange);
        if (!myTeacherId.isEmpty()) {
            sql.append("AND s.teacher_id <> ? ");
            args.add(myTeacherId);
        }
        if (!semester.isEmpty()) {
            sql.append("AND (s.semester = ? OR s.semester IS NULL OR s.semester = '') ");
            args.add(semester);
        }
        sql.append("ORDER BY s.week_day, s.start_period, s.id");
        List<Map<String, Object>> options = new ArrayList<>();
        for (Map<String, Object> r : jdbcTemplate.queryForList(sql.toString(), args.toArray())) {
            // 姓名相同但工号缺失的历史数据也视为同一教师，避免自换
            if (!myTeacherName.isEmpty() && myTeacherName.equals(AdjustmentSupport.str(r.get("teacher_name")))) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scheduleId", r.get("id"));
            m.put("classId", r.get("class_id"));
            m.put("className", r.get("class_name"));
            m.put("courseName", r.get("course_name"));
            m.put("teacherId", r.get("teacher_id"));
            m.put("teacherName", r.get("teacher_name"));
            m.put("weekDay", r.get("week_day"));
            m.put("startPeriod", r.get("start_period"));
            m.put("endPeriod", r.get("end_period"));
            m.put("weekRange", r.get("week_range"));
            m.put("semester", r.get("semester"));
            m.put("classroomNo", r.get("classroom_no"));
            options.add(m);
        }
        result.put("success", true);
        result.put("courses", options);
        return result;
    }

    /** 发起互换：生成待审批记录，等待对方教师确认后由管理员批准 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> applySwap(Long scheduleId, Long peerScheduleId, String reason, User user) {
        Map<String, Object> result = new HashMap<>();
        String note = reason == null ? "" : reason.trim();
        if (scheduleId == null || peerScheduleId == null) {
            result.put("success", false);
            result.put("message", "请选择双方的课程");
            return result;
        }
        if (scheduleId.equals(peerScheduleId)) {
            result.put("success", false);
            result.put("message", "同一门课程不能与自己互换");
            return result;
        }
        if (note.isEmpty()) {
            result.put("success", false);
            result.put("message", "请填写互换原因");
            return result;
        }
        Map<String, Object> mine = AdjustmentSupport.loadOrigSchedule(jdbcTemplate, scheduleId);
        Map<String, Object> peer = AdjustmentSupport.loadOrigSchedule(jdbcTemplate, peerScheduleId);
        if (mine == null || peer == null) {
            result.put("success", false);
            result.put("message", "所选课程不存在");
            return result;
        }
        if (!ownedBy(mine, user)) {
            result.put("success", false);
            result.put("message", "只能互换自己的课程");
            return result;
        }
        String myTeacherId = AdjustmentSupport.str(mine.get("teacher_id"));
        String myTeacherName = AdjustmentSupport.str(mine.get("teacher_name"));
        String peerTeacherId = AdjustmentSupport.str(peer.get("teacher_id"));
        String peerTeacherName = AdjustmentSupport.str(peer.get("teacher_name"));
        if (peerTeacherId.isEmpty()) {
            result.put("success", false);
            result.put("message", "对方课程缺少授课教师信息，无法互换");
            return result;
        }
        if (peerTeacherId.equals(myTeacherId) || (!myTeacherName.isEmpty() && myTeacherName.equals(peerTeacherName))) {
            result.put("success", false);
            result.put("message", "不能与自己或自己的其他课程互换");
            return result;
        }
        String accountError = validatePeerAccount(peerTeacherId);
        if (accountError != null) {
            result.put("success", false);
            result.put("message", accountError);
            return result;
        }
        String weekRange = AdjustmentSupport.str(mine.get("week_range"));
        String peerWeekRange = AdjustmentSupport.str(peer.get("week_range"));
        if (!weekRange.equals(peerWeekRange)) {
            result.put("success", false);
            result.put("message", "仅支持同一周次范围内的课程互换（我的：" + weekRange + "，对方："
                    + (peerWeekRange.isEmpty() ? "未设置" : peerWeekRange) + "）");
            return result;
        }
        String semester = AdjustmentSupport.str(mine.get("semester"));
        String peerSemester = AdjustmentSupport.str(peer.get("semester"));
        if (!semester.isEmpty() && !peerSemester.isEmpty() && !semester.equals(peerSemester)) {
            result.put("success", false);
            result.put("message", "两门课程不在同一学期，无法互换");
            return result;
        }

        // 两条课程都不能已有进行中/已生效的调课，避免与现有流程叠加
        String mineDup = conflictChecker.existingAdjustmentError(scheduleId, weekRange);
        if (mineDup != null) {
            result.put("success", false);
            result.put("message", mineDup);
            return result;
        }
        String peerDup = conflictChecker.existingAdjustmentError(peerScheduleId, weekRange);
        if (peerDup != null) {
            result.put("success", false);
            result.put("message", "对方课程" + peerDup);
            return result;
        }

        // 双向教师时段校验：发起方在对方时段、对方在发起方时段都必须空闲
        List<ScheduleAdjustmentService.ConflictItem> items = new ArrayList<>();
        items.addAll(conflictChecker.collectTeacherConflicts(myTeacherId, myTeacherName,
                intVal(peer.get("week_day")), intVal(peer.get("start_period")), intVal(peer.get("end_period")),
                scheduleId, null, weekRange));
        items.addAll(conflictChecker.collectTeacherConflicts(peerTeacherId, peerTeacherName,
                intVal(mine.get("week_day")), intVal(mine.get("start_period")), intVal(mine.get("end_period")),
                peerScheduleId, null, weekRange));
        if (!items.isEmpty()) {
            List<String> lines = conflictTexts(items);
            result.put("success", false);
            result.put("hardConflict", true);
            result.put("conflicts", lines);
            result.put("message", "教师时段冲突，无法互换：" + String.join("；", lines));
            return result;
        }

        ScheduleAdjustment a = new ScheduleAdjustment();
        a.setScheduleId(scheduleId);
        a.setClassId(toLong(mine.get("class_id")));
        a.setCourseId(toLong(mine.get("course_id")));
        a.setCourseName(AdjustmentSupport.str(mine.get("course_name")));
        a.setTeacherId(myTeacherId.isEmpty() ? null : myTeacherId);
        a.setTeacherName(myTeacherName);
        a.setOrigWeekDay(intVal(mine.get("week_day")));
        a.setOrigStartPeriod(intVal(mine.get("start_period")));
        a.setOrigEndPeriod(intVal(mine.get("end_period")));
        a.setTargetWeekDay(intVal(peer.get("week_day")));
        a.setTargetStartPeriod(intVal(peer.get("start_period")));
        a.setTargetEndPeriod(intVal(peer.get("end_period")));
        a.setOrigWeekRange(weekRange);
        a.setWeekRange(weekRange);
        a.setSemester(semester.isEmpty() ? null : semester);
        a.setReason(note);
        a.setSourceType("manual");
        a.setStatus("pending");
        a.setApplyTime(LocalDateTime.now());
        a.setConflictResult("none");
        a.setPeerScheduleId(peerScheduleId);
        a.setPeerTeacherId(peerTeacherId);
        a.setPeerTeacherName(peerTeacherName);
        a.setPeerConfirmed(0);
        a.setSwapFlag(1);
        adjustmentMapper.insert(a);

        auditLogger.log(a.getId(), "apply", user == null ? null : user.getStudentId(),
                user == null ? null : user.getName(), "teacher", null, note,
                "教师互换申请：与 " + teacherLabel(peerTeacherName, peerTeacherId) + " 互换《"
                        + a.getCourseName() + "》与《" + AdjustmentSupport.str(peer.get("course_name")) + "》");
        notifier.notifySwapRequest(a);

        result.put("success", true);
        result.put("id", a.getId());
        result.put("message", "互换申请已提交，等待对方教师确认");
        return result;
    }

    /** 对方教师确认互换 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> confirmSwap(Long id, User user) {
        Map<String, Object> result = new HashMap<>();
        ScheduleAdjustment a = adjustmentMapper.selectByIdForUpdate(id);
        if (a == null || !isSwap(a)) {
            result.put("success", false);
            result.put("message", "互换记录不存在");
            return result;
        }
        if (!"pending".equals(a.getStatus())) {
            result.put("success", false);
            result.put("message", "仅待审批的互换申请可确认");
            return result;
        }
        if (a.getPeerConfirmed() != null && a.getPeerConfirmed() == 1) {
            result.put("success", false);
            result.put("message", "已确认过，无需重复确认");
            return result;
        }
        if (!isPeerTeacher(a, user)) {
            result.put("success", false);
            result.put("message", "只有被邀请的对方教师可以确认");
            return result;
        }
        a.setPeerConfirmed(1);
        a.setPeerConfirmTime(LocalDateTime.now());
        adjustmentMapper.updateById(a);
        auditLogger.log(id, "swap-confirm", user.getStudentId(), user.getName(), "teacher", null, null,
                "对方教师确认互换");
        result.put("success", true);
        result.put("message", "已确认互换，等待管理员审批");
        return result;
    }

    /** 管理员批准互换；仅当对方已确认、双方时段均空闲时生效 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> approveSwap(ScheduleAdjustment a, String approver) {
        Map<String, Object> result = new HashMap<>();
        if (a.getPeerConfirmed() == null || a.getPeerConfirmed() != 1) {
            auditLogger.log(a.getId(), "approve-blocked", null, approver, "admin", null, null,
                    "对方教师尚未确认互换，禁止批准");
            result.put("success", false);
            result.put("message", "对方教师尚未确认互换，暂不可批准");
            return result;
        }
        Map<String, Object> mine = AdjustmentSupport.loadOrigSchedule(jdbcTemplate, a.getScheduleId());
        Map<String, Object> peer = a.getPeerScheduleId() == null ? null
                : AdjustmentSupport.loadOrigSchedule(jdbcTemplate, a.getPeerScheduleId());
        if (mine == null || peer == null) {
            result.put("success", false);
            result.put("message", "课表记录已变化，请驳回后重新发起互换");
            return result;
        }
        if (!sameTeacher(mine, a.getTeacherId(), a.getTeacherName())) {
            result.put("success", false);
            result.put("message", "你的课程授课教师已变化，请驳回后重新发起互换");
            return result;
        }
        if (!sameTeacher(peer, a.getPeerTeacherId(), a.getPeerTeacherName())) {
            result.put("success", false);
            result.put("message", "对方课程授课教师已变化，请驳回后重新发起互换");
            return result;
        }
        String accountError = validatePeerAccount(a.getPeerTeacherId());
        if (accountError != null) {
            result.put("success", false);
            result.put("message", accountError);
            return result;
        }

        String weekRange = a.getWeekRange() == null ? AdjustmentSupport.str(mine.get("week_range")) : a.getWeekRange();
        List<ScheduleAdjustmentService.ConflictItem> items = new ArrayList<>();
        items.addAll(conflictChecker.collectTeacherConflicts(a.getTeacherId(), a.getTeacherName(),
                intVal(peer.get("week_day")), intVal(peer.get("start_period")), intVal(peer.get("end_period")),
                a.getScheduleId(), a.getId(), weekRange));
        items.addAll(conflictChecker.collectTeacherConflicts(a.getPeerTeacherId(), a.getPeerTeacherName(),
                intVal(mine.get("week_day")), intVal(mine.get("start_period")), intVal(mine.get("end_period")),
                a.getPeerScheduleId(), a.getId(), weekRange));
        if (!items.isEmpty()) {
            List<String> lines = conflictTexts(items);
            a.setConflictResult("teacher");
            a.setConflictDetail(conflictChecker.conflictDetailSnapshot(items));
            a.setCheckTime(LocalDateTime.now());
            a.setCheckBy(approver);
            adjustmentMapper.updateById(a);
            auditLogger.log(a.getId(), "approve-blocked", null, approver, "admin",
                    AdjustmentAuditLogger.conflictSnapshot(a.getConflictResult(), a.getConflictDetail()),
                    null, "教师互换存在教师时段硬冲突，禁止批准");
            result.put("success", false);
            result.put("hardConflict", true);
            result.put("conflicts", lines);
            result.put("message", "存在教师时段硬冲突，不可批准：" + String.join("；", lines));
            return result;
        }

        a.setStatus("approved");
        a.setRejectReason(null);
        a.setApprover(approver);
        a.setApproveTime(LocalDateTime.now());
        a.setConflictResult("none");
        a.setConflictDetail(null);
        a.setCheckTime(LocalDateTime.now());
        a.setCheckBy(approver);
        adjustmentMapper.updateById(a);
        // 两条课表同步换人：时间、教室、班级不变
        jdbcTemplate.update("UPDATE schedule SET teacher_id = ?, teacher_name = ? WHERE id = ?",
                a.getPeerTeacherId(), a.getPeerTeacherName(), a.getScheduleId());
        jdbcTemplate.update("UPDATE schedule SET teacher_id = ?, teacher_name = ? WHERE id = ?",
                a.getTeacherId(), a.getTeacherName(), a.getPeerScheduleId());
        notifier.notifySwapApproved(a);
        auditLogger.log(a.getId(), "approve", null, approver, "admin", null, null,
                "批准教师互换，两条课表同步换人");
        result.put("success", true);
        result.put("message", "已批准，两门课程授课教师已互换");
        return result;
    }

    /** 回滚已生效互换：两条课表换回原教师 */
    public void restoreSwap(ScheduleAdjustment a) {
        jdbcTemplate.update("UPDATE schedule SET teacher_id = ?, teacher_name = ? WHERE id = ?",
                a.getTeacherId(), a.getTeacherName(), a.getScheduleId());
        jdbcTemplate.update("UPDATE schedule SET teacher_id = ?, teacher_name = ? WHERE id = ?",
                a.getPeerTeacherId(), a.getPeerTeacherName(), a.getPeerScheduleId());
    }

    /** 校验当前两条课表是否仍处于互换生效状态；不满足时返回提示文案 */
    public String verifySwapped(ScheduleAdjustment a) {
        if (a.getPeerScheduleId() == null) {
            return "互换记录缺少对方课程，无法回滚";
        }
        Map<String, Object> mine = AdjustmentSupport.loadOrigSchedule(jdbcTemplate, a.getScheduleId());
        Map<String, Object> peer = AdjustmentSupport.loadOrigSchedule(jdbcTemplate, a.getPeerScheduleId());
        if (mine == null || peer == null) {
            return "课表记录已变化，无法紧急回滚";
        }
        if (!sameTeacher(mine, a.getPeerTeacherId(), a.getPeerTeacherName())
                || !sameTeacher(peer, a.getTeacherId(), a.getTeacherName())) {
            return "当前课表已不是互换生效状态，无需紧急回滚";
        }
        return null;
    }

    public static boolean isSwap(ScheduleAdjustment a) {
        return a != null && a.getSwapFlag() != null && a.getSwapFlag() == 1;
    }

    private String validatePeerAccount(String peerTeacherId) {
        if (peerTeacherId == null || peerTeacherId.isEmpty()) {
            return "对方课程缺少授课教师工号，无法互换";
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT role, status FROM user WHERE student_id = ?", peerTeacherId);
        if (rows.isEmpty()) {
            return "对方教师账号不存在，无法互换";
        }
        Map<String, Object> u = rows.get(0);
        if (!"teacher".equals(AdjustmentSupport.str(u.get("role")))) {
            return "对方账号不是教师，无法互换";
        }
        if (u.get("status") == null || ((Number) u.get("status")).intValue() != 1) {
            return "对方教师账号已停用，无法互换";
        }
        return null;
    }

    private boolean isPeerTeacher(ScheduleAdjustment a, User user) {
        if (user == null) {
            return false;
        }
        String peerId = AdjustmentSupport.str(a.getPeerTeacherId());
        if (!peerId.isEmpty() && peerId.equals(user.getStudentId())) {
            return true;
        }
        return peerId.isEmpty() && a.getPeerTeacherName() != null && a.getPeerTeacherName().equals(user.getName());
    }

    private static boolean ownedBy(Map<String, Object> row, User user) {
        return AdjustmentSupport.ownedBy(row, user);
    }

    private static boolean sameTeacher(Map<String, Object> row, String teacherId, String teacherName) {
        String id = AdjustmentSupport.str(row.get("teacher_id"));
        if (!id.isEmpty() && teacherId != null && !teacherId.isEmpty()) {
            return id.equals(teacherId);
        }
        return teacherName != null && teacherName.equals(AdjustmentSupport.str(row.get("teacher_name")));
    }

    private static String teacherLabel(String name, String id) {
        if (name == null || name.isEmpty()) {
            return id == null ? "" : id;
        }
        return (id == null || id.isEmpty()) ? name : name + "（" + id + "）";
    }

    private static int intVal(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        return ((Number) value).longValue();
    }

    private List<String> conflictTexts(List<ScheduleAdjustmentService.ConflictItem> items) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> line : conflictChecker.conflictDetailLines(items)) {
            lines.add(AdjustmentSupport.str(line.get("text")));
        }
        return lines;
    }
}
