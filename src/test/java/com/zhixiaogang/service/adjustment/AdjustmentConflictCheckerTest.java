package com.zhixiaogang.service.adjustment;

import com.zhixiaogang.service.adjustment.ScheduleAdjustmentService.ConflictItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 教师维度占用校验单测（调课校验与代课教师校验共用同一套判定）：
 * 周次有交集判冲突，无交集放行；查询无结果时不产生冲突。
 */
@ExtendWith(MockitoExtension.class)
class AdjustmentConflictCheckerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private AdjustmentConflictChecker checker;

    @Test
    void teacherConflictDetectedWhenWeeksOverlap() {
        // 第一次查询为教师排课，第二次为教师已生效调课
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(scheduleRow("数据结构", "23软件1班", "1-16", 3, 4)))
                .thenReturn(List.of());

        List<ConflictItem> items = checker.collectTeacherConflicts("T001", "张三", 1, 3, 4, null, null, "1-16");

        assertEquals(1, items.size());
        assertEquals("teacher", items.get(0).dimension);
        assertEquals("数据结构", items.get(0).courseName);
    }

    @Test
    void teacherConflictIgnoredWhenWeeksNotOverlap() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(scheduleRow("数据结构", "23软件1班", "1-8", 3, 4)))
                .thenReturn(List.of());

        List<ConflictItem> items = checker.collectTeacherConflicts("T001", "张三", 1, 3, 4, null, null, "9-16");

        assertTrue(items.isEmpty());
    }

    @Test
    void teacherConflictEmptyWhenNoRows() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of())
                .thenReturn(List.of());

        assertTrue(checker.collectTeacherConflicts("T001", "张三", 1, 3, 4, null, null, "1-16").isEmpty());
    }

    @Test
    void slotTextMatchesUiFormat() {
        assertEquals("周三第7-8节", AdjustmentSupport.slotText(3, 7, 8));
        assertEquals("第7-8节", AdjustmentSupport.periodText(7, 8));
        assertTrue(AdjustmentSupport.weekInRange("1-8,10-16", 10));
    }

    private Map<String, Object> scheduleRow(String courseName, String className, String weekRange,
                                            int startPeriod, int endPeriod) {
        Map<String, Object> row = new HashMap<>();
        row.put("course_name", courseName);
        row.put("class_name", className);
        row.put("week_range", weekRange);
        row.put("classroom_no", "B1-101");
        row.put("start_period", startPeriod);
        row.put("end_period", endPeriod);
        return row;
    }
}
