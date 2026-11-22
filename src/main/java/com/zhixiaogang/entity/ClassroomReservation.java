package com.zhixiaogang.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 实体：教室预约（classroom_reservation 表）。 */
@Data
@TableName("classroom_reservation")
public class ClassroomReservation {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("classroom_id")
    private Long classroomId;

    @TableField("student_id")
    private String studentId;

    @TableField("student_name")
    private String studentName;

    @TableField("reason")
    private String reason;

    @TableField("reservation_date")
    private LocalDate reservationDate;

    @TableField("start_period")
    private Integer startPeriod;

    @TableField("end_period")
    private Integer endPeriod;

    @TableField("status")
    private String status;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
