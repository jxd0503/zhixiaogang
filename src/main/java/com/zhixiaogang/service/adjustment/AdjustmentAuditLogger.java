package com.zhixiaogang.service.adjustment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 调课审批动作流水：统一记录谁、何时、什么动作、看到了哪些冲突、强制放行或紧急回滚的理由。
 * 流水用于追溯，写入失败不阻断业务（仅告警），也绝不作为绕过审批的通道。
 */
@Service
public class AdjustmentAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AdjustmentAuditLogger.class);

    private static final int SNAPSHOT_MAX = 1000;
    private static final int REASON_MAX = 500;
    private static final int DETAIL_MAX = 500;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 写入一条审批流水。
     *
     * @param adjustmentId     调课记录ID
     * @param action           apply / approve / force-approve / approve-blocked / reject / withdraw /
     *                         cancel-request / cancel-approve / cancel-reject / cancel /
     *                         emergency-rollback / emergency-review
     * @param operatorId       操作人工号（管理员/系统动作可为空）
     * @param operatorName     操作人姓名
     * @param operatorRole     teacher / admin / system
     * @param conflictSnapshot 当时看到的冲突结论与明细
     * @param reason           强制放行 / 拒绝 / 撤销 / 紧急回滚等理由
     * @param detail           动作说明
     */
    public void log(Long adjustmentId, String action, String operatorId, String operatorName, String operatorRole,
                    String conflictSnapshot, String reason, String detail) {
        if (adjustmentId == null) {
            return;
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO schedule_adjustment_log (adjustment_id, action, operator_id, operator_name, " +
                            "operator_role, conflict_snapshot, reason, detail, create_time) VALUES (?,?,?,?,?,?,?,?,NOW())",
                    adjustmentId, action, operatorId, operatorName, operatorRole,
                    trim(conflictSnapshot, SNAPSHOT_MAX), trim(reason, REASON_MAX), trim(detail, DETAIL_MAX));
        } catch (Exception e) {
            log.warn("调课审批流水写入失败（不影响业务）：adjustmentId={}, action={}", adjustmentId, action, e);
        }
    }

    /** 审批流水查询（按时间正序，供管理端追溯） */
    public List<Map<String, Object>> listByAdjustment(Long adjustmentId) {
        return jdbcTemplate.queryForList(
                "SELECT id, adjustment_id, action, operator_id, operator_name, operator_role, " +
                        "conflict_snapshot, reason, detail, create_time " +
                        "FROM schedule_adjustment_log WHERE adjustment_id = ? ORDER BY id ASC",
                adjustmentId);
    }

    /** 冲突结论 + 明细拼接为流水快照，空值返回 null */
    public static String conflictSnapshot(String conflictResult, String conflictDetail) {
        String result = AdjustmentSupport.str(conflictResult).trim();
        String detail = AdjustmentSupport.str(conflictDetail).trim();
        if (result.isEmpty() && detail.isEmpty()) {
            return null;
        }
        if (detail.isEmpty()) {
            return result;
        }
        return result.isEmpty() ? detail : result + ": " + detail;
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.length() <= max ? v : v.substring(0, max);
    }
}
