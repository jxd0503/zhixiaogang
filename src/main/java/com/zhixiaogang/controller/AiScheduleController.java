package com.zhixiaogang.controller;

import com.zhixiaogang.service.schedule.AiScheduleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 自动排课接口（仅管理端可访问，受 AdminAuthInterceptor 保护）。
 * 课程输入：课程库 / 文字解析 / Excel 导入 / 图片识图；生成预览（不写库）与确认入库。
 */
@RestController
@RequestMapping("/admin/ai-schedule")
public class AiScheduleController {

    @Autowired
    private AiScheduleService aiScheduleService;

    @Autowired
    private com.zhixiaogang.service.ScheduleConstraintService scheduleConstraintService;

    @Autowired
    private com.zhixiaogang.service.OperationLogService operationLogService;

    /** 课程库列表（课程名、周学时、授课教师、教室类型） */
    @GetMapping("/courses")
    public Map<String, Object> listCourses() {
        return aiScheduleService.listCourses();
    }

    /** 自然文字解析课程清单，body {text} */
    @PostMapping("/parse-text")
    public Map<String, Object> parseText(@RequestBody Map<String, Object> body) {
        String text = body.get("text") == null ? "" : String.valueOf(body.get("text"));
        return logged(aiScheduleService.parseText(text), "import", "schedule", null,
                "AI 文字解析课程清单（" + text.length() + " 字）");
    }

    /** Excel 导入课程清单，multipart 上传 file */
    @PostMapping("/parse-excel")
    public Map<String, Object> parseExcel(@RequestParam(value = "file", required = false) MultipartFile file) {
        String detail = "导入 Excel 课程清单"
                + (file == null ? "" : "：" + file.getOriginalFilename() + "（" + file.getSize() + " 字节）");
        return logged(aiScheduleService.parseExcel(file), "import", "schedule", null, detail);
    }

    /** 图片识图解析课程清单，multipart 上传 image */
    @PostMapping("/parse-image")
    public Map<String, Object> parseImage(
            @RequestParam(value = "image", required = false) MultipartFile image,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        MultipartFile target = image != null ? image : file;
        Map<String, Object> result = new HashMap<>();
        if (target == null || target.isEmpty()) {
            result.put("success", false);
            result.put("message", "请上传课程计划图片");
            return result;
        }
        long maxBytes = 5L * 1024 * 1024;
        if (target.getSize() > maxBytes) {
            result.put("success", false);
            result.put("message", "图片大小超过 5MB 限制，请压缩后重新上传");
            return result;
        }
        return logged(aiScheduleService.parseImage(target), "import", "schedule", null,
                "AI 识图导入课程清单：" + target.getOriginalFilename());
    }

    /** 生成排课预览（不写库），body 见 AiScheduleService.generate */
    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestBody Map<String, Object> body) {
        return logged(aiScheduleService.generate(body), "generate", "schedule", null, "AI 排课生成预览");
    }

    /** 自然语言调整预览（不写库），body {items:[...], instruction, semester, weekStart, weekEnd, minPeriod, maxPeriod} */
    @PostMapping("/modify")
    public Map<String, Object> modify(@RequestBody Map<String, Object> body) {
        Object instruction = body.get("instruction");
        return logged(aiScheduleService.modify(body), "update", "schedule", null,
                "AI 排课自然语言调整：" + (instruction == null ? "" : String.valueOf(instruction)));
    }

    /** 确认入库，body {items:[...], semester} */
    @PostMapping("/confirm")
    public Map<String, Object> confirm(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
            String semester = body.get("semester") != null ? String.valueOf(body.get("semester")) : null;
            Map<String, Object> confirmResult = aiScheduleService.confirm(items, semester);
            // 课表确认入库后，该学期已采纳的教师排课要求随之失效（避免跨学期误用）
            if (Boolean.TRUE.equals(confirmResult.get("success"))) {
                scheduleConstraintService.expireAdopted(semester);
            }
            return logged(confirmResult, "confirm", "schedule", null,
                    "AI 排课确认入库 " + (items == null ? 0 : items.size()) + " 条"
                            + (semester == null ? "" : "（" + semester + "）"));
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "课表入库失败，请稍后重试");
            return logged(result, "confirm", "schedule", null, "AI 排课确认入库失败");
        }
    }

    /** 记录日志后返回业务结果 */
    private Map<String, Object> logged(Map<String, Object> result, String action, String targetType,
                                       Object targetId, String detail) {
        boolean ok = result != null && Boolean.TRUE.equals(result.get("success"));
        operationLogService.record(action, targetType, targetId, detail,
                ok, ok ? null : (result == null ? null : String.valueOf(result.get("message"))));
        return result;
    }
}
