package com.zhixiaogang.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CSRF 双提交 Cookie 单测：来源校验 + X-CSRF-Token 比对、原生表单豁免、只读请求放行。
 */
class CsrfProtectionInterceptorTest {

    private CsrfProtectionInterceptor interceptor;
    private StringWriter output;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() throws Exception {
        interceptor = new CsrfProtectionInterceptor();
        ReflectionTestUtils.setField(interceptor, "allowedOrigins", "https://trusted.example.com");
        output = new StringWriter();
        response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(output, true));
    }

    @Test
    void getRequestSkipsCheckAndIssuesCookie() throws Exception {
        HttpServletRequest request = request("GET", null, null, null, null, null, null);
        assertTrue(interceptor.preHandle(request, response, new Object()));
        verify(response).addHeader(contains("Set-Cookie"), contains("XSRF-TOKEN="));
    }

    @Test
    void sameOriginWithMatchingTokenAllowed() throws Exception {
        HttpServletRequest request = request("POST", "http://localhost:8080", null, "/api/x", "abc", "abc", null);
        assertTrue(interceptor.preHandle(request, response, new Object()));
        verify(response, never()).setStatus(403);
    }

    @Test
    void missingTokenRejected() throws Exception {
        HttpServletRequest request = request("POST", "http://localhost:8080", null, "/api/x", "abc", null, null);
        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(403);
        assertTrue(output.toString().contains("CSRF"));
    }

    @Test
    void mismatchedTokenRejected() throws Exception {
        HttpServletRequest request = request("POST", "http://localhost:8080", null, "/api/x", "abc", "xyz", null);
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, captureStatus());
    }

    @Test
    void crossSiteRejectedEvenWithValidToken() throws Exception {
        HttpServletRequest request = request("POST", "https://evil.example.com", null, "/api/x", "abc", "abc", null);
        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(403);
        assertTrue(output.toString().contains("非法请求来源"));
    }

    @Test
    void missingOriginRejected() throws Exception {
        HttpServletRequest request = request("DELETE", null, null, "/api/x", "abc", "abc", null);
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, captureStatus());
    }

    @Test
    void whitelistedOriginAllowedWithToken() throws Exception {
        HttpServletRequest request = request("POST", "https://trusted.example.com", null, "/api/x", "abc", "abc", null);
        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void refererFallbackAllowedWithToken() throws Exception {
        HttpServletRequest request = request("POST", null, "http://localhost/admin/home", "/api/x", "abc", "abc", null);
        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void nativeLoginFormExemptFromTokenButOriginChecked() throws Exception {
        HttpServletRequest ok = request("POST", "http://localhost:8080", null, "/login", null, null, null);
        assertTrue(interceptor.preHandle(ok, response, new Object()));

        HttpServletRequest evil = request("POST", "https://evil.example.com", null, "/login", null, null, null);
        assertFalse(interceptor.preHandle(evil, response, new Object()));
    }

    @Test
    void formParamTokenAcceptedForLogout() throws Exception {
        HttpServletRequest request = request("POST", "http://localhost:8080", null, "/logout", "abc", null, "abc");
        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    private int captureStatus() {
        org.mockito.ArgumentCaptor<Integer> captor = org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(response).setStatus(captor.capture());
        return captor.getValue();
    }

    private HttpServletRequest request(String method, String origin, String referer, String servletPath,
                                       String cookieToken, String headerToken, String paramToken) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getHeader("Origin")).thenReturn(origin);
        when(request.getHeader("Referer")).thenReturn(referer);
        when(request.getHeader(CsrfProtectionInterceptor.HEADER_NAME)).thenReturn(headerToken);
        when(request.getParameter(CsrfProtectionInterceptor.PARAM_NAME)).thenReturn(paramToken);
        when(request.getServletPath()).thenReturn(servletPath == null ? "/" : servletPath);
        when(request.getServerName()).thenReturn("localhost");
        if (cookieToken != null) {
            when(request.getCookies()).thenReturn(new Cookie[]{
                    new Cookie(CsrfProtectionInterceptor.COOKIE_NAME, cookieToken)});
        }
        return request;
    }
}
