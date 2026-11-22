package com.zhixiaogang.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 管理端自定义通知发送记录：用于通知管理页历史追溯（标题、内容、范围、条数）。
 */
@Data
@TableName("notification_send_log")
public class NotificationSendLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("title")
    private String title;

    @TableField("content")
    private String content;

    /** 发送范围：all_users全部用户 / all_students全部学生 / class指定班级 */
    @TableField("scope")
    private String scope;

    @TableField("class_id")
    private Long classId;

    @TableField("class_name")
    private String className;

    /** 本次实际发送条数 */
    @TableField("receiver_count")
    private Integer receiverCount;

    /** 发送人（管理员） */
    @TableField("sender")
    private String sender;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
