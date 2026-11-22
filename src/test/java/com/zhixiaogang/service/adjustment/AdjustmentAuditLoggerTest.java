package com.zhixiaogang.service.adjustment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

/**
 * 审批流水单测：冲突快照拼接，超长字段截断，写入失败不影响业务。
 */
@ExtendWith(MockitoExtension.class)
class AdjustmentAuditLoggerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private AdjustmentAuditLogger auditLogger;

    @Test
    void conflictSnapshotCombinesResultAndDetail() {
        assertEquals("room: 目标教室已被占用", AdjustmentAuditLogger.conflictSnapshot("room", "目标教室已被占用"));
        assertEquals("teacher", AdjustmentAuditLogger.conflictSnapshot("teacher", "  "));
        assertNull(AdjustmentAuditLogger.conflictSnapshot(null, ""));
    }

    @Test
    void logTruncatesLongFields() {
        String longText = "冲".repeat(1200);
        auditLogger.log(5L, "force-approve", null, "管理员", "admin", longText, longText, longText);

        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(anyString(), args.capture(), args.capture(), args.capture(), args.capture(),
                args.capture(), args.capture(), args.capture(), args.capture());
        // 参数顺序：id, action, operatorId, operatorName, operatorRole, snapshot, reason, detail
        assertEquals(1000, String.valueOf(args.getAllValues().get(5)).length());
        assertEquals(500, String.valueOf(args.getAllValues().get(6)).length());
        assertEquals(500, String.valueOf(args.getAllValues().get(7)).length());
    }

    @Test
    void logSwallowsDatabaseError() {
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(jdbcTemplate).update(anyString(), any(Object[].class));

        auditLogger.log(5L, "approve", null, "管理员", "admin", null, "同意", "批准调课");
        // 不抛异常即通过：流水失败不能阻断审批业务
    }
}
