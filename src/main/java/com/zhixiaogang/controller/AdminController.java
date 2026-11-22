package com.zhixiaogang.controller;

import com.zhixiaogang.entity.User;
import com.zhixiaogang.service.AdminService;
import com.zhixiaogang.service.OperationLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.Map;

/**
 * 管理端入口：登录/首页/登出页面路由与概览统计。
 * 业务域已拆分至 AdminClassController、AdminUserController、AdminClassroomController、
 * AdminReservationController、AdminNotificationController、AdminAiController。
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    @Autowired
    private AdminService adminService;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private com.zhixiaogang.service.classroom.ClassroomService classroomService;

    @GetMapping("/login")
    public String showLoginPage() {
        return "admin-login";
    }

    @PostMapping("/login")
    @ResponseBody
    public Map<String, Object> login(@RequestBody Map<String, String> loginData, HttpSession session,
                                     jakarta.servlet.http.HttpServletRequest request) {
        String username = loginData.get("username");
        String password = loginData.get("password");

        Map<String, Object> result = adminService.login(username, password);
        Object auditRole = result.remove("_auditRole");
        Object auditDetail = result.remove("_auditDetail");
        Object auditResult = result.remove("_auditResult");

        if (Boolean.TRUE.equals(result.get("success"))) {
            User user = (User) result.remove("user");
            // 登录成功后更换会话 ID，防止会话固定攻击
            request.changeSessionId();
            session.setAttribute("user", user);
            operationLogService.record("login", "user", user.getStudentId(),
                    "管理端登录：" + user.getName(), true);
        } else if (auditDetail != null) {
            operationLogService.recordAnonymous("login", username,
                    auditRole == null ? null : String.valueOf(auditRole), "user", username,
                    String.valueOf(auditDetail), String.valueOf(auditResult));
        }
        return result;
    }

    @GetMapping("/home")
    public String showHomePage(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/admin/login";
        }
        // 管理端页面仅对管理员开放，其他角色跳转到各自入口
        if (!"admin".equals(user.getRole())) {
            if ("teacher".equals(user.getRole())) {
                return "redirect:/teacher/home";
            }
            return "redirect:/admin/login";
        }
        model.addAttribute("user", user);
        model.addAttribute("buildings", classroomService.getBuildings());
        return "admin-home";
    }

    @PostMapping("/logout")
    public String logout(HttpSession session) {
        operationLogService.record("logout", "user", null, "管理端登出", true);
        session.invalidate();
        return "redirect:/admin/login";
    }

    @GetMapping("/dashboard")
    @ResponseBody
    public Map<String, Object> getDashboard() {
        return adminService.dashboard();
    }
}
