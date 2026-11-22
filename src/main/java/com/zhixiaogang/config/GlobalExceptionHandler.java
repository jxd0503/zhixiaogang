package com.zhixiaogang.config;

import com.zhixiaogang.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 统一异常响应：业务异常返回 4xx + 友好文案，未预期异常返回 500 + 通用文案。
 * 异常详情只写服务端日志，响应体固定为 {"success":false,"message":"..."}，不暴露堆栈、SQL 与路径。
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String GENERIC_ERROR = "服务器内部错误，请稍后重试";

    // 接口/资源不存在（如已下线的写操作）返回 404
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "接口不存在或已禁止访问");
    }

    // P2-8 请求体 JSON 解析失败（非法 JSON）→ 400，不泄露解析细节
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleBadJson(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "请求格式错误，请检查提交的数据格式");
    }

    // 请求方法不被接口支持（如对仅 POST 的接口使用 GET）→ 405，而非 500
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不被支持：" + e.getMethod());
    }

    // P2-8 Content-Type 不支持 → 400
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleBadContentType(HttpMediaTypeNotSupportedException e) {
        return body(HttpStatus.BAD_REQUEST, "请求格式错误，请使用 application/json 提交");
    }

    // P2-9 URL 参数类型不匹配（如 ?date=notadate、非数字 id）→ 400 字段级友好提示
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String name = e.getName() != null ? e.getName() : "参数";
        return body(HttpStatus.BAD_REQUEST, "参数「" + name + "」格式不正确");
    }

    // P2-8/P2-9 缺少必传请求参数 → 400 友好提示
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
        return body(HttpStatus.BAD_REQUEST, "请求参数不完整，缺少：" + e.getParameterName());
    }

    // 业务异常：状态码与文案由业务代码显式给出，可直接展示
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException e) {
        return body(e.getStatus(), e.getMessage());
    }

    // 参数/业务校验异常：按业务错误处理为 400
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = "请求参数不正确";
        }
        return body(HttpStatus.BAD_REQUEST, message);
    }

    // 未预期异常：详情只写日志，响应统一 500 通用文案
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(HttpServletRequest request, Exception e) {
        log.error("未处理异常：{} {}", request.getMethod(), request.getRequestURI(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_ERROR);
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", message);
        return ResponseEntity.status(status).body(result);
    }
}
