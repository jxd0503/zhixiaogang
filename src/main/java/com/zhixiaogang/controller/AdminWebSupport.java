package com.zhixiaogang.controller;

import com.zhixiaogang.service.OperationLogService;

import java.util.Map;

/********** 管理端 Controller 共享工具：字段解析与操作日志埋点（静态方法，无状态） **********/
final class AdminWebSupport {

    private AdminWebSupport() {
    }

    /** 字段统一转字符串并去首尾空格；null 保持 null */
    static String str(Object value) {
        return value != null ? String.valueOf(value).trim() : null;
    }

    /** 统一记录管理端写操作日志（成功/失败由业务返回的 success 判定） */
    static void logAction(OperationLogService operationLogService, String action, String targetType,
                          Object targetId, String detail, Map<String, Object> result) {
        boolean ok = result != null && Boolean.TRUE.equals(result.get("success"));
        operationLogService.record(action, targetType, targetId, detail,
                ok, ok ? null : (result == null ? null : str(result.get("message"))));
    }

    /** 记录日志后返回业务结果，便于多分支方法统一埋点 */
    static Map<String, Object> logged(OperationLogService operationLogService, Map<String, Object> result,
                                      String action, String targetType, Object targetId, String detail) {
        logAction(operationLogService, action, targetType, targetId, detail, result);
        return result;
    }
}
