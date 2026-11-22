package com.zhixiaogang.service.adjustment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 批量调课去重键单测：同一课程的多周展开为多条独立申请（周次不同 → 键不同），
 * 完全相同的明细才视为重复。
 */
class ScheduleAdjustmentBatchTest {

    @Test
    void differentWeeksProduceDifferentKeys() {
        assertNotEquals(ScheduleAdjustmentService.batchKey(3L, "3", null),
                ScheduleAdjustmentService.batchKey(3L, "5", null));
    }

    @Test
    void sameScheduleWeekAndTargetIsDuplicate() {
        assertEquals(ScheduleAdjustmentService.batchKey(3L, "3", null),
                ScheduleAdjustmentService.batchKey(3L, "3 ", null));
    }

    @Test
    void crossWeekTargetChangesKey() {
        assertNotEquals(ScheduleAdjustmentService.batchKey(3L, "3", null),
                ScheduleAdjustmentService.batchKey(3L, "3", 4));
    }

    @Test
    void nullValuesAreTolerated() {
        assertEquals("||", ScheduleAdjustmentService.batchKey(null, null, null));
    }
}
