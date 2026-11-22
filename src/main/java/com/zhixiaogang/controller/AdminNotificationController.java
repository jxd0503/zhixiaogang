package com.zhixiaogang.controller;

import com.zhixiaogang.entity.User;
import com.zhixiaogang.service.OperationLogService;
import com.zhixiaogang.service.notification.NotificationHistoryService;
import com.zhixiaogang.service.notification.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/** 管理端通知管理：发送自定义通知与历史记录。 */
@Controller
@RequestMapping("/admin")
public class AdminNotificationController {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationHistoryService notificationHistoryService;

    @Autowired
    private OperationLogService operationLogService;

    /** 发送自定义通知：{title, content, scope: all_users|all_students|class, classId} */
    @PostMapping("/notifications")
    @ResponseBody
    public Map<String, Object> sendNotification(@RequestBody Map<String, Object> body, HttpSession session) {
        User admin = (User) session.getAttribute("user");
        String sender = admin != null && admin.getName() != null ? admin.getName() : "admin";
        Long classId = null;
        Object cid = body.get("classId");
        if (cid != null) {
            try {
                classId = Long.valueOf(String.valueOf(cid));
            } catch (NumberFormatException ignore) {
            }
        }
        Map<String, Object> result = notificationService.sendCustom(
                AdminWebSupport.str(body.get("title")), AdminWebSupport.str(body.get("content")),
                AdminWebSupport.str(body.get("scope")), classId, sender);
        AdminWebSupport.logAction(operationLogService, "send", "notification", null,
                "发送通知：" + AdminWebSupport.str(body.get("title"))
                        + "（范围 " + AdminWebSupport.str(body.get("scope")) + "）", result);
        return result;
    }

    /** 通知历史（按批次聚合，含系统自动通知与管理端自定义通知） */
    @GetMapping("/notifications/history")
    @ResponseBody
    public Map<String, Object> notificationHistory() {
        return notificationHistoryService.history();
    }
}
