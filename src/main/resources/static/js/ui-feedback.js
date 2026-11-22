/* 统一页面反馈组件
 * showToast(message, type, duration)      type: success | error | warning | info
 * showConfirm({title, message, confirmText, cancelText, danger}) -> Promise<boolean>
 * showPrompt({title, message, defaultValue, placeholder, confirmText, cancelText}) -> Promise<string|null>
 * 说明：原生 alert 会被兜底替换为 info toast，任何遗漏点都不会再阻塞页面。
 */
(function () {
    'use strict';

    var DEFAULT_DURATION = 2600;
    var ICONS = {
        success: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5"/></svg>',
        error: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M15 9l-6 6M9 9l6 6"/></svg>',
        warning: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3.5l9 16H3z"/><path d="M12 9.5v4.2"/><path d="M12 16.8v.01"/></svg>',
        info: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 11v5"/><path d="M12 8v.01"/></svg>'
    };
    var VALID_TYPES = ['success', 'error', 'warning', 'info'];
    var openDialogs = [];

    function ensureToastContainer() {
        var el = document.getElementById('uiToastContainer');
        if (!el) {
            el = document.createElement('div');
            el.id = 'uiToastContainer';
            el.setAttribute('role', 'status');
            el.setAttribute('aria-live', 'polite');
            document.body.appendChild(el);
        }
        return el;
    }

    function showToast(message, type, duration) {
        if (message === undefined || message === null) {
            return null;
        }
        var text = String(message);
        if (!text) {
            return null;
        }
        if (VALID_TYPES.indexOf(type) < 0) {
            type = 'info';
        }
        var ms = (typeof duration === 'number' && duration > 0) ? duration : DEFAULT_DURATION;
        var container = ensureToastContainer();

        var toast = document.createElement('div');
        toast.className = 'ui-toast ui-toast-' + type;

        var icon = document.createElement('span');
        icon.className = 'ui-toast-icon';
        icon.innerHTML = ICONS[type] || '';

        var body = document.createElement('span');
        body.className = 'ui-toast-text';
        body.textContent = text;

        toast.appendChild(icon);
        toast.appendChild(body);
        container.appendChild(toast);

        // 最多同时显示 4 条，超出时移除最早的
        while (container.children.length > 4) {
            container.removeChild(container.firstChild);
        }

        requestAnimationFrame(function () {
            toast.classList.add('ui-toast-show');
        });

        var timer = setTimeout(remove, ms);

        function remove() {
            clearTimeout(timer);
            if (!toast.parentNode) {
                return;
            }
            toast.classList.remove('ui-toast-show');
            toast.classList.add('ui-toast-hide');
            setTimeout(function () {
                if (toast.parentNode) {
                    toast.parentNode.removeChild(toast);
                }
            }, 220);
        }

        toast.addEventListener('click', remove);
        return toast;
    }

    function normalizeOptions(options) {
        if (typeof options === 'string') {
            return { message: options };
        }
        return options || {};
    }

    function isTopDialog(record) {
        return openDialogs.length > 0 && openDialogs[openDialogs.length - 1] === record;
    }

    function buildDialogShell(options, danger) {
        var overlay = document.createElement('div');
        overlay.className = 'ui-dialog-overlay';
        overlay.setAttribute('role', 'dialog');
        overlay.setAttribute('aria-modal', 'true');

        var dialog = document.createElement('div');
        dialog.className = 'ui-dialog';

        var title = document.createElement('div');
        title.className = 'ui-dialog-title';
        title.textContent = options.title || (danger ? '请确认操作' : '确认提示');

        var bodyEl = document.createElement('div');
        bodyEl.className = 'ui-dialog-body';
        if (options.message) {
            bodyEl.textContent = options.message;
        }

        var footer = document.createElement('div');
        footer.className = 'ui-dialog-footer';

        var cancelBtn = document.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.className = 'ui-dialog-btn ui-dialog-cancel';
        cancelBtn.textContent = options.cancelText || '取消';

        var okBtn = document.createElement('button');
        okBtn.type = 'button';
        okBtn.className = 'ui-dialog-btn ui-dialog-ok' + (danger ? ' ui-dialog-ok-danger' : '');
        okBtn.textContent = options.confirmText || '确认';

        footer.appendChild(cancelBtn);
        footer.appendChild(okBtn);
        dialog.appendChild(title);
        dialog.appendChild(bodyEl);
        dialog.appendChild(footer);
        overlay.appendChild(dialog);

        return {
            overlay: overlay,
            bodyEl: bodyEl,
            cancelBtn: cancelBtn,
            okBtn: okBtn
        };
    }

    function mountDialog(parts, record, onConfirm, onCancel, focusEl) {
        document.body.appendChild(parts.overlay);
        openDialogs.push(record);

        function finish(confirmed) {
            document.removeEventListener('keydown', onKeydown, true);
            var idx = openDialogs.indexOf(record);
            if (idx >= 0) {
                openDialogs.splice(idx, 1);
            }
            if (parts.overlay.parentNode) {
                parts.overlay.parentNode.removeChild(parts.overlay);
            }
            if (confirmed) {
                onConfirm();
            } else {
                onCancel();
            }
        }

        function onKeydown(e) {
            if (!isTopDialog(record)) {
                return;
            }
            if (e.key === 'Escape') {
                e.preventDefault();
                finish(false);
            } else if (e.key === 'Enter' && e.target !== parts.cancelBtn) {
                e.preventDefault();
                finish(true);
            }
        }

        record.close = function () {
            finish(false);
        };

        parts.cancelBtn.addEventListener('click', function () {
            finish(false);
        });
        parts.okBtn.addEventListener('click', function () {
            finish(true);
        });
        parts.overlay.addEventListener('mousedown', function (e) {
            if (e.target === parts.overlay) {
                finish(false);
            }
        });
        document.addEventListener('keydown', onKeydown, true);

        if (focusEl) {
            setTimeout(function () {
                focusEl.focus();
            }, 0);
        }
    }

    function showConfirm(options) {
        options = normalizeOptions(options);
        var danger = !!options.danger;
        return new Promise(function (resolve) {
            var parts = buildDialogShell(options, danger);
            var record = {};
            mountDialog(parts, record, function () {
                resolve(true);
            }, function () {
                resolve(false);
            }, danger ? parts.cancelBtn : parts.okBtn);
        });
    }

    // 单按钮提示弹窗：用于必须让用户看到并确认的阻断提示
    function showAlert(options) {
        options = normalizeOptions(options);
        return new Promise(function (resolve) {
            var parts = buildDialogShell(options, false);
            parts.cancelBtn.style.display = 'none';
            var record = {};
            mountDialog(parts, record, function () {
                resolve(true);
            }, function () {
                resolve(true);
            }, parts.okBtn);
        });
    }

    function showPrompt(options) {
        options = normalizeOptions(options);
        return new Promise(function (resolve) {
            var parts = buildDialogShell(options, false);
            var input = document.createElement('input');
            input.type = 'text';
            input.className = 'ui-dialog-input';
            input.value = options.defaultValue || '';
            input.placeholder = options.placeholder || '';
            parts.bodyEl.appendChild(input);

            var settled = false;
            var record = {};

            function done(value) {
                if (settled) {
                    return;
                }
                settled = true;
                resolve(value);
            }

            mountDialog(parts, record, function () {
                done(input.value);
            }, function () {
                done(null);
            }, input);
        });
    }

    // 三端统一的退出登录二次确认（POST 提交，避免被链接预取/GET 触发）
    function confirmLogout(url) {
        showConfirm({
            title: '退出登录',
            message: '确定要退出登录吗？',
            confirmText: '退出登录',
            cancelText: '取消',
            danger: true
        }).then(function (ok) {
            if (ok) {
                var form = document.createElement('form');
                form.method = 'POST';
                form.action = url;
                form.style.display = 'none';
                // 原生 form 无法带自定义请求头，使用 _csrf 参数与服务端双提交 Cookie 比对
                if (typeof window.csrfToken === 'function') {
                    var field = document.createElement('input');
                    field.type = 'hidden';
                    field.name = '_csrf';
                    field.value = window.csrfToken();
                    form.appendChild(field);
                }
                document.body.appendChild(form);
                form.submit();
            }
        });
    }

    // 三端统一的教室容量文案：空值显示「—」，≤0 视为容量未知，避免出现 null 人 / 0 人
    function formatCapacity(capacity) {
        var n = Number(capacity);
        if (capacity === null || capacity === undefined || capacity === '' || isNaN(n)) return '—';
        if (n <= 0) return '容量未知';
        return n + ' 人';
    }

    // 通用分页条：渲染「共 N 条 / 第 x/y 页」与上一页、下一页，翻页时回调 onGo(page)
    function renderPager(container, page, size, total, onGo) {
        var el = typeof container === 'string' ? document.getElementById(container) : container;
        if (!el) return;
        var p = Math.max(1, parseInt(page, 10) || 1);
        var s = Math.max(1, parseInt(size, 10) || 10);
        var t = Math.max(0, parseInt(total, 10) || 0);
        var pages = Math.max(1, Math.ceil(t / s));
        if (p > pages) p = pages;

        el.innerHTML = '';
        el.className = 'ui-pager';

        var info = document.createElement('span');
        info.className = 'ui-pager-info';
        info.textContent = '共 ' + t + ' 条 · 第 ' + p + '/' + pages + ' 页';
        el.appendChild(info);

        var prev = document.createElement('button');
        prev.type = 'button';
        prev.className = 'ui-pager-btn';
        prev.textContent = '上一页';
        prev.disabled = p <= 1;
        prev.addEventListener('click', function () { if (p > 1 && onGo) onGo(p - 1); });
        el.appendChild(prev);

        var next = document.createElement('button');
        next.type = 'button';
        next.className = 'ui-pager-btn';
        next.textContent = '下一页';
        next.disabled = p >= pages;
        next.addEventListener('click', function () { if (p < pages && onGo) onGo(p + 1); });
        el.appendChild(next);
    }

    window.showToast = showToast;
    window.showConfirm = showConfirm;
    window.showAlert = showAlert;
    window.showPrompt = showPrompt;
    window.confirmLogout = confirmLogout;
    window.formatCapacity = formatCapacity;
    window.renderPager = renderPager;

    // 兜底：任何遗留的原生 alert 都不再阻塞页面
    window.alert = function (message) {
        showToast(message === undefined || message === null ? '' : String(message), 'info');
    };
})();
