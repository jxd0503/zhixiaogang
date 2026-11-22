package com.zhixiaogang.controller;

import com.zhixiaogang.service.AdminClassService;
import com.zhixiaogang.service.OperationLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 管理端班级管理：班级增删改查、成员批量加入、班级课表维护与 AI 导入。 */
@Controller
@RequestMapping("/admin")
public class AdminClassController {

    private static final Logger log = LoggerFactory.getLogger(AdminClassController.class);

    @Autowired
    private AdminClassService adminClassService;

    @Autowired
    private OperationLogService operationLogService;

    /** 班级列表（含学生数、课表条目数统计），分页返回 */
    @GetMapping("/classes")
    @ResponseBody
    public Map<String, Object> listClasses(@RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        return adminClassService.listClasses(page, size);
    }

    /** 添加班级：{className, grade, department, teacherId} */
    @PostMapping("/classes")
    @ResponseBody
    public Map<String, Object> addClass(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = adminClassService.addClass(
                AdminWebSupport.str(body.get("className")), AdminWebSupport.str(body.get("grade")),
                AdminWebSupport.str(body.get("department")), AdminWebSupport.str(body.get("teacherId")));
        AdminWebSupport.logAction(operationLogService, "create", "class", result.get("id"),
                "新增班级：" + AdminWebSupport.str(body.get("className")), result);
        return result;
    }

    /** 修改班级 */
    @PutMapping("/classes/{id}")
    @ResponseBody
    public Map<String, Object> updateClass(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> result = adminClassService.updateClass(
                id, AdminWebSupport.str(body.get("className")), AdminWebSupport.str(body.get("grade")),
                AdminWebSupport.str(body.get("department")), AdminWebSupport.str(body.get("teacherId")));
        AdminWebSupport.logAction(operationLogService, "update", "class", id,
                "修改班级：" + AdminWebSupport.str(body.get("className")), result);
        return result;
    }

    /** 删除班级（联动清理成员关系与课表条目） */
    @DeleteMapping("/classes/{id}")
    @ResponseBody
    public Map<String, Object> deleteClass(@PathVariable Long id) {
        Map<String, Object> result;
        try {
            result = adminClassService.deleteClass(id);
        } catch (Exception e) {
            log.error("删除班级失败: id={}", id, e);
            result = new HashMap<>();
            result.put("success", false);
            result.put("message", "班级删除失败，请稍后重试");
        }
        AdminWebSupport.logAction(operationLogService, "delete", "class", id, "删除班级 #" + id, result);
        return result;
    }

    /** 班级详情：班级信息 + 课表 + 学生（含状态） */
    @GetMapping("/classes/{id}")
    @ResponseBody
    public Map<String, Object> classDetail(@PathVariable Long id) {
        return adminClassService.getClassDetail(id);
    }

    /** 可添加学生列表（含本班 / 其他班归属，前端据此禁用不可选学生） */
    @GetMapping("/classes/{id}/addable-students")
    @ResponseBody
    public Map<String, Object> addableStudents(@PathVariable Long id) {
        return adminClassService.listAddableStudents(id);
    }

    /** 批量将学生直接加入班级：{studentIds:["2024003", ...]} */
    @PostMapping("/classes/{id}/members")
    @ResponseBody
    public Map<String, Object> addMembers(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        List<String> studentIds = new ArrayList<>();
        Object raw = body.get("studentIds");
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o != null) studentIds.add(o.toString());
            }
        }
        Map<String, Object> result = adminClassService.addMembers(id, studentIds);
        AdminWebSupport.logAction(operationLogService, "update", "class", id, "批量加入学生 " + studentIds.size() + " 人："
                + String.join(",", studentIds.subList(0, Math.min(10, studentIds.size()))), result);
        return result;
    }

    /** 班级课表列表；传入 week 时只返回该周可见课表（含生效调课的换周逻辑） */
    @GetMapping("/classes/{id}/schedule")
    @ResponseBody
    public Map<String, Object> classSchedule(@PathVariable Long id,
                                             @RequestParam(required = false) Integer week) {
        return adminClassService.listSchedule(id, week);
    }

    /** 手动添加课表条目 */
    @PostMapping("/classes/{id}/schedule")
    @ResponseBody
    public Map<String, Object> addClassSchedule(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> result = adminClassService.addSchedule(id, body);
        AdminWebSupport.logAction(operationLogService, "create", "schedule", result.get("scheduleId"),
                "新增课表（班级 #" + id + "）：" + AdminWebSupport.str(body.get("courseName"))
                        + " 周" + AdminWebSupport.str(body.get("weekDay"))
                        + " 第" + AdminWebSupport.str(body.get("startPeriod")) + "节", result);
        return result;
    }

    /** 修改课表条目 */
    @PutMapping("/classes/{id}/schedule/{sid}")
    @ResponseBody
    public Map<String, Object> updateClassSchedule(@PathVariable Long id, @PathVariable Long sid,
                                                   @RequestBody Map<String, Object> body) {
        Map<String, Object> result = adminClassService.updateSchedule(id, sid, body);
        AdminWebSupport.logAction(operationLogService, "update", "schedule", sid, "修改课表 #" + sid + "（班级 #" + id + "）："
                + AdminWebSupport.str(body.get("courseName")), result);
        return result;
    }

    /** 删除课表条目 */
    @DeleteMapping("/classes/{id}/schedule/{sid}")
    @ResponseBody
    public Map<String, Object> deleteClassSchedule(@PathVariable Long id, @PathVariable Long sid) {
        Map<String, Object> result = adminClassService.deleteSchedule(id, sid);
        AdminWebSupport.logAction(operationLogService, "delete", "schedule", sid,
                "删除课表 #" + sid + "（班级 #" + id + "）", result);
        return result;
    }

    /** 批量删除课表条目：合并为一条操作日志，避免逐条刷屏 */
    @PostMapping("/classes/{id}/schedule/batch-delete")
    @ResponseBody
    public Map<String, Object> batchDeleteClassSchedule(@PathVariable Long id,
                                                        @RequestBody Map<String, Object> body) {
        List<Long> ids = new ArrayList<>();
        Object raw = body.get("ids");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                try {
                    ids.add(Long.valueOf(String.valueOf(item)));
                } catch (NumberFormatException ignored) {
                    // 忽略非法 id
                }
            }
        }
        Map<String, Object> result = new HashMap<>();
        int deleted = 0;
        for (Long sid : ids) {
            Map<String, Object> item = adminClassService.deleteSchedule(id, sid);
            if (item != null && Boolean.TRUE.equals(item.get("success"))) {
                deleted++;
            }
        }
        boolean ok = !ids.isEmpty() && deleted == ids.size();
        result.put("success", ok);
        result.put("deleted", deleted);
        if (!ok) {
            result.put("message", "已删除 " + deleted + "/" + ids.size() + " 条");
        }
        AdminWebSupport.logAction(operationLogService, "delete", "schedule", null,
                "批量删除课表 " + deleted + " 条（班级 #" + id + "）：id=" + idsText(ids), result);
        return result;
    }

    /** 批量 id 摘要：最多展示前 10 个，避免 detail 过长 */
    private static String idsText(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size() && i < 10; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        if (ids.size() > 10) {
            sb.append("…");
        }
        return sb.toString();
    }

    /** AI 文字导入课表：解析预览（不入库），body {text, semester} */
    @PostMapping("/classes/{id}/schedule/import-text")
    @ResponseBody
    public Map<String, Object> importScheduleText(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String text = body.get("text");
        Map<String, Object> result = adminClassService.importPreviewText(id, text, body.get("semester"));
        AdminWebSupport.logAction(operationLogService, "import", "schedule", id,
                "文字导入课表预览（班级 #" + id + "，文本 " + (text == null ? 0 : text.length()) + " 字）", result);
        return result;
    }

    /** AI 识图导入课表：解析预览（不入库），multipart 上传 image */
    @PostMapping("/classes/{id}/schedule/import-image")
    @ResponseBody
    public Map<String, Object> importScheduleImage(@PathVariable Long id,
                                                   @RequestParam(value = "image", required = false)
                                                   org.springframework.web.multipart.MultipartFile image,
                                                   @RequestParam(value = "semester", required = false) String semester) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (image == null || image.isEmpty()) {
                result.put("success", false);
                result.put("message", "请上传课表图片");
            } else {
                long maxBytes = 5L * 1024 * 1024;
                if (image.getSize() > maxBytes) {
                    result.put("success", false);
                    result.put("message", "图片大小超过 5MB 限制，请压缩后重新上传");
                } else {
                    String contentType = image.getContentType();
                    String original = image.getOriginalFilename() != null ? image.getOriginalFilename().toLowerCase() : "";
                    boolean nameOk = original.endsWith(".jpg") || original.endsWith(".jpeg")
                            || original.endsWith(".png") || original.endsWith(".webp");
                    boolean typeOk = contentType != null && (contentType.equals("image/jpeg")
                            || contentType.equals("image/png") || contentType.equals("image/webp"));
                    if (!nameOk || !typeOk) {
                        result.put("success", false);
                        result.put("message", "仅支持 jpg/png/webp 格式图片");
                    } else {
                        byte[] bytes = image.getBytes();
                        String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
                        result = adminClassService.importPreviewImage(id, base64, contentType, semester);
                    }
                }
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "图片解析失败，请稍后重试");
        }
        AdminWebSupport.logAction(operationLogService, "import", "schedule", id,
                "识图导入课表预览（班级 #" + id + "）", result);
        return result;
    }

    /** AI 导入课表：预览确认批量入库，body {items:[...], semester} */
    @PostMapping("/classes/{id}/schedule/import-confirm")
    @ResponseBody
    public Map<String, Object> importScheduleConfirm(@PathVariable Long id,
                                                     @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        int itemCount = 0;
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
            itemCount = items == null ? 0 : items.size();
            String semester = body.get("semester") != null ? String.valueOf(body.get("semester")) : null;
            result = adminClassService.importConfirm(id, items, semester);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "导入失败，请稍后重试");
        }
        AdminWebSupport.logAction(operationLogService, itemCount > 1 ? "import" : "confirm", "schedule", id,
                "导入课表确认入库（班级 #" + id + "，共 " + itemCount + " 条）", result);
        return result;
    }

    /** 一次性数据回填：为 teacher_id 为空的课表解析/创建教师账号并回填（幂等） */
    @PostMapping("/classes/backfill-teacher-ids")
    @ResponseBody
    public Map<String, Object> backfillTeacherIds() {
        Map<String, Object> result = adminClassService.backfillTeacherIds();
        AdminWebSupport.logAction(operationLogService, "update", "schedule", null, "回填课表教师ID", result);
        return result;
    }
}
