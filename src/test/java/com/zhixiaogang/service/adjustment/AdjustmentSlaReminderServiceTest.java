package com.zhixiaogang.service.adjustment;

import com.zhixiaogang.service.SystemConfigService;
import com.zhixiaogang.service.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调课审批超时提醒单测：超过 SLA 的待审批申请提醒全部管理员并标记提醒时间；
 * 无超时申请时不发通知。
 */
@ExtendWith(MockitoExtension.class)
class AdjustmentSlaReminderServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private NotificationService notificationService;

    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private AdjustmentSlaReminderService service;

    @Test
    void overduePendingNotifiesAllAdminsAndMarksReminded() {
        when(systemConfigService.getInt("adjustment.sla_hours", 48)).thenReturn(48);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(adjustmentRow(11L, "数据结构", "23软件1班", "张三"),
                        adjustmentRow(12L, "操作系统", "23软件2班", "李四")));
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(1L, 2L));

        service.remindOverdue();

        // 一次扫描按管理员各发 1 条汇总提醒（内容聚合多条超时申请）
        verify(notificationService, times(2)).create(anyLong(), eq("adjustment"), anyString(), anyString(), any());
        // 每条申请都写入 last_remind_time（24 小时内不重复提醒）
        verify(jdbcTemplate, times(2)).update(anyString(), any(Object[].class));
    }

    @Test
    void noOverdueAdjustmentSendsNothing() {
        when(systemConfigService.getInt("adjustment.sla_hours", 48)).thenReturn(48);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        service.remindOverdue();

        verify(notificationService, never()).create(any(), anyString(), anyString(), anyString(), any());
    }

    private Map<String, Object> adjustmentRow(Long id, String courseName, String className, String teacherName) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("course_name", courseName);
        row.put("class_name", className);
        row.put("teacher_name", teacherName);
        row.put("apply_time", Timestamp.valueOf(LocalDateTime.now().minusHours(72)));
        return row;
    }
}
