package com.zhixiaogang.controller;

import com.zhixiaogang.entity.User;
import com.zhixiaogang.service.UserService;
import com.zhixiaogang.service.intent.IntentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/** 学生端 AI 对话、会话记录与个人资料更新接口。 */
@RestController
@RequestMapping("/api")
public class ChatController {

    @Autowired
    private IntentService intentService;

    @Autowired
    private UserService userService;

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> request, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        
        User user = (User) session.getAttribute("user");
        if (user == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return result;
        }
        
        String message = (String) request.get("message");
        if (message == null || message.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "请输入您想查询的内容");
            return result;
        }
        
        // 聊天一律走 AI 模式：不再读取 aiMode 参数（老请求即使仍带该字段也会被忽略，不再有本地规则回复）；
        // 模型由管理端在「模型提供商管理」中统一指定，不再读取/透传前端 model 字段。
        Map<String, Object> response;
        if ("teacher".equals(user.getRole())) {
            response = intentService.processTeacherMessage(user.getStudentId(), message, null, null);
        } else {
            response = intentService.processMessage(user.getStudentId(), message, null, null);
        }
        result.put("success", true);
        result.put("data", response);
        return result;
    }

    @GetMapping("/user")
    public Map<String, Object> getUser(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        
        User user = (User) session.getAttribute("user");
        if (user == null) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }
        
        result.put("success", true);
        result.put("user", user);
        return result;
    }

    @PostMapping("/user/update-name")
    public Map<String, Object> updateName(@RequestBody Map<String, String> request, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        
        User user = (User) session.getAttribute("user");
        if (user == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return result;
        }
        
        String newName = request.get("name");
        String newEmail = request.get("email");

        if (newName == null || newName.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "姓名不能为空");
            return result;
        }
        // P1-2 先校验长度，超长直接拒绝，不触碰 session
        if (newName.trim().length() > 50) {
            result.put("success", false);
            result.put("message", "姓名过长，请限制在50个字符以内");
            return result;
        }

        try {
            // 局部更新姓名/邮箱，避免把会话中的整对象（含 status/role/password）回写数据库
            User fresh = userService.updateProfile(user.getId(), newName.trim(),
                    newEmail != null ? newEmail.trim() : null);
            if (fresh == null) {
                result.put("success", false);
                result.put("message", "更新失败，请稍后重试");
                return result;
            }
            // 以数据库最新数据刷新会话，保证两者一致
            session.setAttribute("user", fresh);

            result.put("success", true);
            result.put("message", "更新成功");
        } catch (Exception e) {
            // 失败时不泄露底层异常，会话保持原值
            result.put("success", false);
            result.put("message", "更新失败，请稍后重试");
        }
        
        return result;
    }
}
