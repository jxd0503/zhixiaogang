package com.zhixiaogang.service.adjustment;

import com.zhixiaogang.entity.ScheduleAdjustment;
import com.zhixiaogang.entity.User;
import com.zhixiaogang.mapper.ScheduleAdjustmentMapper;
import com.zhixiaogang.service.notification.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调课撤销：教师撤销待审批申请（直接生效），或对已批准调课发起撤销审批申请（管理员同意后才恢复课表）。
 * 状态流转均在事务内对 schedule_adjustment 行加锁，撤销审批记录写在 cancel_* 字段，不覆盖原审批信息。
 */
@Service
public class AdjustmentCancelService {

    /** 撤销申请状态 */
    public static final String CANCEL_PENDING = "PENDING";
    public static final String CANCEL_REJECTED = "REJECTED";
    public static final String CANCEL_DONE = "DONE";

    @Autowired
    private ScheduleAdjustmentMapper adjustmentMapper;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private AdjustmentNotifier notifier;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdjustmentAuditLogger auditLogger;

    @Autowired
    private AdjustmentSwapService swapService;

    @Autowired
    private com.zhixiaogang.service.classroom.ClassroomReleaseNoticeService releaseNoticeService;

    /**
     * 教师点击「撤销调课申请」：
     * 待审批 → 直接撤销（pending→cancelled，调课未生效，课表无变化）；
     * 已批准 → 生成撤销审批申请（cancel_status=PENDING），等待管理员审批。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> requestCancel(Long id, User user, String reason) {
        Map<String, Object> result = new HashMap<>();
        ScheduleAdjustment a = adjustmentMapper.selectByIdForUpdate(id);
        if (a == null) {
            result.put("success", false);
            result.put("message", "调课记录不存在");
            return result;
        }
        if (!ownedBy(a, user)) {
            result.put("success", false);
            result.put("message", "无权操作该调课申请");
            return result;
        }
        String note = reason == null || reason.trim().isEmpty() ? "教师撤销调课申请" : reason.trim();

        if ("pending".equals(a.getStatus())) {
            a.setStatus("cancelled");
            a.setRejectReason(note);
            a.setApproveTime(LocalDateTime.now());
            adjustmentMapper.updateById(a);
            notifier.notifyWithdrawn(a);
            auditLogger.log(id, "withdraw", user.getStudentId(), user.getName(), "teacher", null, note,
                    "教师撤销待审批调课申请");
            result.put("success", true);
            result.put("status", a.getStatus());
            result.put("statusChange", "待审批→已撤销");
            result.put("message", "已撤销该调课申请");
            return result;
        }

        if (!"approved".equals(a.getStatus())) {
            result.put("success", false);
            result.put("message", "仅待审批或已批准的调课申请可撤销");
            return result;
        }
        if (CANCEL_PENDING.equals(a.getCancelStatus())) {
            result.put("success", false);
            result.put("message", "撤销申请正在审批中，请勿重复提交");
            return result;
        }
        a.setCancelStatus(CANCEL_PENDING);
        a.setCancelReason(note);
        a.setCancelApplyTime(LocalDateTime.now());
        a.setCancelRejectReason(null);
        adjustmentMapper.updateById(a);
        auditLogger.log(id, "cancel-request", user.getStudentId(), user.getName(), "teacher", null, note,
                "教师对已批准调课发起撤销审批申请");
        notifyAdmins("教师提交了撤销调课申请",
                "教师 " + teacherLabel(a) + " 申请撤销已批准的调课（《" + a.getCourseName() + "》"
                        + AdjustmentSupport.slotText(a.getOrigWeekDay(), a.getOrigStartPeriod(), a.getOrigEndPeriod())
                        + " → " + AdjustmentSupport.slotText(a.getTargetWeekDay(), a.getTargetStartPeriod(), a.getTargetEndPeriod())
                        + "），原因：" + note + "。请到调课审批页处理。", a.getId());
        result.put("success", true);
        result.put("status", a.getStatus());
        result.put("cancelStatus", a.getCancelStatus());
        result.put("statusChange", "已批准→撤销待审批");
        result.put("message", "撤销申请已提交，等待管理员审批，审批通过后课表恢复原时段");
        return result;
    }

    /** 管理端同意撤销：approved → cancelled，课表恢复原样，通知教师与班级学生 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> approveCancel(Long id, String approver) {
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
        if (!CANCEL_PENDING.equals(a.getCancelStatus())) {
            result.put("success", false);
            result.put("message", "该调课没有待审批的撤销申请");
            return result;
        }
        a.setStatus("cancelled");
        a.setCancelStatus(CANCEL_DONE);
        a.setCancelApprover(approver);
        a.setCancelApproveTime(LocalDateTime.now());
        a.setRejectReason(a.getCancelReason() == null || a.getCancelReason().trim().isEmpty()
                ? "管理员同意撤销调课" : a.getCancelReason());
        // 撤销审批通过：schedule 记录恢复原样（互换记录换回原教师，普通调课恢复原时段）
        if (AdjustmentSwapService.isSwap(a)) {
            swapService.restoreSwap(a);
        } else {
            AdjustmentSupport.restoreOrigSlot(jdbcTemplate, a);
        }
        adjustmentMapper.updateById(a);
        notifier.notifyCancelled(a);
        if (!AdjustmentSwapService.isSwap(a)) {
            releaseNoticeService.onAdjustmentReleased(a);
        }
        auditLogger.log(id, "cancel-approve", null, approver, "admin", null, a.getRejectReason(),
                AdjustmentSwapService.isSwap(a) ? "管理员同意撤销，两门课程恢复原授课教师" : "管理员同意撤销，课表恢复原时段");
        result.put("success", true);
        result.put("status", a.getStatus());
        result.put("cancelStatus", a.getCancelStatus());
        result.put("statusChange", "撤销待审批→已撤销");
        result.put("message", "已同意撤销，课表恢复原时段");
        return result;
    }

    /**
     * 判断课表是否已同步到调课目标时段。
     * 部分周次调课审批时会拆分出目标周次的新课表行（targetScheduleId），
     * 此时原课表行仍是原时段，需以拆分行为准，否则紧急回滚会被误判为“未生效”。
     */
    private boolean synced(ScheduleAdjustment a, Map<String, Object> origRow) {
        if (a.getTargetScheduleId() != null) {
            return AdjustmentSupport.slotSynced(a, loadScheduleRow(a.getTargetScheduleId()));
        }
        return AdjustmentSupport.slotSynced(a, origRow);
    }

    /** 管理端拒绝撤销：cancel_status=REJECTED，调课保持生效，通知发起教师 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> rejectCancel(Long id, String approver, String reason) {
        Map<String, Object> result = new HashMap<>();
        if (reason == null || reason.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "请填写拒绝撤销的原因");
            return result;
        }
        ScheduleAdjustment a = adjustmentMapper.selectByIdForUpdate(id);
        if (a == null) {
            result.put("success", false);
            result.put("message", "调课记录不存在");
            return result;
        }
        if (!"approved".equals(a.getStatus())) {
            result.put("success", false);
            result.put("message", "仅已批准的调课可拒绝撤销");
            return result;
        }
        if (!CANCEL_PENDING.equals(a.getCancelStatus())) {
            result.put("success", false);
            result.put("message", "该调课没有待审批的撤销申请");
            return result;
        }
        a.setCancelStatus(CANCEL_REJECTED);
        a.setCancelRejectReason(reason.trim());
        a.setCancelApprover(approver);
        a.setCancelApproveTime(LocalDateTime.now());
        adjustmentMapper.updateById(a);
        notifier.notifyCancelRejected(a, reason.trim());
        auditLogger.log(id, "cancel-reject", null, approver, "admin", null, reason.trim(),
                "管理员拒绝撤销，调课保持生效");
        result.put("success", true);
        result.put("status", a.getStatus());
        result.put("cancelStatus", a.getCancelStatus());
        result.put("statusChange", "撤销待审批→撤销被拒（调课保持生效）");
        result.put("message", "已拒绝撤销申请，调课保持生效");
        return result;
    }

    /**
     * 教师紧急回滚：已批准且课表已同步的调课直接恢复原时段，不等审批；
     * 记录紧急标记/原因与审批流水，通知管理员事后确认知悉（操作日志由 Controller 统一记录）。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> emergencyRollback(Long id, User user, String reason) {
        Map<String, Object> result = new HashMap<>();
        String note = reason == null ? "" : reason.trim();
        if (note.isEmpty()) {
            result.put("success", false);
            result.put("message", "请填写紧急回滚原因");
            return result;
        }
        ScheduleAdjustment a = adjustmentMapper.selectByIdForUpdate(id);
        if (a == null) {
            result.put("success", false);
            result.put("message", "调课记录不存在");
            return result;
        }
        if (!ownedBy(a, user)) {
            result.put("success", false);
            result.put("message", "无权操作该调课申请");
            return result;
        }
        if (!"approved".equals(a.getStatus())) {
            result.put("success", false);
            result.put("message", "仅已批准且已生效的调课可紧急回滚");
            return result;
        }
        if (a.getEmergencyFlag() != null && a.getEmergencyFlag() == 1) {
            result.put("success", false);
            result.put("message", "该调课已紧急回滚，请勿重复操作");
            return result;
        }
        Map<String, Object> row = loadScheduleRow(a.getScheduleId());
        if (AdjustmentSwapService.isSwap(a)) {
            String swapError = swapService.verifySwapped(a);
            if (swapError != null) {
                result.put("success", false);
                result.put("message", swapError);
                return result;
            }
        } else if (!synced(a, row)) {
            result.put("success", false);
            result.put("message", "当前课表已不是该调课的目标时段，无需紧急回滚");
            return result;
        }

        a.setStatus("cancelled");
        a.setCancelStatus(CANCEL_DONE);
        a.setRejectReason("教师紧急回滚：" + note);
        a.setEmergencyFlag(1);
        a.setEmergencyReason(note);
        a.setEmergencyReviewBy(null);
        a.setEmergencyReviewTime(null);
        if (AdjustmentSwapService.isSwap(a)) {
            swapService.restoreSwap(a);
        } else {
            AdjustmentSupport.restoreOrigSlot(jdbcTemplate, a);
        }
        adjustmentMapper.updateById(a);
        auditLogger.log(id, "emergency-rollback", user.getStudentId(), user.getName(), "teacher",
                null, note, AdjustmentSwapService.isSwap(a)
                        ? "教师紧急回滚，两门课程恢复原授课教师" : "教师紧急回滚，课表已恢复原时段");
        notifier.notifyCancelled(a);
        if (!AdjustmentSwapService.isSwap(a)) {
            releaseNoticeService.onAdjustmentReleased(a);
        }
        String origText = AdjustmentSupport.slotText(a.getOrigWeekDay(), a.getOrigStartPeriod(), a.getOrigEndPeriod());
        String targetText = AdjustmentSupport.slotText(a.getTargetWeekDay(), a.getTargetStartPeriod(), a.getTargetEndPeriod());
        notifyAdmins("教师紧急回滚调课，请事后确认知悉",
                "教师 " + teacherLabel(a) + " 紧急回滚《" + AdjustmentSupport.str(a.getCourseName()) + "》"
                        + (AdjustmentSwapService.isSwap(a) ? "教师互换申请" : "调课")
                        + "（" + targetText + " → " + origText + "），原因：" + note
                        + "。课表已恢复，请到调课审批页确认知悉。", id);

        result.put("success", true);
        result.put("status", a.getStatus());
        result.put("statusChange", AdjustmentSwapService.isSwap(a)
                ? "已批准→紧急回滚（两门课程恢复原授课教师）" : "已批准→紧急回滚（课表已恢复原时段）");
        result.put("message", AdjustmentSwapService.isSwap(a)
                ? "已紧急回滚，两门课程恢复原授课教师；管理员将事后确认知悉"
                : "已紧急回滚，课表恢复原时段；管理员将事后确认知悉");
        return result;
    }

    /** 管理员事后补审：仅确认知悉并写流水，不改变调课状态与课表 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> acknowledgeEmergency(Long id, String approver) {
        Map<String, Object> result = new HashMap<>();
        ScheduleAdjustment a = adjustmentMapper.selectByIdForUpdate(id);
        if (a == null) {
            result.put("success", false);
            result.put("message", "调课记录不存在");
            return result;
        }
        if (a.getEmergencyFlag() == null || a.getEmergencyFlag() != 1) {
            result.put("success", false);
            result.put("message", "该调课不是紧急回滚记录，无需补审");
            return result;
        }
        if (a.getEmergencyReviewBy() != null && !a.getEmergencyReviewBy().isEmpty()) {
            result.put("success", false);
            result.put("message", "已确认知悉，无需重复确认");
            return result;
        }
        a.setEmergencyReviewBy(approver);
        a.setEmergencyReviewTime(LocalDateTime.now());
        adjustmentMapper.updateById(a);
        auditLogger.log(id, "emergency-review", null, approver, "admin", null, null,
                "管理员事后确认知悉紧急回滚");
        result.put("success", true);
        result.put("message", "已确认知悉");
        return result;
    }

    private Map<String, Object> loadScheduleRow(Long scheduleId) {
        if (scheduleId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT week_day, start_period, end_period FROM schedule WHERE id = ?", scheduleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean ownedBy(ScheduleAdjustment a, User user) {
        if (user == null) {
            return false;
        }
        if (a.getTeacherId() != null && !a.getTeacherId().isEmpty()) {
            if (a.getTeacherId().equals(user.getStudentId())) {
                return true;
            }
        } else if (a.getTeacherName() != null && a.getTeacherName().equals(user.getName())) {
            return true;
        }
        // 教师互换的对方教师同为当事人，可发起撤销/紧急回滚
        return AdjustmentSwapService.isSwap(a) && a.getPeerTeacherId() != null
                && a.getPeerTeacherId().equals(user.getStudentId());
    }

    private String teacherLabel(ScheduleAdjustment a) {
        String name = AdjustmentSupport.str(a.getTeacherName());
        String tid = a.getTeacherId() == null ? "" : a.getTeacherId();
        if (name.isEmpty()) {
            return tid;
        }
        return tid.isEmpty() ? name : name + "（" + tid + "）";
    }

    /** 通知全部管理员账号（role=admin） */
    private void notifyAdmins(String title, String content, Long relatedId) {
        try {
            List<Long> adminIds = jdbcTemplate.queryForList(
                    "SELECT id FROM user WHERE role = 'admin' AND status = 1", Long.class);
            if (adminIds == null || adminIds.isEmpty()) {
                return;
            }
            for (Long uid : new ArrayList<>(adminIds)) {
                notificationService.create(uid, "adjustment", title, content, relatedId);
            }
        } catch (Exception ignore) {
            // 通知失败不阻断撤销流程
        }
    }
}
