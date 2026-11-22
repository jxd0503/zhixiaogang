package com.zhixiaogang.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 实体：课程库（course 表）。 */
@Data
@TableName("course")
public class Course {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("course_code")
    private String courseCode;

    @TableField("course_name")
    private String courseName;

    @TableField("credits")
    private BigDecimal credits;

    @TableField("hours")
    private Integer hours;

    /** 每周学时（AI 自动排课用，默认 2 节） */
    @TableField("week_hours")
    private Integer weekHours;

    /** 教室类型（如机房/实训室/普通教室，空表示不限） */
    @TableField("classroom_type")
    private String classroomType;

    @TableField("department")
    private String department;

    @TableField("description")
    private String description;

    @TableField("status")
    private Integer status;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
