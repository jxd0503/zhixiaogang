package com.zhixiaogang.service.adjustment;

import com.zhixiaogang.entity.ScheduleAdjustment;
import com.zhixiaogang.entity.User;
import com.zhixiaogang.mapper.ScheduleAdjustmentMapper;
import com.zhixiaogang.service.notification.NotificationService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 紧急回滚单测：教师可绕过审批立即恢复原课表并留痕，管理员事后仅确认知悉；
 * 非本人 / 非已批准 / 已回滚 / 未填原因均被拒绝。
 */
@ExtendWith(MockitoExtension.class)
class AdjustmentCancelServiceEmergencyTest {

    @Mock
    private ScheduleAdjustmentMapper adjustmentMapper;

    @Mock
    private NotificationService notificationService;

    @Mock
    private AdjustmentNotifier notifier;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private AdjustmentAuditLogger auditLogger;

    @Mock
    private com.zhixiaogang.service.classroom.ClassroomReleaseNoticeService releaseNoticeService;

    @InjectMocks
    private AdjustmentCancelService service;

    @Test
    void emergencyRollbackRestoresSlotAndWritesAudit() {
        ScheduleAdjustment a = approvedSyncedAdjustment();
        when(adjustmentMapper.selectByIdForUpdate(9L)).thenReturn(a);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(scheduleRow(3, 7, 8)));
        lenient().when(jdbcTemplate.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(1L));

        Map<String, Object> res = service.emergencyRollback(9L, teacher(), "突发急事无法到课");

        assertTrue(Boolean.TRUE.equals(res.get("success")));
        assertEquals("cancelled", a.getStatus());
        assertEquals(1, a.getEmergencyFlag());
        assertEquals("突发急事无法到课", a.getEmergencyReason());
        assertEquals("教师紧急回滚：突发急事无法到课", a.getRejectReason());
        assertNull(a.getEmergencyReviewBy());
        // 课表恢复原时段 1 次；审批流水走统一日志组件
        verify(jdbcTemplate, times(1)).update(anyString(), any(Object[].class));
        verify(auditLogger).log(eq(9L), eq("emergency-rollback"), eq("t001"), eq("张老师"), eq("teacher"),
                isNull(), eq("突发急事无法到课"), anyString());
        verify(adjustmentMapper).updateById(a);
        verify(notifier).notifyCancelled(a);
        verify(notificationService).create(eq(1L), eq("adjustment"), anyString(), anyString(), eq(9L));
    }

    @Test
    void emergencyRollbackRejectsEmptyReason() {
        Map<String, Object> res = service.emergencyRollback(9L, teacher(), "  ");

        assertFalse(Boolean.TRUE.equals(res.get("success")));
        assertEquals("请填写紧急回滚原因", res.get("message"));
        verify(adjustmentMapper, never()).updateById(any());
    }

    @Test
    void emergencyRollbackRejectsOtherTeacher() {
        ScheduleAdjustment a = approvedSyncedAdjustment();
        a.setTeacherId("t999");
        a.setTeacherName("他人");
        when(adjustmentMapper.selectByIdForUpdate(9L)).thenReturn(a);

        Map<String, Object> res = service.emergencyRollback(9L, teacher(), "原因");

        assertFalse(Boolean.TRUE.equals(res.get("success")));
        assertEquals("无权操作该调课申请", res.get("message"));
        verify(adjustmentMapper, never()).updateById(any());
    }

    @Test
    void emergencyRollbackRejectsNotApproved() {
        ScheduleAdjustment a = approvedSyncedAdjustment();
        a.setStatus("pending");
        when(adjustmentMapper.selectByIdForUpdate(9L)).thenReturn(a);

        Map<String, Object> res = service.emergencyRollback(9L, teacher(), "原因");

        assertFalse(Boolean.TRUE.equals(res.get("success")));
        assertEquals("仅已批准且已生效的调课可紧急回滚", res.get("message"));
    }

    @Test
    void emergencyRollbackRejectsAlreadyRolledBack() {
        ScheduleAdjustment a = approvedSyncedAdjustment();
        a.setEmergencyFlag(1);
        when(adjustmentMapper.selectByIdForUpdate(9L)).thenReturn(a);

        Map<String, Object> res = service.emergencyRollback(9L, teacher(), "原因");

        assertFalse(Boolean.TRUE.equals(res.get("success")));
        assertEquals("该调课已紧急回滚，请勿重复操作", res.get("message"));
    }

    @Test
    void emergencyRollbackRejectsWhenSlotNotSynced() {
        ScheduleAdjustment a = approvedSyncedAdjustment();
        when(adjustmentMapper.selectByIdForUpdate(9L)).thenReturn(a);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(scheduleRow(3, 1, 2)));

        Map<String, Object> res = service.emergencyRollback(9L, teacher(), "原因");

        assertFalse(Boolean.TRUE.equals(res.get("success")));
        assertEquals("当前课表已不是该调课的目标时段，无需紧急回滚", res.get("message"));
    }

    @Test
    void emergencyRollbackUsesSplitTargetRowForPartialWeekAdjustment() {
        ScheduleAdjustment a = approvedSyncedAdjustment();
        a.setTargetScheduleId(1104L);
        a.setOrigWeekRange("1-18");
        when(adjustmentMapper.selectByIdForUpdate(9L)).thenReturn(a);
        // 第一次取原课表行（已拆分为原时段），第二次取目标周次新课表行（目标时段）
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(scheduleRow(1, 1, 2)), List.of(scheduleRow(3, 7, 8)));
        lenient().when(jdbcTemplate.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(1L));

        Map<String, Object> res = service.emergencyRollback(9L, teacher(), "拆分周次回滚");

        assertTrue(Boolean.TRUE.equals(res.get("success")));
        assertEquals("cancelled", a.getStatus());
        assertEquals(1, a.getEmergencyFlag());
        verify(adjustmentMapper).updateById(a);
    }

    @Test
    void acknowledgeEmergencyMarksReviewedAndWritesAudit() {
        ScheduleAdjustment a = approvedSyncedAdjustment();
        a.setStatus("cancelled");
        a.setEmergencyFlag(1);
        a.setEmergencyReason("突发急事");
        when(adjustmentMapper.selectByIdForUpdate(9L)).thenReturn(a);

        Map<String, Object> res = service.acknowledgeEmergency(9L, "系统管理员");

        assertTrue(Boolean.TRUE.equals(res.get("success")));
        assertEquals("系统管理员", a.getEmergencyReviewBy());
        assertNotNull(a.getEmergencyReviewTime());
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(auditLogger).log(eq(9L), eq("emergency-review"), isNull(), eq("系统管理员"), eq("admin"),
                isNull(), isNull(), anyString());
        verify(adjustmentMapper).updateById(a);
    }

    @Test
    void acknowledgeEmergencyRejectsNonEmergencyRecord() {
        when(adjustmentMapper.selectByIdForUpdate(9L)).thenReturn(approvedSyncedAdjustment());

        Map<String, Object> res = service.acknowledgeEmergency(9L, "系统管理员");

        assertFalse(Boolean.TRUE.equals(res.get("success")));
        assertEquals("该调课不是紧急回滚记录，无需补审", res.get("message"));
    }

    @Test
    void acknowledgeEmergencyRejectsRepeatedReview() {
        ScheduleAdjustment a = approvedSyncedAdjustment();
        a.setStatus("cancelled");
        a.setEmergencyFlag(1);
        a.setEmergencyReviewBy("系统管理员");
        when(adjustmentMapper.selectByIdForUpdate(9L)).thenReturn(a);

        Map<String, Object> res = service.acknowledgeEmergency(9L, "系统管理员");

        assertFalse(Boolean.TRUE.equals(res.get("success")));
        assertEquals("已确认知悉，无需重复确认", res.get("message"));
    }

    private ScheduleAdjustment approvedSyncedAdjustment() {
        ScheduleAdjustment a = new ScheduleAdjustment();
        a.setId(9L);
        a.setScheduleId(100L);
        a.setCourseName("数据结构");
        a.setTeacherId("t001");
        a.setTeacherName("张老师");
        a.setClassId(1L);
        a.setStatus("approved");
        a.setEmergencyFlag(0);
        a.setOrigWeekDay(1);
        a.setOrigStartPeriod(1);
        a.setOrigEndPeriod(2);
        a.setTargetWeekDay(3);
        a.setTargetStartPeriod(7);
        a.setTargetEndPeriod(8);
        a.setWeekRange("1-16");
        return a;
    }

    private Map<String, Object> scheduleRow(int weekDay, int startPeriod, int endPeriod) {
        Map<String, Object> row = new HashMap<>();
        row.put("week_day", weekDay);
        row.put("start_period", startPeriod);
        row.put("end_period", endPeriod);
        return row;
    }

    private User teacher() {
        User user = new User();
        user.setId(2L);
        user.setStudentId("t001");
        user.setName("张老师");
        user.setRole("teacher");
        return user;
    }
}
