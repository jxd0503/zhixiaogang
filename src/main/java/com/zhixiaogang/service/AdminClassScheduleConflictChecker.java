package com.zhixiaogang.service;

import com.zhixiaogang.util.WeekRangeParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.zhixiaogang.service.AdminClassSupport.*;

/********** 班级课表周次冲突校验：同班级同星期且节次重叠的课程，逐一比对周次集合，交集非空即冲突 **********/
@Service
public class AdminClassScheduleConflictChecker {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 校验待保存课表与同班级同星期且节次重叠的已有条目是否周次冲突。
     * 空周次视为整学期（1 到本学期总周数），因此空周次与任意周次都会冲突。
     * 按学期过滤：仅比较同学期条目，历史空学期数据视为同学期。
     * 返回 null 表示无冲突；否则返回可直接下发的失败结果，message 内含冲突课程、周次与时段明细。
     *
     * @param excludeScheduleId 编辑场景传入自身 id，避免与自己比较；新增传 null
     */
    public Map<String, Object> check(Long classId, int weekDay, int startPeriod, int endPeriod,
                                     String weekRange, String semester, Long excludeScheduleId) {
        Set<Integer> weeks = resolveWeeks(weekRange);

        StringBuilder sql = new StringBuilder(
                "SELECT s.id, s.start_period, s.end_period, s.week_range, s.semester, c.course_name " +
                "FROM schedule s JOIN course c ON s.course_id = c.id " +
                "WHERE s.class_id = ? AND s.week_day = ? " +
                "AND NOT (s.end_period < ? OR s.start_period > ?)");
        List<Object> args = new ArrayList<>();
        args.add(classId);
        args.add(weekDay);
        args.add(startPeriod);
        args.add(endPeriod);
        if (excludeScheduleId != null) {
            sql.append(" AND s.id <> ?");
            args.add(excludeScheduleId);
        }
        sql.append(" ORDER BY s.start_period");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> conflicts = new ArrayList<>();
        List<String> details = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!sameSemester(getStr(row, "semester"), semester)) {
                continue;
            }
            // 时段已重叠，仅当周次集合存在交集时才算冲突；无交集允许同时段并存
            Set<Integer> existing = resolveWeeks(getStr(row, "week_range"));
            Set<Integer> overlap = new TreeSet<>(weeks);
            overlap.retainAll(existing);
            if (overlap.isEmpty()) {
                continue;
            }

            String courseName = getStr(row, "course_name");
            String slot = weekDayName(weekDay) + " 第" + row.get("start_period") + "-" + row.get("end_period") + "节";
            String existingRange = displayRange(existing);
            String overlapRange = WeekRangeParser.format(overlap);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("scheduleId", row.get("id"));
            item.put("courseName", courseName);
            item.put("weekDay", weekDay);
            item.put("startPeriod", row.get("start_period"));
            item.put("endPeriod", row.get("end_period"));
            item.put("weekRange", existingRange);
            item.put("overlapWeeks", overlapRange);
            conflicts.add(item);

            details.add("「" + courseName + "」（" + slot + "，该课程周次 " + existingRange + "）与待保存课程周次 "
                    + displayRange(weeks) + " 的公共周次为 " + overlapRange);
        }

        if (conflicts.isEmpty()) {
            return null;
        }
        Map<String, Object> result = fail("同一时段存在周次重叠的课程，请调整周次或节次后再保存。冲突详情："
                + String.join("；", details));
        result.put("conflicts", conflicts);
        return result;
    }

    /**
     * 跨班级校验教室 / 教师占用：同星期节次重叠、周次有交集且同学期（空学期视为同学期）时冲突。
     * 教室按 classroom_id 判定；教师按姓名判定（自动创建的新教师尚无可冲突课程）。
     *
     * @return null 表示无冲突，否则返回可直接展示的冲突说明
     */
    public String checkRoomAndTeacher(Long classroomId, String teacherName, int weekDay, int startPeriod, int endPeriod,
                                      String weekRange, String semester, Long excludeScheduleId) {
        boolean checkRoom = classroomId != null;
        String teacher = teacherName == null ? "" : teacherName.trim();
        boolean checkTeacher = !teacher.isEmpty();
        if (!checkRoom && !checkTeacher) {
            return null;
        }
        Set<Integer> weeks = resolveWeeks(weekRange);

        StringBuilder sql = new StringBuilder(
                "SELECT s.id, s.classroom_id, s.teacher_name, s.week_range, s.semester, " +
                "s.start_period, s.end_period, c.course_name, cl.classroom_no, cl.building, ci.class_name " +
                "FROM schedule s JOIN course c ON s.course_id = c.id " +
                "LEFT JOIN classroom cl ON s.classroom_id = cl.id " +
                "LEFT JOIN class_info ci ON s.class_id = ci.id " +
                "WHERE s.week_day = ? AND NOT (s.end_period < ? OR s.start_period > ?)");
        List<Object> args = new ArrayList<>();
        args.add(weekDay);
        args.add(startPeriod);
        args.add(endPeriod);
        if (excludeScheduleId != null) {
            sql.append(" AND s.id <> ?");
            args.add(excludeScheduleId);
        }
        sql.append(" ORDER BY s.start_period");

        List<String> conflicts = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql.toString(), args.toArray())) {
            if (!sameSemester(getStr(row, "semester"), semester)) {
                continue;
            }
            Set<Integer> overlap = new TreeSet<>(weeks);
            overlap.retainAll(resolveWeeks(getStr(row, "week_range")));
            if (overlap.isEmpty()) {
                continue;
            }
            String range = WeekRangeParser.format(overlap);
            String course = getStr(row, "course_name");
            String className = getStr(row, "class_name");
            String slot = weekDayName(weekDay) + " 第" + row.get("start_period") + "-" + row.get("end_period") + "节";
            boolean roomHit = checkRoom && row.get("classroom_id") instanceof Number
                    && ((Number) row.get("classroom_id")).longValue() == classroomId;
            if (roomHit) {
                String roomNo = getStr(row, "classroom_no");
                conflicts.add("教室「" + roomNo + "」" + slot + " 已被《" + course + "》占用（"
                        + (className.isEmpty() ? "其他班级" : className) + "，周次 " + range + "）");
            } else if (checkTeacher && teacher.equals(getStr(row, "teacher_name"))) {
                conflicts.add("教师「" + teacher + "」" + slot + " 已有课程《" + course + "》（"
                        + (className.isEmpty() ? "" : className + "，") + "周次 " + range + "）");
            }
        }
        return conflicts.isEmpty() ? null : "时段冲突：" + String.join("；", conflicts);
    }

    /** 学期口径：目标学期为空时不做学期过滤（兼容未配置学期）；历史空学期条目视为同学期 */
    private static boolean sameSemester(String rowSemester, String targetSemester) {
        String target = targetSemester == null ? "" : targetSemester.trim();
        if (target.isEmpty()) {
            return true;
        }
        String row = rowSemester == null ? "" : rowSemester.trim();
        return row.isEmpty() || target.equals(row);
    }

    /** 周次解析：空值或无法解析按整学期处理，保证校验口径偏严而非漏判 */
    private static Set<Integer> resolveWeeks(String weekRange) {
        Set<Integer> parsed = WeekRangeParser.weeks(weekRange);
        if (parsed.isEmpty()) {
            int total = WeekRangeParser.getDefaultTotalWeeks();
            Set<Integer> all = new TreeSet<>();
            for (int week = 1; week <= total; week++) {
                all.add(week);
            }
            return all;
        }
        return parsed;
    }

    /** 周次集合展示：整学期显示为「全学期（1-N）」，其余压缩为标准数字格式 */
    private static String displayRange(Set<Integer> weeks) {
        int total = WeekRangeParser.getDefaultTotalWeeks();
        if (weeks.size() >= total && weeks.contains(1) && weeks.contains(total)) {
            return "全学期（1-" + total + "）";
        }
        return WeekRangeParser.format(weeks);
    }
}
