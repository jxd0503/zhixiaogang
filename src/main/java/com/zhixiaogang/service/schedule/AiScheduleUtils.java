package com.zhixiaogang.service.schedule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.zhixiaogang.service.schedule.AiScheduleModels.*;
import com.zhixiaogang.util.WeekRangeParser;

/**
 * AI 排课通用工具：取值 / 类型转换、星期与周次归一、名称匹配、冲突判断等无状态工具方法。
 */
final class AiScheduleUtils {

    static String pick(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && !v.toString().trim().isEmpty()) {
                return v.toString().trim();
            }
        }
        return "";
    }

    /** 周次归一化：自然语言与数字写法统一转为标准数字格式 */
    static String normalizeWeekRange(String raw) {
        if (raw == null) {
            return "";
        }
        return WeekRangeParser.normalizeOrFallback(raw);
    }

    static int parseIntSafe(String s) {
        if (s == null) {
            return 0;
        }
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    static Integer toInt(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return (int) Double.parseDouble(v.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    static int toIntOrDefault(Object v, int def) {
        Integer i = toInt(v);
        return i == null ? def : i;
    }

    static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    static int intOrDefault(Object v, int def) {
        Integer i = toInt(v);
        return i == null ? def : i;
    }

    static boolean boolOrDefault(Object v, boolean def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        String s = v.toString().trim().toLowerCase();
        if (s.isEmpty()) {
            return def;
        }
        return s.equals("true") || s.equals("1") || s.equals("yes");
    }

    static String str(Object v) {
        return v == null ? "" : v.toString().trim();
    }

    static Map<String, Object> fail(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", message);
        return result;
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }

    static <K> Map<K, List<Slot>> deepCopySlots(Map<K, List<Slot>> src) {
        Map<K, List<Slot>> copy = new HashMap<>();
        for (Map.Entry<K, List<Slot>> e : src.entrySet()) {
            copy.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return copy;
    }

    /** 计数快照（班级每天节数统计），用于课程排入失败时回滚 */
    static Map<Long, Map<Integer, Integer>> copyCountMap(Map<Long, Map<Integer, Integer>> src) {
        Map<Long, Map<Integer, Integer>> copy = new HashMap<>();
        for (Map.Entry<Long, Map<Integer, Integer>> e : src.entrySet()) {
            copy.put(e.getKey(), new HashMap<>(e.getValue()));
        }
        return copy;
    }

    /** 条目时段是否与 [day, sp, ep] 重叠 */
    static boolean overlaps(Map<String, Object> o, int day, int sp, int ep) {
        Integer od = toInt(o.get("weekDay"));
        Integer os = toInt(o.get("startPeriod"));
        Integer oe = toInt(o.get("endPeriod"));
        if (od == null || os == null || oe == null) {
            return false;
        }
        return od == day && !(oe < sp || os > ep);
    }

    static boolean boolTrue(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        String s = v.toString().trim().toLowerCase();
        return s.equals("true") || s.equals("1") || s.equals("yes");
    }

    static boolean hasAnyKey(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            if (m.containsKey(k)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasDeleteKeyword(String s) {
        if (s == null) {
            return false;
        }
        return s.contains("删除") || s.contains("删掉") || s.contains("去掉") || s.contains("移除")
                || s.contains("撤掉") || s.contains("取消") || s.contains("不要这条") || s.contains("不排这条");
    }

    /** 是否含约束语义（不可排 / 限定 / 需排课等），用于预览页调整指令分流 */
    static boolean hasConstraintIntent(String s) {
        if (s == null) {
            return false;
        }
        String[] words = {"不能", "不可以", "不可", "不要", "不得", "禁止", "避免", "避开",
                "不排", "别排", "别在", "不安排", "不上", "没空", "只能", "只排", "仅排", "仅",
                "最多", "至少", "也要", "必须", "需要", "单周", "双周"};
        for (String w : words) {
            if (s.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** 是否含改动课表条目的语义（调课 / 删除 / 新增），与纯约束指令区分 */
    static boolean hasItemChangeIntent(String s) {
        if (s == null) {
            return false;
        }
        String[] words = {"调到", "调至", "换到", "换成", "移到", "挪到", "改为", "改到",
                "调整到", "对调", "互换", "交换", "删除", "删掉", "去掉", "移除", "取消",
                "增加一门", "添加", "新增"};
        for (String w : words) {
            if (s.contains(w)) {
                return true;
            }
        }
        return false;
    }

    static List<Integer> parseIntList(Object v) {
        List<Integer> out = new ArrayList<>();
        if (v == null) {
            return out;
        }
        if (v instanceof Number) {
            int d = ((Number) v).intValue();
            if (d >= 1 && d <= 7) {
                out.add(d);
            }
            return out;
        }
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                Integer d = toInt(o);
                if (d != null && d >= 1 && d <= 7) {
                    out.add(d);
                }
            }
            return out;
        }
        String s = v.toString();
        for (String part : s.split("[,;，；]")) {
            part = part.trim();
            if (part.isEmpty()) {
                continue;
            }
            if (part.contains("-")) {
                String[] r = part.split("-");
                int a = parseIntSafe(r[0]);
                int b = r.length > 1 ? parseIntSafe(r[1]) : a;
                for (int d = Math.min(a, b); d <= Math.max(a, b); d++) {
                    if (d >= 1 && d <= 7) {
                        out.add(d);
                    }
                }
            } else {
                int d = parseIntSafe(part);
                if (d >= 1 && d <= 7) {
                    out.add(d);
                }
            }
        }
        return out;
    }

    static String normName(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\\s+", "");
    }

    static String nameMatch(String target, java.util.Collection<String> candidates) {
        if (target == null || target.isEmpty() || candidates == null) {
            return null;
        }
        String tn = normName(target);
        if (tn.isEmpty()) {
            return null;
        }
        for (String c : candidates) {
            if (c != null && !c.isEmpty() && c.equals(target)) {
                return c;
            }
        }
        for (String c : candidates) {
            if (c != null && !c.isEmpty() && normName(c).equals(tn)) {
                return c;
            }
        }
        for (String c : candidates) {
            if (c != null && !c.isEmpty()) {
                String cn = normName(c);
                if (!cn.isEmpty() && (cn.contains(tn) || tn.contains(cn))) {
                    return c;
                }
            }
        }
        return null;
    }

    static Long matchId(String target, Map<String, Long> nameToId) {
        if (nameToId == null || nameToId.isEmpty()) {
            return null;
        }
        String key = nameMatch(target, nameToId.keySet());
        return key == null ? null : nameToId.get(key);
    }

    static List<Map<String, Object>> resolveCourses(Map<String, Object> request) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object obj = request.get("courses");
        if (!(obj instanceof List)) {
            return out;
        }
        for (Object o : (List<?>) obj) {
            if (!(o instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            String courseName = pick(m, "courseName", "course_name", "课程名", "课程名称");
            if (courseName.isEmpty()) {
                continue;
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("courseName", courseName);
            int wh = intOrDefault(m.get("weekHours"), intOrDefault(m.get("week_hours"), 2));
            c.put("weekHours", wh > 0 ? wh : 2);
            c.put("teacherName", pick(m, "teacherName", "teacher_name", "授课教师", "教师"));
            c.put("classroomNo", pick(m, "classroomNo", "classroom_no", "教室号", "教室"));
            c.put("roomMode", pick(m, "roomMode", "room_mode", "教室模式"));
            c.put("classroomType", pick(m, "classroomType", "classroom_type", "教室类型"));
            c.put("allowAutoChange", boolOrDefault(m.get("allowAutoChange"), false));
            c.put("weekRange", normalizeWeekRange(pick(m, "weekRange", "week_range", "周次")));
            out.add(c);
        }
        return out;
    }

    /** 周次范围转 BitSet；空值或无法解析时返回 null（视为全周占用） */
    static java.util.BitSet weeksOf(String weekRange) {
        if (weekRange == null || weekRange.trim().isEmpty()) {
            return null;
        }
        java.util.Set<Integer> set;
        try {
            set = WeekRangeParser.weeks(weekRange.trim());
        } catch (Exception e) {
            return null;
        }
        if (set == null || set.isEmpty()) {
            return null;
        }
        java.util.BitSet bs = new java.util.BitSet();
        for (Integer w : set) {
            if (w != null && w > 0) {
                bs.set(w);
            }
        }
        return bs.isEmpty() ? null : bs;
    }

    /** 占用判定：星期与节次重叠，且周次相交（任一方为全周即视为相交） */
    static boolean isBusy(List<Slot> slots, int day, int start, int end, java.util.BitSet weeks) {
        if (slots == null) {
            return false;
        }
        for (Slot s : slots) {
            if (s.day != day || s.end < start || s.start > end) {
                continue;
            }
            if (weeks == null || s.weeks == null) {
                return true;
            }
            java.util.BitSet tmp = (java.util.BitSet) s.weeks.clone();
            tmp.and(weeks);
            if (!tmp.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    static boolean isBusy(List<Slot> slots, int day, int start, int end) {
        return isBusy(slots, day, start, end, null);
    }

    private AiScheduleUtils() {
    }
}
