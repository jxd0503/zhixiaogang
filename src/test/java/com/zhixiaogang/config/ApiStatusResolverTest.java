package com.zhixiaogang.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 业务失败状态码归类：登录 401、无权限 403、不存在 404、状态冲突 409、参数 400、系统错误 500。 */
class ApiStatusResolverTest {

    @Test
    void loginFailureIsUnauthorized() {
        assertEquals(HttpStatus.UNAUTHORIZED, ApiStatusResolver.resolve("/admin/login", "账号或密码错误"));
        assertEquals(HttpStatus.UNAUTHORIZED, ApiStatusResolver.resolve("/teacher/login", "账号已被禁用"));
    }

    @Test
    void permissionFailureIsForbidden() {
        assertEquals(HttpStatus.FORBIDDEN, ApiStatusResolver.resolve("/api/teacher/courses", "无权访问"));
        assertEquals(HttpStatus.FORBIDDEN, ApiStatusResolver.resolve("/api/class/list", "请先登录"));
    }

    @Test
    void missingResourceIsNotFound() {
        assertEquals(HttpStatus.NOT_FOUND, ApiStatusResolver.resolve("/admin/users/t9", "用户不存在"));
        assertEquals(HttpStatus.NOT_FOUND, ApiStatusResolver.resolve("/api/class/join", "班级不存在或已被删除"));
    }

    @Test
    void stateConflictOrDuplicateIsConflict() {
        assertEquals(HttpStatus.CONFLICT, ApiStatusResolver.resolve("/api/class/join", "已申请或已加入"));
        assertEquals(HttpStatus.CONFLICT, ApiStatusResolver.resolve("/api/class/join", "您已有待审核的加入申请，请先取消后再申请其他班级"));
        assertEquals(HttpStatus.CONFLICT, ApiStatusResolver.resolve("/api/teacher/adjustments/1/emergency-rollback",
                "该调课已紧急回滚，请勿重复操作"));
    }

    @Test
    void invalidParameterFallsBackToBadRequest() {
        assertEquals(HttpStatus.BAD_REQUEST, ApiStatusResolver.resolve("/api/class/join", "缺少班级参数"));
        assertEquals(HttpStatus.BAD_REQUEST, ApiStatusResolver.resolve("/api/class/join", "班级参数格式不正确"));
    }

    @Test
    void systemFailureIsInternalError() {
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ApiStatusResolver.resolve("/admin/users/t1", "删除失败，请稍后重试"));
    }
}
