package com.zhixiaogang.service.adjustment;

import com.zhixiaogang.entity.ScheduleAdjustment;
import com.zhixiaogang.entity.User;
import com.zhixiaogang.mapper.ClassroomMapper;
import com.zhixiaogang.mapper.ScheduleAdjustmentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 调课审批流转：教师端申请列表/撤回、管理端列表/批准/拒绝/撤销/二次校验。
 * 所有状态流转均在事务内对 schedule_adjustment 行加锁，避免并发审批竞态；冲突口径复用 {@link AdjustmentConflictChecker}。
 */
@Service
public class AdjustmentReviewService {

    private static final Logger log = LoggerFactory.getLogger(AdjustmentReviewService.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ScheduleAdjustmentMapper adjustmentMapper;

    @Autowired
    private ClassroomMapper classroomMapper;

    @Autowired
    private AdjustmentConflictChecker conflictChecker;

    @Autowired
    private AdjustmentNotifier notifier;

    @Autowired
    private AdjustmentAuditLogger auditLogger;

    @Autowired
    private AdjustmentSwapService swapService;

    /** 教师端：本人调课申请列表（分页） */
    public Map<String, Object> myAdjustments(String teacherId, String teacherName, Integer page, Integer size) {
        Map<String, Object> result = new HashMap<>();
        int p = normalizePage(page);
        int s = normalizeSize(size);
        try {
            String where = "FROM schedule_adjustment a " +
                    "WHERE (a.teacher_id = ? OR ((a.teacher_id IS NULL OR a.teacher_id = '') AND a.teacher_name = ?) " +
                    "OR (a.swap_flag = 1 AND (a.peer_teacher_id = ? OR ((a.peer_teacher_id IS NULL OR a.peer_teacher_id = '') " +
                    "AND a.peer_teacher_name = ?)))) ";
            Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + where, Long.class,
                    teacherId, teacherName, teacherId, teacherName);
            String sql = "SELECT a.id, a.schedule_id, a.class_id, a.course_id, a.course_name, a.teacher_name, " +
                    "a.orig_week_day, a.orig_start_period, a.orig_end_period, " +
                    "a.target_week_day, a.target_start_period, a.target_end_period, " +
                    "a.target_week, a.orig_week_range, a.target_schedule_id, " +
                    "a.target_classroom_id, a.change_classroom, a.ai_suggested, a.source_type, a.status_text, " +
                    "a.conflict_result, a.conflict_detail, a.check_time, a.check_by, " +
                    "a.week_range, a.semester, a.reason, a.status, a.apply_time, a.approve_time, a.approver, a.reject_reason, " +
                    "a.cancel_status, a.cancel_reason, a.cancel_apply_time, a.cancel_approver, a.cancel_approve_time, a.cancel_reject_reason, " +
                    "a.substitute_teacher_id, a.substitute_teacher_name, " +
                    "a.emergency_flag, a.emergency_reason, a.emergency_review_by, a.emergency_review_time, " +
                    "a.peer_schedule_id, a.peer_teacher_id, a.peer_teacher_name, a.peer_confirmed, a.peer_confirm_time, a.swap_flag, " +
                    "ci.class_name, s.classroom_id AS orig_classroom_id, " +
                    "oc.classroom_no AS orig_classroom_no, oc.room_type AS orig_room_type, " +
                    "tc.classroom_no AS target_classroom_no, tc.room_type AS target_room_type " +
                    "FROM schedule_adjustment a LEFT JOIN class_info ci ON a.class_id = ci.id " +
                    "LEFT JOIN schedule s ON a.schedule_id = s.id " +
                    "LEFT JOIN classroom oc ON s.classroom_id = oc.id " +
                    "LEFT JOIN classroom tc ON a.target_classroom_id = tc.id " +
                    "WHERE (a.teacher_id = ? OR ((a.teacher_id IS NULL OR a.teacher_id = '') AND a.teacher_name = ?) " +
                    "OR (a.swap_flag = 1 AND (a.peer_teacher_id = ? OR ((a.peer_teacher_id IS NULL OR a.peer_teacher_id = '') " +
                    "AND a.peer_teacher_name = ?)))) " +
                    "ORDER BY a.apply_time DESC, a.id DESC LIMIT ? OFFSET ?";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, teacherId, teacherName, teacherId, teacherName,
                    s, (p - 1) * s);
            List<Map<String, Object>> adjustments = toDisplayList(rows);
            // 互换待确认：仅对方教师本人可确认
            for (Map<String, Object> m : adjustments) {
                if (Integer.valueOf(1).equals(m.get("swapFlag")) && "pending".equals(m.get("status"))
                        && !Integer.valueOf(1).equals(m.get("peerConfirmed"))) {
                    boolean isPeer = teacherId != null && teacherId.equals(String.valueOf(m.get("peerTeacherId")));
                    m.put("canConfirm", isPeer);
                }
            }
            result.put("success", true);
            result.put("adjustments", adjustments);
            result.put("total", total == null ? 0L : total);
            result.put("page", p);
            result.put("size", s);
        } catch (Exception e) {
            log.warn("加载教师调课申请失败", e);
            result.put("success", false);
            result.put("message", "加载调课申请失败，请稍后重试");
            result.put("adjustments", List.of());
            result.put("total", 0);
            result.put("page", p);
            result.put("size", s);
        }
        return result;
    }

    private int normalizePage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    private int normalizeSize(Integer size) {
        if (size == null || size < 1) {
            return 10;
        }
        return Math.min(size, 100);
    }

    /** 教师端：撤回待审核申请（pending→cancelled，事务内锁行避免与 approve/reject 的状态竞态） */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> withdraw(Long id, User user, String reason) {
        Map<String, Object> result = new HashMap<>();
        ScheduleAdjustment a = adjustmentMapper.selectByIdForUpdate(id);
        if (a == null) {
            result.put("success", false);
            result.put("message", "调课记录不存在");
            return result;
        }
        if (!ownedByAdjustment(a, user)) {
            result.put("success", false);
            result.put("message", "无权操作该调课申请");
            return result;
        }
        if (!"pending".equals(a.getStatus())) {
            result.put("success", false);
            result.put("message", "仅待审核的申请可撤回");
            return result;
        }
        a.setStatus("cancelled");
        a.setRejectReason((reason == null || reason.trim().isEmpty()) ? "教师撤回" : reason.trim());
        adjustmentMapper.updateById(a);
        notifier.notifyWithdrawn(a);
        auditLogger.log(id, "withdraw", user.getStudentId(), user.getName(), "teacher", null,
                a.getRejectReason(), "教师撤回待审批调课申请");
        result.put("success", true);
        result.put("message", "已撤回该调课申请");
        return result;
    }

    // ==================== 管理端：审批 ====================

    /** 管理端：调课列表（可按状态筛选，分页） */
    public Map<String, Object> adminList(String status, Integer page, Integer size) {
        Map<String, Object> result = new HashMap<>();
        int p = normalizePage(page);
        int s = normalizeSize(size);
        try {
            String whereCond = "";
            List<Object> countArgs = new ArrayList<>();
            if (status != null && !status.trim().isEmpty()) {
                whereCond = "WHERE a.status = ? ";
                countArgs.add(status.trim());
            }
            Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schedule_adjustment a " + whereCond,
                    Long.class, countArgs.toArray());
            StringBuilder sql = new StringBuilder(
                    "SELECT a.id, a.schedule_id, a.class_id, a.course_id, a.course_name, a.teacher_name, " +
                    "a.orig_week_day, a.orig_start_period, a.orig_end_period, " +
                    "a.target_week_day, a.target_start_period, a.target_end_period, " +
                    "a.target_week, a.orig_week_range, a.target_schedule_id, " +
                    "a.target_classroom_id, a.change_classroom, a.ai_suggested, a.source_type, a.status_text, " +
                    "a.conflict_result, a.conflict_detail, a.check_time, a.check_by, " +
                    "a.week_range, a.semester, a.reason, a.status, a.apply_time, a.approve_time, a.approver, a.reject_reason, " +
                    "a.cancel_status, a.cancel_reason, a.cancel_apply_time, a.cancel_approver, a.cancel_approve_time, a.cancel_reject_reason, " +
                    "a.substitute_teacher_id, a.substitute_teacher_name, " +
                    "a.emergency_flag, a.emergency_reason, a.emergency_review_by, a.emergency_review_time, " +
                    "a.peer_schedule_id, a.peer_teacher_id, a.peer_teacher_name, a.peer_confirmed, a.peer_confirm_time, a.swap_flag, " +
                    "ci.class_name, s.classroom_id AS orig_classroom_id, " +
                    "oc.classroom_no AS orig_classroom_no, oc.room_type AS orig_room_type, " +
                    "tc.classroom_no AS target_classroom_no, tc.room_type AS target_room_type " +
                    "FROM schedule_adjustment a LEFT JOIN class_info ci ON a.class_id = ci.id " +
                    "LEFT JOIN schedule s ON a.schedule_id = s.id " +
                    "LEFT JOIN classroom oc ON s.classroom_id = oc.id " +
                    "LEFT JOIN classroom tc ON a.target_classroom_id = tc.id ");
            List<Object> args = new ArrayList<>(countArgs);
            sql.append(whereCond);
            // 按申请时间由近及远：最近提交的排最前，便于管理端优先处理新申请
            sql.append("ORDER BY a.apply_time DESC, a.id DESC LIMIT ? OFFSET ?");
            args.add(s);
            args.add((p - 1) * s);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
            result.put("success", true);
            result.put("adjustments", toDisplayList(rows));
            result.put("total", total == null ? 0L : total);
            result.put("page", p);
            result.put("size", s);
        } catch (Exception e) {
            log.warn("加载调课列表失败", e);
            result.put("success", false);
            result.put("message", "加载调课列表失败，请稍后重试");
            result.put("adjustments", List.of());
            result.put("total", 0);
            result.put("page", p);
            result.put("size", s);
        }
        return result;
    }

    /** 管理端：批准（READ_COMMITTED 避免 REPEATABLE READ 快照读导致 TOCTOU 竞态） */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> approve(Long id, String approver) {
        return approve(id, approver, false, null);
    }

    /** 管理端：批准（兼容旧调用，强制放行时需另传理由） */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> approve(Long id, String approver, boolean force) {
        return approve(id, approver, force, null);
    }

    /**
     * 管理端：批准。
     * 教师 / 班级硬冲突禁止强制放行（管理员只能驳回或要求教师改期）；
     * 教室占用（含已批准预约）属于软冲突，返回 needConfirm，管理员二次确认并填写理由后以 force=true 知情放行。
     * 同一课程已有生效调课（逻辑重复）始终硬拦截。
     *
     * @param forceReason 强制放行理由（force=true 且存在软冲突时必填，落操作日志留痕）
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> approve(Long id, String approver, boolean force, String forceReason) {
        Map<String, Object> result = new HashMap<>();
        // 1) 锁调课记录行（同一申请的并发 approve/reject/cancel/withdraw 在此串行）
        ScheduleAdjustment a = adjustmentMapper.selectByIdForUpdate(id);
        if (a == null) {
            result.put("success", false);
            result.put("message", "调课记录不存在");
            return result;
        }
        if (!"pending".equals(a.getStatus())) {
            result.put("success", false);
            result.put("message", "仅待审核的申请可批准");
            return result;
        }
        // 教师互换：双方确认后直接互换两条课表的授课教师，不走时段迁移逻辑
        if (AdjustmentSwapService.isSwap(a)) {
            return swapService.approveSwap(a, approver);
        }

        // 2) 解析生效教室（换教室时取目标教室），并锁原教室 + 目标教室 + 班级行，
        //    使同教室/同班级的并发 approve 串行，后续校验才能看到前一条已提交的 approved 状态
        Long origClassroomId = null;
        Map<String, Object> orig = a.getScheduleId() == null ? null
                : AdjustmentSupport.loadOrigSchedule(jdbcTemplate, a.getScheduleId());
        if (orig != null && orig.get("classroom_id") != null) {
            origClassroomId = ((Number) orig.get("classroom_id")).longValue();
        }
        Long effClassroomId = effectiveClassroomId(a, origClassroomId);
        Set<Long> lockIds = new LinkedHashSet<>();
        if (origClassroomId != null) lockIds.add(origClassroomId);
        if (effClassroomId != null) lockIds.add(effClassroomId);
        for (Long cid : lockIds) {
            try {
                classroomMapper.selectByIdForUpdate(cid);
            } catch (Exception ignored) { /* 教室不存在，跳过锁 */ }
        }
        // 锁班级（FOR UPDATE），不存在则跳过
        if (a.getClassId() != null) {
            jdbcTemplate.query(
                    "SELECT 1 FROM class_info WHERE id = ? FOR UPDATE",
                    rs -> {}, a.getClassId());
        }

        // 3) 此时同教室 / 同班级 / 同申请的并发请求已串行化，冲突校验可靠
        List<ScheduleAdjustmentService.ConflictItem> items = conflictChecker.collectConflicts(
                effClassroomId, a.getClassId(), a.getTeacherId(), a.getTeacherName(),
                a.getTargetWeekDay(), a.getTargetStartPeriod(), a.getTargetEndPeriod(),
                a.getScheduleId(), a.getId(), AdjustmentSupport.conflictWeekRange(a));
        // 指定代课教师时，代课教师同样必须时段空闲（与教师维度校验共用同一套判定）
        items.addAll(substituteConflicts(a));
        String dupError = conflictChecker.existingApprovedAdjustmentError(a.getScheduleId(), a.getTargetWeekDay(),
                a.getTargetStartPeriod(), a.getTargetEndPeriod(), a.getWeekRange());
        if (dupError != null) {
            a.setApprover(approver);
            a.setApproveTime(LocalDateTime.now());
            a.setStatus("rejected");
            a.setRejectReason("批准时校验冲突：" + dupError);
            adjustmentMapper.updateById(a);
            notifier.notifyRejected(a, a.getRejectReason());
            auditLogger.log(id, "approve-blocked", null, approver, "admin", null, dupError,
                    "批准时校验冲突失败，系统自动拒绝");
            result.put("success", false);
            result.put("message", "校验未通过，已拒绝：" + dupError);
            return result;
        }

        // 4) 落库本次校验快照（结论 + 明细 + 时间 + 校验人）
        a.setConflictResult(conflictChecker.conflictResultOf(items));
        a.setConflictDetail(conflictChecker.conflictDetailSnapshot(items));
        a.setCheckTime(LocalDateTime.now());
        a.setCheckBy(approver);

        // 4.1) 教师 / 班级硬冲突禁止强制放行：同一教师或同一班级同一时段已有在读课程，无论是否 force 一律不批准
        List<ScheduleAdjustmentService.ConflictItem> hardItems = new ArrayList<>();
        for (ScheduleAdjustmentService.ConflictItem item : items) {
            if ("teacher".equals(item.dimension) || "class".equals(item.dimension)) {
                hardItems.add(item);
            }
        }
        if (!hardItems.isEmpty()) {
            adjustmentMapper.updateById(a);
            List<String> hardLines = conflictTexts(hardItems);
            auditLogger.log(id, "approve-blocked", null, approver, "admin",
                    AdjustmentAuditLogger.conflictSnapshot(a.getConflictResult(), a.getConflictDetail()),
                    null, "存在教师/班级硬冲突，禁止强制批准");
            result.put("success", false);
            result.put("needConfirm", false);
            result.put("hardConflict", true);
            result.put("conflictResult", a.getConflictResult());
            result.put("conflictDetail", a.getConflictDetail());
            result.put("conflicts", hardLines);
            result.put("message", "存在教师/班级硬冲突，不可强制批准，请驳回或要求教师改期："
                    + String.join("；", hardLines));
            return result;
        }

        // 4.2) 强制放行必须填写理由，便于操作日志追溯
        if (!items.isEmpty() && force && (forceReason == null || forceReason.trim().isEmpty())) {
            adjustmentMapper.updateById(a);
            auditLogger.log(id, "approve-blocked", null, approver, "admin",
                    AdjustmentAuditLogger.conflictSnapshot(a.getConflictResult(), a.getConflictDetail()),
                    null, "强制放行未填写理由，已拦截");
            result.put("success", false);
            result.put("needConfirm", true);
            result.put("forceReasonRequired", true);
            result.put("conflictResult", a.getConflictResult());
            result.put("conflictDetail", a.getConflictDetail());
            result.put("message", "强制放行需填写理由");
            return result;
        }

        if (!items.isEmpty() && !force) {
            adjustmentMapper.updateById(a);
            List<String> lines = conflictTexts(items);
            auditLogger.log(id, "approve-blocked", null, approver, "admin",
                    AdjustmentAuditLogger.conflictSnapshot(a.getConflictResult(), a.getConflictDetail()),
                    null, "存在冲突，等待管理员确认是否知情放行");
            result.put("success", false);
            result.put("needConfirm", true);
            result.put("conflictResult", a.getConflictResult());
            result.put("conflictDetail", a.getConflictDetail());
            result.put("conflicts", lines);
            result.put("message", "存在冲突，确认批准？");
            return result;
        }

        a.setStatus("approved");
        a.setRejectReason(null);
        a.setApprover(approver);
        a.setApproveTime(LocalDateTime.now());
        // 批准即生效：同步把 schedule 原记录时段更新为目标时段（原时段持久化在 a.orig_* 快照）
        AdjustmentSupport.applyTargetSlot(jdbcTemplate, a);
        adjustmentMapper.updateById(a);
        notifier.notifyApproved(a);
        boolean forced = !items.isEmpty() && force;
        auditLogger.log(id, forced ? "force-approve" : "approve", null, approver, "admin",
                AdjustmentAuditLogger.conflictSnapshot(a.getConflictResult(), a.getConflictDetail()),
                forced ? forceReason : null,
                forced ? "已知情放行冲突并批准，调课即时生效" : "批准调课，课表已同步目标时段");
        result.put("success", true);
        result.put("forced", forced);
        result.put("message", items.isEmpty() ? "已批准，调课即时生效" : "已知情放行冲突并批准，调课即时生效");
        result.put("conflictResult", a.getConflictResult());
        return result;
    }

    /** 冲突明细转可读文案（用于硬冲突提示与强制放行二次确认） */
    private List<String> conflictTexts(List<ScheduleAdjustmentService.ConflictItem> items) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> line : conflictChecker.conflictDetailLines(items)) {
            lines.add(AdjustmentSupport.str(line.get("text")));
        }
        return lines;
    }

    /** 代课教师时段冲突：复用教师维度判定（排课 + 已生效调课），未指定代课教师时为空 */
    private List<ScheduleAdjustmentService.ConflictItem> substituteConflicts(ScheduleAdjustment a) {
        List<ScheduleAdjustmentService.ConflictItem> list = new ArrayList<>();
        if (a.getSubstituteTeacherId() == null || a.getSubstituteTeacherId().isEmpty()) {
            return list;
        }
        list.addAll(conflictChecker.collectTeacherConflicts(a.getSubstituteTeacherId(), a.getSubstituteTeacherName(),
                a.getTargetWeekDay(), a.getTargetStartPeriod(), a.getTargetEndPeriod(),
                a.getScheduleId(), a.getId(), AdjustmentSupport.conflictWeekRange(a)));
        return list;
    }

    /** 调课生效教室：换教室且填了目标教室时取目标教室，否则沿用原课表教室 */
    private Long effectiveClassroomId(ScheduleAdjustment a, Long origClassroomId) {
        if (a.getChangeClassroom() != null && a.getChangeClassroom() == 1 && a.getTargetClassroomId() != null) {
            return a.getTargetClassroomId();
        }
        return origClassroomId;
    }

    /**
     * 管理端：重新校验冲突（不改变审批状态，仅刷新校验快照）。
     * 按目标时段 + 目标教室分别校验教师课表、班级课表、教室占用（排课 + 已批准预约 + 生效调课）。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> conflictCheck(Long id, String checker) {
        Map<String, Object> result = new HashMap<>();
        ScheduleAdjustment a = adjustmentMapper.selectById(id);
        if (a == null) {
            result.put("success", false);
            result.put("message", "调课记录不存在");
            return result;
        }
        Long origClassroomId = null;
        Map<String, Object> orig = a.getScheduleId() == null ? null
                : AdjustmentSupport.loadOrigSchedule(jdbcTemplate, a.getScheduleId());
        if (orig != null && orig.get("classroom_id") != null) {
            origClassroomId = ((Number) orig.get("classroom_id")).longValue();
        }
        Long effClassroomId = effectiveClassroomId(a, origClassroomId);
        List<ScheduleAdjustmentService.ConflictItem> items = conflictChecker.collectConflicts(
                effClassroomId, a.getClassId(), a.getTeacherId(), a.getTeacherName(),
                a.getTargetWeekDay(), a.getTargetStartPeriod(), a.getTargetEndPeriod(),
                a.getScheduleId(), a.getId(), AdjustmentSupport.conflictWeekRange(a));
        items.addAll(substituteConflicts(a));

        String resultCode = conflictChecker.conflictResultOf(items);
        String detail = conflictChecker.conflictDetailSnapshot(items);
        jdbcTemplate.update(
                "UPDATE schedule_adjustment SET conflict_result = ?, conflict_detail = ?, check_time = ?, check_by = ? WHERE id = ?",
                resultCode, detail, LocalDateTime.now(), checker, id);

        boolean hardConflict = false;
        for (ScheduleAdjustmentService.ConflictItem item : items) {
            if ("teacher".equals(item.dimension) || "class".equals(item.dimension)) {
                hardConflict = true;
                break;
            }
        }
        result.put("success", true);
        result.put("hasConflict", !items.isEmpty());
        result.put("hardConflict", hardConflict);
        result.put("checkTime", LocalDateTime.now().toString());
        result.put("checkBy", checker);
        result.put("conflictResult", resultCode);
        result.put("conflictDetail", detail);
        result.put("conflictLines", conflictChecker.conflictDetailLines(items));
        result.put("message", items.isEmpty() ? "无冲突，可批准"
                : hardConflict ? "存在教师/班级硬冲突，不可强制批准" : "存在教室冲突，可知情放行");
        return result;
    }

    /** 管理端：拒绝（pending→rejected，必须填写理由；事务内锁行避免并发 reject/approve 竞态） */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> reject(Long id, String approver, String reason) {
        Map<String, Object> result = new HashMap<>();
        if (reason == null || reason.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "请填写拒绝原因");
            return result;
        }
        ScheduleAdjustment a = adjustmentMapper.selectByIdForUpdate(id);
        if (a == null) {
            result.put("success", false);
            result.put("message", "调课记录不存在");
            return result;
        }
        if (!"pending".equals(a.getStatus())) {
            result.put("success", false);
            result.put("message", "仅待审核的申请可拒绝");
            return result;
        }
        a.setStatus("rejected");
        String finalReason = reason.trim();
        a.setRejectReason(finalReason);
        a.setApprover(approver);
        a.setApproveTime(LocalDateTime.now());
        adjustmentMapper.updateById(a);
        notifier.notifyRejected(a, finalReason);
        auditLogger.log(id, "reject", null, approver, "admin",
                AdjustmentAuditLogger.conflictSnapshot(a.getConflictResult(), a.getConflictDetail()),
                finalReason, "管理员拒绝调课申请");
        result.put("success", true);
        result.put("message", "已拒绝");
        return result;
    }

    /** 管理端：撤销已批准调课（approved→cancelled，事务内锁行避免并发 cancel/approve 竞态） */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> cancel(Long id, String approver, String reason) {
        Map<String, Object> result = new HashMap<>();
        ScheduleAdjustment a = adjustmentMapper.selectByIdForUpdate(id);
        if (a == null) {
            result.put("success", false);
            result.put("message", "调课记录不存在");
            return result;
        }
        if (!"approved".equals(a.getStatus())) {
            result.put("success", false);
            result.put("message", "仅已批准的调课可撤销");
            return result;
        }
        a.setStatus("cancelled");
        a.setRejectReason((reason == null || reason.trim().isEmpty()) ? "管理端撤销" : reason.trim());
        a.setApprover(approver);
        a.setApproveTime(LocalDateTime.now());
        // 撤销即恢复：schedule 记录时段恢复为 orig_* 原时段（与批准时的同步对称）
        AdjustmentSupport.restoreOrigSlot(jdbcTemplate, a);
        adjustmentMapper.updateById(a);
        notifier.notifyCancelled(a);
        auditLogger.log(id, "cancel", null, approver, "admin", null, a.getRejectReason(),
                "管理员撤销已批准调课，课表恢复原时段");
        result.put("success", true);
        result.put("message", "已撤销，课表恢复原样");
        return result;
    }

    private boolean ownedByAdjustment(ScheduleAdjustment a, User user) {
        if (user == null) return false;
        if (a.getTeacherId() != null && !a.getTeacherId().isEmpty()) {
            return a.getTeacherId().equals(user.getStudentId());
        }
        return a.getTeacherName() != null && a.getTeacherName().equals(user.getName());
    }

    /** 教室类型（空值返回空串，前端据此不显示类型徽标） */
    private String roomTypeOrEmpty(Object value) {
        return AdjustmentSupport.str(value).trim();
    }

    /** 统一把 JDBC 返回的时间值转为 LocalDateTime（不同驱动类型不一致） */
    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toLocalDateTime();
        }
        if (value instanceof java.util.Date date) {
            return new java.sql.Timestamp(date.getTime()).toLocalDateTime();
        }
        return null;
    }

    private List<Map<String, Object>> toDisplayList(List<Map<String, Object>> rows) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", row.get("id"));
            m.put("scheduleId", row.get("schedule_id"));
            m.put("classId", row.get("class_id"));
            m.put("className", row.get("class_name"));
            m.put("courseName", row.get("course_name"));
            m.put("teacherName", row.get("teacher_name"));
            m.put("origWeekDay", row.get("orig_week_day"));
            m.put("origStartPeriod", row.get("orig_start_period"));
            m.put("origEndPeriod", row.get("orig_end_period"));
            m.put("origClassroomId", row.get("orig_classroom_id"));
            m.put("origClassroomNo", row.get("orig_classroom_no"));
            m.put("targetWeekDay", row.get("target_week_day"));
            m.put("targetStartPeriod", row.get("target_start_period"));
            m.put("targetEndPeriod", row.get("target_end_period"));
            m.put("targetWeek", row.get("target_week"));
            m.put("origWeekRange", row.get("orig_week_range"));
            m.put("targetClassroomId", row.get("target_classroom_id"));
            m.put("targetClassroomName", row.get("target_classroom_no"));
            String origRoomType = roomTypeOrEmpty(row.get("orig_room_type"));
            String targetRoomType = roomTypeOrEmpty(row.get("target_room_type"));
            m.put("origRoomType", origRoomType);
            m.put("targetRoomType", targetRoomType);
            // 目标教室与原教室类型不一致时提示，不阻断
            m.put("roomTypeMismatch", !origRoomType.isEmpty() && !targetRoomType.isEmpty()
                    && !origRoomType.equals(targetRoomType));
            m.put("changeClassroom", row.get("change_classroom"));
            m.put("aiSuggested", row.get("ai_suggested"));
            m.put("sourceType", row.get("source_type"));
            m.put("statusText", row.get("status_text"));
            m.put("conflictResult", row.get("conflict_result"));
            m.put("conflictDetail", row.get("conflict_detail"));
            m.put("checkTime", row.get("check_time"));
            m.put("checkBy", row.get("check_by"));
            m.put("weekRange", row.get("week_range"));
            m.put("semester", row.get("semester"));
            m.put("reason", row.get("reason"));
            m.put("status", row.get("status"));
            m.put("applyTime", row.get("apply_time"));
            // 待审批挂起时长（小时）：管理端列表标注超时，未审批记录随查询实时计算
            LocalDateTime appliedAt = toLocalDateTime(row.get("apply_time"));
            if ("pending".equals(row.get("status")) && appliedAt != null) {
                long hours = Duration.between(appliedAt, LocalDateTime.now()).toHours();
                m.put("pendingHours", Math.max(0, hours));
            }
            m.put("approveTime", row.get("approve_time"));
            m.put("approver", row.get("approver"));
            m.put("rejectReason", row.get("reject_reason"));
            // 撤销申请信息：PENDING 待审批 / REJECTED 已拒绝 / DONE 已完成，null 表示无撤销申请
            m.put("cancelStatus", row.get("cancel_status"));
            m.put("cancelReason", row.get("cancel_reason"));
            m.put("cancelApplyTime", row.get("cancel_apply_time"));
            m.put("cancelApprover", row.get("cancel_approver"));
            m.put("cancelApproveTime", row.get("cancel_approve_time"));
            m.put("cancelRejectReason", row.get("cancel_reject_reason"));
            m.put("substituteTeacherId", row.get("substitute_teacher_id"));
            m.put("substituteTeacherName", row.get("substitute_teacher_name"));
            // 紧急回滚留痕：标记、原因、补审人与补审时间
            m.put("emergencyFlag", row.get("emergency_flag"));
            m.put("emergencyReason", row.get("emergency_reason"));
            m.put("emergencyReviewBy", row.get("emergency_review_by"));
            m.put("emergencyReviewTime", row.get("emergency_review_time"));
            // 教师互换：对方课程/教师/确认状态
            m.put("swapFlag", row.get("swap_flag"));
            m.put("peerScheduleId", row.get("peer_schedule_id"));
            m.put("peerTeacherId", row.get("peer_teacher_id"));
            m.put("peerTeacherName", row.get("peer_teacher_name"));
            m.put("peerConfirmed", row.get("peer_confirmed"));
            m.put("peerConfirmTime", row.get("peer_confirm_time"));
            list.add(m);
        }
        return list;
    }
}
