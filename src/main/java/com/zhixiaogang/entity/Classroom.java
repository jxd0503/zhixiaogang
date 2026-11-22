package com.zhixiaogang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 实体：教室（classroom 表）。 */
@Data
@TableName("classroom")
public class Classroom {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("classroom_no")
    private String classroomNo;

    @TableField("building")
    private String building;

    @TableField("floor")
    private Integer floor;

    @TableField("capacity")
    private Integer capacity;

    @TableField("room_type")
    private String roomType;

    @TableField("status")
    private String status;

    @TableField("description")
    private String description;
}
