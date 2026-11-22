package com.zhixiaogang.service;

import com.zhixiaogang.mapper.ScheduleMapper;
import com.zhixiaogang.service.notification.NotificationService;
import com.zhixiaogang.util.WeekRangeParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.zhixiaogang.service.AdminClassSupport.*;

/********** 班级课表域：班级详情内课表列表、手动增删改、表单校验与周次冲突调度 **********/
@Service
public class AdminClassScheduleService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ScheduleMapper scheduleMapper;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private AdminClassScheduleConflictChecker scheduleConflictChecker;

    @Autowired
    private com.zhixiaogang.service.adjustment.ScheduleAdjustmentService scheduleAdjustmentService;

    /** 班级课表列表：按星期、节次排序（全量） */
    public Map<String, Object> listSchedule(Long classId) {
        return listSchedule(classId, null);
    }

    /**
     * 班级课表列表：week 为空时保持全量；指定周次时只返回该周可见行。
     * 生效调课按周次范围参与过滤：未同步到课表的历史调课隐藏原时段，已同步行按目标时段显示。
     */
    public Map<String, Object> listSchedule(Long classId, Integer week) {
        Map<String, Object> result = new HashMap<>();
        try {
            String semester = currentSemesterName(jdbcTemplate);
            String sql = "SELECT s.id, s.week_day, s.start_period, s.end_period, s.week_range, s.semester, " +
                    "s.teacher_name, s.teacher_id, " +
                    "c.course_name, c.course_code, " +
                    "cl.classroom_no, cl.building " +
                    "FROM schedule s " +
                    "JOIN course c ON s.course_id = c.id " +
                    "LEFT JOIN classroom cl ON s.classroom_id = cl.id " +
                    "WHERE s.class_id = ? " +
                    (semester.isEmpty() ? "" : "AND (s.semester = ? OR s.semester IS NULL OR s.semester = '') ") +
                    "ORDER BY s.week_day, s.start_period";
            List<Map<String, Object>> rows = semester.isEmpty()
                    ? jdbcTemplate.queryForList(sql, classId)
                    : jdbcTemplate.queryForList(sql, classId, semester);

            // 指定周次时：生效调课按周次过滤；未同步的历史调课隐藏原时段（与三端课表口径一致）
            java.util.Set<Long> hiddenIds = new java.util.HashSet<>();
            if (week != null) {
                for (Map<String, Object> adj : scheduleAdjustmentService.getActiveAdjustmentsForClass(classId, week)) {
                    if (Boolean.TRUE.equals(adj.get("slotSynced"))) {
                        continue;
                    }
                    Object sid = adj.get("scheduleId");
                    if (sid instanceof Number) {
                        hiddenIds.add(((Number) sid).longValue());
                    }
                }
            }

            List<Map<String, Object>> schedule = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                if (week != null) {
                    String weekRange = row.get("week_range") == null ? null : row.get("week_range").toString();
                    if (!WeekRangeParser.contains(weekRange, week)) {
                        continue;
                    }
                    Object sid = row.get("id");
                    if (sid instanceof Number && hiddenIds.contains(((Number) sid).longValue())) {
                        continue;
                    }
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", row.get("id"));
                item.put("weekDay", row.get("week_day"));
                item.put("startPeriod", row.get("start_period"));
                item.put("endPeriod", row.get("end_period"));
                item.put("weekRange", row.get("week_range"));
                item.put("semester", row.get("semester"));
                item.put("teacherName", row.get("teacher_name"));
                item.put("courseName", row.get("course_name"));
                item.put("courseCode", row.get("course_code"));
                Object classroomNo = row.get("classroom_no");
                item.put("classroomNo", classroomNo != null ? classroomNo : NO_CLASSROOM_DISPLAY);
                item.put("building", row.get("building"));
                schedule.add(item);
            }
            result.put("success", true);
            result.put("schedule", schedule);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "课表加载失败：" + e.getMessage());
        }
        return result;
    }

    /** 手动添加课表条目（课程不存在自动创建；同班同星期同教室时段不得重叠） */
    public Map<String, Object> addSchedule(Long classId, Map<String, Object> form) {
        Map<String, Object> validated = validateScheduleForm(classId, form, null);
        if (!Boolean.TRUE.equals(validated.get("success"))) {
            return validated;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> v = (Map<String, Object>) validated.get("validated");
        try {
            return doInsertSchedule(classId, v);
        } catch (Exception e) {
            return fail("课表条目添加失败：" + rootMessage(e));
        }
    }

    /** 修改课表条目（排除自身后做冲突校验；存在生效调课时禁止修改时段，避免与调课 orig_* 快照失真） */
    public Map<String, Object> updateSchedule(Long classId, Long sid, Map<String, Object> form) {
        Integer adjCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schedule_adjustment WHERE schedule_id = ? AND status = 'approved'",
                Integer.class, sid);
        if (adjCount != null && adjCount > 0) {
            return fail("该课表条目存在生效中的调课，请先撤销调课后再修改");
        }
        Map<String, Object> validated = validateScheduleForm(classId, form, sid);
        if (!Boolean.TRUE.equals(validated.get("success"))) {
            return validated;
        }
        Integer owned = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schedule WHERE id = ? AND class_id = ?", Integer.class, sid, classId);
        if (owned == null || owned == 0) {
            return fail("课表条目不存在或不属于该班级");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> v = (Map<String, Object>) validated.get("validated");
        try {
            jdbcTemplate.update(
                    "UPDATE schedule SET course_id = ?, classroom_id = ?, teacher_name = ?, teacher_id = ?, " +
                            "week_day = ?, start_period = ?, end_period = ?, week_range = ?, semester = ? WHERE id = ?",
                    v.get("courseId"), v.get("classroomId"), v.get("teacherName"), v.get("teacherId"),
                    v.get("weekDay"), v.get("startPeriod"), v.get("endPeriod"),
                    v.get("weekRange"), v.get("semester"), sid);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "课表条目修改成功");
            return result;
        } catch (Exception e) {
            return fail("课表条目修改失败：" + rootMessage(e));
        }
    }

    /** 删除课表条目（存在生效调课时禁止删除，避免调课记录指向已删除条目、orig_* 快照失真） */
    public Map<String, Object> deleteSchedule(Long classId, Long sid) {
        try {
            Integer owned = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM schedule WHERE id = ? AND class_id = ?", Integer.class, sid, classId);
            if (owned == null || owned == 0) {
                return fail("课表条目不存在或不属于该班级");
            }
            Integer adjCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM schedule_adjustment WHERE schedule_id = ? AND status = 'approved'",
                    Integer.class, sid);
            if (adjCount != null && adjCount > 0) {
                return fail("该课表条目存在生效中的调课，请先撤销调课后再删除");
            }
            scheduleMapper.deleteById(sid);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "课表条目已删除");
            return result;
        } catch (Exception e) {
            return fail("课表条目删除失败：" + rootMessage(e));
        }
    }

    /** 课表表单校验：字段合法性 + 周次解析 + 教室选填（填了则须存在）+ 周次交集冲突检查 */
    private Map<String, Object> validateScheduleForm(Long classId, Map<String, Object> form, Long excludeScheduleId) {
        Map<String, Object> cls = queryClass(jdbcTemplate, classId);
        if (cls == null) {
            return fail("班级不存在");
        }

        Integer weekDay = parseInt(form.get("weekDay"));
        Integer startPeriod = parseInt(form.get("startPeriod"));
        Integer endPeriod = parseInt(form.get("endPeriod"));
        String courseName = form.get("courseName") != null ? form.get("courseName").toString().trim() : "";
        String classroomNo = form.get("classroomNo") != null ? form.get("classroomNo").toString().trim() : "";
        String teacherName = form.get("teacherName") != null ? form.get("teacherName").toString().trim() : "";
        String weekRange = form.get("weekRange") != null ? form.get("weekRange").toString().trim() : "";
        String semester = form.get("semester") != null ? form.get("semester").toString().trim() : "";

        if (weekDay == null || weekDay < 1 || weekDay > 7) {
            return fail("星期需为 1-7");
        }
        // 节次口径统一为 1-10（与学生课表 5 个节次段、预约/空教室查询一致；调课校验同样限制 1-10）
        if (startPeriod == null || endPeriod == null || startPeriod < 1 || endPeriod > 10) {
            return fail("节次需为 1-10 的数字");
        }
        if (startPeriod > endPeriod) {
            return fail("开始节次不能大于结束节次");
        }
        if (courseName.isEmpty()) {
            return fail("请填写课程名称");
        }

        // 周次统一归一化为数字格式；非法写法直接拒绝，避免周次集合失真影响冲突判定
        String normalizedWeekRange = "";
        if (!weekRange.isEmpty()) {
            WeekRangeParser.Result weekParsed = WeekRangeParser.parse(weekRange);
            if (!weekParsed.valid) {
                return fail("周次范围无法解析：" + weekParsed.error);
            }
            normalizedWeekRange = weekParsed.normalized;
        }

        // 教室选填：为空或为约定值（无教室/室外/操场等）时置 null，体育课等无教室课程不占用教室
        Long classroomId = null;
        if (!isNoClassroom(classroomNo)) {
            classroomId = queryId(jdbcTemplate, "SELECT id FROM classroom WHERE classroom_no = ?", classroomNo);
            if (classroomId == null) {
                return fail("教室「" + classroomNo + "」不存在");
            }
        }

        // 学期：未填写时默认当前学期，避免新课表落到空学期或历史学期
        String effectiveSemester = semester.isEmpty() ? currentSemesterName(jdbcTemplate) : semester;

        // 教师姓名默认班级管理教师（用于教师冲突校验）
        if (teacherName.isEmpty()) {
            Object teacher = cls.get("teacher_name");
            teacherName = teacher != null ? teacher.toString() : "";
        }

        // 冲突校验：同班级同星期节次重叠时，逐一比对周次集合；无交集允许同时段并存，有交集才阻止保存
        Map<String, Object> conflict = scheduleConflictChecker.check(
                classId, weekDay, startPeriod, endPeriod, normalizedWeekRange, effectiveSemester, excludeScheduleId);
        if (conflict != null) {
            return conflict;
        }

        // 教室 / 教师冲突校验：跨班级检查同时段是否已有其他课程占用同一教室或同一教师
        String roomTeacherConflict = scheduleConflictChecker.checkRoomAndTeacher(
                classroomId, teacherName, weekDay, startPeriod, endPeriod, normalizedWeekRange,
                effectiveSemester, excludeScheduleId);
        if (roomTeacherConflict != null) {
            return fail(roomTeacherConflict);
        }

        // 课程不存在自动创建
        Long courseId = queryId(jdbcTemplate, "SELECT id FROM course WHERE course_name = ?", courseName);
        boolean courseCreated = false;
        if (courseId == null) {
            String dept = queryString(jdbcTemplate, "SELECT department FROM class_info WHERE id = ?", classId);
            String code = generateCourseCode(courseName);
            jdbcTemplate.update(
                    "INSERT INTO course (course_code, course_name, department, status) VALUES (?, ?, ?, 1)",
                    code, courseName, dept);
            courseId = queryId(jdbcTemplate, "SELECT id FROM course WHERE course_name = ?", courseName);
            courseCreated = true;
        }

        // teacher_id 按姓名解析（无账号自动创建）
        String teacherId = null;
        boolean teacherCreated = false;
        String teacherInitialPassword = null;
        if (!teacherName.isEmpty()) {
            String dept = cls.get("department") != null ? cls.get("department").toString() : "";
            StringBuilder errorOut = new StringBuilder();
            Map<String, Object> resolved = resolveTeacherAccount(jdbcTemplate, teacherName, dept, new HashMap<>(), errorOut);
            if (resolved.get("error") != null) {
                return fail(String.valueOf(resolved.get("error")));
            }
            teacherId = (String) resolved.get("teacherId");
            teacherCreated = Boolean.TRUE.equals(resolved.get("created"));
            teacherInitialPassword = (String) resolved.get("initialPassword");
        }

        Map<String, Object> v = new HashMap<>();
        v.put("courseId", courseId);
        v.put("courseName", courseName);
        v.put("courseCreated", courseCreated);
        v.put("classroomId", classroomId);
        v.put("targetClassId", classId);
        v.put("className", cls.get("class_name"));
        v.put("classroomNo", isNoClassroom(classroomNo) ? null : classroomNo);
        v.put("teacherName", teacherName.isEmpty() ? null : teacherName);
        v.put("teacherId", teacherId);
        v.put("teacherCreated", teacherCreated);
        v.put("teacherInitialPassword", teacherInitialPassword);
        v.put("weekDay", weekDay);
        v.put("startPeriod", startPeriod);
        v.put("endPeriod", endPeriod);
        v.put("weekRange", normalizedWeekRange.isEmpty() ? null : normalizedWeekRange);
        v.put("semester", effectiveSemester.isEmpty() ? null : effectiveSemester);

        Map<String, Object> ok = new HashMap<>();
        ok.put("success", true);
        ok.put("validated", v);
        return ok;
    }

    private Map<String, Object> doInsertSchedule(Long classId, Map<String, Object> v) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO schedule (student_id, course_id, classroom_id, teacher_name, teacher_id, " +
                            "week_day, start_period, end_period, week_range, semester, class_id) " +
                            "VALUES (NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, v.get("courseId"));
            ps.setObject(2, v.get("classroomId"));
            ps.setObject(3, v.get("teacherName"));
            ps.setObject(4, v.get("teacherId"));
            ps.setObject(5, v.get("weekDay"));
            ps.setObject(6, v.get("startPeriod"));
            ps.setObject(7, v.get("endPeriod"));
            ps.setObject(8, v.get("weekRange"));
            ps.setObject(9, v.get("semester"));
            ps.setObject(10, classId);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        Long newId = key == null ? null : key.longValue();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "课表条目添加成功");
        result.put("scheduleId", newId);
        if (Boolean.TRUE.equals(v.get("courseCreated"))) {
            result.put("message", "课表条目添加成功（已自动创建课程「" + v.get("courseName") + "」）");
        }
        if (Boolean.TRUE.equals(v.get("teacherCreated"))) {
            result.put("message", result.get("message") + "（已自动创建教师账号 " + v.get("teacherId")
                    + "，初始口令 " + v.get("teacherInitialPassword") + "，请转告教师本人）");
        }
        // 写入成功即向受影响班级学生与授课教师发布课表通知
        notificationService.onSchedulePublished(List.of(buildSchedulePub(v)));
        return result;
    }
}
