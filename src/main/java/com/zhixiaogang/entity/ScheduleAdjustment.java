package com.zhixiaogang.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 调课记录表实体：教师在教师端发起调课、管理端审批。
 * 原 schedule 表记录一律不修改、不删除，调课仅作为展示层叠加。
 */
@Data
@TableName("schedule_adjustment")
public class ScheduleAdjustment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 原课表条目ID（保留关联，不修改原记录） */
    @TableField("schedule_id")
    private Long scheduleId;

    @TableField("class_id")
    private Long classId;

    @TableField("course_id")
    private Long courseId;

    @TableField("course_name")
    private String courseName;

    @TableField("teacher_name")
    private String teacherName;

    /** 授课教师工号（权限过滤与关联用） */
    @TableField("teacher_id")
    private String teacherId;

    /** 原星期几: 1-7 */
    @TableField("orig_week_day")
    private Integer origWeekDay;

    @TableField("orig_start_period")
    private Integer origStartPeriod;

    @TableField("orig_end_period")
    private Integer origEndPeriod;

    /** 目标星期几: 1-7 */
    @TableField("target_week_day")
    private Integer targetWeekDay;

    @TableField("target_start_period")
    private Integer targetStartPeriod;

    @TableField("target_end_period")
    private Integer targetEndPeriod;

    /** 目标周次（null=仅调星期节次，不跨周） */
    @TableField("target_week")
    private Integer targetWeek;

    /** 原课表条目原始周次范围快照（跨周调课撤销用） */
    @TableField("orig_week_range")
    private String origWeekRange;

    /** 原教室ID快照（跨周调课原地改写时撤销用） */
    @TableField("orig_classroom_id")
    private Long origClassroomId;

    /** 跨周调课生效后新建的课表行ID（撤销时删除） */
    @TableField("target_schedule_id")
    private Long targetScheduleId;

    /** 目标教室ID（null=沿用原教室） */
    @TableField("target_classroom_id")
    private Long targetClassroomId;

    /** 是否换教室: 0沿用原教室 1换到目标教室 */
    @TableField("change_classroom")
    private Integer changeClassroom;

    /** 是否来自AI推荐: 0手动 1AI推荐 */
    @TableField("ai_suggested")
    private Integer aiSuggested;

    /** 来源: ai AI推荐 local AI不可用本地规则 manual 手动选择 */
    @TableField("source_type")
    private String sourceType;

    /** 提交时该候选的状态文字快照 */
    @TableField("status_text")
    private String statusText;

    /** 冲突结论: none/teacher/class/room/multi */
    @TableField("conflict_result")
    private String conflictResult;

    /** 校验明细快照 */
    @TableField("conflict_detail")
    private String conflictDetail;

    /** 最近一次校验时间 */
    @TableField("check_time")
    private LocalDateTime checkTime;

    /** 校验人 */
    @TableField("check_by")
    private String checkBy;

    /** 调课生效周次范围（默认整学期，如 1-16） */
    @TableField("week_range")
    private String weekRange;

    @TableField("semester")
    private String semester;

    /** 调课原因 */
    @TableField("reason")
    private String reason;

    /** pending待审核 / approved已批准 / rejected已拒绝 / cancelled已撤销或撤回 */
    @TableField("status")
    private String status;

    @TableField("apply_time")
    private LocalDateTime applyTime;

    @TableField("approve_time")
    private LocalDateTime approveTime;

    /** 审批人 */
    @TableField("approver")
    private String approver;

    /** 拒绝或撤销原因 */
    @TableField("reject_reason")
    private String rejectReason;

    /** 撤销申请状态: null 无 / PENDING 待审批 / REJECTED 已拒绝 / DONE 已完成 */
    @TableField("cancel_status")
    private String cancelStatus;

    /** 撤销申请原因 */
    @TableField("cancel_reason")
    private String cancelReason;

    /** 撤销申请时间 */
    @TableField("cancel_apply_time")
    private LocalDateTime cancelApplyTime;

    /** 撤销审批人 */
    @TableField("cancel_approver")
    private String cancelApprover;

    /** 撤销审批时间 */
    @TableField("cancel_approve_time")
    private LocalDateTime cancelApproveTime;

    /** 撤销申请被拒原因 */
    @TableField("cancel_reject_reason")
    private String cancelRejectReason;

    /** 代课教师工号（可选；时间与教室不变时仅更换授课教师） */
    @TableField("substitute_teacher_id")
    private String substituteTeacherId;

    /** 代课教师姓名（冗余展示用） */
    @TableField("substitute_teacher_name")
    private String substituteTeacherName;

    /** 教师互换：对方课表行ID */
    @TableField("peer_schedule_id")
    private Long peerScheduleId;

    /** 教师互换：对方教师工号快照（回滚凭此还原对方课表） */
    @TableField("peer_teacher_id")
    private String peerTeacherId;

    /** 教师互换：对方教师姓名快照 */
    @TableField("peer_teacher_name")
    private String peerTeacherName;

    /** 教师互换：对方是否已确认 0否 1是 */
    @TableField("peer_confirmed")
    private Integer peerConfirmed;

    /** 教师互换：对方确认时间 */
    @TableField("peer_confirm_time")
    private LocalDateTime peerConfirmTime;

    /** 是否教师互换: 0普通调课 1互换 */
    @TableField("swap_flag")
    private Integer swapFlag;

    /** 待审批超时提醒的最近发送时间（防重复提醒） */
    @TableField("last_remind_time")
    private LocalDateTime lastRemindTime;

    /** 是否紧急回滚: 0否 1是 */
    @TableField("emergency_flag")
    private Integer emergencyFlag;

    /** 紧急回滚原因 */
    @TableField("emergency_reason")
    private String emergencyReason;

    /** 紧急回滚补审人 */
    @TableField("emergency_review_by")
    private String emergencyReviewBy;

    /** 紧急回滚补审时间 */
    @TableField("emergency_review_time")
    private LocalDateTime emergencyReviewTime;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
