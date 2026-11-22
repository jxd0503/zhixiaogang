package com.zhixiaogang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学期配置实体类
 */
@Data
@TableName("semester_config")
public class SemesterConfig {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /** 学期名称 */
    private String semesterName;
    
    /** 开始日期 */
    private LocalDate startDate;
    
    /** 持续周数 */
    private Integer weeks;
    
    /** 结束日期（计算得出） */
    private LocalDate endDate;
    
    /** 创建时间 */
    private LocalDateTime createTime;
    
    /** 更新时间 */
    private LocalDateTime updateTime;
}