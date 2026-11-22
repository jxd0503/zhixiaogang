/* 登录守卫：拦截页面所有 fetch 响应，检测到会话失效时自动跳转对应登录页。
   规则仅作用于与当前页面同区域的请求（student/teacher/admin），
   避免干扰学生/教师端对 /admin/ai/status 的跨区域静默探测（其本身依赖重定向降级）。 */
(function () {
    'use strict';
    if (window.__authGuardInstalled) return;
    window.__authGuardInstalled = true;

    function areaOf(pathname) {
        if (pathname.indexOf('/admin') === 0 || pathname.indexOf('/api/admin') === 0) return 'admin';
        if (pathname.indexOf('/teacher') === 0 || pathname.indexOf('/api/teacher') === 0) return 'teacher';
        return 'student';
    }

    var pageArea = areaOf(window.location.pathname);
    var loginUrl = pageArea === 'admin' ? '/admin/login'
                 : pageArea === 'teacher' ? '/teacher/login' : '/login';

    function gotoLogin() {
        if (window.__authGuardRedirecting) return;
        window.__authGuardRedirecting = true;
        window.location.replace(loginUrl);
    }

    function pathOfRequest(args) {
        try {
            var url = args[0];
            if (url && typeof url === 'object' && url.url) url = url.url;
            return new URL(url, window.location.origin).pathname;
        } catch (e) { return ''; }
    }

    function isLoginPage(url) {
        try { return /\/login$/.test(new URL(url, window.location.origin).pathname); }
        catch (e) { return false; }
    }

    function hitByJson(data) {
        if (!data || data.success !== false || typeof data.message !== 'string') return false;
        if (data.message.indexOf('请先登录') !== -1 || data.message.indexOf('未登录') !== -1) return true;
        return pageArea === 'admin' && data.message.indexOf('无权访问') !== -1;
    }

    var originalFetch = window.fetch;
    if (!originalFetch) return;
    window.fetch = function () {
        var sameArea = areaOf(pathOfRequest(arguments)) === pageArea;
        return originalFetch.apply(this, arguments).then(function (response) {
            try {
                if (response && sameArea) {
                    if (response.redirected && isLoginPage(response.url)) {
                        // 后端 sendRedirect 被 fetch 跟随，最终落在登录页 → 会话已失效
                        gotoLogin();
                    } else {
                        var ct = response.headers ? (response.headers.get('content-type') || '') : '';
                        if (ct.indexOf('application/json') !== -1) {
                            response.clone().json().then(function (data) {
                                if (hitByJson(data)) gotoLogin();
                            }).catch(function () {});
                        }
                    }
                }
            } catch (e) { /* 守卫异常不影响原流程 */ }
            return response;
        });
    };
})();
