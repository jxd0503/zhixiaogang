package com.zhixiaogang.controller;

import com.zhixiaogang.entity.User;
import com.zhixiaogang.service.LoginAttemptService;
import com.zhixiaogang.service.OperationLogService;
import com.zhixiaogang.service.UserService;
import com.zhixiaogang.service.impl.QwenAIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/** 学生端登录/注册/登出及 AI 可用状态探测。 */
@Controller
public class AuthController {

    @Autowired
    private UserService userService;

    @Autowired
    private QwenAIService qwenAIService;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private LoginAttemptService loginAttemptService;

    @Autowired
    private com.zhixiaogang.service.classroom.ClassroomService classroomService;

    /**
     * 只读 AI 可用状态：供学生端 / 教师端静默探测，路径不经 /admin 鉴权拦截器，避免控制台 403 噪音。
     * 仅返回可用性标志，不暴露任何管理端能力。
     */
    @GetMapping("/api/ai/status")
    @ResponseBody
    public Map<String, Object> aiStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("disabled", qwenAIService.isAiDisabled());
        result.put("enabled", qwenAIService.isEnabled());
        return result;
    }

    @GetMapping("/")
    public String index(HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user != null) {
            return "redirect:/home";
        }
        return "login";
    }

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String studentId, @RequestParam String password,
                       HttpSession session, HttpServletRequest request, Model model,
                       jakarta.servlet.http.HttpServletResponse response) {
        String attemptKey = "student:" + studentId;
        if (loginAttemptService.isLocked(attemptKey)) {
            long seconds = loginAttemptService.remainingLockSeconds(attemptKey);
            model.addAttribute("error", "登录失败次数过多，请 " + Math.max(1, (seconds + 59) / 60) + " 分钟后再试");
            response.setStatus(401);
            return "login";
        }

        Map<String, Object> result = userService.login(studentId, password);
        
        if ((Boolean) result.get("success")) {
            User user = (User) result.get("user");
            if (!"student".equals(user.getRole())) {
                loginAttemptService.recordFailure(attemptKey);
                operationLogService.recordAnonymous("login", studentId, user.getRole(), "user", studentId,
                        "学生端登录（角色不符）", "failed: 请使用正确入口登录");
                // P2-5 按角色给出准确的入口指引
                if ("teacher".equals(user.getRole())) {
                    model.addAttribute("error", "请使用教师端登录");
                } else if ("admin".equals(user.getRole())) {
                    model.addAttribute("error", "请使用管理端登录");
                } else {
                    model.addAttribute("error", "请使用正确的入口登录");
                }
                response.setStatus(401);
                return "login";
            }
            loginAttemptService.reset(attemptKey);
            // 登录成功后更换会话 ID，防止会话固定攻击
            request.changeSessionId();
            session.setAttribute("user", user);
            operationLogService.record("login", "user", user.getStudentId(),
                    "学生端登录：" + user.getName(), true);
            return "redirect:/home";
        } else {
            loginAttemptService.recordFailure(attemptKey);
            operationLogService.recordAnonymous("login", studentId, "student", "user", studentId,
                    "学生端登录", "failed: " + result.get("message"));
            model.addAttribute("error", result.get("message"));
            response.setStatus(401);
            return "login";
        }
    }

    @GetMapping("/register")
    public String registerPage() {
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String studentId, @RequestParam String phone, 
                          @RequestParam String password, @RequestParam(required = false) String department,
                          @RequestParam(required = false) String major, Model model,
                          jakarta.servlet.http.HttpServletResponse response) {
        Map<String, Object> result = userService.register(studentId, phone, password, department, major);
        
        if ((Boolean) result.get("success")) {
            operationLogService.recordAnonymous("register", studentId, "student", "user", studentId,
                    "学生注册：" + (department == null ? "" : department), "success");
            model.addAttribute("success", "注册成功，请登录");
            return "login";
        } else {
            operationLogService.recordAnonymous("register", studentId, "student", "user", studentId,
                    "学生注册", "failed: " + result.get("message"));
            model.addAttribute("error", result.get("message"));
            response.setStatus(400);
            return "register";
        }
    }

    @PostMapping("/logout")
    public String logout(HttpSession session) {
        operationLogService.record("logout", "user", null, "学生端登出", true);
        session.invalidate();
        return "redirect:/login";
    }

    @GetMapping("/home")
    public String home(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        // 学生端页面仅对学生开放，其他角色跳转到各自入口
        if (!"student".equals(user.getRole())) {
            if ("teacher".equals(user.getRole())) {
                return "redirect:/teacher/home";
            }
            if ("admin".equals(user.getRole())) {
                return "redirect:/admin/home";
            }
            return "redirect:/login";
        }
        model.addAttribute("user", user);
        model.addAttribute("buildings", classroomService.getBuildings());
        return "home";
    }
}
