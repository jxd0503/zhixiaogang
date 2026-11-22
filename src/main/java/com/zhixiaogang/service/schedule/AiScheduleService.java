package com.zhixiaogang.service.schedule;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.zhixiaogang.service.AdminClassService;
import com.zhixiaogang.service.SemesterConfigService;
import com.zhixiaogang.service.schedule.AiScheduleModels.*;
import static com.zhixiaogang.service.schedule.AiScheduleUtils.*;
import static com.zhixiaogang.service.schedule.AiConstraintEntrySupport.*;
import static com.zhixiaogang.service.schedule.AiScheduleTexts.*;

/**
 * AI 自动排课服务入口：课程清单解析委托、约束试解析、排课编排（准备 → 分配 → 校验）、预览确认入库。
 */
@Service
public class AiScheduleService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SemesterConfigService semesterConfigService;

    @Autowired
    private AdminClassService adminClassService;

    @Autowired
    private AiCourseImportService aiCourseImportService;

    @Autowired
    private AiConstraintParser aiConstraintParser;

    @Autowired
    private AiSchedulePreparer aiSchedulePreparer;

    @Autowired
    private AiSchedulingEngine aiSchedulingEngine;

    @Autowired
    private AiScheduleModifier aiScheduleModifier;

    /** 试解析等待上限（秒）：预览失败仍可按原文提交，不做重试，避免等待翻倍 */
    private static final long PREVIEW_AI_TIMEOUT_SECONDS = 60L;

    /** 课程库列表：课程名、周学时、授课教师、教室号 */
    public Map<String, Object> listCourses() {
        return aiCourseImportService.listCourses();
    }

    /** 解析文字课程清单 */
    public Map<String, Object> parseText(String text) {
        return aiCourseImportService.parseText(text);
    }

    /** 解析 Excel 课程清单 */
    public Map<String, Object> parseExcel(MultipartFile file) {
        return aiCourseImportService.parseExcel(file);
    }

    /** 解析图片课程清单 */
    public Map<String, Object> parseImage(MultipartFile file) {
        return aiCourseImportService.parseImage(file);
    }

    /**
     * 教师排课要求「试解析」：把教师提交的自然语言预解析为约束条目清单，仅用于提交前核对，不参与排课。
     * 教师姓名会自动作为主语前缀标注，保证多教师约束合并后仍能正确归属。
     */
    public Map<String, Object> previewConstraints(String text, String teacherName) {
        Map<String, Object> result = new HashMap<>();
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            result.put("success", false);
            result.put("message", "请填写排课要求");
            return result;
        }
        String tn = teacherName == null ? "" : teacherName.trim();
        // 先做合规标准化：跨教师条目剔除，无主语条目补全本人姓名
        ConstraintTextNormalizer.Result norm = normalizeConstraintText(tn, t);
        if (norm.hasRemoved()) {
            result.put("success", false);
            result.put("message", "排课要求只能填写本人约束，请移除涉及其他教师的内容：" + String.join("、", norm.removedSegments));
            result.put("removed", norm.removedSegments);
            result.put("normalizedText", norm.displayText);
            result.put("items", new ArrayList<>());
            result.put("unrecognized", new ArrayList<>());
            result.put("warnings", new ArrayList<>());
            return result;
        }
        if (norm.displayText.isEmpty()) {
            result.put("success", false);
            result.put("message", "请填写排课要求");
            result.put("items", new ArrayList<>());
            return result;
        }
        Set<String> teacherNames = new HashSet<>();
        Set<String> workloadTeachers = new HashSet<>();
        if (!tn.isEmpty()) {
            teacherNames.add(tn);
            workloadTeachers.add(tn);
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT name FROM user WHERE role = 'teacher' AND name IS NOT NULL AND name <> ''")) {
            String name = str(row.get("name"));
            if (!name.isEmpty()) {
                teacherNames.add(name);
            }
        }
        int weekEnd = 16;
        try {
            com.zhixiaogang.entity.SemesterConfig cfg = semesterConfigService.getCurrentSemester();
            if (cfg != null && cfg.getWeeks() != null && cfg.getWeeks() > 0) {
                weekEnd = cfg.getWeeks();
            }
        } catch (Exception ignore) {
            // 学期配置读取失败时保持默认 16
        }
        ConstraintParseResult parsed = aiConstraintParser.parseConstraints(norm.parseText, weekEnd,
                teacherNames, workloadTeachers, PREVIEW_AI_TIMEOUT_SECONDS, false);
        if (parsed.fatalError != null) {
            result.put("success", false);
            result.put("message", parsed.fatalError);
            result.put("items", new ArrayList<>());
            result.put("unrecognized", parsed.unrecognized);
            result.put("warnings", parsed.warnings);
            return result;
        }
        List<ConstraintEntry> entries = new ArrayList<>();
        for (Constraint c : parsed.constraints) {
            mergeConstraintEntry(entries, c);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).id = i + 1;
            items.add(constraintItem(entries.get(i)));
        }
        result.put("success", true);
        result.put("items", items);
        result.put("unrecognized", parsed.unrecognized);
        result.put("warnings", parsed.warnings);
        result.put("count", items.size());
        result.put("normalizedText", norm.displayText);
        return result;
    }

    /**
     * 给自然语言约束标注教师主语，用于管理端多教师约束合并。
     * 跨教师条目一律剔除，无主语条目补全本人姓名，第一人称条目解析时前置姓名，保证归属本人。
     */
    public String attributeConstraintText(String teacherName, String text) {
        return normalizeConstraintText(teacherName, text).parseText;
    }

    /** 约束文本合规标准化：剔除针对其他教师的条目并补全主语，展示文本与解析文本分别返回 */
    public ConstraintTextNormalizer.Result normalizeConstraintText(String teacherName, String text) {
        return ConstraintTextNormalizer.normalize(teacherName, text, otherTeacherNames(teacherName));
    }

    /** 其他教师姓名集合（排除本人），用于识别跨教师约束条目 */
    private Set<String> otherTeacherNames(String teacherName) {
        Set<String> names = new HashSet<>();
        String self = teacherName == null ? "" : teacherName.trim();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT name FROM user WHERE role = 'teacher' AND name IS NOT NULL AND name <> ''")) {
            String name = str(row.get("name"));
            if (!name.isEmpty() && !name.equals(self)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * 生成排课预览（不写库）。request 字段：
     * mode(semester/single)、classIds、semester、weekStart、weekEnd、minPeriod、maxPeriod、
     * allowWeekend、allowEvening、courses[{courseName,weekHours,teacherName,classroomNo,weekRange}]。
     *
     * 编排顺序：准备基础数据（阶段 A）→ 准备约束（阶段 B）→ 逐课程分配（阶段 C）→ 冲突校验与结果（阶段 D）。
     */
    public Map<String, Object> generate(Map<String, Object> request) {
        try {
            // 前置校验（请求进入即执行）：目标班级已有课程表时直接阻断，避免重复排课覆盖数据
            Map<String, Object> blocked = aiSchedulePreparer.blockExistingSchedule(request);
            if (blocked != null) {
                return blocked;
            }
            AiScheduleContext ctx = new AiScheduleContext(request);
            Map<String, Object> err = aiSchedulePreparer.prepareBasics(ctx);
            if (err != null) {
                return err;
            }
            err = aiSchedulePreparer.prepareConstraints(ctx);
            if (err != null) {
                return err;
            }
            aiSchedulingEngine.allocate(ctx);
            return aiSchedulingEngine.finish(ctx);
        } catch (Exception e) {
            return fail("排课生成失败：" + rootMessage(e));
        }
    }

    /** 确认入库：复用 AI 导入课表确认写库路径（课程 / 教室 / 教师自动创建与回填） */
    public Map<String, Object> confirm(List<Map<String, Object>> items, String semester) {
        return adminClassService.importConfirmMulti(items, semester);
    }

    /** 自然语言调整预览：把当前预览条目（带临时 idx）+ 用户调整指令交给模型，模型输出调整后的条目 */
    public Map<String, Object> modify(Map<String, Object> request) {
        return aiScheduleModifier.modify(request);
    }
}
