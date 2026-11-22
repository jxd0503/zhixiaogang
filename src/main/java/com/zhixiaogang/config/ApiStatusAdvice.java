package com.zhixiaogang.config;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Map;

/**
 * 统一把业务失败（success=false）的 JSON 响应映射为 4xx/5xx 语义状态码，响应体保持不变。
 * 只处理返回 Map 的接口，文件下载等 ResponseEntity 不受影响；
 * 未预期异常仍由 GlobalExceptionHandler 返回 500 + 通用文案。
 */
@ControllerAdvice
public class ApiStatusAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return Map.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof Map<?, ?> map)) {
            return body;
        }
        if (!Boolean.FALSE.equals(map.get("success"))) {
            return body;
        }

        HttpStatus status = explicitStatus(map);
        if (status == null) {
            status = ApiStatusResolver.resolve(request.getURI().getPath(), String.valueOf(map.get("message")));
        }
        response.setStatusCode(status);
        return body;
    }

    /** 业务显式指定 _status（int）时优先使用，并从响应体中移除该内部字段 */
    private HttpStatus explicitStatus(Map<?, ?> map) {
        Object raw = map.get("_status");
        if (raw == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> writable = (Map<String, Object>) map;
        writable.remove("_status");
        HttpStatus resolved = HttpStatus.resolve(Integer.parseInt(String.valueOf(raw)));
        return resolved == null ? HttpStatus.BAD_REQUEST : resolved;
    }
}
