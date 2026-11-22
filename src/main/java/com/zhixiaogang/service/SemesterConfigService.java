package com.zhixiaogang.service;

import com.zhixiaogang.entity.SemesterConfig;
import java.time.LocalDate;
import java.util.Map;

/**
 * 学期配置服务接口
 */
public interface SemesterConfigService {
    
    /**
     * 获取当前学期配置
     */
    SemesterConfig getCurrentSemester();
    
    /**
     * 保存或更新学期配置
     */
    Map<String, Object> saveSemester(SemesterConfig config);
    
    /**
     * 计算当前周次
     * @param startDate 学期开始日期
     * @param weeks 学期总周数
     * @return [当前周次, 是否在学期范围内]
     */
    Object[] calculateCurrentWeek(LocalDate startDate, Integer weeks);
}