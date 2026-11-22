package com.zhixiaogang.config;

import com.zhixiaogang.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 统一角色校验拦截器：学生端 / 教师端 API 按角色隔离。
 * 未登录返回 401，已登录但角色不匹配返回 403，响应体均为 {"success":false,"message":"..."}。
 * 支持配置多个允许角色（如教师端同时允许教师与管理员，与教师端登录入口口径保持一致）。
 */
public class RoleAuthInterceptor implements HandlerInterceptor {

    private final Set<String> roles;
    private final String message;

    public RoleAuthInterceptor(String role, String message) {
        this(message, new String[]{role});
    }

    public RoleAuthInterceptor(String message, String... roles) {
        this.roles = new LinkedHashSet<>(Arrays.asList(roles));
        this.message = message;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        User user = (User) request.getSession().getAttribute("user");
        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"message\":\"请先登录\"}");
            return false;
        }
        if (user.getRole() == null || !roles.contains(user.getRole())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\"}");
            return false;
        }
        return true;
    }
}
