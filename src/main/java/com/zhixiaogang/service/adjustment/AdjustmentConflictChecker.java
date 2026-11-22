package com.zhixiaogang.service.adjustment;

import com.zhixiaogang.service.adjustment.ScheduleAdjustmentService.ConflictItem;
import com.zhixiaogang.util.WeekRangeParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 调课冲突校验领域：教室 / 班级 / 教师三方占用校验、周次交集判断、冲突文案与明细生成。
 * 发起校验（教师端实时校验）与批准校验（管理端二次校验）共用同一套口径。
 */
@Service
public class AdjustmentConflictChecker {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 多方占用校验（口径统一）：目标时段该教室无排课、无已批准预约、无已生效调课占用；
     * 该班级无排课、无已生效调课占用；该教师（含其他班级）无排课、无已生效调课占用。
     * 所有七类冲突都按"调课生效周次与占用方生效周次是否存在交集"判断，
     * 完全不重叠时允许通过，完全或部分重叠时判定冲突。
     *
     * @param classroomId       原课程教室 ID（null 表示无教室课程，跳过教室校验）
     * @param classId           班级 ID
     * @param teacherId         授课教师工号
     * @param teacherName       授课教师姓名
     * @param targetWeekDay     目标星期 1-7
     * @param targetStart       目标开始节次
     * @param targetEnd         目标结束节次
     * @param excludeScheduleId 原课表条目 ID（排除自身，避免与原时段自冲突）
     * @param excludeAdjustmentId 当前调课记录 ID（批准时排除自身，发起时传 null）
     * @param targetWeekRange   调课目标生效周次（如 "1-16"、"3,5,7"；null 或空表示全学期）
     * @return 冲突原因列表（空表示无冲突）
     */
    public List<String> checkConflicts(Long classroomId, Long classId, String teacherId, String teacherName,
                                       int targetWeekDay, int targetStart, int targetEnd,
                                       Long excludeScheduleId, Long excludeAdjustmentId,
                                       String targetWeekRange) {
        List<String> conflicts = new ArrayList<>();
        for (ConflictItem item : collectConflicts(classroomId, classId, teacherId, teacherName,
                targetWeekDay, targetStart, targetEnd, excludeScheduleId, excludeAdjustmentId, targetWeekRange)) {
            conflicts.add(conflictText(item));
        }
        return conflicts;
    }

    /**
     * 按维度收集冲突明细（教室 / 班级 / 教师），供统一校验、AI 推荐与管理端二次校验共用。
     * @param classroomId 目标教室（换教室时应传目标教室；沿用原教室时传原教室）
     */
    public List<ConflictItem> collectConflicts(Long classroomId, Long classId, String teacherId, String teacherName,
                                               int targetWeekDay, int targetStart, int targetEnd,
                                               Long excludeScheduleId, Long excludeAdjustmentId,
                                               String targetWeekRange) {
        List<ConflictItem> items = new ArrayList<>();

        // 1) 教室排课占用（按周次交集过滤）
        if (classroomId != null) {
            StringBuilder sql = new StringBuilder(
                    "SELECT s.start_period, s.end_period, s.week_range, c.course_name, cl.class_name, s.teacher_name " +
                    "FROM schedule s LEFT JOIN course c ON s.course_id = c.id " +
                    "LEFT JOIN class_info cl ON s.class_id = cl.id " +
                    "WHERE s.classroom_id = ? AND s.week_day = ? AND s.start_period <= ? AND s.end_period >= ?");
            List<Object> args = new ArrayList<>(Arrays.asList(classroomId, targetWeekDay, targetEnd, targetStart));
            if (excludeScheduleId != null) {
                sql.append(" AND s.id <> ?");
                args.add(excludeScheduleId);
            }
            for (Map<String, Object> r : jdbcTemplate.queryForList(sql.toString(), args.toArray())) {
                String rowWeekRange = r.get("week_range") == null ? null : r.get("week_range").toString();
                if (weeksOverlap(targetWeekRange, rowWeekRange)) {
                    ConflictItem it = new ConflictItem();
                    it.dimension = "room";
                    it.kind = "schedule";
                    it.courseName = AdjustmentSupport.str(r.get("course_name"));
                    it.className = AdjustmentSupport.str(r.get("class_name"));
                    it.teacherName = AdjustmentSupport.str(r.get("teacher_name"));
                    it.weekDay = targetWeekDay;
                    it.startPeriod = AdjustmentSupport.intVal(r.get("start_period"));
                    it.endPeriod = AdjustmentSupport.intVal(r.get("end_period"));
                    it.weekRange = rowWeekRange;
                    items.add(it);
                }
            }

            // 2) 教室已批准预约占用（reservation_date → 第几教学周 → 与 targetWeekRange 求交集）
            // 注意：预约表 start_period/end_period 为节次段索引（1-5），课表为实际节次（1-10），比较前需换算
            String rsql = "SELECT r.start_period, r.end_period, r.student_name, r.reason, r.reservation_date " +
                    "FROM classroom_reservation r " +
                    "WHERE r.classroom_id = ? AND r.status = 'approved' " +
                    "AND (WEEKDAY(r.reservation_date) + 1) = ? AND r.start_period * 2 - 1 <= ? AND r.end_period * 2 >= ?";
            for (Map<String, Object> r : jdbcTemplate.queryForList(rsql, classroomId, targetWeekDay, targetEnd, targetStart)) {
                Object rd = r.get("reservation_date");
                LocalDate rDate = null;
                if (rd instanceof java.sql.Date) {
                    rDate = ((java.sql.Date) rd).toLocalDate();
                } else if (rd instanceof LocalDate) {
                    rDate = (LocalDate) rd;
                }
                Integer week = rDate == null ? null : weekOfDate(rDate);
                // 无法换算教学周（如未配置学期起始日）时按最保守口径视为冲突，避免漏拦已预约教室
                if (week == null || weekInRange(week, targetWeekRange)) {
                    ConflictItem it = new ConflictItem();
                    it.dimension = "room";
                    it.kind = "reservation";
                    String reason = AdjustmentSupport.str(r.get("reason"));
                    it.courseName = reason.isEmpty() ? AdjustmentSupport.str(r.get("student_name")) : reason;
                    it.className = "";
                    it.teacherName = AdjustmentSupport.str(r.get("student_name"));
                    it.weekDay = targetWeekDay;
                    Integer segStart = AdjustmentSupport.intVal(r.get("start_period"));
                    Integer segEnd = AdjustmentSupport.intVal(r.get("end_period"));
                    it.startPeriod = segStart == null ? null : segStart * 2 - 1;
                    it.endPeriod = segEnd == null ? null : segEnd * 2;
                    it.weekRange = targetWeekRange;
                    items.add(it);
                }
            }

            // 3) 教室已生效调课占用（目标教室 = 换教室后的教室，否则沿用原教室）
            {
                StringBuilder asql = new StringBuilder(
                        "SELECT a.course_name, a.teacher_name, ci.class_name, " +
                        "a.target_start_period, a.target_end_period, a.week_range " +
                        "FROM schedule_adjustment a " +
                        "LEFT JOIN schedule s ON a.schedule_id = s.id " +
                        "LEFT JOIN class_info ci ON a.class_id = ci.id " +
                        "WHERE a.status = 'approved' AND a.target_schedule_id IS NULL AND " + effectiveClassroomExpr("a", "s") + " = ? " +
                        "AND a.target_week_day = ? AND a.target_start_period <= ? AND a.target_end_period >= ?");
                List<Object> aargs = new ArrayList<>(Arrays.asList(classroomId, targetWeekDay, targetEnd, targetStart));
                if (excludeAdjustmentId != null) {
                    asql.append(" AND a.id <> ?");
                    aargs.add(excludeAdjustmentId);
                }
                for (Map<String, Object> r : jdbcTemplate.queryForList(asql.toString(), aargs.toArray())) {
                    String rowWeekRange = r.get("week_range") == null ? null : r.get("week_range").toString();
                    if (weeksOverlap(targetWeekRange, rowWeekRange)) {
                        ConflictItem it = new ConflictItem();
                        it.dimension = "room";
                        it.kind = "adjustment";
                        it.courseName = AdjustmentSupport.str(r.get("course_name"));
                        it.className = AdjustmentSupport.str(r.get("class_name"));
                        it.teacherName = AdjustmentSupport.str(r.get("teacher_name"));
                        it.weekDay = targetWeekDay;
                        it.startPeriod = AdjustmentSupport.intVal(r.get("target_start_period"));
                        it.endPeriod = AdjustmentSupport.intVal(r.get("target_end_period"));
                        it.weekRange = rowWeekRange;
                        items.add(it);
                    }
                }
            }
        }

        // 4) 班级排课占用
        if (classId != null) {
            StringBuilder sql = new StringBuilder(
                    "SELECT s.start_period, s.end_period, c.course_name, s.teacher_name, s.week_range, cl.classroom_no " +
                    "FROM schedule s LEFT JOIN course c ON s.course_id = c.id " +
                    "LEFT JOIN classroom cl ON s.classroom_id = cl.id " +
                    "WHERE s.class_id = ? AND s.week_day = ? AND s.start_period <= ? AND s.end_period >= ?");
            List<Object> args = new ArrayList<>(Arrays.asList(classId, targetWeekDay, targetEnd, targetStart));
            if (excludeScheduleId != null) {
                sql.append(" AND s.id <> ?");
                args.add(excludeScheduleId);
            }
            for (Map<String, Object> r : jdbcTemplate.queryForList(sql.toString(), args.toArray())) {
                String rowWeekRange = r.get("week_range") == null ? null : r.get("week_range").toString();
                if (weeksOverlap(targetWeekRange, rowWeekRange)) {
                    ConflictItem it = new ConflictItem();
                    it.dimension = "class";
                    it.kind = "schedule";
                    it.courseName = AdjustmentSupport.str(r.get("course_name"));
                    it.teacherName = AdjustmentSupport.str(r.get("teacher_name"));
                    it.classroomNo = AdjustmentSupport.str(r.get("classroom_no"));
                    it.weekDay = targetWeekDay;
                    it.startPeriod = AdjustmentSupport.intVal(r.get("start_period"));
                    it.endPeriod = AdjustmentSupport.intVal(r.get("end_period"));
                    it.weekRange = rowWeekRange;
                    items.add(it);
                }
            }

            // 5) 班级已生效调课占用
            {
                StringBuilder asql = new StringBuilder(
                        "SELECT a.course_name, a.teacher_name, " +
                        "a.target_start_period, a.target_end_period, a.week_range, " +
                        "COALESCE(tc.classroom_no, cr.classroom_no) AS classroom_no " +
                        "FROM schedule_adjustment a " +
                        "LEFT JOIN schedule s ON a.schedule_id = s.id " +
                        "LEFT JOIN classroom cr ON s.classroom_id = cr.id " +
                        "LEFT JOIN classroom tc ON a.target_classroom_id = tc.id " +
                        "WHERE a.status = 'approved' AND a.target_schedule_id IS NULL AND a.class_id = ? " +
                        "AND a.target_week_day = ? AND a.target_start_period <= ? AND a.target_end_period >= ?");
                List<Object> aargs = new ArrayList<>(Arrays.asList(classId, targetWeekDay, targetEnd, targetStart));
                if (excludeAdjustmentId != null) {
                    asql.append(" AND a.id <> ?");
                    aargs.add(excludeAdjustmentId);
                }
                for (Map<String, Object> r : jdbcTemplate.queryForList(asql.toString(), aargs.toArray())) {
                    String rowWeekRange = r.get("week_range") == null ? null : r.get("week_range").toString();
                    if (weeksOverlap(targetWeekRange, rowWeekRange)) {
                        ConflictItem it = new ConflictItem();
                        it.dimension = "class";
                        it.kind = "adjustment";
                        it.courseName = AdjustmentSupport.str(r.get("course_name"));
                        it.teacherName = AdjustmentSupport.str(r.get("teacher_name"));
                        it.classroomNo = AdjustmentSupport.str(r.get("classroom_no"));
                        it.weekDay = targetWeekDay;
                        it.startPeriod = AdjustmentSupport.intVal(r.get("target_start_period"));
                        it.endPeriod = AdjustmentSupport.intVal(r.get("target_end_period"));
                        it.weekRange = rowWeekRange;
                        items.add(it);
                    }
                }
            }
        }

        // 6/7) 教师排课与已生效调课占用（含其他班级授课），与代课教师校验复用同一套判定
        items.addAll(collectTeacherConflicts(teacherId, teacherName, targetWeekDay, targetStart, targetEnd,
                excludeScheduleId, excludeAdjustmentId, targetWeekRange));

        return items;
    }

    /**
     * 教师维度占用：该教师此时段已有排课，或已有生效调课占用（按周次交集判断）。
     * 调课发起/批准校验与代课教师时段校验共用，保证口径一致。
     */
    public List<ConflictItem> collectTeacherConflicts(String teacherId, String teacherName,
                                                      int targetWeekDay, int targetStart, int targetEnd,
                                                      Long excludeScheduleId, Long excludeAdjustmentId,
                                                      String targetWeekRange) {
        List<ConflictItem> items = new ArrayList<>();
        boolean hasId = teacherId != null && !teacherId.isEmpty();
        boolean hasName = teacherName != null && !teacherName.isEmpty();
        if (hasId || hasName) {
            StringBuilder sql = new StringBuilder(
                    "SELECT s.start_period, s.end_period, c.course_name, cl.class_name, s.week_range, cr.classroom_no " +
                    "FROM schedule s LEFT JOIN course c ON s.course_id = c.id " +
                    "LEFT JOIN class_info cl ON s.class_id = cl.id " +
                    "LEFT JOIN classroom cr ON s.classroom_id = cr.id " +
                    "WHERE " + AdjustmentSupport.teacherMatchCondition(hasId, hasName) + " " +
                    "AND s.week_day = ? AND s.start_period <= ? AND s.end_period >= ?");
            List<Object> args = new ArrayList<>();
            if (hasId) args.add(teacherId);
            if (hasName) args.add(teacherName);
            args.add(targetWeekDay);
            args.add(targetEnd);
            args.add(targetStart);
            if (excludeScheduleId != null) {
                sql.append(" AND s.id <> ?");
                args.add(excludeScheduleId);
            }
            for (Map<String, Object> r : jdbcTemplate.queryForList(sql.toString(), args.toArray())) {
                String rowWeekRange = r.get("week_range") == null ? null : r.get("week_range").toString();
                if (weeksOverlap(targetWeekRange, rowWeekRange)) {
                    ConflictItem it = new ConflictItem();
                    it.dimension = "teacher";
                    it.kind = "schedule";
                    it.courseName = AdjustmentSupport.str(r.get("course_name"));
                    it.className = AdjustmentSupport.str(r.get("class_name"));
                    it.classroomNo = AdjustmentSupport.str(r.get("classroom_no"));
                    it.weekDay = targetWeekDay;
                    it.startPeriod = AdjustmentSupport.intVal(r.get("start_period"));
                    it.endPeriod = AdjustmentSupport.intVal(r.get("end_period"));
                    it.weekRange = rowWeekRange;
                    items.add(it);
                }
            }

            StringBuilder asql = new StringBuilder(
                    "SELECT a.course_name, ci.class_name, " +
                    "a.target_start_period, a.target_end_period, a.week_range, " +
                    "COALESCE(tc.classroom_no, cr.classroom_no) AS classroom_no " +
                    "FROM schedule_adjustment a " +
                    "LEFT JOIN class_info ci ON a.class_id = ci.id " +
                    "LEFT JOIN schedule s ON a.schedule_id = s.id " +
                    "LEFT JOIN classroom cr ON s.classroom_id = cr.id " +
                    "LEFT JOIN classroom tc ON a.target_classroom_id = tc.id " +
                    "WHERE a.status = 'approved' AND a.target_schedule_id IS NULL AND " + AdjustmentSupport.teacherAdjustmentMatchCondition(hasId, hasName) + " " +
                    "AND a.target_week_day = ? AND a.target_start_period <= ? AND a.target_end_period >= ?");
            List<Object> aargs = new ArrayList<>();
            if (hasId) aargs.add(teacherId);
            if (hasName) aargs.add(teacherName);
            aargs.add(targetWeekDay);
            aargs.add(targetEnd);
            aargs.add(targetStart);
            if (excludeAdjustmentId != null) {
                asql.append(" AND a.id <> ?");
                aargs.add(excludeAdjustmentId);
            }
            for (Map<String, Object> r : jdbcTemplate.queryForList(asql.toString(), aargs.toArray())) {
                String rowWeekRange = r.get("week_range") == null ? null : r.get("week_range").toString();
                if (weeksOverlap(targetWeekRange, rowWeekRange)) {
                    ConflictItem it = new ConflictItem();
                    it.dimension = "teacher";
                    it.kind = "adjustment";
                    it.courseName = AdjustmentSupport.str(r.get("course_name"));
                    it.className = AdjustmentSupport.str(r.get("class_name"));
                    it.classroomNo = AdjustmentSupport.str(r.get("classroom_no"));
                    it.weekDay = targetWeekDay;
                    it.startPeriod = AdjustmentSupport.intVal(r.get("target_start_period"));
                    it.endPeriod = AdjustmentSupport.intVal(r.get("target_end_period"));
                    it.weekRange = rowWeekRange;
                    items.add(it);
                }
            }
        }
        return items;
    }

    /** 调课生效教室表达式：换教室且填了目标教室时取目标教室，否则沿用原课表教室 */
    String effectiveClassroomExpr(String a, String s) {
        return "(CASE WHEN " + a + ".change_classroom = 1 AND " + a + ".target_classroom_id IS NOT NULL " +
                "THEN " + a + ".target_classroom_id ELSE " + s + ".classroom_id END)";
    }

    /** 冲突明细 → 统一校验文案（保持原有文案风格） */
    String conflictText(ConflictItem it) {
        String period = AdjustmentSupport.periodText(it.startPeriod, it.endPeriod);
        String roomPart = it.classroomNo == null || it.classroomNo.isEmpty() ? "" : " @" + it.classroomNo;
        if ("room".equals(it.dimension)) {
            if ("reservation".equals(it.kind)) {
                return "教室已被预约占用：" + it.teacherName + " " + period;
            }
            String who = it.className.isEmpty() ? it.teacherName : it.className + " " + it.teacherName;
            return ("adjustment".equals(it.kind) ? "教室已被调课占用：" : "教室已被排课占用：")
                    + it.courseName + "（" + who + " " + period + "）";
        }
        if ("class".equals(it.dimension)) {
            return ("adjustment".equals(it.kind) ? "该班级此时段已被调课占用：" : "该班级此时段已有课程：")
                    + it.courseName + "（" + it.teacherName + " " + period + roomPart + "）";
        }
        return ("adjustment".equals(it.kind) ? "该教师此时段已被调课占用：" : "该教师此时段已有其他课程：")
                + it.courseName + "（" + it.className + " " + period + roomPart + "）";
    }

    /** 冲突结论分类：none / teacher / class / room / multi */
    String conflictResultOf(List<ConflictItem> items) {
        if (items == null || items.isEmpty()) {
            return "none";
        }
        Set<String> dims = new LinkedHashSet<>();
        for (ConflictItem it : items) {
            dims.add(it.dimension);
        }
        if (dims.size() > 1) {
            return "multi";
        }
        return dims.iterator().next();
    }

    /** 管理端二次校验明细：如「教师 周三 3-4节 有《数据库原理与实践》B3-203」 */
    List<Map<String, Object>> conflictDetailLines(List<ConflictItem> items) {
        List<Map<String, Object>> lines = new ArrayList<>();
        int count = 0;
        for (ConflictItem it : items) {
            if (count >= 8) break;
            String period = AdjustmentSupport.periodText(it.startPeriod, it.endPeriod);
            String line;
            if ("room".equals(it.dimension)) {
                String room = it.classroomNo == null || it.classroomNo.isEmpty() ? "目标教室" : it.classroomNo;
                line = "reservation".equals(it.kind)
                        ? "教室 " + room + " 被《" + it.courseName + "》预约占用"
                        : "教室 " + room + " " + AdjustmentSupport.weekdayText(it.weekDay) + " " + period + " 被《" + it.courseName + "》占用";
            } else if ("class".equals(it.dimension)) {
                line = "班级 " + AdjustmentSupport.weekdayText(it.weekDay) + " " + period + " 有《" + it.courseName + "》"
                        + (it.classroomNo == null || it.classroomNo.isEmpty() ? "" : " " + it.classroomNo);
            } else {
                line = "教师 " + AdjustmentSupport.weekdayText(it.weekDay) + " " + period + " 有《" + it.courseName + "》"
                        + (it.classroomNo == null || it.classroomNo.isEmpty() ? "" : " " + it.classroomNo);
            }
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("dimension", it.dimension);
            m.put("text", line);
            lines.add(m);
            count++;
        }
        return lines;
    }

    /**
     * 后端确定性生成候选状态文字（AI 只负责排序/筛选，不允许改写）：
     * 三方全空 → 全部空闲；仅教室占用 → 可换教室；教师/班级冲突 → 不推荐；多项冲突 → 拼装前两项。
     */
    String statusTextOf(List<ConflictItem> items, boolean changeClassroom) {
        if (items == null || items.isEmpty()) {
            return "全部空闲，可放心调课";
        }
        ConflictItem room = null, teacher = null, clazz = null;
        for (ConflictItem it : items) {
            if ("room".equals(it.dimension)) {
                if (room == null) room = it;
            } else if ("teacher".equals(it.dimension)) {
                if (teacher == null) teacher = it;
            } else if ("class".equals(it.dimension)) {
                if (clazz == null) clazz = it;
            }
        }
        if (room != null && teacher == null && clazz == null) {
            return "教室被《" + room.courseName + "》占用，其余空闲，可换教室";
        }
        if (room == null) {
            List<String> parts = new ArrayList<>();
            if (teacher != null) parts.add("您有《" + teacher.courseName + "》课");
            if (clazz != null) parts.add("班级有《" + clazz.courseName + "》课");
            return "该时段" + String.join("、", parts) + "，不推荐";
        }
        List<String> parts = new ArrayList<>();
        parts.add("教室被《" + room.courseName + "》占用");
        if (teacher != null) {
            parts.add("您有《" + teacher.courseName + "》课");
        } else if (clazz != null) {
            parts.add("班级有《" + clazz.courseName + "》课");
        }
        return "该时段" + String.join("，且", parts) + "，不推荐";
    }

    /** 冲突明细快照（落库 conflict_detail，最长 1000 字符） */
    String conflictDetailSnapshot(List<ConflictItem> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        List<String> texts = new ArrayList<>();
        for (Map<String, Object> line : conflictDetailLines(items)) {
            texts.add(AdjustmentSupport.str(line.get("text")));
        }
        String s = String.join("；", texts);
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }

    // ==================== 周次范围工具方法 ====================

    /**
     * 判断两个 weekRange 字符串（如 "1-8"、"10"、"3,5,7"）的周次集合是否有交集。
     * null 或空字符串视为"全学期"（永远与任何范围重叠）。
     */
    boolean weeksOverlap(String a, String b) {
        if (isAllWeeks(a) || isAllWeeks(b)) {
            return true; // null/空 → 全学期 → 永远重叠
        }
        Set<Integer> setA = parseWeekSet(a);
        Set<Integer> setB = parseWeekSet(b);
        if (setA == null || setB == null) return true;
        for (Integer w : setA) {
            if (setB.contains(w)) return true;
        }
        return false;
    }

    /** 判断 weekRange 是否为"全学期"（null / 空 / 全空格） */
    private boolean isAllWeeks(String range) {
        return range == null || range.trim().isEmpty();
    }

    /** 判断单个周次是否在 weekRange 内（支持自然语言写法） */
    private boolean weekInRange(int week, String range) {
        if (isAllWeeks(range)) return true;
        return WeekRangeParser.contains(range, week);
    }

    /** 把 weekRange 字符串展开为周次集合；无法解析时返回 null（视为全学期） */
    private Set<Integer> parseWeekSet(String range) {
        if (isAllWeeks(range)) return null;
        if (!WeekRangeParser.isValid(range)) return null;
        return new LinkedHashSet<>(WeekRangeParser.weeks(range));
    }

    /**
     * 把具体日期换算为"第几教学周"。
     * 基准：semester_config 表最新一条 start_date（本学期第 1 周的第一天，应当是周一）。
     * 换算逻辑：把目标日期对齐到周一 → 与 start_date 相差的完整周数 + 1。
     * 若配置缺失或结果异常，返回 null。
     */
    private Integer weekOfDate(LocalDate date) {
        if (date == null) return null;
        try {
            LocalDate startDate = jdbcTemplate.queryForObject(
                    "SELECT start_date FROM semester_config ORDER BY id DESC LIMIT 1",
                    LocalDate.class);
            if (startDate == null) return null;
            LocalDate dateMonday = date.with(DayOfWeek.MONDAY);
            LocalDate termMonday = startDate.with(DayOfWeek.MONDAY);
            long days = ChronoUnit.DAYS.between(termMonday, dateMonday);
            if (days < 0) return null;
            return (int) (days / 7) + 1;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 同一课程（同一 schedule 记录）存在进行中调课申请的校验：
     * 仅待审核（pending）与已批准（approved）的调课记录、且生效周次与本次目标周次有交集时禁止再次发起；
     * 已驳回 / 已撤销的申请不阻断重新提交（原记录保留作历史）。
     *
     * @return 拦截文案；无进行中记录时返回 null
     */
    String existingAdjustmentError(Long scheduleId, String targetWeekRange) {
        if (scheduleId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, status, week_range FROM schedule_adjustment " +
                        "WHERE schedule_id = ? AND status IN ('pending', 'approved') ORDER BY id DESC",
                scheduleId);
        for (Map<String, Object> r : rows) {
            String existRange = r.get("week_range") == null ? null : r.get("week_range").toString();
            if (!weeksOverlap(targetWeekRange, existRange)) {
                continue;
            }
            String status = r.get("status") == null ? "" : r.get("status").toString();
            String statusText = "pending".equals(status) ? "管理端待审批" : "已批准";
            return "该课程存在" + statusText + "的调课申请（申请编号 #" + r.get("id")
                    + "），同一课程同一周次不允许重复发起调课";
        }
        return null;
    }

    /**
     * 同一课程（同一 schedule 记录）重复调课校验：
     * 存在 status=approved 的调课、且其生效周次与本次目标周次有交集时拦截；
     * 周次完全不重叠（如已生效 1-8 周、本次 9-16 周）时放行。
     * 目标时段与已生效调课完全相同时给简短文案，否则给"原时段 已调整为 目标时段"文案。
     *
     * @return 冲突文案；无冲突返回 null
     */
    String existingApprovedAdjustmentError(Long scheduleId, int targetWeekDay,
                                           int targetStart, int targetEnd, String targetWeekRange) {
        if (scheduleId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT orig_week_day, orig_start_period, orig_end_period, " +
                "target_week_day, target_start_period, target_end_period, week_range " +
                "FROM schedule_adjustment WHERE schedule_id = ? AND status = 'approved'", scheduleId);
        for (Map<String, Object> r : rows) {
            String existRange = r.get("week_range") == null ? null : r.get("week_range").toString();
            if (!weeksOverlap(targetWeekRange, existRange)) {
                continue;
            }
            int eDay = ((Number) r.get("target_week_day")).intValue();
            int eStart = ((Number) r.get("target_start_period")).intValue();
            int eEnd = ((Number) r.get("target_end_period")).intValue();
            String targetText = AdjustmentSupport.slotText(eDay, r.get("target_start_period"), r.get("target_end_period"));
            if (eDay == targetWeekDay && eStart == targetStart && eEnd == targetEnd) {
                return "该课程已有生效调课（" + targetText + "），请先撤销后再发起调课";
            }
            String origText = AdjustmentSupport.slotText(((Number) r.get("orig_week_day")).intValue(),
                    r.get("orig_start_period"), r.get("orig_end_period"));
            return "该课程已有生效调课（" + origText + " 已调整为 " + targetText + "），请先撤销后再发起调课";
        }
        return null;
    }
}
