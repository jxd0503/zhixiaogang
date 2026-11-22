package com.zhixiaogang.config;

import com.zhixiaogang.service.OperationLogService;
import com.zhixiaogang.service.SystemConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 操作日志拦截器：
 * 1) 为每个请求打上开始时间，供 OperationLogService 计算耗时；
 * 2) 读操作按需记录——默认关闭，管理员开启「记录所有读操作」后，仅记录有业务意义的 JSON 查询接口，
 *    轮询、心跳、通知未读数、AI 流式、验证码等噪音一律豁免。
 */
@Component
public class OperationLogInterceptor implements HandlerInterceptor {

    /** 噪音路径关键词：命中则不记录读操作 */
    private static final String[] NOISE_KEYWORDS = {
            "notification", "pending-count", "unread-count", "dashboard", "statistics", "stats",
            "heartbeat", "health", "captcha", "verify", "stream", "chat", "ai/status",
            "poll", "favicon", "operation-logs"
    };

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private SystemConfigService systemConfigService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(OperationLogService.REQ_ATTR_START, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        try {
            if (!"GET".equalsIgnoreCase(request.getMethod())) {
                return;
            }
            if (!systemConfigService.getBoolean("operation_log.record_all_reads", false)) {
                return;
            }
            String uri = request.getRequestURI();
            if (!uri.startsWith("/api/") && !uri.startsWith("/admin/")) {
                return;
            }
            if (isNoise(uri)) {
                return;
            }
            String contentType = response.getContentType();
            if (contentType == null || !contentType.contains("json")) {
                return;
            }
            String query = request.getQueryString();
            String detail = uri + (query == null || query.isBlank() ? "" : "?" + query);
            operationLogService.record("query", targetType(uri), uri, detail,
                    ex == null && response.getStatus() < 400);
        } catch (Exception ignored) {
            // 读日志失败不影响业务
        }
    }

    private boolean isNoise(String uri) {
        String lower = uri.toLowerCase();
        for (String keyword : NOISE_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String targetType(String uri) {
        String[] parts = uri.split("/");
        // /api/xxx/... 或 /admin/xxx/...，取第一个业务段
        if (parts.length >= 3 && parts[2] != null && !parts[2].isBlank()) {
            return parts[2];
        }
        return "api";
    }
}
