package com.zhixiaogang.config;

import org.springframework.http.HttpStatus;

/**
 * 业务失败响应 → HTTP 语义状态码。
 *
 * 现有 Service/Controller 以 Map 返回 success=false + message，这里按错误语义归类，
 * 保证所有校验失败不再返回 200；需要精确指定的场景可在结果中放 _status 覆盖。
 * 规则顺序：登录失败 → 系统错误 → 无权限 → 资源不存在 → 状态冲突/重复 → 参数非法。
 */
public final class ApiStatusResolver {

    private ApiStatusResolver() {
    }

    /** 登录入口：账号/密码/角色/锁定等失败统一 401 */
    public static boolean isLoginPath(String path) {
        return path != null && (path.equals("/login") || path.endsWith("/login"));
    }

    public static HttpStatus resolve(String path, String message) {
        String msg = message == null ? "" : message;

        if (isLoginPath(path)) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (containsAny(msg, "请稍后重试", "请稍候", "系统繁忙", "服务器内部", "服务不可用")) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (containsAny(msg, "无权", "无权限", "请先登录", "未登录", "不是管理员", "不是教师", "不是学生",
                "账号已被禁用", "禁止", "权限不足", "只有")) {
            return HttpStatus.FORBIDDEN;
        }
        if (containsAny(msg, "不存在", "未找到", "已被删除")) {
            return HttpStatus.NOT_FOUND;
        }
        if (containsAny(msg, "已申请", "已加入", "已存在", "重复", "正在审核", "审核中", "待审核",
                "已被占用", "已被预约", "被占用", "冲突", "已批准", "已撤销", "已回滚", "已拒绝", "已撤回",
                "已确认", "尚未", "不能", "无法", "不允许", "请先", "只能", "仅已", "不满足", "超出", "超过", "状态")) {
            return HttpStatus.CONFLICT;
        }
        return HttpStatus.BAD_REQUEST;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
