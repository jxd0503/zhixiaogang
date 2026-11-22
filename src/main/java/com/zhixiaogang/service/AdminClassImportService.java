package com.zhixiaogang.service;

import com.zhixiaogang.service.impl.QwenAIService;
import com.zhixiaogang.service.ai.AiCallExecutor;
import com.zhixiaogang.service.notification.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.zhixiaogang.service.AdminClassSupport.*;

/********** 班级课表 AI 导入域：文字/识图解析预览、逐条校验、确认入库（课程/教室/教师自动创建） **********/
@Service
public class AdminClassImportService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private QwenAIService qwenAIService;

    @Autowired
    private AiCallExecutor aiCallExecutor;

    @Autowired
    private AIConfigService aiConfigService;

    @Autowired
    private TeacherCourseService teacherCourseService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private AdminClassScheduleConflictChecker scheduleConflictChecker;

    /** AI 文字导入：课表文本 → AI 解析 → 结构化预览（含逐条校验结果，不入库） */
    public Map<String, Object> importPreviewText(Long classId, String text, String defaultSemester) {
        Map<String, Object> cls = queryClass(jdbcTemplate, classId);
        if (cls == null) {
            return fail("班级不存在");
        }
        List<Map<String, Object>> parsed;
        try {
            parsed = teacherCourseService.parseScheduleText(text);
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
        return buildPreview(classId, cls, parsed, defaultSemester);
    }

    /**
     * AI 识图导入：先判定当前生效模型是否支持识图（multimodal_models），支持则调多模态接口识别。
     */
    public Map<String, Object> importPreviewImage(Long classId, String base64Image, String mimeType, String defaultSemester) {
        Map<String, Object> cls = queryClass(jdbcTemplate, classId);
        if (cls == null) {
            return fail("班级不存在");
        }
        if (base64Image == null || base64Image.trim().isEmpty()) {
            return fail("请上传课表图片");
        }
        if (!qwenAIService.isEnabled()) {
            return fail("AI 功能未启用，无法识图。请先在管理端模型提供商中配置生效提供商与 API Key");
        }

        // 多模态能力判定：取生效提供商 current_model（空则 models 第一个），在 multimodal_models 列表内才可识图
        Map<String, Object> mmStatus = aiConfigService.getMultimodalStatus();
        if (mmStatus == null) {
            return fail("AI 功能未启用，无法识图。请先在管理端模型提供商中设置生效提供商");
        }
        boolean supported = Boolean.TRUE.equals(mmStatus.get("supported"));
        String currentModel = String.valueOf(mmStatus.get("model"));
        if (!supported) {
            return fail("当前生效模型不支持识图（当前模型：" + currentModel
                    + "），请切换到多模态模型（如 qwen-vl-plus）或在模型提供商管理中启用多模态模型后再使用");
        }

        String instruction = buildImageInstruction();
        AiCallExecutor.CallResult aiResult = aiCallExecutor.callImage(instruction, base64Image.trim(), mimeType);
        if (!aiResult.success) {
            // 透传模型返回的具体错误（如图片尺寸过小/格式不支持），超时含等待上限与重试次数
            return fail(aiResult.errorText);
        }
        String aiResp = aiResult.content;
        List<Map<String, Object>> parsed = teacherCourseService.parseScheduleJsonText(aiResp);
        if (parsed.isEmpty()) {
            String summary = aiResp.length() > 200 ? aiResp.substring(0, 200) + "..." : aiResp;
            return fail("未能从图片中识别出有效课表条目。模型响应摘要：" + summary);
        }
        return buildPreview(classId, cls, parsed, defaultSemester);
    }

    /** 预览确认：批量入库（逐条校验，失败项不入库并返回原因） */
    @Transactional
    public Map<String, Object> importConfirm(Long classId, List<Map<String, Object>> items, String defaultSemester) {
        Map<String, Object> cls = queryClass(jdbcTemplate, classId);
        if (cls == null) {
            return fail("班级不存在");
        }
        return doConfirmImport(cls, items, defaultSemester);
    }

    /**
     * AI 自动排课确认入库：条目自带班级名（className），逐条解析目标班级后写入 schedule。
     * 复用与 importConfirm 相同的写库路径（课程 / 教室 / 教师自动创建与回填）。
     */
    @Transactional
    public Map<String, Object> importConfirmMulti(List<Map<String, Object>> items, String defaultSemester) {
        return doConfirmImport(null, items, defaultSemester);
    }

    /** 确认入库共同实现：cls 为默认班级（可空，此时条目必须自带 className） */
    private Map<String, Object> doConfirmImport(Map<String, Object> cls, List<Map<String, Object>> items, String defaultSemester) {
        Map<String, Object> result = new HashMap<>();
        if (items == null || items.isEmpty()) {
            return fail("没有可导入的课表条目");
        }

        int successCount = 0;
        int skippedCount = 0;
        List<String> skippedRows = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<String> createdCourses = new ArrayList<>();
        List<String> createdClassrooms = new ArrayList<>();
        List<String> createdTeachers = new ArrayList<>();
        // 成功写入的课表条目，用于向受影响班级学生与授课教师发布「新课表已发布」通知（失败条目不收集）
        List<NotificationService.SchedulePub> published = new ArrayList<>();
        // 批量导入时同一教师只创建一次
        Map<String, Map<String, Object>> teacherCache = new HashMap<>();

        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            String rowTag = "第" + (i + 1) + "条";
            try {
                Map<String, Object> resolved = resolveItemForConfirm(cls, item, defaultSemester, teacherCache);
                if (resolved.get("error") != null) {
                    failures.add(rowTag + "：" + resolved.get("error"));
                    continue;
                }

                String courseName = (String) resolved.get("courseName");
                Long courseId = (Long) resolved.get("courseId");
                if (Boolean.TRUE.equals(resolved.get("courseCreated"))) {
                    createdCourses.add(courseName);
                }
                if (Boolean.TRUE.equals(resolved.get("classroomCreated"))) {
                    createdClassrooms.add(getStr(item, "classroomNo"));
                }
                if (Boolean.TRUE.equals(resolved.get("teacherCreated"))) {
                    String tag = resolved.get("teacherName") + "(" + resolved.get("teacherId")
                            + "，初始口令 " + resolved.get("initialPassword") + ")";
                    if (!createdTeachers.contains(tag)) {
                        createdTeachers.add(tag);
                    }
                }

                // 重复导入防护：同一班级 + 课程 + 星期 + 节次 + 教室 + 周次 + 学期 + 教师已存在时跳过，不写重复记录
                if (scheduleExists(resolved)) {
                    skippedCount++;
                    skippedRows.add(rowTag + "：" + courseName + " 周" + resolved.get("weekDay")
                            + " 第" + resolved.get("startPeriod") + "-" + resolved.get("endPeriod") + "节");
                    continue;
                }

                // 教室 / 教师冲突校验：跨班级检查同时段是否已有课程占用同一教室或同一教师
                String roomTeacherConflict = scheduleConflictChecker.checkRoomAndTeacher(
                        (Long) resolved.get("classroomId"), (String) resolved.get("teacherName"),
                        ((Number) resolved.get("weekDay")).intValue(),
                        ((Number) resolved.get("startPeriod")).intValue(),
                        ((Number) resolved.get("endPeriod")).intValue(),
                        (String) resolved.get("weekRange"), (String) resolved.get("semester"), null);
                if (roomTeacherConflict != null) {
                    failures.add(rowTag + "：" + roomTeacherConflict);
                    continue;
                }

                jdbcTemplate.update(
                        "INSERT INTO schedule (student_id, course_id, classroom_id, teacher_name, teacher_id, " +
                                "week_day, start_period, end_period, week_range, semester, class_id) " +
                                "VALUES (NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        courseId, resolved.get("classroomId"), resolved.get("teacherName"), resolved.get("teacherId"),
                        resolved.get("weekDay"), resolved.get("startPeriod"), resolved.get("endPeriod"),
                        resolved.get("weekRange"), resolved.get("semester"), resolved.get("targetClassId"));
                successCount++;
                published.add(buildSchedulePub(resolved));
            } catch (Exception e) {
                failures.add(rowTag + "：入库失败 " + rootMessage(e));
            }
        }

        // 仅对成功写入的条目发布课表通知（同班级 / 同教师自动合并为一条）
        notificationService.onSchedulePublished(published);

        // 批量导入采用「部分成功」语义：逐条独立校验入库，失败行不影响其他行，明细在 failures 中逐行列出
        boolean partial = successCount > 0 && !failures.isEmpty();
        result.put("success", true);
        result.put("atomic", false);
        result.put("partial", partial);
        result.put("totalCount", items.size());
        result.put("successCount", successCount);
        result.put("skippedCount", skippedCount);
        result.put("skipped", skippedRows);
        result.put("failCount", failures.size());
        result.put("failures", failures);
        if (!createdCourses.isEmpty()) {
            result.put("createdCourses", createdCourses);
        }
        if (!createdClassrooms.isEmpty()) {
            result.put("createdClassrooms", createdClassrooms);
        }
        if (!createdTeachers.isEmpty()) {
            result.put("createdTeachers", createdTeachers);
        }
        String message = (partial ? "部分导入成功" : "导入完成")
                + "：成功 " + successCount + " 条，失败 " + failures.size() + " 条";
        if (skippedCount > 0) {
            message += "，跳过重复 " + skippedCount + " 条";
        }
        if (!createdClassrooms.isEmpty()) {
            message += "；自动创建教室：" + String.join("、", createdClassrooms);
        }
        if (!createdTeachers.isEmpty()) {
            message += "；自动创建教师账号：" + String.join("、", createdTeachers) + "（初始口令仅展示一次，请转告教师本人）";
        }
        result.put("message", message);
        return result;
    }

    /** 重复导入判定：同一班级 / 课程 / 星期 / 节次 / 教室 / 周次 / 学期 / 教师 的课表条目是否已存在 */
    private boolean scheduleExists(Map<String, Object> resolved) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schedule WHERE class_id = ? AND course_id = ? AND week_day = ? " +
                        "AND start_period = ? AND end_period = ? " +
                        "AND COALESCE(classroom_id, 0) = COALESCE(?, 0) " +
                        "AND COALESCE(week_range, '') = COALESCE(?, '') " +
                        "AND COALESCE(semester, '') = COALESCE(?, '') " +
                        "AND COALESCE(teacher_id, '') = COALESCE(?, '')",
                Integer.class,
                resolved.get("targetClassId"), resolved.get("courseId"), resolved.get("weekDay"),
                resolved.get("startPeriod"), resolved.get("endPeriod"), resolved.get("classroomId"),
                resolved.get("weekRange"), resolved.get("semester"), resolved.get("teacherId"));
        return count != null && count > 0;
    }

    /** 构建预览：逐条校验（班级/教室存在性、字段完整性），标红失败项 */
    private Map<String, Object> buildPreview(Long classId, Map<String, Object> cls,
                                             List<Map<String, Object>> parsed, String defaultSemester) {
        List<Map<String, Object>> preview = new ArrayList<>();
        for (Map<String, Object> item : parsed) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("className", getStr(item, "class_name"));
            p.put("courseName", getStr(item, "course_name"));
            p.put("classroomNo", getStr(item, "classroom_no"));
            p.put("teacherName", getStr(item, "teacher_name"));
            p.put("weekDay", getInt(item, "week_day"));
            p.put("startPeriod", getInt(item, "start_period"));
            p.put("endPeriod", getInt(item, "end_period"));
            p.put("weekRange", normalizeWeekRange(getStr(item, "week_range")));
            // 与入库口径一致：未指定学期时落到当前学期，预览与实际写入保持一致
            String previewSemester = getStr(item, "semester");
            if (previewSemester.isEmpty() && defaultSemester != null && !defaultSemester.isEmpty()) {
                previewSemester = defaultSemester;
            }
            if (previewSemester.isEmpty()) {
                previewSemester = currentSemesterName(jdbcTemplate);
            }
            p.put("semester", previewSemester.isEmpty() ? null : previewSemester);

            String reason = validatePreviewItem(classId, p);
            p.put("valid", reason == null);
            p.put("reason", reason == null ? "" : reason);
            preview.add(p);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("items", preview);
        result.put("totalCount", preview.size());
        return result;
    }

    /** 预览条目校验：返回 null 表示通过，否则返回失败原因 */
    private String validatePreviewItem(Long defaultClassId, Map<String, Object> p) {
        String className = (String) p.get("className");
        String courseName = (String) p.get("courseName");
        Integer weekDay = (Integer) p.get("weekDay");
        Integer startPeriod = (Integer) p.get("startPeriod");
        Integer endPeriod = (Integer) p.get("endPeriod");

        if (courseName == null || courseName.isEmpty()) {
            return "课程名称缺失";
        }
        if (weekDay == null || weekDay < 1 || weekDay > 7) {
            return "星期 week_day 缺失或越界（应为 1-7）";
        }
        if (startPeriod == null || endPeriod == null) {
            return "节次缺失";
        }
        if (startPeriod > endPeriod) {
            return "开始节次大于结束节次";
        }
        if (startPeriod < 1 || endPeriod > 10) {
            return "节次越界（应为 1-10）";
        }
        // 教室选填：为空或为约定值（无教室/室外/操场等）表示体育课等无教室课程，放行；
        // 其余编号不在此校验存在性，确认入库时教室不存在会自动创建
        if (className != null && !className.isEmpty()) {
            Long classId = queryId(jdbcTemplate, "SELECT id FROM class_info WHERE class_name = ?", className);
            if (classId == null) {
                return "班级「" + className + "」不存在";
            }
        }
        return null;
    }

    /** 确认入库条目解析：班级名解析为目标班级或已存在班级；教室/课程不存在自动创建；教师无账号自动创建 */
    private Map<String, Object> resolveItemForConfirm(Map<String, Object> cls, Map<String, Object> item,
                                                      String defaultSemester,
                                                      Map<String, Map<String, Object>> teacherCache) {
        Map<String, Object> resolved = new HashMap<>();

        String className = getStr(item, "className");
        Long targetClassId;
        String classNameResolved = className;
        if (className.isEmpty()) {
            if (cls == null) {
                resolved.put("error", "班级名称缺失");
                return resolved;
            }
            targetClassId = ((Number) cls.get("id")).longValue();
            classNameResolved = (String) cls.get("class_name");
        } else {
            targetClassId = queryId(jdbcTemplate, "SELECT id FROM class_info WHERE class_name = ?", className);
            if (targetClassId == null) {
                resolved.put("error", "班级「" + className + "」不存在");
                return resolved;
            }
        }
        resolved.put("targetClassId", targetClassId);
        resolved.put("className", classNameResolved);

        String courseName = getStr(item, "courseName");
        if (courseName.isEmpty()) {
            resolved.put("error", "课程名称缺失");
            return resolved;
        }

        String classroomNo = getStr(item, "classroomNo");
        // 教室选填：为空或为约定值（无教室/室外/操场/体育场等）时置 null，体育课等无教室课程不占用教室
        if (isNoClassroom(classroomNo)) {
            resolved.put("classroomId", null);
            resolved.put("classroomCreated", false);
        } else {
            // 教室不存在自动创建（与课程自动创建一致），楼栋/楼层按编号解析（如 B5-302 → B5 楼 3 层）
            Long classroomId = queryId(jdbcTemplate, "SELECT id FROM classroom WHERE classroom_no = ?", classroomNo);
            boolean classroomCreated = false;
            if (classroomId == null) {
                String building = null;
                Integer floor = null;
                int dash = classroomNo.indexOf('-');
                if (dash > 0) {
                    building = classroomNo.substring(0, dash);
                    String roomPart = classroomNo.substring(dash + 1);
                    if (!roomPart.isEmpty() && Character.isDigit(roomPart.charAt(0))) {
                        floor = Character.getNumericValue(roomPart.charAt(0));
                    }
                }
                jdbcTemplate.update(
                        "INSERT INTO classroom (classroom_no, building, floor, status) VALUES (?, ?, ?, 'available')",
                        classroomNo, building, floor);
                classroomId = queryId(jdbcTemplate, "SELECT id FROM classroom WHERE classroom_no = ?", classroomNo);
                classroomCreated = true;
            }
            resolved.put("classroomId", classroomId);
            resolved.put("classroomCreated", classroomCreated);
        }
        // 通知内容用：无教室课程记为空
        resolved.put("classroomNo", isNoClassroom(classroomNo) ? null : classroomNo);

        Integer weekDay = getInt(item, "weekDay");
        Integer startPeriod = getInt(item, "startPeriod");
        Integer endPeriod = getInt(item, "endPeriod");
        if (weekDay == null || weekDay < 1 || weekDay > 7) {
            resolved.put("error", "星期 week_day 缺失或越界（应为 1-7）");
            return resolved;
        }
        if (startPeriod == null || endPeriod == null || startPeriod < 1 || endPeriod > 10 || startPeriod > endPeriod) {
            resolved.put("error", "节次非法（需 1-10 且开始不大于结束）");
            return resolved;
        }
        resolved.put("weekDay", weekDay);
        resolved.put("startPeriod", startPeriod);
        resolved.put("endPeriod", endPeriod);

        // 课程不存在自动创建
        Long courseId = queryId(jdbcTemplate, "SELECT id FROM course WHERE course_name = ?", courseName);
        boolean created = false;
        if (courseId == null) {
            String dept = queryString(jdbcTemplate, "SELECT department FROM class_info WHERE id = ?", targetClassId);
            String code = generateCourseCode(courseName);
            jdbcTemplate.update(
                    "INSERT INTO course (course_code, course_name, department, status) VALUES (?, ?, ?, 1)",
                    code, courseName, dept);
            courseId = queryId(jdbcTemplate, "SELECT id FROM course WHERE course_name = ?", courseName);
            created = true;
        }
        resolved.put("courseId", courseId);
        resolved.put("courseName", courseName);
        resolved.put("courseCreated", created);

        // 教师姓名：条目优先，否则默认该班级管理教师；teacher_id 按姓名解析（无账号自动创建）
        String teacherName = getStr(item, "teacherName");
        if (teacherName.isEmpty()) {
            Object teacher = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(u.name, ci.teacher_id) FROM class_info ci LEFT JOIN user u ON ci.teacher_id = u.student_id WHERE ci.id = ?",
                    String.class, targetClassId);
            teacherName = teacher != null ? teacher.toString() : "";
        }
        String teacherId = null;
        boolean teacherCreated = false;
        if (!teacherName.isEmpty()) {
            String dept = queryString(jdbcTemplate, "SELECT department FROM class_info WHERE id = ?", targetClassId);
            Map<String, Object> tr = resolveTeacherAccount(jdbcTemplate, teacherName, dept, teacherCache, null);
            if (tr.get("error") != null) {
                resolved.put("error", String.valueOf(tr.get("error")));
                return resolved;
            }
            teacherId = (String) tr.get("teacherId");
            teacherCreated = Boolean.TRUE.equals(tr.get("created"));
        }
        resolved.put("teacherName", teacherName.isEmpty() ? null : teacherName);
        resolved.put("teacherId", teacherId);
        resolved.put("teacherCreated", teacherCreated);

        String weekRange = normalizeWeekRange(getStr(item, "weekRange"));
        String semester = getStr(item, "semester");
        if (semester.isEmpty() && defaultSemester != null && !defaultSemester.isEmpty()) {
            semester = defaultSemester;
        }
        // 仍未指定学期时落到当前学期，避免新课表写入空学期后被按学期过滤掉
        if (semester.isEmpty()) {
            semester = currentSemesterName(jdbcTemplate);
        }
        resolved.put("weekRange", weekRange.isEmpty() ? null : weekRange);
        resolved.put("semester", semester.isEmpty() ? null : semester);
        return resolved;
    }
}
