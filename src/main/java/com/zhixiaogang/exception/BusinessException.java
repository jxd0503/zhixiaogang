package com.zhixiaogang.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带可直接展示给用户的友好文案与 HTTP 状态码。
 * 由 GlobalExceptionHandler 统一转换为 {"success":false,"message":"..."} 的 4xx 响应，
 * 不包含堆栈、SQL、文件路径等系统细节。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(String message) {
        this(HttpStatus.BAD_REQUEST, message);
    }

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
