package com.zhixiaogang.service.schedule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.zhixiaogang.service.schedule.AiScheduleModels.*;
import static com.zhixiaogang.service.schedule.AiScheduleConstants.*;
import static com.zhixiaogang.service.schedule.AiScheduleUtils.*;
import static com.zhixiaogang.service.schedule.AiTimeExpressionParser.*;
import static com.zhixiaogang.service.schedule.AiConstraintSupport.*;

/**
 * AI 排课约束条目管理：结构化条目解析、同主体合并、覆盖判断与约束生效周次。
 */
final class AiConstraintEntrySupport {

    /**
     * 本地确定性解析"目标 + 时间维度"的禁用约束（如"王伟杰周一不能上课""不要排周末""第3周不能排课"）。
     * 星期词只参与时间维度判定，绝不会被当作教师 / 班级目标；无法确定目标时返回 null（交回 AI）。
     */
    static Constraint parseTargetDayBan(String seg, int totalWeekEnd) {
        if (seg == null || seg.isEmpty()) {
            return null;
        }
        Set<Integer> periods = parseAllowedPeriods(seg);
        Integer ps = null;
        Integer pe = null;
        if (!periods.isEmpty()) {
            List<Integer> pl = new ArrayList<>(periods);
            Collections.sort(pl);
            ps = pl.get(0);
            pe = pl.get(pl.size() - 1);
        }
        Set<Integer> days = parseAllowedDays(seg);
        List<Integer> dayList = days.isEmpty() ? null : new ArrayList<>(days);
        int[] wk = parseWeekRangeLocal(seg, totalWeekEnd);
        String cleaned = cleanTargetText(seg);
        String[] target = resolveTarget(cleaned);
        if (target == null) {
            return null;
        }
        if (dayList == null && ps == null && wk == null) {
            return null; // 无任何时间维度，无法确定
        }
        return new Constraint(target[0], target[1], dayList, ps, pe,
                wk == null ? null : wk[0], wk == null ? null : wk[1]);
    }

    /** 结构化条目回传解析：subjectType / subjectName + parts（缺 parts 时按单片段字段解析） */
    static List<ConstraintEntry> parseStructuredEntries(List<?> raw) {
        List<ConstraintEntry> entries = new ArrayList<>();
        for (Object o : raw) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<?, ?> m = (Map<?, ?>) o;
            String type = str(m.get("subjectType"));
            if (type.isEmpty()) {
                type = str(m.get("type"));
                if ("room".equals(type)) {
                    type = "classroom";
                }
            }
            if (!"teacher".equals(type) && !"class".equals(type) && !"classroom".equals(type)) {
                continue;
            }
            String name = str(m.get("subjectName"));
            if (name.isEmpty()) {
                name = str(m.get("name"));
                if ("目标班级".equals(name)) {
                    name = TARGET_CLASS_NAME;
                }
            }
            if (name.isEmpty()) {
                continue;
            }
            Object partsRaw = m.get("parts");
            boolean merged = false;
            if (partsRaw instanceof List) {
                for (Object po : (List<?>) partsRaw) {
                    if (po instanceof Map) {
                        Constraint c = constraintFromMap((Map<?, ?>) po, type, name);
                        if (c != null) {
                            mergeConstraintEntry(entries, c);
                            merged = true;
                        }
                    }
                }
            }
            if (!merged) {
                Constraint c = constraintFromMap(m, type, name);
                if (c != null) {
                    mergeConstraintEntry(entries, c);
                }
            }
        }
        return entries;
    }

    static Constraint constraintFromMap(Map<?, ?> m, String type, String name) {
        List<Integer> days = parseIntList(m.get("days"));
        Integer ps = toInt(m.get("periodStart"));
        if (ps == null) {
            ps = toInt(m.get("period_start"));
        }
        Integer pe = toInt(m.get("periodEnd"));
        if (pe == null) {
            pe = toInt(m.get("period_end"));
        }
        Integer ws = toInt(m.get("weekStart"));
        if (ws == null) {
            ws = toInt(m.get("week_start"));
        }
        Integer we = toInt(m.get("weekEnd"));
        if (we == null) {
            we = toInt(m.get("week_end"));
        }
        Integer parity = toInt(m.get("weekParity"));
        if (parity == null) {
            parity = toInt(m.get("week_parity"));
        }
        Integer cap = toInt(m.get("maxPerDay"));
        if (cap == null) {
            cap = toInt(m.get("max_per_day"));
        }
        boolean must = boolTrue(m.get("mustSchedule")) || boolTrue(m.get("must_schedule"));
        if (days.isEmpty() && ps == null && pe == null && ws == null && parity == null && cap == null) {
            return null;
        }
        return new Constraint(type, name, days, ps, pe, ws, we, parity, cap, must);
    }

    /** 同一主体：type 相同且名称归一后一致（教室忽略大小写） */
    static boolean sameSubject(ConstraintEntry entry, Constraint c) {
        if (!entry.type.equals(c.type)) {
            return false;
        }
        String a = normName(entry.name);
        String b = normName(c.name);
        if ("classroom".equals(c.type)) {
            return a.equalsIgnoreCase(b);
        }
        return a.equals(b);
    }

    /**
     * 合并一条约束：同主体且同为时间类 → 并入该条目；已被已有片段完整表达 → 不新增不修改；
     * 涉及新主体 / 新维度 → 新增条目。每日课时上限不参与时间合并，仅完全相同去重。
     */
    static void mergeConstraintEntry(List<ConstraintEntry> entries, Constraint c) {
        if (c.maxPerDay != null || c.mustSchedule) {
            for (ConstraintEntry e : entries) {
                if (!sameSubject(e, c)) {
                    continue;
                }
                for (Constraint p : e.parts) {
                    if (c.maxPerDay != null && p.maxPerDay != null && p.maxPerDay.equals(c.maxPerDay)) {
                        return;
                    }
                    if (c.mustSchedule && p.mustSchedule) {
                        if (partCovers(p, c)) {
                            return;
                        }
                        if (partCovers(c, p)) {
                            e.parts.remove(p);
                            e.parts.add(c);
                            return;
                        }
                    }
                }
            }
            ConstraintEntry e = new ConstraintEntry(c.type, c.name);
            e.parts.add(c);
            entries.add(e);
            return;
        }
        ConstraintEntry target = null;
        for (ConstraintEntry e : entries) {
            if (sameSubject(e, c) && e.timeType()) {
                target = e;
                break;
            }
        }
        if (target == null) {
            ConstraintEntry e = new ConstraintEntry(c.type, c.name);
            e.parts.add(c);
            entries.add(e);
            return;
        }
        // 新约束已被已有片段完整覆盖（完全等价或包含）：不新增、不修改
        if (entryCovers(target.parts, c)) {
            return;
        }
        target.parts.add(c);
        // 新片段完全覆盖旧片段时剔除旧片段（等价片段保留靠前者）
        for (int i = target.parts.size() - 2; i >= 0; i--) {
            Constraint old = target.parts.get(i);
            if (partCovers(c, old) && !partCovers(old, c)) {
                target.parts.remove(i);
            }
        }
    }

    /** 片段集合是否已完整表达约束 c（逐日存在覆盖片段） */
    static boolean entryCovers(List<Constraint> parts, Constraint c) {
        List<Integer> days = (c.days == null || c.days.isEmpty()) ? allWeekDays() : c.days;
        for (Integer d : days) {
            boolean covered = false;
            for (Constraint p : parts) {
                if (partCoversDay(p, d, c)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                return false;
            }
        }
        return true;
    }

    /** 单个片段是否覆盖约束 c 在某天的全部范围（节次区间包含 + 周次集合包含） */
    static boolean partCoversDay(Constraint part, int day, Constraint c) {
        if (part.days != null && !part.days.isEmpty() && !part.days.contains(day)) {
            return false;
        }
        int pps = part.periodStart == null ? 0 : part.periodStart;
        int ppe = part.periodEnd == null ? 99 : part.periodEnd;
        int cps = c.periodStart == null ? 0 : c.periodStart;
        int cpe = c.periodEnd == null ? 99 : c.periodEnd;
        if (pps > cps || ppe < cpe) {
            return false;
        }
        return constraintWeeks(part).containsAll(constraintWeeks(c));
    }

    /** 片段是否整体覆盖另一片段（星期、节次、周次三方面） */
    static boolean partCovers(Constraint a, Constraint b) {
        List<Integer> days = (b.days == null || b.days.isEmpty()) ? allWeekDays() : b.days;
        for (Integer d : days) {
            if (!partCoversDay(a, d, b)) {
                return false;
            }
        }
        return true;
    }

    /** 约束生效的周次集合（1-20）：单双周 / 周次区间 / 全周 */
    static Set<Integer> constraintWeeks(Constraint c) {
        Set<Integer> weeks = new HashSet<>();
        if (c.weekParity != null) {
            int mod = c.weekParity % 2;
            for (int w = 1; w <= 20; w++) {
                if (w % 2 == mod) {
                    weeks.add(w);
                }
            }
            return weeks;
        }
        if (c.weekStart != null) {
            int ws = Math.max(1, c.weekStart);
            int we = Math.min(20, c.weekEnd == null ? c.weekStart : c.weekEnd);
            if (we < ws) {
                int tmp = ws;
                ws = we;
                we = tmp;
            }
            for (int w = ws; w <= we; w++) {
                weeks.add(w);
            }
            return weeks;
        }
        for (int w = 1; w <= 20; w++) {
            weeks.add(w);
        }
        return weeks;
    }

    private AiConstraintEntrySupport() {
    }
}
