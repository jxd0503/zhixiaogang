package com.zhixiaogang.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 实体：AI 全局配置（ai_config 表）。 */
@Data
@TableName("ai_config")
public class AIConfig {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("api_key")
    private String apiKey;

    @TableField("api_url")
    private String apiUrl;

    @TableField("model")
    private String model;

    @TableField("default_provider")
    private String defaultProvider;

    @TableField("ai_disabled")
    private Integer aiDisabled;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;
}
