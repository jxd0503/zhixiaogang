package com.zhixiaogang.service.schedule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.zhixiaogang.service.schedule.AiScheduleModels.*;
import static com.zhixiaogang.service.schedule.AiScheduleConstants.*;
import static com.zhixiaogang.service.schedule.AiScheduleUtils.*;
import static com.zhixiaogang.service.schedule.AiConstraintSupport.*;

/**
 * AI 排课文案输出：约束描述、时间片段压缩、排课失败原因与提示文案构建。
 */
final class AiScheduleTexts {

    /** 约束条目的结构化输出：id 为条目编号（从 1 开始），parts 为各时间片段（供前端无损回传） */
    static Map<String, Object> constraintItem(ConstraintEntry entry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", entry.id);
        m.put("description", describeEntry(entry));
        m.put("type", "classroom".equals(entry.type) ? "room" : entry.type);
        m.put("name", isTargetClass(entry.name) ? "目标班级"
                : ("classroom".equals(entry.type) ? roomDisplayName(entry.name) : entry.name));
        // 原始主体：name 字段仅用于展示（目标班级 / 教室号归一），回传合并需用原始值
        m.put("subjectType", entry.type);
        m.put("subjectName", entry.name);
        List<Map<String, Object>> parts = new ArrayList<>();
        for (Constraint p : entry.parts) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("days", p.days == null ? new ArrayList<>() : new ArrayList<>(p.days));
            pm.put("periodStart", p.periodStart);
            pm.put("periodEnd", p.periodEnd);
            pm.put("weekStart", p.weekStart);
            pm.put("weekEnd", p.weekEnd);
            pm.put("weekParity", p.weekParity);
            pm.put("maxPerDay", p.maxPerDay);
            pm.put("mustSchedule", p.mustSchedule);
            parts.add(pm);
        }
        m.put("parts", parts);
        // 兼容字段：取首个片段
        Constraint first = entry.parts.isEmpty() ? null : entry.parts.get(0);
        m.put("days", first == null || first.days == null ? new ArrayList<>() : new ArrayList<>(first.days));
        m.put("periodStart", first == null ? null : first.periodStart);
        m.put("periodEnd", first == null ? null : first.periodEnd);
        m.put("weekStart", first == null ? null : first.weekStart);
        m.put("weekEnd", first == null ? null : first.weekEnd);
        m.put("weekParity", first == null ? null : first.weekParity);
        m.put("maxPerDay", first == null ? null : first.maxPerDay);
        m.put("mustSchedule", first != null && first.mustSchedule);
        return m;
    }

    /** 【类型 - 名称】前缀：教师 / 班级 / 教室，通配符与目标班级单独展示 */
    static String subjectLabel(String type, String name) {
        boolean wild = isWildcard(name);
        if ("teacher".equals(type)) {
            return wild ? "【所有教师】" : "【教师 - " + name + "】";
        }
        if ("class".equals(type)) {
            // 目标班级（无明确主语的约束）显示为「班级」，便于用户核对归属
            return wild ? "【所有班级】" : (isTargetClass(name) ? "【班级】" : "【班级 - " + name + "】");
        }
        return wild ? "【所有教室】" : "【教室 - " + roomDisplayName(name) + "】";
    }

    static String describe(Constraint c) {
        if (c.maxPerDay != null) {
            return subjectLabel(c.type, c.name) + "每天最多 " + c.maxPerDay + " 节课";
        }
        if (c.mustSchedule) {
            return subjectLabel(c.type, c.name) + describeTimePart(c, false) + "需排课";
        }
        return subjectLabel(c.type, c.name) + describeTimePart(c, false) + "不排课";
    }

    /** 条目描述：单片段沿用 describe；多片段按时间顺序以顿号连接，如「周一上午、周二、周三不排课」 */
    static String describeEntry(ConstraintEntry entry) {
        if (entry.parts.size() == 1) {
            return describe(entry.parts.get(0));
        }
        if (!entry.timeType()) {
            List<String> texts = new ArrayList<>();
            for (Constraint p : entry.parts) {
                texts.add(describeTimePart(p, false) + (p.mustSchedule ? "需排课" : ""));
            }
            return subjectLabel(entry.type, entry.name) + String.join("、", texts);
        }
        List<Constraint> sorted = splitAndSortTimeParts(entry.parts);
        List<String> texts = new ArrayList<>();
        for (Constraint p : sorted) {
            texts.add(describeTimePart(p, true));
        }
        return subjectLabel(entry.type, entry.name) + String.join("、", texts) + "不排课";
    }

    /**
     * 多片段按星期顺序拆分排序：先把片段按天拆开（如「周一、周二、周五」+「周四上午」），
     * 再按星期 + 起始节次排序，最后把相邻且时间维度相同的天重新合并（保留「周一至周五」这类紧凑展示）。
     * 从而输出「周一、周二、周四上午、周五、周六、周日」而不是把整段放在末尾。
     */
    static List<Constraint> splitAndSortTimeParts(List<Constraint> parts) {
        List<Constraint> fragments = new ArrayList<>();
        for (Constraint p : parts) {
            if (p.days == null || p.days.isEmpty()) {
                fragments.add(p);
                continue;
            }
            List<Integer> ds = new ArrayList<>(p.days);
            Collections.sort(ds);
            for (int d : ds) {
                fragments.add(new Constraint(p.type, p.name, Collections.singletonList(d),
                        p.periodStart, p.periodEnd, p.weekStart, p.weekEnd, p.weekParity,
                        p.maxPerDay, p.mustSchedule));
            }
        }
        fragments.sort(Comparator.comparingInt(AiScheduleTexts::firstDay)
                .thenComparingInt(p -> p.periodStart == null ? 0 : p.periodStart)
                .thenComparingInt(p -> p.periodEnd == null ? 0 : p.periodEnd));
        List<Constraint> merged = new ArrayList<>();
        for (Constraint f : fragments) {
            Constraint prev = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (prev != null && !prev.days.isEmpty() && !f.days.isEmpty()
                    && firstDay(f) == lastDay(prev) + 1 && sameTimeShape(prev, f)) {
                List<Integer> ds = new ArrayList<>(prev.days);
                ds.addAll(f.days);
                merged.set(merged.size() - 1, new Constraint(prev.type, prev.name, ds,
                        prev.periodStart, prev.periodEnd, prev.weekStart, prev.weekEnd,
                        prev.weekParity, prev.maxPerDay, prev.mustSchedule));
            } else {
                merged.add(f);
            }
        }
        return merged;
    }

    /** 两个片段是否只有星期不同（节次 / 周次 / 单双周 / 每日上限 / 正向语义完全一致） */
    static boolean sameTimeShape(Constraint a, Constraint b) {
        return java.util.Objects.equals(a.periodStart, b.periodStart)
                && java.util.Objects.equals(a.periodEnd, b.periodEnd)
                && java.util.Objects.equals(a.weekStart, b.weekStart)
                && java.util.Objects.equals(a.weekEnd, b.weekEnd)
                && java.util.Objects.equals(a.weekParity, b.weekParity)
                && java.util.Objects.equals(a.maxPerDay, b.maxPerDay)
                && a.mustSchedule == b.mustSchedule;
    }

    static int lastDay(Constraint c) {
        if (c.days == null || c.days.isEmpty()) {
            return -1;
        }
        return Collections.max(c.days);
    }

    static int firstDay(Constraint c) {
        if (c.days == null || c.days.isEmpty()) {
            return 0;
        }
        return Collections.min(c.days);
    }

    /** 时间片段描述：如「周一上午」「周二、周三」「单周下午」「第3周」「周一（第1-8周）」 */
    static String describeTimePart(Constraint c, boolean omitAllDay) {
        if (c.maxPerDay != null) {
            return "每天最多 " + c.maxPerDay + " 节课";
        }
        // 纯周次片段：如「第3周」
        boolean pureWeek = c.weekStart != null && (c.days == null || c.days.isEmpty())
                && c.periodStart == null && c.periodEnd == null;
        if (pureWeek) {
            return "第" + c.weekStart + (c.weekEnd != null && !c.weekEnd.equals(c.weekStart)
                    ? "-" + c.weekEnd : "") + "周";
        }
        StringBuilder sb = new StringBuilder();
        if (c.days != null && !c.days.isEmpty()) {
            List<Integer> ds = new ArrayList<>(c.days);
            Collections.sort(ds);
            sb.append(compressDays(ds));
        }
        // 单双周与星期 / 时段可组合，如「周五单周」「单周下午」
        String parityText = "";
        if (c.weekParity != null) {
            parityText = c.weekParity == 1 ? "单周" : "双周";
            sb.append(parityText);
        }
        if (c.periodStart != null && c.periodEnd != null) {
            sb.append(periodLabel(c.periodStart, c.periodEnd));
        } else if (!omitAllDay && parityText.isEmpty()) {
            sb.append("全天");
        }
        if (c.weekStart != null) {
            sb.append("（").append(c.weekEnd != null && !c.weekEnd.equals(c.weekStart)
                    ? "第" + c.weekStart + "-" + c.weekEnd + "周" : "第" + c.weekStart + "周").append("）");
        }
        return sb.toString();
    }

    static String periodLabel(int ps, int pe) {
        if (ps == 1 && pe == 4) {
            return "上午";
        }
        if (ps == 5 && pe == 8) {
            return "下午";
        }
        if (ps == 9 && pe >= 10) {
            return "晚间";
        }
        if (ps == 1 && pe >= 10) {
            return "全天";
        }
        if (ps == pe) {
            return "第" + ps + "节";
        }
        return "第" + ps + "-" + pe + "节";
    }

    /** 教室名称展示归一化：「B5-306教室」等由模型附带类型后缀的名称只保留教室号 */
    static String roomDisplayName(String name) {
        if (name == null) {
            return "";
        }
        String s = name.trim();
        Matcher m = Pattern.compile("(?i)([A-Za-z]{1,3}\\d{1,2}-\\d{2,4})(?:教室|机房|实训室|实验室)?").matcher(s);
        if (m.matches()) {
            return m.group(1).toUpperCase();
        }
        return s;
    }

    /** 星期列表压缩显示：{1..5} → 周一至周五；{6,7} → 周六、周日；一般情况顿号连接 */
    static String compressDays(List<Integer> ds) {
        if (ds == null || ds.isEmpty()) {
            return "";
        }
        boolean contig = true;
        for (int i = 1; i < ds.size(); i++) {
            if (ds.get(i) != ds.get(i - 1) + 1) {
                contig = false;
                break;
            }
        }
        if (ds.size() >= 3 && contig) {
            return WEEKDAY_CN[ds.get(0)] + "至" + WEEKDAY_CN[ds.get(ds.size() - 1)];
        }
        List<String> dn = new ArrayList<>();
        for (int d : ds) {
            if (d >= 1 && d <= 7) {
                dn.add(WEEKDAY_CN[d]);
            }
        }
        return String.join("、", dn);
    }

    static String weekConstraintNames(List<Constraint> weekConstraints) {
        if (weekConstraints == null || weekConstraints.isEmpty()) {
            return "";
        }
        List<String> ds = new ArrayList<>();
        for (Constraint c : weekConstraints) {
            ds.add(describe(c));
        }
        return String.join("；", ds);
    }

    /**
     * 统一的排课失败提示：固定包含班级、课程、教师与可排时间（周次 + 星期 + 节次），
     * 便于用户直接定位到具体条目与时间范围。
     */
    static String failureMessage(String className, String courseName, String teacher, String weekRange,
                                  List<Integer> days, List<int[]> segments,
                                  String progress, String cause, String advice) {
        StringBuilder sb = new StringBuilder();
        String tn = teacher == null ? "" : teacher.trim();
        sb.append("未能排入：班级「").append(className).append("」的课程「").append(courseName)
                .append("」（教师：").append(tn.isEmpty() ? "未指定" : tn).append("）");
        if (progress != null && !progress.isEmpty()) {
            sb.append("，").append(progress);
        }
        sb.append("\n可排时间：").append(failureTimeScope(weekRange, days, segments));
        sb.append("\n原因：").append(cause);
        sb.append("\n建议：").append(advice);
        return sb.toString();
    }

    /** 失败提示里的时间范围：周次 + 星期 + 节次 + 候选时段数 */
    static String failureTimeScope(String weekRange, List<Integer> days, List<int[]> segments) {
        StringBuilder sb = new StringBuilder();
        sb.append(weekRange == null || weekRange.isEmpty() ? "全部周次" : "第 " + weekRange + " 周");
        if (days != null && !days.isEmpty()) {
            sb.append("、").append(compressDays(days));
        }
        if (segments != null && !segments.isEmpty()) {
            int pStart = segments.get(0)[0];
            int pEnd = segments.get(segments.size() - 1)[1];
            sb.append("、").append(pStart == pEnd ? "第 " + pStart + " 节" : "第 " + pStart + "-" + pEnd + " 节");
            if (days != null && !days.isEmpty()) {
                sb.append("（共 ").append(days.size() * segments.size()).append(" 个候选时段）");
            }
        }
        return sb.toString();
    }

    /** 无教室课程（体育 / 室外）无解时的原因分析：只需看班级与教师空闲情况 */
    static String buildFailure(String className, String courseName, int weekHours, int segNo, int segLen,
                               String teacherKey, String weekRange,
                               List<Integer> days, List<int[]> segments, Long classId,
                               Map<Long, List<Slot>> workClass, Map<String, List<Slot>> workTeacher,
                               List<Constraint> constraints) {
        int freeCount = 0;
        boolean teacherBlocked = false;
        boolean classBlocked = false;
        for (int day : days) {
            for (int[] seg : segments) {
                int s = seg[0];
                int e = seg[1];
                boolean classFree = !isBusy(workClass.get(classId), day, s, e);
                boolean teacherFree = teacherKey.isEmpty() || !isBusy(workTeacher.get(teacherKey), day, s, e);
                if (classFree && teacherFree) {
                    freeCount++;
                }
                if (!classFree) classBlocked = true;
                if (!teacherFree) teacherBlocked = true;
            }
        }
        String course = "第 " + segNo + " 个时段（" + segLen + " 节，周学时 " + weekHours + "）排不进去";
        String cause;
        if (teacherBlocked && classBlocked) {
            cause = "这些时段内班级和教师都已被其他课程占用";
        } else if (teacherBlocked) {
            cause = "教师「" + teacherKey + "」在这些时段内都已有课";
        } else if (classBlocked) {
            cause = "班级在这些时段内都已有课";
        } else if (freeCount > 0) {
            cause = "班级与教师同时空闲的时段只有 " + freeCount + " 个，无法满足连续 " + segLen + " 节的安排";
        } else {
            cause = "这些时段内已无班级与教师同时空闲的时间";
        }
        String advice = "请调整周次（当前 " + weekRange + "）或节次范围后重试";
        String base = failureMessage(className, courseName, teacherKey, weekRange, days, segments,
                course, cause, advice);
        String note = constraintFailureNote(constraints, teacherKey, className, false);
        return note.isEmpty() ? base : base + note;
    }

    /** 排课失败时附注匹配到的排课约束，注明因哪条约束无法满足 */
    static String constraintFailureNote(List<Constraint> constraints, String teacher, String className,
                                         boolean roomBlocked) {
        if (constraints == null || constraints.isEmpty()) {
            return "";
        }
        List<String> notes = new ArrayList<>();
        for (Constraint c : constraints) {
            if (c == null || c.name == null || c.name.isEmpty()) {
                continue;
            }
            boolean hit;
            boolean wild = isWildcard(c.name) || isTargetClass(c.name);
            if ("teacher".equals(c.type)) {
                hit = wild ? (teacher != null && !teacher.isEmpty())
                        : (teacher != null && !teacher.isEmpty() && normName(c.name).equals(normName(teacher)));
            } else if ("class".equals(c.type)) {
                hit = wild ? (className != null && !className.isEmpty())
                        : (className != null && !className.isEmpty() && normName(c.name).equals(normName(className)));
            } else {
                hit = roomBlocked;
            }
            if (hit) {
                notes.add("因排课约束「" + describe(c) + "」无法满足");
            }
        }
        return notes.isEmpty() ? "" : "；" + String.join("；", notes);
    }

    private AiScheduleTexts() {
    }
}
