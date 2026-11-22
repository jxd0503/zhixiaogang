package com.zhixiaogang.service.classroom;

import com.zhixiaogang.entity.ClassroomReservation;
import com.zhixiaogang.entity.ScheduleAdjustment;
import com.zhixiaogang.service.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 教室释放提醒单测：优先通知曾预约被拒的学生，无匹配对象时给管理员汇总提示；
 * 同一事件对同一用户不重复提醒。
 */
@ExtendWith(MockitoExtension.class)
class ClassroomReleaseNoticeServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private ClassroomReleaseNoticeService service;

    @Test
    void adjustmentReleaseNotifiesRejectedStudents() {
        ScheduleAdjustment a = adjustment(30L, 5L, 3, 5, 6);
        when(jdbcTemplate.queryForList(contains("classroom_no FROM classroom"), any(Object[].class)))
                .thenReturn(List.of(row("classroom_no", "B5-302")));
        when(jdbcTemplate.queryForList(contains("FROM classroom_reservation r"), any(Object[].class)))
                .thenReturn(List.of(row("student_id", "2024001", "reservation_date", "2026-11-25")));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(notificationService.findUserId("2024001")).thenReturn(11L);

        service.onAdjustmentReleased(a);

        verify(notificationService).create(eq(11L), eq("classroom"), eq("教室时段已释放"),
                contains("B5-302"), eq(30L));
    }

    @Test
    void adjustmentReleaseWithoutCandidatesNotifiesAdmins() {
        ScheduleAdjustment a = adjustment(31L, 5L, 3, 5, 6);
        when(jdbcTemplate.queryForList(contains("classroom_no FROM classroom"), any(Object[].class)))
                .thenReturn(List.of(row("classroom_no", "B5-302")));
        when(jdbcTemplate.queryForList(contains("FROM classroom_reservation r"), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("role = 'admin'"), eq(Long.class))).thenReturn(List.of(99L));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);

        service.onAdjustmentReleased(a);

        verify(notificationService).create(eq(99L), eq("classroom"), eq("教室释放提醒"),
                contains("暂无曾预约被拒"), eq(31L));
    }

    @Test
    void reservationReleaseNotifiesOtherRejectedStudents() {
        ClassroomReservation r = new ClassroomReservation();
        r.setId(40L);
        r.setClassroomId(5L);
        r.setStudentId("2024001");
        r.setReservationDate(LocalDate.of(2026, 11, 25));
        r.setStartPeriod(1);
        r.setEndPeriod(2);
        when(jdbcTemplate.queryForList(contains("classroom_no FROM classroom"), any(Object[].class)))
                .thenReturn(List.of(row("classroom_no", "B5-302")));
        when(jdbcTemplate.queryForList(contains("DISTINCT student_id"), any(Object[].class)))
                .thenReturn(List.of(row("student_id", "2024002")));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(notificationService.findUserId("2024002")).thenReturn(12L);

        service.onReservationReleased(r);

        verify(notificationService).create(eq(12L), eq("classroom"), eq("教室时段已释放"),
                contains("预约撤销"), eq(40L));
    }

    @Test
    void repeatedEventDoesNotNotifySameUserTwice() {
        ClassroomReservation r = new ClassroomReservation();
        r.setId(41L);
        r.setClassroomId(5L);
        r.setStudentId("2024001");
        r.setReservationDate(LocalDate.of(2026, 11, 25));
        r.setStartPeriod(1);
        r.setEndPeriod(2);
        when(jdbcTemplate.queryForList(contains("classroom_no FROM classroom"), any(Object[].class)))
                .thenReturn(List.of(row("classroom_no", "B5-302")));
        when(jdbcTemplate.queryForList(contains("DISTINCT student_id"), any(Object[].class)))
                .thenReturn(List.of(row("student_id", "2024002")));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(notificationService.findUserId("2024002")).thenReturn(12L);

        service.onReservationReleased(r);

        verify(notificationService, never()).create(anyLong(), anyString(), anyString(), anyString(), anyLong());
    }

    private static ScheduleAdjustment adjustment(Long id, Long classroomId, int weekDay, int start, int end) {
        ScheduleAdjustment a = new ScheduleAdjustment();
        a.setId(id);
        a.setScheduleId(9L);
        a.setTargetClassroomId(classroomId);
        a.setTargetWeekDay(weekDay);
        a.setTargetStartPeriod(start);
        a.setTargetEndPeriod(end);
        return a;
    }

    private static Map<String, Object> row(String key, Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, value);
        return m;
    }

    private static Map<String, Object> row(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = row(k1, v1);
        m.put(k2, v2);
        return m;
    }
}
