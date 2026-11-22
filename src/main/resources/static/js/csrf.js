/**
 * CSRF 双提交 Cookie 前端封装：
 * - 服务端首次请求下发 XSRF-TOKEN Cookie；
 * - 统一包装 window.fetch，为所有非只读请求自动注入 X-CSRF-Token 请求头，
 *   页面脚本无需逐处修改；
 * - 原生 form 提交（登录/注册）由服务端单独豁免，动态登出 form 使用 csrfToken() 填 _csrf。
 */
(function () {
    function readCookie(name) {
        var parts = (document.cookie || '').split(';');
        for (var i = 0; i < parts.length; i++) {
            var pair = parts[i].trim();
            if (pair.indexOf(name + '=') === 0) {
                return decodeURIComponent(pair.substring(name.length + 1));
            }
        }
        return '';
    }

    window.csrfToken = function () {
        return readCookie('XSRF-TOKEN');
    };

    if (window.__csrfFetchWrapped) {
        return;
    }
    window.__csrfFetchWrapped = true;

    var originalFetch = window.fetch;
    if (!originalFetch) {
        return;
    }

    window.fetch = function (input, init) {
        init = init || {};
        var method = (init.method || (input && input.method) || 'GET').toUpperCase();
        if (method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS') {
            var token = window.csrfToken();
            if (token) {
                var headers = new Headers(init.headers || (input && input.headers) || {});
                if (!headers.has('X-CSRF-Token')) {
                    headers.set('X-CSRF-Token', token);
                }
                init.headers = headers;
            }
        }
        return originalFetch.call(window, input, init);
    };
})();
