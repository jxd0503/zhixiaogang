package com.zhixiaogang.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 实体：课表条目（schedule 表）。 */
@Data
@TableName("schedule")
public class Schedule {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("student_id")
    private String studentId;

    @TableField("course_id")
    private Long courseId;

    @TableField("classroom_id")
    private Long classroomId;

    @TableField("teacher_name")
    private String teacherName;

    /** 授课教师工号（权限过滤与关联用） */
    @TableField("teacher_id")
    private String teacherId;

    @TableField("week_day")
    private Integer weekDay;

    @TableField("start_period")
    private Integer startPeriod;

    @TableField("end_period")
    private Integer endPeriod;

    @TableField("week_range")
    private String weekRange;

    @TableField("semester")
    private String semester;

    @TableField("class_id")
    private Long classId;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
