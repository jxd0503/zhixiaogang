package com.zhixiaogang.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * 伪静态 URL 配置：
 * 访问 /login.html、/teacher/login.html 等带 .html 后缀的地址时，
 * 自动转发到对应的控制器路径（/login、/teacher/login）。
 * 静态资源目录下无 .html 文件，转发不会与真实静态资源冲突；
 * 查询参数在 forward 时自动保留。
 */
@Configuration
public class PseudoStaticUrlConfig {

    @Bean
    public FilterRegistrationBean<Filter> pseudoStaticFilter() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new PseudoStaticFilter());
        registration.addUrlPatterns("*.html");
        registration.setOrder(1);
        registration.setName("pseudoStaticFilter");
        return registration;
    }

    public static class PseudoStaticFilter implements Filter {
        @Override
        public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest request = (HttpServletRequest) servletRequest;
            String uri = request.getRequestURI();
            String target = uri.substring(0, uri.length() - ".html".length());
            if (target.isEmpty()) {
                target = "/";
            }
            request.getRequestDispatcher(target).forward(servletRequest, servletResponse);
        }
    }
}
