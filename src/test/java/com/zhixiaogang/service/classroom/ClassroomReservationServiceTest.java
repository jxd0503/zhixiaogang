package com.zhixiaogang.service.classroom;

import com.zhixiaogang.entity.Classroom;
import com.zhixiaogang.entity.ClassroomReservation;
import com.zhixiaogang.mapper.ClassroomMapper;
import com.zhixiaogang.mapper.ClassroomReservationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 教室预约校验单测：过去日期、7 天窗口、原因长度、节次范围与时段重叠（纯单测，Mapper 用 mock）。
 */
@ExtendWith(MockitoExtension.class)
class ClassroomReservationServiceTest {

    @Mock
    private ClassroomMapper classroomMapper;

    @Mock
    private ClassroomReservationMapper reservationMapper;

    @Mock
    private ClassroomOccupancyService classroomOccupancyService;

    @InjectMocks
    private ClassroomReservationService service;

    @Test
    void rejectsPastDate() {
        Map<String, Object> result = service.reserveClassroom(1L, "20230001", "张三", "自习",
                LocalDate.now().minusDays(1), 1, 1);
        assertFalse((Boolean) result.get("success"));
        assertEquals("不能预约过去的日期", result.get("message"));
    }

    @Test
    void rejectsBeyondSevenDays() {
        Map<String, Object> result = service.reserveClassroom(1L, "20230001", "张三", "自习",
                LocalDate.now().plusDays(8), 1, 1);
        assertFalse((Boolean) result.get("success"));
        assertEquals("最多只能提前 7 天预约", result.get("message"));
    }

    @Test
    void acceptsExactlySevenDays() {
        when(classroomMapper.selectByIdForUpdate(1L)).thenReturn(null);
        Map<String, Object> result = service.reserveClassroom(1L, "20230001", "张三", "自习",
                LocalDate.now().plusDays(7), 1, 1);
        // 已通过 7 天窗口校验，继续走到教室查询
        assertEquals("教室不存在", result.get("message"));
    }

    @Test
    void rejectsReasonLongerThan100Chars() {
        String reason = "长".repeat(101);
        Map<String, Object> result = service.reserveClassroom(1L, "20230001", "张三", reason,
                LocalDate.now().plusDays(1), 1, 1);
        assertFalse((Boolean) result.get("success"));
        assertEquals("预约原因不能超过 100 字", result.get("message"));
    }

    @Test
    void acceptsReasonExactly100Chars() {
        when(classroomMapper.selectByIdForUpdate(1L)).thenReturn(null);
        String reason = "长".repeat(100);
        Map<String, Object> result = service.reserveClassroom(1L, "20230001", "张三", reason,
                LocalDate.now().plusDays(1), 1, 1);
        assertEquals("教室不存在", result.get("message"));
    }

    @Test
    void rejectsInvalidPeriodRange() {
        assertEquals("预约节次不正确", service.reserveClassroom(1L, "20230001", "张三", "自习",
                LocalDate.now().plusDays(1), 0, 2).get("message"));
        assertEquals("预约节次不正确", service.reserveClassroom(1L, "20230001", "张三", "自习",
                LocalDate.now().plusDays(1), 2, 1).get("message"));
        assertEquals("预约节次不正确", service.reserveClassroom(1L, "20230001", "张三", "自习",
                LocalDate.now().plusDays(1), 5, 6).get("message"));
    }

    @Test
    void rejectsOverlappingReservation() {
        Classroom classroom = new Classroom();
        classroom.setStatus("available");
        when(classroomMapper.selectByIdForUpdate(1L)).thenReturn(classroom);
        when(classroomOccupancyService.findScheduleConflict(1L, LocalDate.now().plusDays(1), 2, 3))
                .thenReturn(null);

        ClassroomReservation existing = new ClassroomReservation();
        existing.setStartPeriod(1);
        existing.setEndPeriod(2);
        existing.setStatus("pending");
        when(reservationMapper.selectList(any())).thenReturn(List.of(existing));

        Map<String, Object> result = service.reserveClassroom(1L, "20230001", "张三", "自习",
                LocalDate.now().plusDays(1), 2, 3);

        assertFalse((Boolean) result.get("success"));
        assertEquals("该时间段已有预约，请选择其他时间", result.get("message"));
        verify(reservationMapper, never()).insert(any());
    }

    @Test
    void acceptsAdjacentReservation() {
        Classroom classroom = new Classroom();
        classroom.setStatus("available");
        when(classroomMapper.selectByIdForUpdate(1L)).thenReturn(classroom);
        when(classroomOccupancyService.findScheduleConflict(1L, LocalDate.now().plusDays(1), 2, 3))
                .thenReturn(null);

        ClassroomReservation existing = new ClassroomReservation();
        existing.setStartPeriod(1);
        existing.setEndPeriod(1);
        when(reservationMapper.selectList(any())).thenReturn(List.of(existing));

        Map<String, Object> result = service.reserveClassroom(1L, "20230001", "张三", "自习",
                LocalDate.now().plusDays(1), 2, 3);

        assertTrue((Boolean) result.get("success"));
        verify(reservationMapper).insert(any(ClassroomReservation.class));
    }
}
