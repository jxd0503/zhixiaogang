package com.zhixiaogang.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 实体：AI 对话记录（chat_log 表）。 */
@Data
@TableName("chat_log")
public class ChatLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("student_id")
    private String studentId;

    @TableField("user_message")
    private String userMessage;

    @TableField("bot_response")
    private String botResponse;

    @TableField("intent_type")
    private String intentType;

    /** 对话模式：AI_MODE / LOCAL_MODE */
    @TableField("mode")
    private String mode;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
