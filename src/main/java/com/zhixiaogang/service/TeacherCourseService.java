package com.zhixiaogang.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.zhixiaogang.service.impl.QwenAIService;
import com.zhixiaogang.service.ai.AiCallExecutor;
import com.zhixiaogang.service.adjustment.ScheduleAdjustmentService;
import com.zhixiaogang.util.WeekRangeParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 课程查询服务：查询教师授课安排、课表文本 AI 解析（解析能力供管理端课表导入复用）。
 * 课表写入统一由管理端维护，教师端课表只读。
 */
@Service
public class TeacherCourseService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private QwenAIService qwenAIService;

    @Autowired
    private AiCallExecutor aiCallExecutor;

    @Autowired
    private ScheduleAdjustmentService scheduleAdjustmentService;

    /**
     * 查询当前教师本学期的授课安排（联查 schedule + course + classroom + class_info）。
     * 授课关联规则：优先按 schedule.teacher_id = 教师工号过滤；
     * teacher_id 为空的历史数据按 teacher_name = 教师姓名兼容；两条件取并集。
     */
    public Map<String, Object> getCourses(String teacherName, String teacherId) {
        Map<String, Object> result = new HashMap<>();
        boolean hasId = teacherId != null && !teacherId.isEmpty();
        boolean hasName = teacherName != null && !teacherName.isEmpty();
        if (!hasId && !hasName) {
            result.put("success", false);
            result.put("message", "教师信息缺失");
            return result;
        }

        try {
            String sql = "SELECT s.id, s.teacher_name, s.week_day, s.start_period, s.end_period, " +
                    "s.week_range, s.semester, s.class_id, s.classroom_id, " +
                    "c.course_name, c.course_code, " +
                    "cl.classroom_no, cl.building, cl.room_type, " +
                    "ci.class_name, " +
                    "(SELECT COUNT(*) FROM class_member cm WHERE cm.class_id = s.class_id " +
                    "AND cm.status IN ('approved','withdraw_pending')) AS student_count " +
                    "FROM schedule s " +
                    "JOIN course c ON s.course_id = c.id " +
                    "LEFT JOIN classroom cl ON s.classroom_id = cl.id " +
                    "LEFT JOIN class_info ci ON s.class_id = ci.id " +
                    "WHERE " + teacherMatchCondition(hasId, hasName) + " " +
                    "ORDER BY s.semester DESC, s.week_day, s.start_period";

            List<Object> args = new ArrayList<>();
            if (hasId) args.add(teacherId);
            if (hasName) args.add(teacherName);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());

            List<Map<String, Object>> courses = new ArrayList<>();
            java.util.Map<Long, String> substituteMap =
                    scheduleAdjustmentService.substituteByScheduleForTeacher(teacherId, teacherName);
            for (Map<String, Object> row : rows) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", row.get("id"));
                Object rowId = row.get("id");
                if (rowId instanceof Number && substituteMap.containsKey(((Number) rowId).longValue())) {
                    item.put("substituteTeacherName", substituteMap.get(((Number) rowId).longValue()));
                }
                item.put("courseName", row.get("course_name"));
                item.put("courseCode", row.get("course_code"));
                item.put("className", row.get("class_name"));
                item.put("classId", row.get("class_id"));
                item.put("teacherName", row.get("teacher_name"));
                item.put("weekDay", row.get("week_day"));
                item.put("startPeriod", row.get("start_period"));
                item.put("endPeriod", row.get("end_period"));
                item.put("weekRange", row.get("week_range"));
                item.put("semester", row.get("semester"));
                item.put("classroomNo", row.get("classroom_no"));
                item.put("building", row.get("building"));
                item.put("roomType", row.get("room_type"));
                item.put("classroomId", row.get("classroom_id"));
                Object studentCount = row.get("student_count");
                item.put("studentCount", studentCount instanceof Number ? ((Number) studentCount).intValue() : 0);
                courses.add(item);
            }

            // 作为代课教师的授课安排（时间与教室不变，仅更换授课教师）
            for (Map<String, Object> adj : scheduleAdjustmentService.getSubstituteAdjustmentsForTeacher(teacherId, teacherName, null)) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", adj.get("scheduleId"));
                item.put("courseName", adj.get("courseName"));
                item.put("courseCode", adj.get("courseCode"));
                item.put("className", adj.get("className"));
                item.put("classId", adj.get("classId"));
                item.put("teacherName", adj.get("originalTeacherName"));
                item.put("weekDay", adj.get("weekDay"));
                item.put("startPeriod", adj.get("startPeriod"));
                item.put("endPeriod", adj.get("endPeriod"));
                item.put("weekRange", adj.get("weekRange"));
                item.put("classroomNo", adj.get("classroomNo"));
                item.put("building", adj.get("building"));
                item.put("classroomId", adj.get("classroomId"));
                item.put("studentCount", 0);
                item.put("isSubstitute", true);
                item.put("originalTeacherName", adj.get("originalTeacherName"));
                item.put("substituteTeacherName", adj.get("substituteTeacherName"));
                courses.add(item);
            }

            result.put("success", true);
            result.put("courses", courses);
            result.put("teacherName", teacherName);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "查询授课安排失败：" + e.getMessage());
            result.put("courses", List.of());
        }
        return result;
    }

    /** teacher_id 优先、teacher_name 兼容（历史数据 teacher_id 为空）的并集匹配条件 */
    private String teacherMatchCondition(boolean hasId, boolean hasName) {
        StringBuilder cond = new StringBuilder();
        if (hasId) {
            cond.append("s.teacher_id = ?");
        }
        if (hasName) {
            if (cond.length() > 0) {
                cond.append(" OR ");
            }
            cond.append("((s.teacher_id IS NULL OR s.teacher_id = '') AND s.teacher_name = ?)");
        }
        return "(" + cond + ")";
    }

    /**
     * 教师课表查询（只读）：返回当前教师授课的全部班级课表，按周次过滤，
     * 输出与学生端 loadSchedule 一致的格子格式（day/period），额外带 className。
     */
    public Map<String, Object> getSchedule(String teacherName, String teacherId, Integer week) {
        Map<String, Object> result = new HashMap<>();
        boolean hasId = teacherId != null && !teacherId.isEmpty();
        boolean hasName = teacherName != null && !teacherName.isEmpty();
        if (!hasId && !hasName) {
            result.put("success", false);
            result.put("message", "教师信息缺失");
            result.put("schedule", List.of());
            return result;
        }

        try {
            String sql = "SELECT s.id, s.week_day, s.start_period, s.end_period, s.week_range, s.semester, s.teacher_name, " +
                    "c.course_name, c.course_code, cl.classroom_no, cl.building, ci.class_name " +
                    "FROM schedule s " +
                    "JOIN course c ON s.course_id = c.id " +
                    "LEFT JOIN classroom cl ON s.classroom_id = cl.id " +
                    "LEFT JOIN class_info ci ON s.class_id = ci.id " +
                    "WHERE " + teacherMatchCondition(hasId, hasName) + " " +
                    "ORDER BY s.week_day, s.start_period";
            List<Object> args = new ArrayList<>();
            if (hasId) args.add(teacherId);
            if (hasName) args.add(teacherName);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());

            // 生效中的调课（已同步到 schedule 的行直接在原格子打 isAdjustment 标）：按周次过滤
            List<Map<String, Object>> adjustments = scheduleAdjustmentService.getActiveAdjustmentsForTeacher(teacherId, teacherName, week);
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
            java.util.Map<Long, String> substituteMap =
                    scheduleAdjustmentService.substituteByScheduleForTeacher(teacherId, teacherName);
            for (Map<String, Object> row : rows) {
                String weekRange = row.get("week_range") == null ? null : row.get("week_range").toString();
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
                item.put("id", row.get("id"));
                item.put("day", row.get("week_day"));
                item.put("period", (startPeriod + 1) / 2);
                item.put("startPeriod", startPeriod);
                item.put("endPeriod", row.get("end_period"));
                item.put("courseName", row.get("course_name"));
                item.put("courseCode", row.get("course_code"));
                item.put("className", row.get("class_name"));
                item.put("teacherName", row.get("teacher_name"));
                Object classroomNo = row.get("classroom_no");
                item.put("location", classroomNo != null ? classroomNo : "室外（体育课）");
                item.put("building", row.get("building"));
                item.put("weekRange", weekRange);
                item.put("semester", row.get("semester"));
                // 已同步的调课行（时段即目标时段）保留显示并打 isAdjustment 标，与叠加补偿显示结果一致
                Object finalSid = sid;
                if (sid instanceof Number && adjustments.stream().anyMatch(adj ->
                        Boolean.TRUE.equals(adj.get("slotSynced"))
                                && finalSid.equals(adj.get("scheduleId")))) {
                    item.put("isAdjustment", true);
                }
                if (sid instanceof Number && substituteMap.containsKey(((Number) sid).longValue())) {
                    item.put("substituteTeacherName", substituteMap.get(((Number) sid).longValue()));
                }
                formattedList.add(item);
            }

            // 叠加调课结果：目标时段显示该课程并标注「调课」（已同步的行已由原格子打标，跳过避免重复）
            for (Map<String, Object> adj : adjustments) {
                if (Boolean.TRUE.equals(adj.get("slotSynced"))) {
                    continue;
                }
                int startPeriod = ((Number) adj.get("targetStartPeriod")).intValue();
                Map<String, Object> item = new HashMap<>();
                item.put("day", adj.get("targetWeekDay"));
                item.put("period", (startPeriod + 1) / 2);
                item.put("startPeriod", startPeriod);
                item.put("endPeriod", adj.get("targetEndPeriod"));
                item.put("courseName", adj.get("courseName"));
                item.put("courseCode", adj.get("courseCode"));
                item.put("className", adj.get("className"));
                item.put("teacherName", adj.get("teacherName"));
                item.put("location", adj.get("location"));
                item.put("building", adj.get("building"));
                item.put("weekRange", adj.get("weekRange"));
                item.put("semester", adj.get("semester"));
                item.put("isAdjustment", true);
                formattedList.add(item);
            }

            // 作为代课教师的课表安排：在目标时段叠加显示，并标注「代课」
            for (Map<String, Object> adj : scheduleAdjustmentService.getSubstituteAdjustmentsForTeacher(teacherId, teacherName, week)) {
                int startPeriod = ((Number) adj.get("startPeriod")).intValue();
                Map<String, Object> item = new HashMap<>();
                item.put("day", adj.get("weekDay"));
                item.put("period", (startPeriod + 1) / 2);
                item.put("startPeriod", startPeriod);
                item.put("endPeriod", adj.get("endPeriod"));
                item.put("courseName", adj.get("courseName"));
                item.put("courseCode", adj.get("courseCode"));
                item.put("className", adj.get("className"));
                item.put("teacherName", adj.get("originalTeacherName"));
                item.put("location", adj.get("classroomNo") != null ? adj.get("classroomNo") : "室外（体育课）");
                item.put("building", adj.get("building"));
                item.put("weekRange", adj.get("weekRange"));
                item.put("isSubstitute", true);
                item.put("originalTeacherName", adj.get("originalTeacherName"));
                item.put("substituteTeacherName", adj.get("substituteTeacherName"));
                formattedList.add(item);
            }
            result.put("success", true);
            result.put("schedule", formattedList);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "加载失败：" + e.getMessage());
            result.put("schedule", List.of());
        }
        return result;
    }

    /** 判断 week 是否落在 weekRange（如 "1-18"、"1-9,11-16"、"3"、"单周"）内 */
    private boolean weekInRange(String weekRange, int week) {
        if (weekRange == null || weekRange.trim().isEmpty()) {
            return true;
        }
        return WeekRangeParser.contains(weekRange, week);
    }

    /**
     * 调用 AI 将课表文本解析为结构化 JSON 数组（不含入库动作，供管理端预览确认流程使用）。
     * 解析失败 / AI 未启用时抛出 IllegalArgumentException，消息可直接展示。
     */
    public List<Map<String, Object>> parseScheduleText(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("课表文本不能为空");
        }
        if (!qwenAIService.isEnabled()) {
            throw new IllegalArgumentException("AI 功能未启用，无法解析课表。请先在管理端模型提供商中配置生效提供商与 API Key");
        }
        try {
            AiCallExecutor.CallResult call = callAIForParse(text);
            if (!call.success) {
                throw new IllegalArgumentException(aiFailText(call));
            }
            List<Map<String, Object>> parsed = parseScheduleJson(call.content);
            if (parsed.isEmpty()) {
                throw new IllegalArgumentException("AI 未能解析出有效课表，请检查文本或重试。原始响应：" + call.content);
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("AI 解析失败：" + e.getMessage());
        }
    }

    /**
     * 调用 AI 将课程计划文本解析为课程清单 JSON 数组（AI 自动排课 - 自然文字解析用）。
     * 字段：course_name、week_hours、teacher_name、classroom_type、week_range。
     * 解析失败 / AI 未启用时抛出 IllegalArgumentException，消息可直接展示。
     */
    public List<Map<String, Object>> parseCourseListText(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("课程文本不能为空");
        }
        if (!qwenAIService.isEnabled()) {
            throw new IllegalArgumentException("AI 功能未启用，无法解析课程清单。请先在管理端模型提供商中配置生效提供商与 API Key");
        }
        try {
            AiCallExecutor.CallResult call = callAIForCourseParse(text);
            if (!call.success) {
                throw new IllegalArgumentException(aiFailText(call));
            }
            List<Map<String, Object>> parsed = parseScheduleJson(call.content);
            if (parsed.isEmpty()) {
                throw new IllegalArgumentException("AI 未能解析出有效课程清单，请检查文本或重试。原始响应：" + call.content);
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("AI 解析失败：" + e.getMessage());
        }
    }

    /** 调用 AI 模型解析课程计划文本，要求返回纯 JSON 数组 */
    private AiCallExecutor.CallResult callAIForCourseParse(String text) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是课程计划解析助手。请将下面的课程描述解析为JSON数组，只输出JSON数组本身，");
        prompt.append("不要输出任何解释文字，不要输出markdown代码块。\n");
        prompt.append("字段说明：course_name(课程名称), week_hours(每周学时/节数,数字,未指定输出2), ");
        prompt.append("teacher_name(授课教师姓名,未知输出空字符串), ");
        prompt.append("classroom_type(教室类型,如机房/实训室/普通教室/多媒体教室;体育课等无固定教室课程输出空字符串), ");
        prompt.append("classroom_no(教室编号,如B5-302、B6-310;若文本明确给出具体教室编号则原样提取,体育课等无固定教室课程输出空字符串), ");
        prompt.append("week_range(上课周次,如1-16,未指定输出空字符串)。\n");
        prompt.append("输出格式示例：\n");
        prompt.append("[{\"course_name\":\"Java程序设计\",\"week_hours\":4,\"teacher_name\":\"张三\",");
        prompt.append("\"classroom_type\":\"机房\",\"classroom_no\":\"B6-310\",\"week_range\":\"1-16\"}]\n");
        prompt.append("课程描述：\n");
        prompt.append(text);
        return aiCallExecutor.callText(prompt.toString(), "admin-ai-schedule", null);
    }

    /**
     * 调用 AI 模型解析课表文本，要求返回纯 JSON 数组
     */
    private AiCallExecutor.CallResult callAIForParse(String text) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是课表解析助手。请将下面的课表文本解析为JSON数组，只输出JSON数组本身，");
        prompt.append("不要输出任何解释文字，不要输出markdown代码块。\n");
        prompt.append("字段说明：class_name(班级名称), course_name(课程名称), teacher_name(教师姓名), ");
        prompt.append("classroom_no(教室编号,如B5-302;若课程为体育课等无固定教室的课程,如操场、室外上课,输出空字符串), ");
        prompt.append("week_day(星期几,数字1到7,1表示周一), ");
        prompt.append("start_period(开始节次,数字), end_period(结束节次,数字), ");
        prompt.append("week_range(上课周次,如1-16)。\n");
        prompt.append("输出格式示例：\n");
        prompt.append("[{\"class_name\":\"23级软件1班\",\"course_name\":\"数据库原理与实践\",");
        prompt.append("\"teacher_name\":\"张三\",\"classroom_no\":\"B5-302\",\"week_day\":1,");
        prompt.append("\"start_period\":3,\"end_period\":4,\"week_range\":\"1-16\"}]\n");
        prompt.append("课表文本：\n");
        prompt.append(text);
        return aiCallExecutor.callText(prompt.toString(), "admin-import", null);
    }

    /** AI 调用失败的可展示文案（超时含等待上限与重试次数） */
    private String aiFailText(AiCallExecutor.CallResult call) {
        if ("timeout".equals(call.errorType)) {
            return "模型响应超时（已等待 " + AiCallExecutor.TEXT_TIMEOUT_SECONDS + " 秒"
                    + (call.retried ? "，已自动重试 1 次" : "") + "），请稍后重试或换用更快模型";
        }
        return call.errorText != null && !call.errorText.isEmpty() ? call.errorText : "AI 解析失败，请稍后重试";
    }

    /**
     * 从 AI 响应文本中提取并解析课表 JSON 数组（识图识别结果复用同一解析）。
     * 解析不出有效数组时返回空列表。
     */
    public List<Map<String, Object>> parseScheduleJsonText(String aiResp) {
        try {
            return parseScheduleJson(aiResp);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 从 AI 响应中提取并解析 JSON 数组
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseScheduleJson(String aiResp) throws Exception {
        if (aiResp == null || aiResp.trim().isEmpty()) {
            return List.of();
        }
        String s = aiResp.trim();
        // 去除可能的 markdown 代码块包裹
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline != -1) {
                s = s.substring(firstNewline + 1);
            }
            int fence = s.lastIndexOf("```");
            if (fence != -1) {
                s = s.substring(0, fence);
            }
            s = s.trim();
        }
        // 截取第一个 [ 到最后一个 ]
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start == -1 || end == -1 || end < start) {
            return List.of();
        }
        String json = s.substring(start, end + 1);
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
    }
}
