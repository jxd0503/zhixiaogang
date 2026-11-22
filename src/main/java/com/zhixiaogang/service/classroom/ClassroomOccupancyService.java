package com.zhixiaogang.service.classroom;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixiaogang.entity.Classroom;
import com.zhixiaogang.entity.ClassroomReservation;
import com.zhixiaogang.mapper.ClassroomMapper;
import com.zhixiaogang.mapper.ClassroomReservationMapper;
import com.zhixiaogang.service.adjustment.ScheduleAdjustmentService;
import com.zhixiaogang.util.WeekRangeParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/********** 教室占用域：空教室查询、教室占用明细、占用来源聚合（课表 / 调课 / 预约） **********/
@Service
public class ClassroomOccupancyService {

    @Autowired
    private ClassroomMapper classroomMapper;

    @Autowired
    private ClassroomReservationMapper reservationMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ScheduleAdjustmentService scheduleAdjustmentService;

    /**
     * 教师端：查询指定时段（星期 × 节次）未被 schedule 占用且 status='available' 的教室
     * weekDay、startPeriod、endPeriod 均可空（默认全部时段）
     * startPeriod/endPeriod 为节次段行索引（1=1-2节, 2=3-4节, 3=5-6节, 4=7-8节, 5=9-10节），与现有预约约定一致
     */
    public Map<String, Object> getEmptyClassrooms(Integer weekDay, Integer startPeriod, Integer endPeriod) {
        Map<String, Object> result = new HashMap<>();

        // 无任何过滤条件 → 直接返回所有可用教室
        if (weekDay == null && startPeriod == null && endPeriod == null) {
            List<Map<String, Object>> list = classroomMapper.getAvailableClassrooms();
            result.put("success", true);
            result.put("data", list);
            result.put("filtered", false);
            return result;
        }

        // 只给星期或只给节次都不足以判断空闲：必须同时提供星期与起止节次
        boolean anyInput = weekDay != null || startPeriod != null || endPeriod != null;
        if (anyInput && (weekDay == null || startPeriod == null || endPeriod == null)) {
            result.put("success", false);
            result.put("message", "请同时提供星期与起止节次后再查询空教室");
            return result;
        }
        if (weekDay != null && (weekDay < 1 || weekDay > 7)) {
            result.put("success", false);
            result.put("message", "星期需为 1-7");
            return result;
        }
        if (startPeriod != null && (startPeriod < 1 || startPeriod > 5 || endPeriod < 1 || endPeriod > 5)) {
            result.put("success", false);
            result.put("message", "节次段需为 1-5（对应 1-10 节）");
            return result;
        }

        // 节次段行索引 → 实际节次（schedule 表存实际节次 1-10）
        Integer actualStart = null;
        Integer actualEnd = null;
        if (startPeriod != null) {
            actualStart = (startPeriod - 1) * 2 + 1;   // 1→1, 2→3, 3→5, 4→7, 5→9
        }
        if (endPeriod != null) {
            actualEnd = endPeriod * 2;                  // 1→2, 2→4, 3→6, 4→8, 5→10
        }
        // 校验节次范围合理性
        if (actualStart != null && actualEnd != null && actualStart > actualEnd) {
            result.put("success", false);
            result.put("message", "开始节次不能大于结束节次");
            return result;
        }

        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT c.id, c.classroom_no, c.building, c.capacity, c.room_type " +
                    "FROM classroom c WHERE c.status = 'available'");
            List<Object> args = new java.util.ArrayList<>();
            boolean hasScheduleFilter = weekDay != null && actualStart != null && actualEnd != null;
            sql.append(" ORDER BY c.building, c.classroom_no");
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());

            // 有时段过滤时标注可用性 + 占用来源，空闲在前占用在后
            if (hasScheduleFilter) {
                Map<Long, String> schedMap = new java.util.HashMap<>();
                Map<Long, String> adjMap = new java.util.HashMap<>();
                Map<Long, String> resvMap = new java.util.HashMap<>();

                // 已批准调课：目标时段占用目标教室；原时段在下方课表占用中剔除（与课表展示层口径一致）
                java.util.Set<Long> hiddenScheduleIds = new java.util.HashSet<>();
                collectAdjustmentOccupancy(weekDay, actualStart, actualEnd, adjMap, hiddenScheduleIds);

                Integer currentWeek = currentWeekNumber();
                String schedSql = "SELECT s.id, s.classroom_id, s.week_range, c.course_name FROM schedule s " +
                        "LEFT JOIN course c ON s.course_id = c.id " +
                        "WHERE s.classroom_id IS NOT NULL AND s.week_day = ? AND s.start_period < ? AND s.end_period > ?";
                String semester = currentSemesterName();
                if (!semester.isEmpty()) {
                    schedSql += " AND (s.semester = ? OR s.semester IS NULL OR s.semester = '')";
                }
                List<Map<String, Object>> schedRows = semester.isEmpty()
                        ? jdbcTemplate.queryForList(schedSql, weekDay, actualEnd, actualStart)
                        : jdbcTemplate.queryForList(schedSql, weekDay, actualEnd, actualStart, semester);
                for (Map<String, Object> r : schedRows) {
                    // 周次过滤：仅当前教学周实际生效的课程占用教室
                    if (currentWeek != null && !isWeekInRange(currentWeek, weekRangeOf(r))) {
                        continue;
                    }
                    Object sid = r.get("id");
                    if (sid instanceof Number && hiddenScheduleIds.contains(((Number) sid).longValue())) {
                        continue;
                    }
                    Long cid = ((Number) r.get("classroom_id")).longValue();
                    String cn = r.get("course_name") == null ? "" : r.get("course_name").toString();
                    schedMap.putIfAbsent(cid, cn);
                }

                // 已批准预约：按具体日期存储，教师端无具体日期，按「学期周次范围内 + 星期映射」判定占用
                collectReservationOccupancy(weekDay, startPeriod, endPeriod, null, resvMap);

                List<Map<String, Object>> free = new java.util.ArrayList<>();
                List<Map<String, Object>> occupied = new java.util.ArrayList<>();
                for (Map<String, Object> row : rows) {
                    Long cid = ((Number) row.get("id")).longValue();
                    String reason = null;
                    if (schedMap.containsKey(cid)) {
                        String cn = schedMap.get(cid);
                        reason = (cn != null && !cn.isEmpty()) ? "已被《" + cn + "》占用" : "已有排课";
                    } else if (adjMap.containsKey(cid)) {
                        reason = adjMap.get(cid);
                    } else if (resvMap.containsKey(cid)) {
                        reason = resvMap.get(cid);
                    }
                    if (reason != null) {
                        row.put("available", false);
                        row.put("occupiedReason", reason);
                        occupied.add(row);
                    } else {
                        row.put("available", true);
                        free.add(row);
                    }
                }
                rows = new java.util.ArrayList<>();
                rows.addAll(free);
                rows.addAll(occupied);
            }
            result.put("success", true);
            result.put("data", rows);
            result.put("filtered", hasScheduleFilter);
            result.put("weekDay", weekDay);
            result.put("startPeriod", startPeriod);
            result.put("endPeriod", endPeriod);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "查询失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 收集「已批准调课」造成的教室占用，口径与空教室查询的课表占用一致（不按周过滤，只看星期 × 节次重叠）：
     * 目标时段占用目标教室（换教室取 target_classroom_id，否则沿用原教室）。
     * 仅当调课未同步到 schedule（历史数据，schedule 行仍在原时段）时，才把原时段对应的课表条目
     * 加入 hiddenScheduleIds 释放原教室占用；已同步的 schedule 行时段即目标时段，本身就是真实占用，不隐藏。
     */
    void collectAdjustmentOccupancy(Integer weekDay, Integer actualStart, Integer actualEnd,
                                    Map<Long, String> occupied, java.util.Set<Long> hiddenScheduleIds) {
        String semester = currentSemesterName();
        String sql = "SELECT a.schedule_id, a.orig_week_day, a.orig_start_period, a.orig_end_period, " +
                "a.target_week_day, a.target_start_period, a.target_end_period, a.course_name, a.week_range, " +
                "a.change_classroom, a.target_classroom_id, a.target_schedule_id, s.classroom_id AS orig_classroom_id, " +
                "s.week_day, s.start_period, s.end_period " +
                "FROM schedule_adjustment a LEFT JOIN schedule s ON a.schedule_id = s.id " +
                "WHERE a.status = 'approved'";
        if (!semester.isEmpty()) {
            sql += " AND (a.semester = ? OR a.semester IS NULL OR a.semester = '')";
        }
        List<Map<String, Object>> adjustments = semester.isEmpty()
                ? jdbcTemplate.queryForList(sql)
                : jdbcTemplate.queryForList(sql, semester);
        Integer currentWeek = currentWeekNumber();
        for (Map<String, Object> r : adjustments) {
            // 已物化为 schedule 行的调课（跨周 / 部分周次拆分）由课表行本身表达占用，无需再叠加
            if (r.get("target_schedule_id") != null) {
                continue;
            }
            // 周次过滤：仅当前教学周实际生效的调课参与占用判断
            if (currentWeek != null && !isWeekInRange(currentWeek, weekRangeOf(r))) {
                continue;
            }
            // 已同步（schedule 行时段 == 目标时段）：schedule 行即目标占用，无需隐藏原时段
            boolean synced = intOrNull(r.get("week_day")) != null
                    && intOrNull(r.get("week_day")).equals(intOrNull(r.get("target_week_day")))
                    && intOrNull(r.get("start_period")).equals(intOrNull(r.get("target_start_period")))
                    && intOrNull(r.get("end_period")).equals(intOrNull(r.get("target_end_period")));
            if (!synced && weekDay != null
                    && weekDay.equals(intOrNull(r.get("orig_week_day")))
                    && periodOverlap(intOrNull(r.get("orig_start_period")), intOrNull(r.get("orig_end_period")),
                    actualStart, actualEnd)) {
                Object sid = r.get("schedule_id");
                if (sid instanceof Number) {
                    hiddenScheduleIds.add(((Number) sid).longValue());
                }
            }
            Integer day = intOrNull(r.get("target_week_day"));
            if (weekDay == null || day == null || !day.equals(weekDay)) {
                continue;
            }
            if (!periodOverlap(intOrNull(r.get("target_start_period")), intOrNull(r.get("target_end_period")),
                    actualStart, actualEnd)) {
                continue;
            }
            Long roomId = null;
            Integer changed = intOrNull(r.get("change_classroom"));
            if (changed != null && changed == 1 && r.get("target_classroom_id") != null) {
                roomId = ((Number) r.get("target_classroom_id")).longValue();
            } else if (r.get("orig_classroom_id") != null) {
                roomId = ((Number) r.get("orig_classroom_id")).longValue();
            }
            if (roomId == null) {
                continue;
            }
            String cn = r.get("course_name") == null ? "" : r.get("course_name").toString();
            occupied.putIfAbsent(roomId, cn.isEmpty() ? "已被调课占用" : "已被《" + cn + "》调课占用");
        }
    }

    /**
     * 收集「已批准或待审核预约」造成的教室占用（待审预约同样占用时段，避免列表展示可预约但提交冲突）。
     * refDate 非空：按具体日期精确匹配（学生端按日期查询）。
     * refDate 为空：预约按具体日期存储而查询只给星期，故把落在当前学期周次范围内、
     *              且日期对应星期与查询星期一致的预约映射为占用（教师端按星期查询）。
     * 预约的 start_period/end_period 为节次段索引（1-5），因此与查询的节次段比较。
     */
    void collectReservationOccupancy(Integer weekDay, Integer startSeg, Integer endSeg,
                                     LocalDate refDate, Map<Long, String> occupied) {
        List<ClassroomReservation> reservations = reservationMapper.selectList(
                new LambdaQueryWrapper<ClassroomReservation>()
                        .in(ClassroomReservation::getStatus, "approved", "pending"));
        LocalDate[] term = refDate == null ? termDateRange() : null;
        for (ClassroomReservation r : reservations) {
            LocalDate d = r.getReservationDate();
            if (d == null || r.getClassroomId() == null) {
                continue;
            }
            if (refDate != null) {
                if (!refDate.equals(d)) {
                    continue;
                }
            } else {
                if (weekDay == null || d.getDayOfWeek().getValue() != weekDay) {
                    continue;
                }
                if (term != null && (d.isBefore(term[0]) || d.isAfter(term[1]))) {
                    continue;
                }
            }
            if (!periodOverlap(r.getStartPeriod(), r.getEndPeriod(), startSeg, endSeg)) {
                continue;
            }
            String person = r.getStudentName() == null ? "" : r.getStudentName().trim();
            occupied.putIfAbsent(r.getClassroomId(),
                    person.isEmpty() ? "已被预约占用" : "已被预约占用（预约人：" + person + "）");
        }
    }

    /**
     * 预约提交/审批前校验：目标教室在指定日期的节次段（段索引 1-5，分别对应 1-2 … 9-10 节）
     * 是否被已批准调课或课表占用。按学期周次精确过滤；日期不在当前学期内时不按课表拦截。
     * 返回冲突说明；无冲突返回 null。
     */
    public String findScheduleConflict(Long classroomId, LocalDate date, int startSeg, int endSeg) {
        if (classroomId == null || date == null || startSeg < 1 || endSeg > 5 || startSeg > endSeg) {
            return null;
        }
        LocalDate[] term = termDateRange();
        if (term == null || date.isBefore(term[0]) || date.isAfter(term[1])) {
            return null;
        }
        int week = (int) java.time.temporal.ChronoUnit.WEEKS.between(term[0], date) + 1;
        int weekDay = date.getDayOfWeek().getValue();
        int actualStart = (startSeg - 1) * 2 + 1;
        int actualEnd = endSeg * 2;

        // 已批准调课：换入目标教室判占用；换出目标教室则释放其原课表行
        String semester = currentSemesterName();
        String adjSql = "SELECT a.schedule_id, a.course_name, a.week_range, a.change_classroom, " +
                "a.target_classroom_id, s.classroom_id AS orig_classroom_id " +
                "FROM schedule_adjustment a LEFT JOIN schedule s ON s.id = a.schedule_id " +
                "WHERE a.status = 'approved' AND a.target_schedule_id IS NULL AND a.target_week_day = ? " +
                "AND a.target_start_period < ? AND a.target_end_period > ?";
        if (!semester.isEmpty()) {
            adjSql += " AND (a.semester = ? OR a.semester IS NULL OR a.semester = '')";
        }
        List<Map<String, Object>> adjustments = semester.isEmpty()
                ? jdbcTemplate.queryForList(adjSql, weekDay, actualEnd, actualStart)
                : jdbcTemplate.queryForList(adjSql, weekDay, actualEnd, actualStart, semester);
        java.util.Set<Long> hiddenScheduleIds = new java.util.HashSet<>();
        for (Map<String, Object> a : adjustments) {
            if (!isWeekInRange(week, weekRangeOf(a))) {
                continue;
            }
            Long targetRoom = null;
            Integer changed = intOrNull(a.get("change_classroom"));
            if (changed != null && changed == 1 && a.get("target_classroom_id") != null) {
                targetRoom = ((Number) a.get("target_classroom_id")).longValue();
            } else if (a.get("orig_classroom_id") != null) {
                targetRoom = ((Number) a.get("orig_classroom_id")).longValue();
            }
            if (targetRoom == null) {
                continue;
            }
            if (targetRoom.equals(classroomId)) {
                String cn = a.get("course_name") == null ? "" : a.get("course_name").toString();
                return "该时段教室已被调课占用：" + (cn.isEmpty() ? "已调课程" : cn);
            }
            if (a.get("orig_classroom_id") != null
                    && ((Number) a.get("orig_classroom_id")).longValue() == classroomId
                    && a.get("schedule_id") instanceof Number) {
                hiddenScheduleIds.add(((Number) a.get("schedule_id")).longValue());
            }
        }

        // 课表占用（周次、星期、节次三重相交；按当前学期过滤，历史空学期条目视为有效）
        String schedSql = "SELECT s.id, c.course_name, cl.class_name, s.teacher_name, s.week_range " +
                "FROM schedule s LEFT JOIN course c ON c.id = s.course_id " +
                "LEFT JOIN class_info cl ON cl.id = s.class_id " +
                "WHERE s.classroom_id = ? AND s.week_day = ? AND s.start_period < ? AND s.end_period > ?";
        if (!semester.isEmpty()) {
            schedSql += " AND (s.semester = ? OR s.semester IS NULL OR s.semester = '')";
        }
        List<Map<String, Object>> rows = semester.isEmpty()
                ? jdbcTemplate.queryForList(schedSql, classroomId, weekDay, actualEnd, actualStart)
                : jdbcTemplate.queryForList(schedSql, classroomId, weekDay, actualEnd, actualStart, semester);
        for (Map<String, Object> r : rows) {
            Object sid = r.get("id");
            if (sid instanceof Number && hiddenScheduleIds.contains(((Number) sid).longValue())) {
                continue;
            }
            if (!isWeekInRange(week, weekRangeOf(r))) {
                continue;
            }
            String cn = r.get("course_name") == null ? "" : r.get("course_name").toString();
            String cls = r.get("class_name") == null ? "" : r.get("class_name").toString();
            String teacher = r.get("teacher_name") == null ? "" : r.get("teacher_name").toString();
            StringBuilder msg = new StringBuilder("该时段教室已被排课占用：")
                    .append(cn.isEmpty() ? "课程" : cn);
            if (!cls.isEmpty() || !teacher.isEmpty()) {
                msg.append("（").append(cls);
                if (!teacher.isEmpty()) {
                    msg.append(cls.isEmpty() ? "" : " ").append(teacher);
                }
                msg.append("）");
            }
            return msg.toString();
        }
        return null;
    }

    /** 当前学期名称（semester_config 最新一条）；未配置时返回空串，表示不按学期过滤 */
    private String currentSemesterName() {
        try {
            String name = jdbcTemplate.queryForObject(
                    "SELECT semester_name FROM semester_config ORDER BY id DESC LIMIT 1", String.class);
            return name == null ? "" : name.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 当前学期起止日期（semester_config 最新一条，按周一对齐）；无配置时返回 null */
    private LocalDate[] termDateRange() {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT start_date, weeks FROM semester_config ORDER BY id DESC LIMIT 1");
            Object sd = row.get("start_date");
            Object wk = row.get("weeks");
            if (sd == null || wk == null) {
                return null;
            }
            LocalDate start = sd instanceof java.sql.Date
                    ? ((java.sql.Date) sd).toLocalDate() : LocalDate.parse(sd.toString());
            int weeks = ((Number) wk).intValue();
            if (weeks <= 0) {
                return null;
            }
            LocalDate startMonday = start.with(java.time.DayOfWeek.MONDAY);
            return new LocalDate[]{startMonday, startMonday.plusWeeks(weeks).minusDays(1)};
        } catch (Exception e) {
            return null;
        }
    }

    /** 当前教学周（按学期起始日计算）；未配置学期或不在学期内时返回 null，表示不按周次过滤 */
    private Integer currentWeekNumber() {
        LocalDate[] term = termDateRange();
        if (term == null) {
            return null;
        }
        LocalDate today = LocalDate.now();
        if (today.isBefore(term[0]) || today.isAfter(term[1])) {
            return null;
        }
        return (int) (java.time.temporal.ChronoUnit.DAYS.between(term[0], today) / 7) + 1;
    }

    /** 闭区间节次重叠判断；任一参数为空视为不重叠 */
    private boolean periodOverlap(Integer s1, Integer e1, Integer s2, Integer e2) {
        if (s1 == null || e1 == null || s2 == null || e2 == null) {
            return false;
        }
        return s1 <= e2 && e1 >= s2;
    }

    private Integer intOrNull(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : null;
    }

    /**
     * 管理端：单个教室一周占用明细。
     * 口径与空教室查询一致：只统计 schedule 表中 classroom_id 等于该教室的条目；
     * 无教室课程（classroom_id IS NULL，体育课等）不参与占用。
     * 叠加已批准预约（classroom_reservation status='approved'）作为额外占用来源，
     * 通过 source 字段标注来源（schedule=课表 / reservation=预约）与预约人。
     */
    public Map<String, Object> getClassroomOccupancy(Long classroomId, Integer week) {
        Map<String, Object> result = new HashMap<>();
        Classroom classroom = classroomMapper.selectById(classroomId);
        if (classroom == null) {
            result.put("success", false);
            result.put("message", "教室不存在");
            return result;
        }
        result.put("success", true);
        Map<String, Object> info = new HashMap<>();
        info.put("id", classroom.getId());
        info.put("classroom_no", classroom.getClassroomNo());
        info.put("building", classroom.getBuilding());
        info.put("status", classroom.getStatus());
        result.put("classroom", info);
        result.put("occupancy", collectOccupancy(classroom.getId(), week));
        return result;
    }

    /**
     * 管理端：全部教室一周占用概览（schedule 来源条目 + 占用节次数）。
     * 无课表占用的教室返回空数组，不报错。
     */
    public Map<String, Object> getAllClassroomsOccupancy(Integer week) {
        Map<String, Object> result = new HashMap<>();
        List<Classroom> classrooms = classroomMapper.selectList(
                new LambdaQueryWrapper<Classroom>()
                        .orderByAsc(Classroom::getBuilding)
                        .orderByAsc(Classroom::getClassroomNo));
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (Classroom classroom : classrooms) {
            List<Map<String, Object>> occupancy = collectOccupancy(classroom.getId(), week);
            long schedulePeriods = occupancy.stream()
                    .filter(o -> "schedule".equals(o.get("source")))
                    .mapToLong(o -> {
                        int start = Integer.parseInt(String.valueOf(o.get("startPeriod")));
                        int end = Integer.parseInt(String.valueOf(o.get("endPeriod")));
                        return Math.max(0, end - start + 1);
                    }).sum();
            Map<String, Object> item = new HashMap<>();
            item.put("id", classroom.getId());
            item.put("classroom_no", classroom.getClassroomNo());
            item.put("building", classroom.getBuilding());
            item.put("schedulePeriods", schedulePeriods);
            item.put("occupancy", occupancy);
            list.add(item);
        }
        result.put("success", true);
        result.put("data", list);
        return result;
    }

    /** 取周次字段：NULL 保持为 null（视为不限周次），避免 String.valueOf(null) 变成 "null" 被误过滤 */
    private static String weekRangeOf(Map<String, Object> row) {
        return weekRangeOf(row, "week_range");
    }

    private static String weekRangeOf(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString();
    }

    /** 判断某周是否在 weekRange 范围内（支持 "1-8"、"10"、"3,5,7" 及 单周/双周 等写法） */
    private boolean isWeekInRange(Integer week, String weekRange) {
        if (week == null || weekRange == null || weekRange.trim().isEmpty()) {
            return true; // 未指定周次或范围为空时默认显示
        }
        return WeekRangeParser.contains(weekRange, week);
    }

    /** 收集单个教室的占用条目：课表占用（按星期、节次排序）+ 已批准预约占用（按日期排序） */
    private List<Map<String, Object>> collectOccupancy(Long classroomId, Integer week) {
        List<Map<String, Object>> list = new java.util.ArrayList<>();

        // 生效中的调课：未同步的历史数据行原时段隐藏、目标时段叠加占用；已同步的行时段即目标时段，直接显示并打标
        List<Map<String, Object>> adjustments = scheduleAdjustmentService.getActiveAdjustmentsForClassroom(classroomId, week);
        java.util.Set<Long> hiddenIds = new java.util.HashSet<>();
        for (Map<String, Object> adj : adjustments) {
            if (Boolean.TRUE.equals(adj.get("slotSynced"))) {
                continue;
            }
            Object sid = adj.get("scheduleId");
            if (sid instanceof Number) {
                hiddenIds.add(((Number) sid).longValue());
            }
        }

        // 原教室视角：本教室的课表条目只要存在已批准调课，原时段同样从本教室明细中释放。
        // getActiveAdjustmentsForClassroom 按「生效教室」匹配，换教室时只能查到目标教室，
        // 原教室拿不到该调课，故这里额外按 s.classroom_id = 当前教室 补一次隐藏（仅未同步的历史数据行）。
        List<Map<String, Object>> movedRows = jdbcTemplate.queryForList(
                "SELECT s.id AS schedule_id, a.week_range AS adj_week_range, " +
                "a.orig_week_day, a.orig_start_period, a.orig_end_period, " +
                "a.target_week_day, a.target_start_period, a.target_end_period, " +
                "s.week_day, s.start_period, s.end_period " +
                "FROM schedule s JOIN schedule_adjustment a ON a.schedule_id = s.id " +
                "WHERE a.status = 'approved' AND a.target_schedule_id IS NULL AND s.classroom_id = ?", classroomId);

        for (Map<String, Object> moved : movedRows) {
            // 已同步（schedule 行时段 == 目标时段）：该行即目标占用，不再隐藏
            boolean synced = intOrNull(moved.get("week_day")) != null
                    && intOrNull(moved.get("week_day")).equals(intOrNull(moved.get("target_week_day")))
                    && intOrNull(moved.get("start_period")).equals(intOrNull(moved.get("target_start_period")))
                    && intOrNull(moved.get("end_period")).equals(intOrNull(moved.get("target_end_period")));
            if (synced) {
                continue;
            }
            if (week != null && !isWeekInRange(week, weekRangeOf(moved, "adj_week_range"))) {
                continue;
            }
            Object movedId = moved.get("schedule_id");
            if (movedId instanceof Number) {
                hiddenIds.add(((Number) movedId).longValue());
            }
        }

        // 课表占用：schedule LEFT JOIN course、class_info，按星期 × 节次排序（按当前学期过滤）
        String semester = currentSemesterName();
        String occSql = "SELECT s.id, s.week_day, s.start_period, s.end_period, c.course_name, cl.class_name, " +
                "s.teacher_name, s.week_range, s.semester " +
                "FROM schedule s LEFT JOIN course c ON s.course_id = c.id " +
                "LEFT JOIN class_info cl ON s.class_id = cl.id " +
                "WHERE s.classroom_id = ?";
        if (!semester.isEmpty()) {
            occSql += " AND (s.semester = ? OR s.semester IS NULL OR s.semester = '')";
        }
        occSql += " ORDER BY s.week_day, s.start_period";
        List<Map<String, Object>> rows = semester.isEmpty()
                ? jdbcTemplate.queryForList(occSql, classroomId)
                : jdbcTemplate.queryForList(occSql, classroomId, semester);
        for (Map<String, Object> row : rows) {
            // 按周次过滤
            if (week != null && !isWeekInRange(week, weekRangeOf(row))) {
                continue;
            }
            // 被调课的原时段不再显示该课程占用（不修改原课表数据）
            Object sid = row.get("id");
            if (sid instanceof Number && hiddenIds.contains(((Number) sid).longValue())) {
                continue;
            }
            Map<String, Object> item = new HashMap<>();
            item.put("source", "schedule");
            item.put("sourceText", "课表");
            item.put("weekDay", row.get("week_day"));
            item.put("startPeriod", row.get("start_period"));
            item.put("endPeriod", row.get("end_period"));
            item.put("courseName", row.get("course_name"));
            item.put("className", row.get("class_name"));
            item.put("teacherName", row.get("teacher_name"));
            item.put("weekRange", row.get("week_range"));
            item.put("semester", row.get("semester"));
            // 已同步的调课行（时段即目标时段）保留显示并打 isAdjustment 标，与叠加补偿显示结果一致
            Object finalSid = sid;
            if (sid instanceof Number && adjustments.stream().anyMatch(adj ->
                    Boolean.TRUE.equals(adj.get("slotSynced"))
                            && finalSid.equals(adj.get("scheduleId")))) {
                item.put("isAdjustment", true);
            }
            list.add(item);
        }

        // 叠加调课结果：目标时段显示该课程占用（已同步的行已由原行打标，跳过避免重复）
        for (Map<String, Object> adj : adjustments) {
            if (Boolean.TRUE.equals(adj.get("slotSynced"))) {
                continue;
            }
            Map<String, Object> item = new HashMap<>();
            item.put("source", "schedule");
            item.put("sourceText", "课表");
            item.put("weekDay", adj.get("targetWeekDay"));
            item.put("startPeriod", adj.get("targetStartPeriod"));
            item.put("endPeriod", adj.get("targetEndPeriod"));
            item.put("courseName", adj.get("courseName"));
            item.put("className", adj.get("className"));
            item.put("teacherName", adj.get("teacherName"));
            item.put("weekRange", adj.get("weekRange"));
            item.put("semester", adj.get("semester"));
            item.put("isAdjustment", true);
            list.add(item);
        }

        // 已批准预约占用（具体日期，非每周固定）
        List<ClassroomReservation> reservations = reservationMapper.selectList(
                new LambdaQueryWrapper<ClassroomReservation>()
                        .eq(ClassroomReservation::getClassroomId, classroomId)
                        .eq(ClassroomReservation::getStatus, "approved")
                        .orderByAsc(ClassroomReservation::getReservationDate)
                        .orderByAsc(ClassroomReservation::getStartPeriod));
        for (ClassroomReservation r : reservations) {
            Map<String, Object> item = new HashMap<>();
            item.put("source", "reservation");
            item.put("sourceText", "预约");
            item.put("reservationDate", r.getReservationDate());
            item.put("startPeriod", r.getStartPeriod());
            item.put("endPeriod", r.getEndPeriod());
            item.put("person", r.getStudentName());
            item.put("reason", r.getReason());
            list.add(item);
        }
        return list;
    }
}
