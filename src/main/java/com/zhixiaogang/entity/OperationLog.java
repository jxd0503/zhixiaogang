package com.zhixiaogang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志实体：三端写操作统一记录，字段精简，不存请求/响应全文。
 */
@Data
@TableName("operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人用户ID（未登录为空） */
    @TableField("user_id")
    private Long userId;

    /** 操作人账号 */
    @TableField("username")
    private String username;

    /** 操作人角色：student / teacher / admin */
    @TableField("role")
    private String role;

    /** 操作类型：login/logout/apply/approve/reject/import/export/generate/confirm/update/delete/query */
    @TableField("action")
    private String action;

    /** 操作对象类型 */
    @TableField("target_type")
    private String targetType;

    /** 操作对象ID */
    @TableField("target_id")
    private String targetId;

    /** 关键参数摘要（不超过 100 字） */
    @TableField("detail")
    private String detail;

    /** 结果：success 或 failed: 简短原因 */
    @TableField("result")
    private String result;

    /** 客户端IP */
    @TableField("ip")
    private String ip;

    /** 接口耗时(ms) */
    @TableField("cost_ms")
    private Integer costMs;

    @TableField("create_time")
    private LocalDateTime createTime;
}
