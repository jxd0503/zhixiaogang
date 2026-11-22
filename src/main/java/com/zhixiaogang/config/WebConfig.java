package com.zhixiaogang.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Web MVC 配置：拦截器注册、静态资源与 CORS 白名单。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private AdminAuthInterceptor adminAuthInterceptor;

    @Autowired
    private OperationLogInterceptor operationLogInterceptor;

    @Autowired
    private CsrfProtectionInterceptor csrfProtectionInterceptor;

    @Autowired
    private AccountStatusInterceptor accountStatusInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 账号状态：会话中的账号被禁用/删除后立即失效，旧会话不能再调用接口
        registry.addInterceptor(accountStatusInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/css/**", "/js/**", "/images/**", "/favicon.ico", "/error",
                        "/webjars/**", "/static/**", "/login", "/register", "/admin/login", "/teacher/login",
                        "/api/ai/status");
        // CSRF 防护：双提交 Cookie + X-CSRF-Token，并保留 Origin/Referer 同源校验
        registry.addInterceptor(csrfProtectionInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/css/**", "/js/**", "/images/**", "/favicon.ico", "/error",
                        "/webjars/**", "/static/**");
        // 操作日志：为请求打耗时标记，并在开关开启时记录有业务意义的读操作
        registry.addInterceptor(operationLogInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/css/**", "/js/**", "/images/**", "/favicon.ico", "/error",
                        "/webjars/**", "/static/**");
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns("/admin/login", "/admin/logout");
        // 学生端专属接口：仅 student 角色（/api/services、/api/classrooms 为师生共用，不在此列）
        registry.addInterceptor(new RoleAuthInterceptor("student", "无权限访问学生端接口"))
                .addPathPatterns("/api/class/**", "/api/schedule/**");
        // 教师端专属接口：教师与管理员均可访问，与教师端登录入口（TeacherController 允许 teacher/admin）保持一致
        registry.addInterceptor(new RoleAuthInterceptor("教师端接口仅限教师或管理员访问", "teacher", "admin"))
                .addPathPatterns("/api/teacher/**");
    }
}
