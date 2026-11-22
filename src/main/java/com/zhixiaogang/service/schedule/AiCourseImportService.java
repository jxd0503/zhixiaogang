package com.zhixiaogang.service.schedule;

import org.springframework.stereotype.Service;
import com.zhixiaogang.service.impl.QwenAIService;
import com.zhixiaogang.service.ai.AiCallExecutor;
import com.zhixiaogang.util.XlsxParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.zhixiaogang.service.AIConfigService;
import com.zhixiaogang.service.TeacherCourseService;
import static com.zhixiaogang.service.schedule.AiScheduleUtils.*;

/**
 * AI 排课课程清单解析：课程库、文字、Excel、图片四种输入归一为课程清单。
 */
@Service
public class AiCourseImportService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TeacherCourseService teacherCourseService;

    @Autowired
    private QwenAIService qwenAIService;

    @Autowired
    private AiCallExecutor aiCallExecutor;

    @Autowired
    private AIConfigService aiConfigService;

    /** 课程库列表：课程名、周学时、授课教师（取该课程最近一次课表的教师）、教室号 */
    public Map<String, Object> listCourses() {
        Map<String, Object> result = new HashMap<>();
        try {
            String sql = "SELECT c.id, c.course_name, c.week_hours, c.department, "
                    + "(SELECT s.teacher_name FROM schedule s WHERE s.course_id = c.id "
                    + "  AND s.teacher_name IS NOT NULL AND s.teacher_name <> '' ORDER BY s.id DESC LIMIT 1) AS teacher_name, "
                    + "(SELECT cl.classroom_no FROM schedule s JOIN classroom cl ON s.classroom_id = cl.id "
                    + "  WHERE s.course_id = c.id AND s.classroom_id IS NOT NULL ORDER BY s.id DESC LIMIT 1) AS classroom_no "
                    + "FROM course c WHERE c.status = 1 ORDER BY c.id";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            List<Map<String, Object>> courses = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("courseId", row.get("id"));
                item.put("courseName", row.get("course_name"));
                Object wh = row.get("week_hours");
                item.put("weekHours", wh instanceof Number ? ((Number) wh).intValue() : 2);
                item.put("classroomNo", row.get("classroom_no") == null ? "" : row.get("classroom_no"));
                item.put("teacherName", row.get("teacher_name") == null ? "" : row.get("teacher_name"));
                item.put("department", row.get("department") == null ? "" : row.get("department"));
                courses.add(item);
            }
            result.put("success", true);
            result.put("courses", courses);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "课程库加载失败：" + rootMessage(e));
        }
        return result;
    }

    public Map<String, Object> parseText(String text) {
        try {
            List<Map<String, Object>> parsed = teacherCourseService.parseCourseListText(text);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("courses", normalizeCourses(parsed));
            return result;
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        } catch (Exception e) {
            return fail("解析失败：" + rootMessage(e));
        }
    }

    public Map<String, Object> parseExcel(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return fail("请上传 xlsx 文件");
        }
        String name = file.getOriginalFilename();
        if (name != null && !name.toLowerCase().endsWith(".xlsx")) {
            return fail("仅支持 .xlsx 格式的 Excel 文件");
        }
        try (InputStream in = file.getInputStream()) {
            List<List<String>> rows = XlsxParser.parseRows(in);
            return buildCoursesFromRows(rows);
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        } catch (Exception e) {
            return fail("Excel 解析失败：" + rootMessage(e));
        }
    }

    private Map<String, Object> buildCoursesFromRows(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return fail("Excel 内容为空");
        }
        Map<String, Integer> colMap = new HashMap<>();
        int headerIdx = -1;
        for (int i = 0; i < Math.min(rows.size(), 5); i++) {
            Map<String, Integer> m = mapHeader(rows.get(i));
            if (m.containsKey("courseName")) {
                headerIdx = i;
                colMap = m;
                break;
            }
        }
        List<Map<String, Object>> courses = new ArrayList<>();
        if (headerIdx >= 0) {
            for (int i = headerIdx + 1; i < rows.size(); i++) {
                Map<String, Object> c = rowToCourse(rows.get(i), colMap);
                if (c != null) {
                    courses.add(c);
                }
            }
        } else {
            // 无表头：按约定列顺序解析（课程名、周学时、授课教师、教室号、周次）
            Map<String, Integer> fixed = new HashMap<>();
            fixed.put("courseName", 0);
            fixed.put("weekHours", 1);
            fixed.put("teacherName", 2);
            fixed.put("classroomNo", 3);
            fixed.put("weekRange", 4);
            for (List<String> row : rows) {
                Map<String, Object> c = rowToCourse(row, fixed);
                if (c != null) {
                    courses.add(c);
                }
            }
        }
        if (courses.isEmpty()) {
            return fail("未从 Excel 中解析出课程，请检查列名（课程名 / 周学时 / 授课教师 / 教室 / 周次）");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("courses", courses);
        return result;
    }

    private Map<String, Integer> mapHeader(List<String> header) {
        Map<String, Integer> map = new HashMap<>();
        if (header == null) {
            return map;
        }
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i) == null ? "" : header.get(i).trim().toLowerCase();
            if (h.isEmpty()) {
                continue;
            }
            if (!map.containsKey("courseName") && (h.contains("课程名") || h.contains("课程名称") || h.equals("课程") || h.contains("course_name") || h.contains("coursename"))) {
                map.put("courseName", i);
            } else if (!map.containsKey("weekHours") && (h.contains("周学时") || h.contains("每周") || h.contains("学时") || h.contains("week_hours") || h.contains("weekhours"))) {
                map.put("weekHours", i);
            } else if (!map.containsKey("teacherName") && (h.contains("教师") || h.contains("老师") || h.contains("teacher"))) {
                map.put("teacherName", i);
            } else if (!map.containsKey("classroomNo") && (h.contains("教室") || h.contains("classroom"))) {
                map.put("classroomNo", i);
            } else if (!map.containsKey("weekRange") && (h.contains("周次") || h.contains("周范围") || h.contains("week_range") || h.contains("weekrange"))) {
                map.put("weekRange", i);
            }
        }
        return map;
    }

    private Map<String, Object> rowToCourse(List<String> row, Map<String, Integer> col) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        String courseName = cell(row, col.get("courseName"));
        if (courseName.isEmpty()) {
            return null;
        }
        int wh = parseIntSafe(cell(row, col.get("weekHours")));
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("courseName", courseName);
        c.put("weekHours", wh > 0 ? wh : 2);
        c.put("teacherName", cell(row, col.get("teacherName")));
        c.put("classroomNo", cell(row, col.get("classroomNo")));
        c.put("weekRange", normalizeWeekRange(cell(row, col.get("weekRange"))));
        return c;
    }

    private String cell(List<String> row, Integer idx) {
        if (idx == null || idx < 0 || idx >= row.size()) {
            return "";
        }
        String v = row.get(idx);
        return v == null ? "" : v.trim();
    }

    public Map<String, Object> parseImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return fail("请上传课程计划图片");
        }
        if (!qwenAIService.isEnabled()) {
            return fail("AI 功能未启用，无法识图。请先在管理端模型提供商中配置生效提供商与 API Key");
        }
        Map<String, Object> mmStatus = aiConfigService.getMultimodalStatus();
        if (mmStatus == null) {
            return fail("AI 功能未启用，无法识图。请先在管理端模型提供商中设置生效提供商");
        }
        if (!Boolean.TRUE.equals(mmStatus.get("supported"))) {
            return fail("当前生效模型不支持识图（当前模型：" + mmStatus.get("model")
                    + "），请切换到多模态模型（如 qwen-vl-plus）后再使用");
        }
        String mimeType = resolveMimeType(file.getOriginalFilename(), file.getContentType());
        String base64;
        try {
            base64 = Base64.getEncoder().encodeToString(file.getBytes());
        } catch (Exception e) {
            return fail("图片读取失败：" + rootMessage(e));
        }
        AiCallExecutor.CallResult aiResult = aiCallExecutor.callImage(buildCourseImageInstruction(), base64, mimeType);
        if (!aiResult.success) {
            return fail(aiResult.errorText);
        }
        String aiResp = aiResult.content;
        List<Map<String, Object>> parsed = teacherCourseService.parseScheduleJsonText(aiResp);
        if (parsed.isEmpty()) {
            return fail("未能从图片中识别出有效课程清单");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("courses", normalizeCourses(parsed));
        return result;
    }

    private String buildCourseImageInstruction() {
        return "请识别这张课程计划 / 课表图片中的课程，输出 JSON 数组，字段为 "
                + "course_name（课程名称）、week_hours（每周学时，数字，未确定输出 2）、"
                + "teacher_name（授课教师姓名，未知输出空字符串）、"
                + "classroom_no（教室号，如 B3-201，如果图片只有教室类型如\"机房\"\"实训室\"等描述则填写教室类型原文，体育课等输出空字符串）、"
                + "week_range（上课周次，如 1-16，未确定输出空字符串）。"
                + "只输出 JSON 数组本身，不要输出任何解释文字，不要输出 markdown 代码块。";
    }

    private String resolveMimeType(String filename, String contentType) {
        if (contentType != null && contentType.startsWith("image/")) {
            return contentType;
        }
        String f = filename == null ? "" : filename.toLowerCase();
        if (f.endsWith(".png")) return "image/png";
        if (f.endsWith(".webp")) return "image/webp";
        if (f.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    /** 将 AI / 解析结果统一为课程清单字段（courseName / weekHours / teacherName / classroomNo / weekRange） */
    private List<Map<String, Object>> normalizeCourses(List<Map<String, Object>> raw) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (raw == null) {
            return list;
        }
        for (Map<String, Object> r : raw) {
            String courseName = pick(r, "course_name", "courseName", "课程名", "课程名称");
            if (courseName.isEmpty()) {
                continue;
            }
            int wh = parseIntSafe(pick(r, "week_hours", "weekHours", "周学时", "每周学时", "学时"));
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("courseName", courseName);
            c.put("weekHours", wh > 0 ? wh : 2);
            c.put("teacherName", pick(r, "teacher_name", "teacherName", "授课教师", "教师", "老师"));
            c.put("classroomNo", pick(r, "classroom_no", "classroomNo", "教室号", "教室"));
            c.put("weekRange", normalizeWeekRange(pick(r, "week_range", "weekRange", "周次", "周次范围")));
            list.add(c);
        }
        return list;
    }
}
