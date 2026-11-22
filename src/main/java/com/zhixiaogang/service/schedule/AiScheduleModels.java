package com.zhixiaogang.service.schedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 排课数据模型：时间槽、约束条目、约束解析结果、课表修复结果。
 */
final class AiScheduleModels {

    /** 占用时段（星期 + 起止节次，闭区间）；weeks 为空表示整学期全部周次 */
    static final class Slot {
        final int day;
        final int start;
        final int end;
        final java.util.BitSet weeks;

        Slot(int day, int start, int end) {
            this(day, start, end, null);
        }

        Slot(int day, int start, int end, java.util.BitSet weeks) {
            this.day = day;
            this.start = start;
            this.end = end;
            this.weeks = weeks;
        }
    }

    /** 自然语言排课约束：教师 / 班级 / 教室在指定星期与节次范围内不可排课 */
    static final class Constraint {
        final String type;           // teacher / class / classroom
        final String name;           // 教师姓名 / 班级名称 / 教室号，"*" 表示全量
        final List<Integer> days;    // 1-7，空表示每天
        final Integer periodStart;   // null 表示全天
        final Integer periodEnd;
        final Integer weekStart;     // null 表示全周次；非空时按周次扣除而非节次占用
        final Integer weekEnd;
        final Integer weekParity;    // 1=单周（第 1,3,5… 周禁用），2=双周（第 2,4,6… 周禁用），null 表示不限奇偶
        final Integer maxPerDay;     // 每日最多排入的节数（按节计数），null 表示不限
        final boolean mustSchedule;  // 正向约束：该时段需要排课（与禁用类约束互不合并）

        Constraint(String type, String name, List<Integer> days, Integer periodStart, Integer periodEnd) {
            this(type, name, days, periodStart, periodEnd, null, null, null, null);
        }

        Constraint(String type, String name, List<Integer> days, Integer periodStart, Integer periodEnd,
                   Integer weekStart, Integer weekEnd) {
            this(type, name, days, periodStart, periodEnd, weekStart, weekEnd, null, null);
        }

        Constraint(String type, String name, List<Integer> days, Integer periodStart, Integer periodEnd,
                   Integer weekStart, Integer weekEnd, Integer weekParity, Integer maxPerDay) {
            this(type, name, days, periodStart, periodEnd, weekStart, weekEnd, weekParity, maxPerDay, false);
        }

        Constraint(String type, String name, List<Integer> days, Integer periodStart, Integer periodEnd,
                   Integer weekStart, Integer weekEnd, Integer weekParity, Integer maxPerDay,
                   boolean mustSchedule) {
            this.type = type;
            this.name = name;
            this.days = days;
            this.periodStart = periodStart;
            this.periodEnd = periodEnd;
            this.weekStart = weekStart;
            this.weekEnd = weekEnd;
            this.weekParity = weekParity;
            this.maxPerDay = maxPerDay;
            this.mustSchedule = mustSchedule;
        }
    }

    /**
     * 约束条目：同一主体（教师 / 班级 / 教室）的时间类约束合并为一条，parts 为各时间片段。
     * 如「周二、周三全天」+「周一上午」→ 一条条目、两个片段，展示为「周一上午、周二、周三不排课」。
     */
    static final class ConstraintEntry {
        int id;
        final String type;
        final String name;
        final List<Constraint> parts = new ArrayList<>();

        ConstraintEntry(String type, String name) {
            this.type = type;
            this.name = name;
        }

        /** 每日课时上限、需排课等非「禁用」维度单独成条，不参与时间片段合并 */
        boolean timeType() {
            for (Constraint p : parts) {
                if (p.maxPerDay != null || p.mustSchedule) {
                    return false;
                }
            }
            return true;
        }
    }

    /** 约束解析结果：结构化约束清单 + 未能识别的原始描述 + 主语提示 + 可选致命错误（模型不可用等） */
    static final class ConstraintParseResult {
        final List<Constraint> constraints;
        final List<String> unrecognized;
        /** 主语歧义（同姓教师）或查无此姓等提示，仅用于提示用户，不影响已识别约束 */
        final List<String> warnings;
        /** 非空表示致命失败（如模型不可用），调用方应直接终止排课流程 */
        final String fatalError;

        ConstraintParseResult(List<Constraint> constraints, List<String> unrecognized) {
            this(constraints, unrecognized, new ArrayList<>(), null);
        }

        ConstraintParseResult(List<Constraint> constraints, List<String> unrecognized,
                              List<String> warnings, String fatalError) {
            this.constraints = constraints;
            this.unrecognized = unrecognized;
            this.warnings = warnings;
            this.fatalError = fatalError;
        }
    }

    /** 约束变更后的重排结果：可入库条目 + 未能安置的原因 + 被移动的条数 */
    static final class RepairResult {
        final List<Map<String, Object>> items = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        int moved;
    }

    private AiScheduleModels() {
    }
}
