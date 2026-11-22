package com.zhixiaogang.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 通用通知表实体：系统自动通知（调课 / 预约等业务事件）与管理端自定义通知共用，type 区分。
 */
@Data
@TableName("notifications")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收用户ID（user.id） */
    @TableField("user_id")
    private Long userId;

    /** 通知类型：adjustment调课 / reservation教室预约 / custom管理端自定义 */
    @TableField("type")
    private String type;

    @TableField("title")
    private String title;

    /** 通知内容（纯文本，不含 HTML） */
    @TableField("content")
    private String content;

    /** 关联业务ID（如调课申请 id、预约 id），便于追溯 */
    @TableField("related_id")
    private Long relatedId;

    /** 是否已读：0未读 1已读 */
    @TableField("is_read")
    private Integer isRead;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
