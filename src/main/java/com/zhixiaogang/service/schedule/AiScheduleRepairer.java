package com.zhixiaogang.service.schedule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.zhixiaogang.service.schedule.AiScheduleModels.*;
import static com.zhixiaogang.service.schedule.AiScheduleConstants.*;
import static com.zhixiaogang.service.schedule.AiScheduleUtils.*;
import static com.zhixiaogang.service.schedule.AiTimeExpressionParser.*;
import static com.zhixiaogang.service.schedule.AiConstraintSupport.*;

/**
 * AI 排课调整修复：预览条目归一、按新约束重排单条课表、冲突定位与失败提示。
 */
final class AiScheduleRepairer {

    /** 规范化预览条目：补齐字段默认值，保留教室展示文案 */
    static Map<String, Object> normalizePreviewItem(Map<String, Object> raw, String semester) {
        Map<String, Object> it = new LinkedHashMap<>();
        it.put("classId", toLong(raw.get("classId")));
        it.put("className", pick(raw, "className", "class_name", "班级"));
        it.put("courseName", pick(raw, "courseName", "course_name", "课程名", "课程名称"));
        String roomNo = pick(raw, "classroomNo", "classroom_no", "教室号", "教室");
        it.put("classroomNo", roomNo);
        String disp = pick(raw, "classroomDisplay", "classroom_display");
        if (disp.isEmpty()) {
            disp = roomNo.isEmpty() ? NO_CLASSROOM_DISPLAY : roomNo;
        }
        it.put("classroomDisplay", disp);
        it.put("teacherName", pick(raw, "teacherName", "teacher_name", "授课教师", "教师"));
        it.put("weekDay", intOrDefault(raw.get("weekDay"), 1));
        it.put("startPeriod", intOrDefault(raw.get("startPeriod"), 1));
        it.put("endPeriod", intOrDefault(raw.get("endPeriod"), 2));
        it.put("weekRange", normalizeWeekRange(pick(raw, "weekRange", "week_range", "周次", "周次范围")));
        String sem = pick(raw, "semester");
        it.put("semester", sem.isEmpty() ? semester : sem);
        it.put("note", pick(raw, "note"));
        it.put("conflict", false);
        it.put("conflictReason", "");
        return it;
    }

    /**
     * 约束变更后的局部重排：以数据库现有课表 + 未违反约束的条目为基线占用，
     * 把违反新约束的条目挪到满足全部约束的空闲时段；挪不动的条目从结果中移除并给出明确原因。
     */
    static RepairResult repairPreviewItems(List<Map<String, Object>> items, List<ConstraintEntry> entries,
                                            boolean allowWeekend, int minPeriod, int maxPeriod,
                                            Map<String, List<Slot>> existTeacher,
                                            Map<Long, List<Slot>> existClass,
                                            Map<Long, List<Slot>> existRoom,
                                            Map<String, Long> roomIdByNo,
                                            Map<String, Long> classIdByName,
                                            java.util.Set<String> teacherNames) {
        RepairResult result = new RepairResult();
        // 约束片段分流（与排课引擎一致）：每日上限 / 正向约束不参与禁用占用，纯周次约束单独扣减
        List<Constraint> slotConstraints = new ArrayList<>();
        List<Constraint> weekConstraints = new ArrayList<>();
        for (ConstraintEntry entry : entries) {
            for (Constraint p : entry.parts) {
                if (p.maxPerDay != null || p.mustSchedule) {
                    continue;
                }
                boolean noSlot = (p.days == null || p.days.isEmpty())
                        && p.periodStart == null && p.periodEnd == null;
                if (noSlot && (p.weekStart != null || p.weekParity != null)) {
                    weekConstraints.add(p);
                } else {
                    slotConstraints.add(p);
                }
            }
        }
        Map<String, List<Slot>> consTeacher = new HashMap<>();
        Map<Long, List<Slot>> consClass = new HashMap<>();
        Map<Long, List<Slot>> consRoom = new HashMap<>();
        applyConstraints(slotConstraints, consTeacher, consClass, consRoom, classIdByName, roomIdByNo, teacherNames);

        List<Integer> days = new ArrayList<>();
        for (int d = 1; d <= (allowWeekend ? 7 : 5); d++) {
            days.add(d);
        }

        // 1) 周次类约束：按周次扣除当前周次范围；扣空说明该条目已无可行周次
        List<Map<String, Object>> alive = new ArrayList<>();
        for (Map<String, Object> it : items) {
            int day = intOrDefault(it.get("weekDay"), 1);
            int sp = intOrDefault(it.get("startPeriod"), 1);
            int ep = intOrDefault(it.get("endPeriod"), sp);
            String teacher = str(it.get("teacherName"));
            Long classId = toLong(it.get("classId"));
            String roomNo = str(it.get("classroomNo"));
            String weekRange = str(it.get("weekRange"));
            List<Constraint> hits = weekConstraintHits(weekConstraints, teacher, classId, roomNo,
                    classIdByName, teacherNames);
            if (!hits.isEmpty() && !weekRange.isEmpty()) {
                Set<Integer> allowed = buildAllowedWeeks(weekRange);
                for (Constraint wc : hits) {
                    if (wc.weekParity != null) {
                        int parityMod = wc.weekParity % 2;
                        for (int w = 1; w <= 20; w++) {
                            if (w % 2 == parityMod) {
                                allowed.remove(w);
                            }
                        }
                        continue;
                    }
                    int ws = Math.max(1, wc.weekStart);
                    int we = Math.min(20, wc.weekEnd == null ? wc.weekStart : wc.weekEnd);
                    if (we < ws) {
                        int tmp = ws;
                        ws = we;
                        we = tmp;
                    }
                    for (int w = ws; w <= we; w++) {
                        allowed.remove(w);
                    }
                }
                if (allowed.isEmpty()) {
                    result.failures.add(repairFailureMessage(it, day, sp, ep,
                            "该课程所属周次全部被新约束禁止，已无可行周次",
                            "可放宽该约束或调整课程周次后重试"));
                    continue;
                }
                it.put("weekRange", joinWeeks(allowed));
            }
            alive.add(it);
        }

        // 2) 找出违反新约束的条目；未违反的条目先占位，保证挪动后整体仍然无冲突
        Map<Map<String, Object>, String> violations = new java.util.IdentityHashMap<>();
        for (Map<String, Object> it : alive) {
            int day = intOrDefault(it.get("weekDay"), 1);
            int sp = intOrDefault(it.get("startPeriod"), 1);
            int ep = intOrDefault(it.get("endPeriod"), sp);
            String teacher = str(it.get("teacherName"));
            Long classId = toLong(it.get("classId"));
            String roomNo = str(it.get("classroomNo"));
            Long rid = roomNo.isEmpty() ? null : roomIdByNo.get(roomNo);
            List<String> reasons = new ArrayList<>();
            if (!teacher.isEmpty() && isBusy(consTeacher.get(teacher), day, sp, ep)) {
                reasons.add("教师「" + teacher + "」该时段不排课");
            }
            if (classId != null && isBusy(consClass.get(classId), day, sp, ep)) {
                reasons.add("班级该时段不排课");
            }
            if (rid != null && isBusy(consRoom.get(rid), day, sp, ep)) {
                reasons.add("教室「" + roomNo + "」该时段不排课");
            }
            if (!reasons.isEmpty()) {
                violations.put(it, String.join("；", reasons));
            }
        }
        if (violations.isEmpty()) {
            result.items.addAll(alive);
            return result;
        }

        Map<String, List<Slot>> busyTeacher = deepCopySlots(existTeacher);
        Map<Long, List<Slot>> busyClass = deepCopySlots(existClass);
        Map<Long, List<Slot>> busyRoom = deepCopySlots(existRoom);
        for (Map<String, Object> it : alive) {
            if (!violations.containsKey(it)) {
                addOccupancy(it, busyTeacher, busyClass, busyRoom, roomIdByNo);
            }
        }

        // 3) 逐条挪到满足全部约束的最近空闲时段（先偶数对齐起点，再任意起点）
        for (Map<String, Object> it : alive) {
            if (!violations.containsKey(it)) {
                result.items.add(it);
                continue;
            }
            int origDay = intOrDefault(it.get("weekDay"), 1);
            int origStart = intOrDefault(it.get("startPeriod"), 1);
            int origEnd = intOrDefault(it.get("endPeriod"), origStart);
            int len = Math.max(1, origEnd - origStart + 1);
            String teacher = str(it.get("teacherName"));
            Long classId = toLong(it.get("classId"));
            String roomNo = str(it.get("classroomNo"));
            Long rid = roomNo.isEmpty() ? null : roomIdByNo.get(roomNo);

            int bestDay = -1;
            int bestStart = -1;
            double bestScore = Double.MAX_VALUE;
            for (int pass = 0; pass < 2 && bestDay < 0; pass++) {
                for (int d : days) {
                    for (int s = minPeriod; s + len - 1 <= maxPeriod; s++) {
                        // 第一轮优先与原有节次同奇偶（通常为 1-2 / 3-4 等标准时段）
                        if (pass == 0 && s % 2 == 0) {
                            continue;
                        }
                        int e = s + len - 1;
                        if (!teacher.isEmpty()
                                && (isBusy(busyTeacher.get(teacher), d, s, e)
                                    || isBusy(consTeacher.get(teacher), d, s, e))) {
                            continue;
                        }
                        if (classId != null
                                && (isBusy(busyClass.get(classId), d, s, e)
                                    || isBusy(consClass.get(classId), d, s, e))) {
                            continue;
                        }
                        if (rid != null
                                && (isBusy(busyRoom.get(rid), d, s, e)
                                    || isBusy(consRoom.get(rid), d, s, e))) {
                            continue;
                        }
                        double score = Math.abs(d - origDay) * 100.0 + Math.abs(s - origStart)
                                + d * 0.1 + s * 0.01;
                        if (score < bestScore) {
                            bestScore = score;
                            bestDay = d;
                            bestStart = s;
                        }
                    }
                }
            }
            if (bestDay < 0) {
                result.failures.add(repairFailureMessage(it, origDay, origStart, origEnd,
                        repairBlockReason(it, days, len, minPeriod, maxPeriod, busyTeacher, busyClass, busyRoom,
                                consTeacher, consClass, consRoom, roomIdByNo),
                        "可放宽该约束、调整课程节次或改到其他周次后重试"));
                continue;
            }
            it.put("weekDay", bestDay);
            it.put("startPeriod", bestStart);
            it.put("endPeriod", bestStart + len - 1);
            addOccupancy(it, busyTeacher, busyClass, busyRoom, roomIdByNo);
            result.items.add(it);
            result.moved++;
        }
        return result;
    }

    /** 周次类约束命中判定：教师 / 班级 / 教室维度 */
    static List<Constraint> weekConstraintHits(List<Constraint> weekConstraints, String teacher, Long classId,
                                                String roomNo, Map<String, Long> classIdByName,
                                                java.util.Set<String> teacherNames) {
        List<Constraint> hits = new ArrayList<>();
        for (Constraint wc : weekConstraints) {
            boolean hit = false;
            boolean wild = isWildcard(wc.name) || isTargetClass(wc.name);
            if ("class".equals(wc.type)) {
                hit = wild ? (classId != null) : (classId != null
                        && classId.equals(matchId(wc.name, classIdByName)));
            } else if ("teacher".equals(wc.type)) {
                hit = wild ? (!teacher.isEmpty())
                        : (!teacher.isEmpty() && teacher.equals(nameMatch(wc.name, teacherNames)));
            } else {
                hit = wild ? (!roomNo.isEmpty())
                        : (!roomNo.isEmpty() && normName(wc.name).equalsIgnoreCase(normName(roomNo)));
            }
            if (hit) {
                hits.add(wc);
            }
        }
        return hits;
    }

    /** 把预览条目按教师 / 班级 / 教室写入占用表 */
    static void addOccupancy(Map<String, Object> it, Map<String, List<Slot>> busyTeacher,
                              Map<Long, List<Slot>> busyClass, Map<Long, List<Slot>> busyRoom,
                              Map<String, Long> roomIdByNo) {
        int day = intOrDefault(it.get("weekDay"), 1);
        int sp = intOrDefault(it.get("startPeriod"), 1);
        int ep = intOrDefault(it.get("endPeriod"), sp);
        String teacher = str(it.get("teacherName"));
        Long classId = toLong(it.get("classId"));
        String roomNo = str(it.get("classroomNo"));
        if (!teacher.isEmpty()) {
            busyTeacher.computeIfAbsent(teacher, k -> new ArrayList<>()).add(new Slot(day, sp, ep));
        }
        if (classId != null) {
            busyClass.computeIfAbsent(classId, k -> new ArrayList<>()).add(new Slot(day, sp, ep));
        }
        Long rid = roomNo.isEmpty() ? null : roomIdByNo.get(roomNo);
        if (rid != null) {
            busyRoom.computeIfAbsent(rid, k -> new ArrayList<>()).add(new Slot(day, sp, ep));
        }
    }

    /** 重排失败原因定位：教师 / 班级 / 教室哪一侧的候选时段先被占满 */
    static String repairBlockReason(Map<String, Object> it, List<Integer> days, int len,
                                     int minPeriod, int maxPeriod,
                                     Map<String, List<Slot>> busyTeacher,
                                     Map<Long, List<Slot>> busyClass,
                                     Map<Long, List<Slot>> busyRoom,
                                     Map<String, List<Slot>> consTeacher,
                                     Map<Long, List<Slot>> consClass,
                                     Map<Long, List<Slot>> consRoom,
                                     Map<String, Long> roomIdByNo) {
        String teacher = str(it.get("teacherName"));
        Long classId = toLong(it.get("classId"));
        String roomNo = str(it.get("classroomNo"));
        Long rid = roomNo.isEmpty() ? null : roomIdByNo.get(roomNo);
        boolean teacherFull = !teacher.isEmpty();
        boolean classFull = classId != null;
        boolean roomFull = rid != null;
        for (int d : days) {
            for (int s = minPeriod; s + len - 1 <= maxPeriod; s++) {
                int e = s + len - 1;
                if (!teacher.isEmpty() && !isBusy(busyTeacher.get(teacher), d, s, e)
                        && !isBusy(consTeacher.get(teacher), d, s, e)) {
                    teacherFull = false;
                }
                if (classId != null && !isBusy(busyClass.get(classId), d, s, e)
                        && !isBusy(consClass.get(classId), d, s, e)) {
                    classFull = false;
                }
                if (rid != null && !isBusy(busyRoom.get(rid), d, s, e)
                        && !isBusy(consRoom.get(rid), d, s, e)) {
                    roomFull = false;
                }
            }
        }
        List<String> reasons = new ArrayList<>();
        if (teacherFull) {
            reasons.add("教师「" + teacher + "」可排时段已满");
        }
        if (classFull) {
            reasons.add("班级「" + str(it.get("className")) + "」可排时段已满");
        }
        if (roomFull) {
            reasons.add("教室「" + roomNo + "」可排时段已满");
        }
        if (reasons.isEmpty()) {
            return "已无同时满足班级、教师、教室空余的连续 " + len + " 节时段";
        }
        return String.join("；", reasons);
    }

    /** 约束变更后无法安置条目的提示：课程 / 班级 / 教师 + 原时段 + 周次 + 原因 + 建议 */
    static String repairFailureMessage(Map<String, Object> it, int day, int sp, int ep,
                                        String cause, String advice) {
        String className = str(it.get("className"));
        String courseName = str(it.get("courseName"));
        String teacher = str(it.get("teacherName"));
        String weekRange = str(it.get("weekRange"));
        StringBuilder sb = new StringBuilder();
        sb.append("未能排入：班级「").append(className).append("」的课程「").append(courseName)
                .append("」（教师：").append(teacher.isEmpty() ? "未指定" : teacher).append("）");
        sb.append("，原排时间：").append(day >= 1 && day <= 7 ? WEEKDAY_CN[day] : "未知")
                .append(" 第 ").append(sp).append("-").append(ep).append(" 节，已被新约束禁止");
        sb.append("\n周次：").append(weekRange.isEmpty() ? "全部周次" : "第 " + weekRange + " 周");
        sb.append("\n原因：").append(cause);
        sb.append("\n建议：").append(advice);
        return sb.toString();
    }

    private AiScheduleRepairer() {
    }
}
