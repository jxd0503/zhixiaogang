package com.zhixiaogang.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 教师排课要求提交记录：教师以自然语言提交个人排课约束，管理端 AI 排课时加载填入。
 * status: PENDING待处理 / ADOPTED已采纳 / REJECTED无法满足 / CANCELLED教师取消 / EXPIRED过期。
 */
@Data
@TableName("schedule_constraint_request")
public class ScheduleConstraintRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 教师用户ID（user.id） */
    @TableField("teacher_id")
    private Long teacherId;

    /** 教师姓名（冗余展示用） */
    @TableField("teacher_name")
    private String teacherName;

    /** 学期名称 */
    @TableField("semester")
    private String semester;

    /** 适用班级ID（空表示不限定） */
    @TableField("class_id")
    private Long classId;

    /** 适用班级名称（冗余展示用） */
    @TableField("class_name")
    private String className;

    /** 原始自然语言约束 */
    @TableField("constraint_text")
    private String constraintText;

    /** AI 预解析的约束条目 JSON（可空） */
    @TableField("parsed_json")
    private String parsedJson;

    /** 状态：PENDING / ADOPTED / REJECTED / CANCELLED / EXPIRED */
    @TableField("status")
    private String status;

    /** 管理端处理备注（采纳说明 / 驳回原因） */
    @TableField("admin_note")
    private String adminNote;

    /** 采纳后排课应用中是否全部生效 */
    @TableField("apply_result")
    private String applyResult;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
