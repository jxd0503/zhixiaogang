package com.zhixiaogang.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 实体：班级信息（class_info 表）。 */
@Data
@TableName("class_info")
public class ClassInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("class_name")
    private String className;

    @TableField("grade")
    private String grade;

    @TableField("department")
    private String department;

    @TableField("teacher_id")
    private String teacherId;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
