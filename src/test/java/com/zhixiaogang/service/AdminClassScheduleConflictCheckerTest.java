package com.zhixiaogang.service;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * 课表冲突校验单测：教室/教师时段冲突、周次交集、学期过滤（纯单测，JdbcTemplate 用 mock）。
 */
@ExtendWith(MockitoExtension.class)
class AdminClassScheduleConflictCheckerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private AdminClassScheduleConflictChecker checker;

    /** JdbcTemplate 的 queryForList 为可变参数，统一按整个 Object[] 匹配 */
    private void stubRows(List<Map<String, Object>> rows) {
        lenient().when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(rows);
    }

    @Test
    void roomConflictDetected() {
        stubRows(List.of(row(10L, "张老师", "1-16", "2025-2026-1", 1, 2)));

        String message = checker.checkRoomAndTeacher(10L, "李老师", 1, 1, 2,
                "1-16", "2025-2026-1", null);

        assertNotNull(message);
        assertTrue(message.contains("教室"));
        assertTrue(message.contains("A101"));
    }

    @Test
    void teacherConflictDetected() {
        stubRows(List.of(row(99L, "张老师", "1-16", "2025-2026-1", 1, 2)));

        String message = checker.checkRoomAndTeacher(10L, "张老师", 1, 1, 2,
                "1-16", "2025-2026-1", null);

        assertNotNull(message);
        assertTrue(message.contains("教师"));
        assertTrue(message.contains("张老师"));
    }

    @Test
    void differentSemesterIgnored() {
        stubRows(List.of(row(10L, "张老师", "1-16", "2024-2025-2", 1, 2)));

        assertNull(checker.checkRoomAndTeacher(10L, "张老师", 1, 1, 2,
                "1-16", "2025-2026-1", null));
    }

    @Test
    void disjointWeeksIgnored() {
        stubRows(List.of(row(10L, "张老师", "1-5", "2025-2026-1", 1, 2)));

        assertNull(checker.checkRoomAndTeacher(10L, "张老师", 1, 1, 2,
                "6-10", "2025-2026-1", null));
    }

    @Test
    void classConflictRespectsSemester() {
        stubRows(List.of(row(10L, "张老师", "1-16", "2024-2025-2", 1, 2)));

        assertNull(checker.check(1L, 1, 1, 2, "1-16", "2025-2026-1", null));
    }

    @Test
    void classConflictDetectedInSameSemester() {
        Map<String, Object> row = row(10L, "张老师", "1-16", "2025-2026-1", 1, 2);
        row.put("id", 8L);
        row.put("course_name", "高等数学");
        stubRows(List.of(row));

        Map<String, Object> result = checker.check(1L, 1, 1, 2, "1-16", "2025-2026-1", null);

        assertNotNull(result);
        assertEquals(false, result.get("success"));
        assertTrue(String.valueOf(result.get("message")).contains("周次重叠"));
    }

    @Test
    void noRoomAndNoTeacherSkipsQuery() {
        assertNull(checker.checkRoomAndTeacher(null, "  ", 1, 1, 2, "1-16", "2025-2026-1", null));
    }

    private Map<String, Object> row(Long classroomId, String teacherName, String weekRange,
                                    String semester, int startPeriod, int endPeriod) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L);
        row.put("classroom_id", classroomId);
        row.put("teacher_name", teacherName);
        row.put("week_range", weekRange);
        row.put("semester", semester);
        row.put("start_period", startPeriod);
        row.put("end_period", endPeriod);
        row.put("course_name", "数据结构");
        row.put("classroom_no", "A101");
        row.put("building", "A");
        row.put("class_name", "软件工程1班");
        return row;
    }
}
