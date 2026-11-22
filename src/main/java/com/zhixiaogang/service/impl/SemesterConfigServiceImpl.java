package com.zhixiaogang.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixiaogang.entity.SemesterConfig;
import com.zhixiaogang.mapper.SemesterConfigMapper;
import com.zhixiaogang.service.SemesterConfigService;
import com.zhixiaogang.util.WeekRangeParser;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 学期配置服务实现
 */
@Service
public class SemesterConfigServiceImpl implements SemesterConfigService {

    @Autowired
    private SemesterConfigMapper semesterConfigMapper;

    /** 启动时把学期总周数注入周次解析工具，供「单周/双周/全部/前N周」等写法使用 */
    @PostConstruct
    public void initTotalWeeks() {
        applyTotalWeeks(getCurrentSemester());
    }

    private void applyTotalWeeks(SemesterConfig config) {
        if (config != null && config.getWeeks() != null) {
            WeekRangeParser.setDefaultTotalWeeks(config.getWeeks());
        }
    }

    @Override
    public SemesterConfig getCurrentSemester() {
        LambdaQueryWrapper<SemesterConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(SemesterConfig::getUpdateTime);
        wrapper.last("LIMIT 1");
        List<SemesterConfig> list = semesterConfigMapper.selectList(wrapper);
        SemesterConfig current = list.isEmpty() ? null : list.get(0);
        applyTotalWeeks(current);
        return current;
    }

    @Override
    @Transactional
    public Map<String, Object> saveSemester(SemesterConfig config) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 数据校验
            if (config.getSemesterName() == null || config.getSemesterName().trim().isEmpty()) {
                result.put("success", false);
                result.put("message", "学期名称不能为空");
                return result;
            }
            if (config.getSemesterName().length() > 128) {
                result.put("success", false);
                result.put("message", "学期名称不能超过 128 字");
                return result;
            }
            if (config.getStartDate() == null) {
                result.put("success", false);
                result.put("message", "开始日期不能为空");
                return result;
            }
            if (config.getWeeks() == null) {
                result.put("success", false);
                result.put("message", "持续周数不能为空");
                return result;
            }
            if (config.getWeeks() < 1 || config.getWeeks() > 40) {
                result.put("success", false);
                result.put("message", "持续周数必须为 1-40 之间的整数");
                return result;
            }

            // 结束日期 = 开始日期 + weeks*7 - 1 天；weeks ≥ 1 必然 ≥ startDate，仅防御性兜底
            LocalDate endDate = config.getStartDate().plusWeeks(config.getWeeks()).minusDays(1);
            if (endDate.isBefore(config.getStartDate())) {
                result.put("success", false);
                result.put("message", "结束日期不能早于开始日期，请检查周数");
                return result;
            }

            // 合理性警示：开始日期与当前日期实际相差 ≥ 180 天（约半年，允许保存，前端需二次确认）
            // 覆盖验收用例：2027-06-01（晚 228 天）、2025-01-01（早 654 天）均触发；当前学期（数十天）不触发
            LocalDate today = LocalDate.now();
            long diffDays = Math.abs(ChronoUnit.DAYS.between(today, config.getStartDate()));
            String warning = null;
            if (diffDays >= 180) {
                warning = "注意：开始日期与当前日期相差较远（" + diffDays + " 天），请确认是否为误填"
                        + "（开始日期 " + config.getStartDate() + "，当前日期 " + today + "）";
            }

            config.setEndDate(endDate);

            // 设置时间
            LocalDateTime now = LocalDateTime.now();
            if (config.getId() == null) {
                config.setCreateTime(now);
            }
            config.setUpdateTime(now);

            // 保存或更新（同一时间仅保留一份，覆盖最近的）
            if (config.getId() != null) {
                int rows = semesterConfigMapper.updateById(config);
                if (rows == 0) {
                    // id 对应的配置不存在（如已被删除），不能假成功
                    result.put("success", false);
                    result.put("message", "学期配置不存在或已被删除，请刷新后重试");
                    return result;
                }
            } else {
                // 删除旧配置，只保留最新的
                LambdaQueryWrapper<SemesterConfig> deleteWrapper = new LambdaQueryWrapper<>();
                semesterConfigMapper.delete(deleteWrapper);
                semesterConfigMapper.insert(config);
            }

            result.put("success", true);
            result.put("data", config);
            WeekRangeParser.setDefaultTotalWeeks(config.getWeeks());
            result.put("endDate", endDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
            if (warning != null) {
                result.put("warning", true);
                result.put("message", "学期配置保存成功。" + warning);
            } else {
                result.put("message", "学期配置保存成功");
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "保存失败：" + e.getMessage());
        }
        return result;
    }

    @Override
    public Object[] calculateCurrentWeek(LocalDate startDate, Integer weeks) {
        if (startDate == null || weeks == null) {
            return new Object[]{1, false};
        }

        LocalDate today = LocalDate.now();
        
        // 计算当前日期与开始日期相差的天数
        long daysBetween = ChronoUnit.DAYS.between(startDate, today);
        
        // 如果是开始日期之前，返回第1周
        if (daysBetween < 0) {
            return new Object[]{1, false};
        }

        // 计算当前周：从周一开始计算
        LocalDate weekMonday = today.with(DayOfWeek.MONDAY);
        LocalDate startMonday = startDate.with(DayOfWeek.MONDAY);
        long weeksDiff = ChronoUnit.WEEKS.between(startMonday, weekMonday);
        int currentWeek = (int) weeksDiff + 1;

        // 检查是否在学期范围内
        boolean inRange = currentWeek >= 1 && currentWeek <= weeks;
        if (!inRange) {
            currentWeek = Math.max(1, Math.min(currentWeek, weeks));
        }

        return new Object[]{currentWeek, inRange};
    }
}