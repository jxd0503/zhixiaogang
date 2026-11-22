package com.zhixiaogang.controller;

import com.zhixiaogang.entity.User;
import com.zhixiaogang.service.ScheduleConstraintService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 教师排课要求提交通道接口：
 * 教师端 /api/schedule-constraints/**（仅 teacher，非 teacher 返回 403）；
 * 管理端 /admin/schedule-constraints/**（由 AdminAuthInterceptor 保证仅 admin）。
 */
@RestController
public class ScheduleConstraintController {

    @Autowired
    private ScheduleConstraintService scheduleConstraintService;

    @Autowired
    private com.zhixiaogang.service.OperationLogService operationLogService;

    // ==================== 教师端 ====================

    /** 可选班级（本人管理或授课的班级） */
    @GetMapping("/api/schedule-constraints/classes")
    public Map<String, Object> classes(HttpSession session, HttpServletResponse response) {
        User user = requireTeacher(session, response);
        if (user == null) return denied();
        return scheduleConstraintService.listTeacherClasses(user);
    }

    /** 提交前试解析 */
    @PostMapping("/api/schedule-constraints/preview")
    public Map<String, Object> preview(@RequestBody Map<String, Object> body,
                                       HttpSession session, HttpServletResponse response) {
        User user = requireTeacher(session, response);
        if (user == null) return denied();
        return scheduleConstraintService.preview(str(body.get("text")), user);
    }

    /** 提交排课要求（PENDING，并通知管理员） */
    @PostMapping("/api/schedule-constraints/submit")
    public Map<String, Object> submit(@RequestBody Map<String, Object> body,
                                      HttpSession session, HttpServletResponse response) {
        User user = requireTeacher(session, response);
        if (user == null) return denied();
        Long classId = toLong(body.get("classId"));
        return logged(scheduleConstraintService.submit(user, str(body.get("text")),
                str(body.get("semester")), classId), "apply", "schedule_constraint", classId,
                "提交排课要求" + (classId == null ? "" : "（班级 #" + classId + "）"));
    }

    /** 我的排课要求列表 */
    @GetMapping("/api/schedule-constraints/my")
    public Map<String, Object> my(@RequestParam(required = false) String semester,
                                  @RequestParam(required = false) String status,
                                  HttpSession session, HttpServletResponse response) {
        User user = requireTeacher(session, response);
        if (user == null) return denied();
        return scheduleConstraintService.myList(user, semester, status);
    }

    /** 本学期本人有效（已采纳）的排课约束清单 */
    @GetMapping("/api/schedule-constraints/effective")
    public Map<String, Object> effective(@RequestParam(required = false) String semester,
                                         HttpSession session, HttpServletResponse response) {
        User user = requireTeacher(session, response);
        if (user == null) return denied();
        return scheduleConstraintService.myEffective(user, semester);
    }

    /** 撤销单条待处理约束 */
    @PostMapping("/api/schedule-constraints/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id,
                                      HttpSession session, HttpServletResponse response) {
        User user = requireTeacher(session, response);
        if (user == null) return denied();
        return logged(scheduleConstraintService.cancel(user, id), "cancel", "schedule_constraint", id,
                "撤销排课要求 #" + id);
    }

    /** 批量撤销待处理约束 */
    @PostMapping("/api/schedule-constraints/batch-cancel")
    public Map<String, Object> batchCancel(@RequestBody Map<String, Object> body,
                                           HttpSession session, HttpServletResponse response) {
        User user = requireTeacher(session, response);
        if (user == null) return denied();
        List<Long> ids = toLongList(body.get("ids"));
        return logged(scheduleConstraintService.batchCancel(user, ids), "cancel", "schedule_constraint", null,
                "批量撤销排课要求 " + ids.size() + " 条：" + idsText(ids));
    }

    // ==================== 管理端 ====================

    /** 教师已提交排课要求列表（默认待处理 + 已采纳） */
    @GetMapping("/admin/schedule-constraints/list")
    public Map<String, Object> adminList(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) String semester) {
        return scheduleConstraintService.adminList(status, semester);
    }

    /** 待处理数量（角标） */
    @GetMapping("/admin/schedule-constraints/pending-count")
    public Map<String, Object> pendingCount() {
        return scheduleConstraintService.pendingCount();
    }

    /** 批量采纳 */
    @PostMapping("/admin/schedule-constraints/adopt")
    public Map<String, Object> adopt(@RequestBody Map<String, Object> body) {
        List<Long> ids = toLongList(body.get("ids"));
        return logged(scheduleConstraintService.adopt(ids, str(body.get("note"))),
                "approve", "schedule_constraint", null,
                "批量采纳排课要求 " + ids.size() + " 条：" + idsText(ids));
    }

    /** 批量驳回（必填原因） */
    @PostMapping("/admin/schedule-constraints/reject")
    public Map<String, Object> reject(@RequestBody Map<String, Object> body) {
        List<Long> ids = toLongList(body.get("ids"));
        return logged(scheduleConstraintService.reject(ids, str(body.get("reason"))),
                "reject", "schedule_constraint", null,
                "批量驳回排课要求 " + ids.size() + " 条：" + idsText(ids));
    }

    /** 一键填入排课约束输入框（勾选记录转已采纳并返回合并文本） */
    @PostMapping("/admin/schedule-constraints/fill")
    public Map<String, Object> fill(@RequestBody Map<String, Object> body) {
        List<Long> ids = toLongList(body.get("ids"));
        return logged(scheduleConstraintService.fill(ids, str(body.get("semester"))),
                "update", "schedule_constraint", null,
                "填入排课约束 " + ids.size() + " 条：" + idsText(ids));
    }

    /** 排课未生效 / 冲突后一键通知教师（命中记录标记为无法满足） */
    @PostMapping("/admin/schedule-constraints/notify-unmatched")
    public Map<String, Object> notifyUnmatched(@RequestBody Map<String, Object> body) {
        List<String> messages = new ArrayList<>();
        Object raw = body.get("messages");
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o != null && !String.valueOf(o).trim().isEmpty()) {
                    messages.add(String.valueOf(o).trim());
                }
            }
        }
        return logged(scheduleConstraintService.notifyUnmatched(messages, str(body.get("semester"))),
                "send", "schedule_constraint", null, "通知未生效排课要求 " + messages.size() + " 条");
    }

    /** 学期切换：将指定学期的已采纳约束置为过期 */
    @PostMapping("/admin/schedule-constraints/expire")
    public Map<String, Object> expire(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        String semester = str(body.get("semester"));
        int count = scheduleConstraintService.expireAdopted(semester);
        result.put("success", true);
        result.put("count", count);
        result.put("message", "已失效 " + count + " 条已采纳约束");
        return logged(result, "update", "schedule_constraint", null,
                "失效学期 " + semester + " 已采纳排课要求 " + count + " 条");
    }

    /** 约束统计（按教师 / 学期：提交数、采纳数、驳回数） */
    @GetMapping("/admin/schedule-constraints/stats")
    public Map<String, Object> stats(@RequestParam(required = false) String semester) {
        return scheduleConstraintService.stats(semester);
    }

    // ==================== 内部工具 ====================

    /** 取当前教师：未登录 401、非教师 403，并返回 null */
    private User requireTeacher(HttpSession session, HttpServletResponse response) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            response.setStatus(401);
            return null;
        }
        if (!"teacher".equals(user.getRole())) {
            response.setStatus(403);
            return null;
        }
        return user;
    }

    private Map<String, Object> denied() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", "仅教师可提交排课要求");
        return result;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /** 记录日志后返回业务结果 */
    private Map<String, Object> logged(Map<String, Object> result, String action, String targetType,
                                       Object targetId, String detail) {
        boolean ok = result != null && Boolean.TRUE.equals(result.get("success"));
        operationLogService.record(action, targetType, targetId, detail,
                ok, ok ? null : (result == null ? null : String.valueOf(result.get("message"))));
        return result;
    }

    /** 批量操作日志的 id 摘要（最多展示前 10 个，避免 detail 过长） */
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

    private static Long toLong(Object o) {
        if (o == null || String.valueOf(o).trim().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Long> toLongList(Object o) {
        List<Long> ids = new ArrayList<>();
        if (o instanceof List) {
            for (Object e : (List<?>) o) {
                Long id = toLong(e);
                if (id != null) {
                    ids.add(id);
                }
            }
        } else {
            Long id = toLong(o);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }
}
