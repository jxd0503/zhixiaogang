package com.zhixiaogang.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixiaogang.entity.User;
import com.zhixiaogang.mapper.UserMapper;
import com.zhixiaogang.service.OperationLogService;
import com.zhixiaogang.util.PasswordUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/** 教师端登录、首页与登出。 */
@Controller
@RequestMapping("/teacher")
public class TeacherController {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private com.zhixiaogang.service.LoginAttemptService loginAttemptService;

    @Autowired
    private com.zhixiaogang.service.classroom.ClassroomService classroomService;

    @GetMapping("/login")
    public String showLoginPage() {
        return "teacher-login";
    }

    @PostMapping("/login")
    @ResponseBody
    public Map<String, Object> login(@RequestBody Map<String, String> loginData, HttpSession session,
                                     jakarta.servlet.http.HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();

        String username = loginData.get("username");
        String password = loginData.get("password");

        String attemptKey = "teacher:" + username;
        if (username != null && loginAttemptService.isLocked(attemptKey)) {
            result.put("success", false);
            result.put("message", "登录失败次数过多，请稍后再试");
            return result;
        }

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getStudentId, username));

        if (user == null) {
            loginAttemptService.recordFailure(attemptKey);
            operationLogService.recordAnonymous("login", username, "teacher", "user", username,
                    "教师端登录", "failed: 账号或密码错误");
            result.put("success", false);
            result.put("message", "账号或密码错误");
            return result;
        }

        if (user.getStatus() != null && user.getStatus() == 0) {
            loginAttemptService.recordFailure(attemptKey);
            operationLogService.recordAnonymous("login", username, user.getRole(), "user", username,
                    "教师端登录", "failed: 账号已被禁用");
            result.put("success", false);
            result.put("message", "账号已被禁用");
            return result;
        }

        // 仅允许 role=teacher 与 role=admin 登录教师端
        if (!"teacher".equals(user.getRole()) && !"admin".equals(user.getRole())) {
            loginAttemptService.recordFailure(attemptKey);
            operationLogService.recordAnonymous("login", username, user.getRole(), "user", username,
                    "教师端登录（角色不符）", "failed: 请使用学生端登录");
            result.put("success", false);
            result.put("message", "请使用学生端登录");
            return result;
        }

        if (!PasswordUtils.matches(password, user.getPassword())) {
            loginAttemptService.recordFailure(attemptKey);
            operationLogService.recordAnonymous("login", username, user.getRole(), "user", username,
                    "教师端登录", "failed: 账号或密码错误");
            result.put("success", false);
            result.put("message", "账号或密码错误");
            return result;
        }

        // 历史 MD5 口令校验通过后自动升级为加盐哈希
        if (PasswordUtils.needsUpgrade(user.getPassword())) {
            user.setPassword(PasswordUtils.encode(password));
            userMapper.updateById(user);
        }

        loginAttemptService.reset(attemptKey);
        // 登录成功后更换会话 ID，防止会话固定攻击
        request.changeSessionId();
        session.setAttribute("user", user);
        operationLogService.record("login", "user", user.getStudentId(),
                "教师端登录：" + user.getName(), true);
        result.put("success", true);
        result.put("message", "登录成功");
        return result;
    }

    @GetMapping("/home")
    public String showHomePage(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/teacher/login";
        }
        // 教师端页面仅对教师开放，其他角色跳转到各自入口
        if (!"teacher".equals(user.getRole())) {
            if ("admin".equals(user.getRole())) {
                return "redirect:/admin/home";
            }
            return "redirect:/teacher/login";
        }
        model.addAttribute("user", user);
        model.addAttribute("buildings", classroomService.getBuildings());
        return "teacher-home";
    }

    @PostMapping("/logout")
    public String logout(HttpSession session) {
        operationLogService.record("logout", "user", null, "教师端登出", true);
        session.invalidate();
        return "redirect:/teacher/login";
    }
}
