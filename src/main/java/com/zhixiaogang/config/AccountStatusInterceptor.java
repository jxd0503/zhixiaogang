package com.zhixiaogang.config;

import com.zhixiaogang.entity.User;
import com.zhixiaogang.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Objects;

/**
 * 会话账号状态拦截器：每次请求按主键复查会话中的账号是否仍存在且未被禁用。
 * 账号被禁用/删除后，即使旧会话仍然存在也无法继续调用任何接口（页面请求跳登录页，接口返回 401）。
 * 同时同步角色变更，避免管理员改角色后旧会话继续沿用旧权限。
 */
@Component
public class AccountStatusInterceptor implements HandlerInterceptor {

    @Autowired
    private UserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return true;
        }
        Object attribute = session.getAttribute("user");
        if (!(attribute instanceof User sessionUser) || sessionUser.getId() == null) {
            return true;
        }

        User current = userMapper.selectById(sessionUser.getId());
        boolean active = current != null && (current.getStatus() == null || current.getStatus() == 1);
        if (active) {
            if (!Objects.equals(sessionUser.getRole(), current.getRole())
                    || !Objects.equals(sessionUser.getName(), current.getName())) {
                session.setAttribute("user", current);
            }
            return true;
        }

        try {
            session.invalidate();
        } catch (IllegalStateException ignored) {
            // 会话已失效，无需处理
        }
        reject(request, response);
        return false;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/") || isAjax(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"message\":\"账号已被禁用或不存在，请重新登录\"}");
            return;
        }
        if (uri.startsWith("/admin/")) {
            response.sendRedirect("/admin/login");
        } else if (uri.startsWith("/teacher/")) {
            response.sendRedirect("/teacher/login");
        } else {
            response.sendRedirect("/login");
        }
    }

    private boolean isAjax(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        String xrw = request.getHeader("X-Requested-With");
        return (xrw != null && xrw.contains("XMLHttpRequest"))
                || (accept != null && accept.contains("application/json"));
    }
}
