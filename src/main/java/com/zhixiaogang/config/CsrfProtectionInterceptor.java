package com.zhixiaogang.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * CSRF 防护（双提交 Cookie）：
 * 1. 首次访问时下发随机 XSRF-TOKEN Cookie（SameSite=Lax，前端可读）；
 * 2. 写操作要求请求头 X-CSRF-Token 与 Cookie 一致，原生 form 可改用 _csrf 参数；
 * 3. 始终保留 Origin/Referer 同源校验作为第二道防线；
 * 4. 登录/注册等原生 form 入口单独豁免 token 校验（仍校验来源），避免登录退出被破坏；
 * 5. GET/HEAD/OPTIONS 等只读请求与静态资源不受影响。
 */
@Component
public class CsrfProtectionInterceptor implements HandlerInterceptor {

    public static final String COOKIE_NAME = "XSRF-TOKEN";
    public static final String HEADER_NAME = "X-CSRF-Token";
    public static final String PARAM_NAME = "_csrf";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> FORM_AUTH_PATHS = Set.of("/login", "/register", "/admin/login", "/teacher/login");

    @Value("${cors.allowed-origins:}")
    private String allowedOrigins;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        ensureCookie(request, response);
        if (!isStateChanging(request.getMethod())) {
            return true;
        }

        String source = request.getHeader("Origin");
        if (isBlank(source) || "null".equalsIgnoreCase(source)) {
            source = request.getHeader("Referer");
        }
        if (isBlank(source) || (!isAllowedOrigin(source) && !isSameHost(source, request))) {
            return reject(response, "非法请求来源，已拒绝");
        }

        if (isFormAuthPath(request)) {
            return true;
        }

        String cookieToken = cookieValue(request);
        String submitted = request.getHeader(HEADER_NAME);
        if (isBlank(submitted)) {
            submitted = request.getParameter(PARAM_NAME);
        }
        if (isBlank(cookieToken) || isBlank(submitted) || !constantTimeEquals(cookieToken, submitted)) {
            return reject(response, "CSRF 校验失败，请刷新页面后重试");
        }
        return true;
    }

    private boolean isStateChanging(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
    }

    private void ensureCookie(HttpServletRequest request, HttpServletResponse response) {
        if (!isBlank(cookieValue(request))) {
            return;
        }
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        response.addHeader("Set-Cookie", COOKIE_NAME + "=" + token + "; Path=/; SameSite=Lax");
    }

    private String cookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private boolean isFormAuthPath(HttpServletRequest request) {
        String path = request.getServletPath();
        return path != null && FORM_AUTH_PATHS.contains(path);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isAllowedOrigin(String source) {
        return allowedOriginSet().contains(stripTrailingSlash(source));
    }

    private boolean isSameHost(String source, HttpServletRequest request) {
        try {
            String host = URI.create(source).getHost();
            return host != null && host.equalsIgnoreCase(request.getServerName());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Set<String> allowedOriginSet() {
        Set<String> origins = new LinkedHashSet<>();
        Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty() && !"*".equals(origin))
                .map(this::stripTrailingSlash)
                .forEach(origins::add);
        return origins;
    }

    private String stripTrailingSlash(String origin) {
        String value = origin == null ? "" : origin.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean reject(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\"}");
        return false;
    }
}
