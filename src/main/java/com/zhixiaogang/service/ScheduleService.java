package com.zhixiaogang.service;

import com.zhixiaogang.entity.User;
import com.zhixiaogang.service.adjustment.ScheduleAdjustmentService;
import com.zhixiaogang.util.WeekRangeParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 课表查询、学期过滤与课表相关业务处理。 */
@Service
public class ScheduleService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private ScheduleAdjustmentService scheduleAdjustmentService;

    public Map<String, Object> checkSchedule(String studentId) {
        Map<String, Object> result = new HashMap<>();

        User user = userService.getUserByStudentId(studentId);
        if (user == null) {
            result.put("success", false);
            result.put("message", "用户不存在");
            return result;
        }

        // 检测是否为新建账号（注册时间在最近7天内）
        LocalDateTime createTime = user.getCreateTime();
        boolean isNewUser = createTime != null && createTime.isAfter(LocalDateTime.now().minusDays(7));

        // 查询当前学生 status=approved 的班级
        Long classId = getApprovedClassId(studentId);
        boolean joinedClass = classId != null;
        boolean isEmpty = true;
        String className = null;
        if (joinedClass) {
            String semester = currentSemesterName();
            Integer count;
            if (semester.isEmpty()) {
                count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM schedule WHERE class_id = ?", Integer.class, classId);
            } else {
                // 按当前学期统计，历史空学期数据仍视为有效，避免老数据被过滤后误判为空课表
                count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM schedule WHERE class_id = ? " +
                                "AND (semester = ? OR semester IS NULL OR semester = '')",
                        Integer.class, classId, semester);
            }
            isEmpty = count == null || count == 0;
            try {
                className = jdbcTemplate.queryForObject(
                        "SELECT class_name FROM class_info WHERE id = ?", String.class, classId);
            } catch (Exception e) {
                className = null;
            }
        }

        result.put("success", true);
        result.put("joinedClass", joinedClass);
        result.put("classId", classId);
        result.put("className", className);
        result.put("isNewUser", isNewUser);
        result.put("isEmpty", isEmpty);

        return result;
    }

    /**
     * 查询当前学生所在班级某一周的课表（只读，数据由管理员在管理端统一维护）
     */
    public Map<String, Object> loadSchedule(String studentId, Integer week) {
        Map<String, Object> result = new HashMap<>();

        Long classId = getApprovedClassId(studentId);
        if (classId == null) {
            result.put("success", true);
            result.put("joinedClass", false);
            result.put("message", "请先加入班级后查看课表");
            result.put("schedule", List.of());
            return result;
        }

        try {
            String semester = currentSemesterName();
            String sql = "SELECT s.id, s.week_day, s.start_period, s.end_period, s.week_range, s.semester, s.teacher_name, " +
                    "c.course_name, c.course_code, cl.classroom_no, cl.building " +
                    "FROM schedule s " +
                    "JOIN course c ON s.course_id = c.id " +
                    "LEFT JOIN classroom cl ON s.classroom_id = cl.id " +
                    "WHERE s.class_id = ? " +
                    (semester.isEmpty() ? "" : "AND (s.semester = ? OR s.semester IS NULL OR s.semester = '') ") +
                    "ORDER BY s.week_day, s.start_period";
            List<Map<String, Object>> rows = semester.isEmpty()
                    ? jdbcTemplate.queryForList(sql, classId)
                    : jdbcTemplate.queryForList(sql, classId, semester);

            // 生效中的调课（已同步到 schedule 的行直接在原格子打 isAdjustment 标）：按周次过滤
            List<Map<String, Object>> adjustments = scheduleAdjustmentService.getActiveAdjustmentsForClass(classId, week);
            java.util.Set<Long> hiddenIds = new java.util.HashSet<>();
            for (Map<String, Object> adj : adjustments) {
                // 未同步的历史数据才隐藏原时段（保持叠加补偿）；已同步的行时段即目标时段，直接显示并打标
                if (Boolean.TRUE.equals(adj.get("slotSynced"))) {
                    continue;
                }
                Object sid = adj.get("scheduleId");
                if (sid instanceof Number) {
                    hiddenIds.add(((Number) sid).longValue());
                }
            }

            List<Map<String, Object>> formattedList = new ArrayList<>();
            // 已同步的调课 / 待审批调课按 schedule_id 建索引，便于在课表格子上叠加标记
            Map<Long, Map<String, Object>> syncedBySid = new HashMap<>();
            for (Map<String, Object> adj : adjustments) {
                if (!Boolean.TRUE.equals(adj.get("slotSynced"))) {
                    continue;
                }
                Object sid = adj.get("scheduleId");
                if (sid instanceof Number) {
                    syncedBySid.put(((Number) sid).longValue(), adj);
                }
            }
            // 待审批调课仅用于"调课申请中"提示，不参与任何占用/冲突判定
            Map<Long, List<Map<String, Object>>> pendingBySid = new HashMap<>();
            for (Map<String, Object> p : scheduleAdjustmentService.getPendingAdjustmentsForClass(classId, week)) {
                Object psid = p.get("scheduleId");
                if (psid instanceof Number) {
                    pendingBySid.computeIfAbsent(((Number) psid).longValue(), k -> new ArrayList<>()).add(p);
                }
            }
            for (Map<String, Object> row : rows) {
                String weekRange = row.get("week_range") == null ? null : row.get("week_range").toString();
                // 按周次范围过滤
                if (week != null && !weekInRange(weekRange, week)) {
                    continue;
                }
                // 被调课的原时段直接隐藏（不修改原课表数据）
                Object sid = row.get("id");
                if (sid instanceof Number && hiddenIds.contains(((Number) sid).longValue())) {
                    continue;
                }
                Integer startPeriod = ((Number) row.get("start_period")).intValue();

                Map<String, Object> item = new HashMap<>();
                item.put("scheduleId", sid);
                item.put("day", row.get("week_day"));
                // start_period(1/3/5/7/9) 映射为表格行序号(1-5)
                item.put("period", (startPeriod + 1) / 2);
                item.put("startPeriod", startPeriod);
                item.put("endPeriod", row.get("end_period"));
                item.put("courseName", row.get("course_name"));
                item.put("courseCode", row.get("course_code"));
                item.put("teacherName", row.get("teacher_name"));
                // 无教室课程：classroom_no 为 null 时返回约定展示值
                Object classroomNo = row.get("classroom_no");
                item.put("location", classroomNo != null ? classroomNo : "无教室");
                item.put("building", row.get("building"));
                item.put("weekRange", weekRange);
                item.put("semester", row.get("semester"));
                // 已同步的调课行（时段即目标时段）保留显示并打 isAdjustment 标，与叠加补偿显示结果一致
                if (sid instanceof Number) {
                    Map<String, Object> syncedAdj = syncedBySid.get(((Number) sid).longValue());
                    if (syncedAdj != null) {
                        item.put("isAdjustment", true);
                        String subName = syncedAdj.get("substituteTeacherName") == null ? ""
                                : String.valueOf(syncedAdj.get("substituteTeacherName")).trim();
                        if (!subName.isEmpty()) {
                            item.put("substituteTeacherName", subName);
                        }
                    }
                    List<Map<String, Object>> pending = pendingBySid.get(((Number) sid).longValue());
                    if (pending != null && !pending.isEmpty()) {
                        item.put("pendingAdjustment", true);
                        item.put("pendingTargetText", pending.get(0).get("targetText"));
                    }
                }
                formattedList.add(item);
            }

            // 叠加调课结果：目标时段显示该课程并标注「调课」（已同步的行已由原格子打标，跳过避免重复）
            for (Map<String, Object> adj : adjustments) {
                if (Boolean.TRUE.equals(adj.get("slotSynced"))) {
                    continue;
                }
                formattedList.add(buildAdjustedItem(adj));
            }

            result.put("success", true);
            result.put("joinedClass", true);
            result.put("schedule", formattedList);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "加载失败：" + e.getMessage());
            result.put("schedule", List.of());
        }

        return result;
    }

    /** 将生效中的调课转为课表格子（day/period 口径与原课表一致，并标注 isAdjustment） */
    private Map<String, Object> buildAdjustedItem(Map<String, Object> adj) {
        int startPeriod = ((Number) adj.get("targetStartPeriod")).intValue();
        Map<String, Object> item = new HashMap<>();
        item.put("day", adj.get("targetWeekDay"));
        item.put("period", (startPeriod + 1) / 2);
        item.put("startPeriod", startPeriod);
        item.put("endPeriod", adj.get("targetEndPeriod"));
        item.put("courseName", adj.get("courseName"));
        item.put("courseCode", adj.get("courseCode"));
        item.put("teacherName", adj.get("teacherName"));
        item.put("location", adj.get("location"));
        item.put("building", adj.get("building"));
        item.put("weekRange", adj.get("weekRange"));
        item.put("semester", adj.get("semester"));
        item.put("isAdjustment", true);
        String subName = adj.get("substituteTeacherName") == null ? ""
                : String.valueOf(adj.get("substituteTeacherName")).trim();
        if (!subName.isEmpty()) {
            item.put("substituteTeacherName", subName);
        }
        return item;
    }

    /**
     * 获取学生已加入的班级ID（approved 与 withdraw_pending 均视为在班，退班须待教师审核通过）
     */
    private Long getApprovedClassId(String studentId) {
        try {
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT class_id FROM class_member WHERE student_id = ? AND status IN ('approved','withdraw_pending') LIMIT 1",
                    Long.class, studentId);
            return ids.isEmpty() ? null : ids.get(0);
        } catch (Exception e) {
            return null;
        }
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

    /**
     * 判断指定周次是否落在周次范围内（支持 "1-18"、"5"、"1-8,11-18" 及 单周/双周 等自然语言写法）
     */
    private boolean weekInRange(String weekRange, int week) {
        if (weekRange == null || weekRange.trim().isEmpty()) {
            return true;
        }
        return WeekRangeParser.contains(weekRange, week);
    }
}
