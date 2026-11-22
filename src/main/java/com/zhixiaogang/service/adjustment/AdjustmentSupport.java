package com.zhixiaogang.service.adjustment;

import com.zhixiaogang.entity.ScheduleAdjustment;
import com.zhixiaogang.entity.User;
import com.zhixiaogang.util.WeekRangeParser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 调课共享工具：字段解析、周次范围解析与校验、SQL 条件拼装、展示文案格式化。
 * 全部为静态方法、无状态，供冲突校验、AI 推荐、审批流转与通知等类复用，避免同名工具在多个类中重复定义。
 */
final class AdjustmentSupport {

    private AdjustmentSupport() {
    }

    private static final String[] ADJ_WEEKDAY_CN = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    static Long longVal(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(o.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    static Integer intVal(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(o.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 宽松布尔解析：兼容前端传 true/false 与 1/0、"true"/"1" */
    static boolean flagVal(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.intValue() == 1;
        String s = o.toString().trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s);
    }

    /** 归一化调课来源：ai（AI 推荐）/ local（AI 不可用时本地规则）/ manual（手动选择） */
    static String normalizeSourceType(Object raw, boolean aiSuggestedFallback) {
        String s = raw == null ? "" : raw.toString().trim().toLowerCase();
        if ("ai".equals(s) || "local".equals(s) || "manual".equals(s)) return s;
        return aiSuggestedFallback ? "ai" : "manual";
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> asDetailList(Object o) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (o instanceof List<?> raw) {
            for (Object item : raw) {
                if (item instanceof Map<?, ?> m) {
                    list.add((Map<String, Object>) m);
                }
            }
        }
        return list;
    }

    static String defaultWeekRange(String origWeekRange) {
        return (origWeekRange == null || origWeekRange.trim().isEmpty()) ? "1-16" : origWeekRange.trim();
    }

    /** 判断 week 是否落在 weekRange（如 "1-18"、"1-9,11-16"、"3"、"单周前8周"）内 */
    static boolean weekInRange(String weekRange, int week) {
        if (weekRange == null || weekRange.trim().isEmpty()) {
            return true;
        }
        return WeekRangeParser.contains(weekRange, week);
    }

    /** 判断 inner 周次范围是否完全包含于 outer 周次范围（outer 为空视为整学期，允许任意周次） */
    static boolean rangeWithin(String outer, String inner) {
        if (outer == null || outer.trim().isEmpty()) {
            return true;
        }
        List<int[]> outerRanges = parseRanges(outer);
        if (outerRanges.isEmpty()) {
            return true;
        }
        List<int[]> innerRanges = parseRanges(inner);
        if (innerRanges.isEmpty()) {
            return true;
        }
        for (int[] ir : innerRanges) {
            boolean covered = false;
            for (int[] or : outerRanges) {
                if (or[0] <= ir[0] && ir[1] <= or[1]) {
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

    static List<int[]> parseRanges(String range) {
        return WeekRangeParser.ranges(range);
    }

    static String teacherMatchCondition(boolean hasId, boolean hasName) {
        StringBuilder cond = new StringBuilder();
        if (hasId) {
            cond.append("s.teacher_id = ?");
        }
        if (hasName) {
            if (cond.length() > 0) cond.append(" OR ");
            cond.append("((s.teacher_id IS NULL OR s.teacher_id = '') AND s.teacher_name = ?)");
        }
        return "(" + cond + ")";
    }

    /** schedule_adjustment 表教师匹配条件（a.teacher_id / a.teacher_name） */
    static String teacherAdjustmentMatchCondition(boolean hasId, boolean hasName) {
        StringBuilder cond = new StringBuilder();
        if (hasId) {
            cond.append("a.teacher_id = ?");
        }
        if (hasName) {
            if (cond.length() > 0) cond.append(" OR ");
            cond.append("((a.teacher_id IS NULL OR a.teacher_id = '') AND a.teacher_name = ?)");
        }
        return "(" + cond + ")";
    }

    /** 读取原课表条目（含课程名） */
    static Map<String, Object> loadOrigSchedule(JdbcTemplate jdbcTemplate, Long scheduleId) {
        String sql = "SELECT s.id, s.course_id, s.classroom_id, s.class_id, s.teacher_id, s.teacher_name, " +
                "s.week_day, s.start_period, s.end_period, s.week_range, s.semester, c.course_name " +
                "FROM schedule s LEFT JOIN course c ON s.course_id = c.id WHERE s.id = ?";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, scheduleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    static boolean ownedBy(Map<String, Object> orig, User user) {
        if (user == null) return false;
        String tid = orig.get("teacher_id") == null ? null : orig.get("teacher_id").toString();
        String tname = orig.get("teacher_name") == null ? null : orig.get("teacher_name").toString();
        if (tid != null && !tid.isEmpty()) {
            return tid.equals(user.getStudentId());
        }
        return tname != null && tname.equals(user.getName());
    }

    /** 星期几文本：1-7 → 周一~周日 */
    static String weekdayText(Integer weekDay) {
        if (weekDay == null || weekDay < 1 || weekDay > 7) {
            return "";
        }
        String[] names = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return names[weekDay];
    }

    static String periodText(Object start, Object end) {
        int s = start == null ? 0 : ((Number) start).intValue();
        int e = end == null ? 0 : ((Number) end).intValue();
        return s == e ? ("第" + s + "节") : ("第" + s + "-" + e + "节");
    }

    /** 星期+节次组合文案，如 "周三第5-6节" */
    static String slotText(int weekDay, Object startPeriod, Object endPeriod) {
        String day = (weekDay >= 1 && weekDay <= 7) ? ADJ_WEEKDAY_CN[weekDay] : "";
        return day + periodText(startPeriod, endPeriod);
    }

    /** 审批/二次校验使用的目标周次口径：跨周调课取目标单周，否则取调课生效周次 */
    static String conflictWeekRange(ScheduleAdjustment a) {
        if (a == null) {
            return null;
        }
        return a.getTargetWeek() != null ? String.valueOf(a.getTargetWeek()) : a.getWeekRange();
    }

    /**
     * 调课批准生效：把 schedule 表中对应课程记录的时段同步更新为目标时段（week_day/start_period/end_period）。
     * 原时段持久化在 schedule_adjustment 的 orig_* 字段（发起申请时已落库），此处只写 schedule。
     * 跨周调课（target_week 非空）额外处理周次：源周次从原行移除，目标周次单独成一节。
     */
    static void applyTargetSlot(JdbcTemplate jdbcTemplate, ScheduleAdjustment a) {
        if (a == null || a.getScheduleId() == null
                || a.getTargetWeekDay() == null || a.getTargetStartPeriod() == null || a.getTargetEndPeriod() == null) {
            return;
        }
        if (a.getTargetWeek() != null) {
            applyCrossWeek(jdbcTemplate, a);
            return;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT class_id, course_id, teacher_id, teacher_name, classroom_id, week_range, semester " +
                "FROM schedule WHERE id = ?", a.getScheduleId());
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> orig = rows.get(0);
        // 原课表完整周次：优先取申请时的快照，历史数据回退到当前行
        String origWeekRange = a.getOrigWeekRange() != null && !a.getOrigWeekRange().trim().isEmpty()
                ? a.getOrigWeekRange()
                : (orig.get("week_range") == null ? null : orig.get("week_range").toString());
        Set<Integer> origWeeks = WeekRangeParser.weeks(
                (origWeekRange == null || origWeekRange.trim().isEmpty())
                        ? ("1-" + WeekRangeParser.getDefaultTotalWeeks()) : origWeekRange);
        Set<Integer> targetWeeks = WeekRangeParser.weeks(a.getWeekRange());
        boolean changeRoom = a.getChangeClassroom() != null && a.getChangeClassroom() == 1
                && a.getTargetClassroomId() != null;

        // 只对部分周次生效时不能直接改写原行：原行保留剩余周次，生效周次单独成行，
        // 否则一次"临时调课"会把整学期都改掉（周次与教室都会被全局替换）
        if (!targetWeeks.isEmpty() && origWeeks.containsAll(targetWeeks) && !origWeeks.equals(targetWeeks)) {
            Set<Integer> remaining = new TreeSet<>(origWeeks);
            remaining.removeAll(targetWeeks);
            Long classroomId = orig.get("classroom_id") == null ? null : ((Number) orig.get("classroom_id")).longValue();
            if (changeRoom) {
                classroomId = a.getTargetClassroomId();
            }
            jdbcTemplate.update("UPDATE schedule SET week_range = ? WHERE id = ?",
                    WeekRangeParser.format(remaining), a.getScheduleId());

            KeyHolder keyHolder = new GeneratedKeyHolder();
            final Long finalClassroomId = classroomId;
            final String targetWeekRange = WeekRangeParser.format(targetWeeks);
            jdbcTemplate.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO schedule (class_id, course_id, teacher_id, teacher_name, classroom_id, " +
                        "week_day, start_period, end_period, week_range, semester) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        new String[]{"id"});
                setLong(ps, 1, orig.get("class_id"));
                setLong(ps, 2, orig.get("course_id"));
                setStr(ps, 3, orig.get("teacher_id"));
                setStr(ps, 4, orig.get("teacher_name"));
                setLong(ps, 5, finalClassroomId);
                ps.setInt(6, a.getTargetWeekDay());
                ps.setInt(7, a.getTargetStartPeriod());
                ps.setInt(8, a.getTargetEndPeriod());
                ps.setString(9, targetWeekRange);
                setStr(ps, 10, orig.get("semester"));
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            a.setTargetScheduleId(key == null ? null : key.longValue());
            // 撤销时按此快照恢复原行完整周次
            a.setOrigWeekRange(origWeekRange == null || origWeekRange.trim().isEmpty()
                    ? WeekRangeParser.format(origWeeks) : origWeekRange);
            return;
        }

        // 全学期生效：换教室时同步更新 classroom_id，否则仅改时段，避免误清空原教室
        if (changeRoom) {
            jdbcTemplate.update(
                    "UPDATE schedule SET week_day = ?, start_period = ?, end_period = ?, classroom_id = ? WHERE id = ?",
                    a.getTargetWeekDay(), a.getTargetStartPeriod(), a.getTargetEndPeriod(),
                    a.getTargetClassroomId(), a.getScheduleId());
        } else {
            jdbcTemplate.update("UPDATE schedule SET week_day = ?, start_period = ?, end_period = ? WHERE id = ?",
                    a.getTargetWeekDay(), a.getTargetStartPeriod(), a.getTargetEndPeriod(), a.getScheduleId());
        }
    }

    /**
     * 跨周调课生效：
     * ① 原行还有其他周次 → 原行周次裁剪为剩余周次，另插入目标周次的新课表行；
     * ② 原行只有源周次 → 原行原地改为目标周次 + 目标时段。
     */
    private static void applyCrossWeek(JdbcTemplate jdbcTemplate, ScheduleAdjustment a) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT class_id, course_id, teacher_id, teacher_name, classroom_id, week_range, semester " +
                "FROM schedule WHERE id = ?", a.getScheduleId());
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> orig = rows.get(0);

        String origWeekRange = a.getOrigWeekRange() != null ? a.getOrigWeekRange()
                : (orig.get("week_range") == null ? null : orig.get("week_range").toString());
        Set<Integer> origWeeks = WeekRangeParser.weeks(
                (origWeekRange == null || origWeekRange.trim().isEmpty())
                        ? ("1-" + WeekRangeParser.getDefaultTotalWeeks()) : origWeekRange);
        Set<Integer> remaining = new TreeSet<>(origWeeks);
        remaining.removeAll(WeekRangeParser.weeks(a.getWeekRange()));

        Long classroomId = orig.get("classroom_id") == null ? null : ((Number) orig.get("classroom_id")).longValue();
        if (a.getChangeClassroom() != null && a.getChangeClassroom() == 1 && a.getTargetClassroomId() != null) {
            classroomId = a.getTargetClassroomId();
        }

        if (remaining.isEmpty()) {
            // 原行只覆盖源周次：原地改为目标周次与目标时段
            jdbcTemplate.update(
                    "UPDATE schedule SET week_day = ?, start_period = ?, end_period = ?, week_range = ?, classroom_id = ? WHERE id = ?",
                    a.getTargetWeekDay(), a.getTargetStartPeriod(), a.getTargetEndPeriod(),
                    String.valueOf(a.getTargetWeek()), classroomId, a.getScheduleId());
            a.setTargetScheduleId(null);
            return;
        }

        // 原行保留剩余周次，另插入目标周次的新行
        jdbcTemplate.update("UPDATE schedule SET week_range = ? WHERE id = ?",
                WeekRangeParser.format(remaining), a.getScheduleId());

        KeyHolder keyHolder = new GeneratedKeyHolder();
        final Long finalClassroomId = classroomId;
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO schedule (class_id, course_id, teacher_id, teacher_name, classroom_id, " +
                    "week_day, start_period, end_period, week_range, semester) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    new String[]{"id"});
            setLong(ps, 1, orig.get("class_id"));
            setLong(ps, 2, orig.get("course_id"));
            setStr(ps, 3, orig.get("teacher_id"));
            setStr(ps, 4, orig.get("teacher_name"));
            setLong(ps, 5, finalClassroomId);
            ps.setInt(6, a.getTargetWeekDay());
            ps.setInt(7, a.getTargetStartPeriod());
            ps.setInt(8, a.getTargetEndPeriod());
            ps.setString(9, String.valueOf(a.getTargetWeek()));
            setStr(ps, 10, orig.get("semester"));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        a.setTargetScheduleId(key == null ? null : key.longValue());
    }

    private static void setLong(PreparedStatement ps, int index, Object value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else if (value instanceof Number n) {
            ps.setLong(index, n.longValue());
        } else {
            ps.setLong(index, Long.parseLong(value.toString().trim()));
        }
    }

    private static void setStr(PreparedStatement ps, int index, Object value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value.toString());
        }
    }

    /** 撤销审批通过/管理端撤销：把 schedule 记录的时段恢复为 schedule_adjustment 中保存的 orig_* 原时段 */
    static void restoreOrigSlot(JdbcTemplate jdbcTemplate, ScheduleAdjustment a) {
        if (a == null || a.getScheduleId() == null) {
            return;
        }
        if (a.getTargetWeek() == null) {
            if (a.getTargetScheduleId() != null) {
                // 部分周次生效的拆分场景：删除生效周次新行，原行恢复完整周次（原时段未被改写）
                jdbcTemplate.update("DELETE FROM schedule WHERE id = ?", a.getTargetScheduleId());
                restoreWeekRange(jdbcTemplate, a);
                return;
            }
            if (a.getOrigWeekDay() == null || a.getOrigStartPeriod() == null || a.getOrigEndPeriod() == null) {
                return;
            }
            // 非跨周换教室：审批时同步过 classroom_id，撤销时按原教室快照恢复；
            // 历史记录没有原教室快照（origClassroomId 为空），保持原有时段恢复逻辑不动
            boolean changeRoom = a.getChangeClassroom() != null && a.getChangeClassroom() == 1
                    && a.getOrigClassroomId() != null;
            if (changeRoom) {
                jdbcTemplate.update(
                        "UPDATE schedule SET week_day = ?, start_period = ?, end_period = ?, classroom_id = ? WHERE id = ?",
                        a.getOrigWeekDay(), a.getOrigStartPeriod(), a.getOrigEndPeriod(),
                        a.getOrigClassroomId(), a.getScheduleId());
            } else {
                jdbcTemplate.update("UPDATE schedule SET week_day = ?, start_period = ?, end_period = ? WHERE id = ?",
                        a.getOrigWeekDay(), a.getOrigStartPeriod(), a.getOrigEndPeriod(), a.getScheduleId());
            }
            return;
        }
        if (a.getTargetScheduleId() != null) {
            // 跨周拆分场景：删除目标周次新行，原行恢复原始周次
            jdbcTemplate.update("DELETE FROM schedule WHERE id = ?", a.getTargetScheduleId());
            restoreWeekRange(jdbcTemplate, a);
            return;
        }
        // 跨周原地改写场景：恢复原时段、原教室、原周次
        if (a.getOrigWeekDay() == null || a.getOrigStartPeriod() == null || a.getOrigEndPeriod() == null) {
            return;
        }
        jdbcTemplate.update(
                "UPDATE schedule SET week_day = ?, start_period = ?, end_period = ?, week_range = ?, classroom_id = ? WHERE id = ?",
                a.getOrigWeekDay(), a.getOrigStartPeriod(), a.getOrigEndPeriod(),
                a.getOrigWeekRange(), a.getOrigClassroomId(), a.getScheduleId());
    }

    /**
     * 拆分调课撤销后把生效周次并回原行：以原行当前周次为基准并入本申请的周次，
     * 不直接用申请时的全量快照覆盖，避免把同一课程其它拆分（如另一周的单周调课）一并"恢复"造成重复排课。
     */
    private static void restoreWeekRange(JdbcTemplate jdbcTemplate, ScheduleAdjustment a) {
        Set<Integer> weeks = new TreeSet<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT week_range FROM schedule WHERE id = ?", a.getScheduleId());
            if (!rows.isEmpty() && rows.get(0).get("week_range") != null) {
                weeks.addAll(WeekRangeParser.weeks(rows.get(0).get("week_range").toString()));
            }
        } catch (Exception ignored) {
            // 读取失败时退回申请时的周次快照
        }
        if (weeks.isEmpty() && a.getOrigWeekRange() != null && !a.getOrigWeekRange().trim().isEmpty()) {
            weeks.addAll(WeekRangeParser.weeks(a.getOrigWeekRange()));
        }
        weeks.addAll(WeekRangeParser.weeks(a.getWeekRange()));
        if (weeks.isEmpty()) {
            return;
        }
        jdbcTemplate.update("UPDATE schedule SET week_range = ? WHERE id = ?",
                WeekRangeParser.format(weeks), a.getScheduleId());
    }

    /**
     * 判断调课记录的 schedule 行时段是否已同步为目标时段（审批通过后同步，撤销后恢复）。
     * 已同步时展示层无需再叠加目标格子（schedule 行本身即目标占用），未同步（历史数据）时保留叠加补偿。
     */
    static boolean slotSynced(Map<String, Object> adj) {
        if (adj == null) {
            return false;
        }
        Integer wd = intVal(adj.get("target_week_day"));
        Integer sp = intVal(adj.get("target_start_period"));
        Integer ep = intVal(adj.get("target_end_period"));
        if (wd == null || sp == null || ep == null) {
            return false;
        }
        return wd.equals(intVal(adj.get("week_day")))
                && sp.equals(intVal(adj.get("start_period")))
                && ep.equals(intVal(adj.get("end_period")));
    }

    /** 实体版 slotSynced：判断 schedule 行当前时段是否已等于调课目标时段 */
    static boolean slotSynced(ScheduleAdjustment a, Map<String, Object> scheduleRow) {
        if (a == null || scheduleRow == null
                || a.getTargetWeekDay() == null || a.getTargetStartPeriod() == null || a.getTargetEndPeriod() == null) {
            return false;
        }
        return a.getTargetWeekDay().equals(intVal(scheduleRow.get("week_day")))
                && a.getTargetStartPeriod().equals(intVal(scheduleRow.get("start_period")))
                && a.getTargetEndPeriod().equals(intVal(scheduleRow.get("end_period")));
    }
}
