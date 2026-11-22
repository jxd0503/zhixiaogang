package com.zhixiaogang.controller;

import com.zhixiaogang.entity.User;
import com.zhixiaogang.service.OperationLogService;
import com.zhixiaogang.service.notification.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/**
 * 学生端 / 教师端通用通知接口：仅允许读取与操作本人通知。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationApiController {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private OperationLogService operationLogService;

    /** 本人通知列表（最新在前） */
    @GetMapping
    public Map<String, Object> list(HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return fail("请先登录");
        }
        return notificationService.listForUser(user.getId());
    }

    /** 未读数量（供铃铛角标） */
    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount(HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return fail("请先登录");
        }
        return notificationService.unreadCount(user.getId());
    }

    /** 单条标记已读 */
    @PostMapping("/{id}/read")
    public Map<String, Object> markRead(@PathVariable Long id, HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return fail("请先登录");
        }
        Map<String, Object> res = notificationService.markRead(user.getId(), id);
        return logged(res, "read", "notification", id, "标记通知已读 #" + id);
    }

    /** 全部标记已读 */
    @PostMapping("/read-all")
    public Map<String, Object> markReadAll(HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return fail("请先登录");
        }
        Map<String, Object> res = notificationService.markReadAll(user.getId());
        return logged(res, "read", "notification", null, "全部标记通知已读");
    }

    /** 记录日志后返回业务结果 */
    private Map<String, Object> logged(Map<String, Object> result, String action, String targetType,
                                       Object targetId, String detail) {
        boolean ok = result != null && Boolean.TRUE.equals(result.get("success"));
        operationLogService.record(action, targetType, targetId, detail,
                ok, ok ? null : (result == null ? null : String.valueOf(result.get("message"))));
        return result;
    }

    private Map<String, Object> fail(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", message);
        return result;
    }
}
