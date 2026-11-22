package com.zhixiaogang.config;

import com.zhixiaogang.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 管理端鉴权拦截器：未登录或非管理员访问 /admin 接口时拒绝。 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();

        // 登录页和登出不拦截
        if (uri.equals("/admin/login") || uri.equals("/admin/logout")) {
            return true;
        }

        User user = (User) request.getSession().getAttribute("user");
        if (user == null || !"admin".equals(user.getRole())) {
            // 判断是否为页面请求：Accept 优先 text/html 且未明确请求 JSON
            String accept = request.getHeader("Accept");
            String xrw = request.getHeader("X-Requested-With");
            boolean preferHtml = accept != null
                    && accept.startsWith("text/html")
                    && (xrw == null || !xrw.contains("XMLHttpRequest"));
            if (preferHtml) {
                // 页面请求重定向到登录页
                response.sendRedirect("/admin/login");
            } else {
                // 其他一律视为 API 调用（fetch/axios/直接访问接口），返回 403 JSON
                response.setContentType("application/json;charset=UTF-8");
                response.setStatus(403);
                response.getWriter().write("{\"success\":false,\"message\":\"无权访问，请先以管理员身份登录\"}");
            }
            return false;
        }
        return true;
    }
}
