package com.zhixiaogang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 模型提供商（通义千问 / DeepSeek 等 OpenAI 兼容服务）
 */
@Data
@TableName("ai_provider")
public class AIConfigProvider {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 提供商名称 */
    private String name;

    /** OpenAI 兼容端点 */
    private String apiUrl;

    /** API Key（接口返回时必须脱敏） */
    private String apiKey;

    /** 可用模型，逗号分隔 */
    private String models;

    /** 该提供商当前生效模型（空则取 models 第一个） */
    private String currentModel;

    /** 支持识图的多模态模型名，逗号分隔 */
    private String multimodalModels;

    /** 1启用 0停用 */
    private Integer enabled;

    /** 1生效中 0否（全局唯一） */
    private Integer isDefault;

    private Integer sortOrder;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
