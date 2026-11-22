/********** 教师排课要求：已提交约束请求列表、采纳 / 驳回 / 填入与一键通知 **********/
        // ===================== 教师已提交排课要求（管理端） =====================

        var asReqRows = [];                 // 当前列表数据
        var asReqUnmatchedMessages = [];    // 预览/失败提示中未生效的约束信息（一键通知教师用）

        var AS_REQ_STATUS = {
            PENDING: { label: '待处理', cls: 'pending' },
            ADOPTED: { label: '已采纳', cls: 'adopted' },
            REJECTED: { label: '已驳回', cls: 'rejected' },
            CANCELLED: { label: '已撤销', cls: 'cancelled' },
            EXPIRED: { label: '已过期', cls: 'expired' }
        };

        function asSetReqBadge(n) {
            var count = parseInt(n, 10) || 0;
            ['asReqBadge', 'asReqModalBadge'].forEach(function (id) {
                var el = document.getElementById(id);
                if (!el) return;
                if (count > 0) {
                    el.textContent = count > 99 ? '99+' : String(count);
                    el.style.display = 'inline-block';
                } else {
                    el.style.display = 'none';
                }
            });
        }

        function asLoadConstraintRequestCount() {
            fetch('/admin/schedule-constraints/pending-count')
                .then(function (r) { return r.json(); })
                .then(function (data) {
                    if (data && data.success) asSetReqBadge(data.count || 0);
                })
                .catch(function () { /* 角标失败不影响排课主流程 */ });
        }

        function asOpenConstraintRequests() {
            var modal = document.getElementById('asReqModal');
            if (!modal) return;
            modal.classList.add('show');
            asLoadConstraintRequests();
        }

        function asCloseConstraintRequests() {
            var modal = document.getElementById('asReqModal');
            if (modal) modal.classList.remove('show');
        }

        function asFormatReqTime(t) {
            if (!t) return '';
            if (Array.isArray(t)) {
                var p = function (v) { return String(v).padStart(2, '0'); };
                return t[0] + '-' + p(t[1]) + '-' + p(t[2]) + ' ' + p(t[3] || 0) + ':' + p(t[4] || 0);
            }
            return String(t).replace('T', ' ').substring(0, 16);
        }

        function asParseReqItems(json) {
            if (!json) return [];
            try {
                var arr = typeof json === 'string' ? JSON.parse(json) : json;
                return Array.isArray(arr) ? arr : [];
            } catch (e) {
                return [];
            }
        }

        function asLoadConstraintRequests() {
            var listEl = document.getElementById('asReqList');
            var filterEl = document.getElementById('asReqStatusFilter');
            var status = filterEl ? (filterEl.value || '') : '';
            if (listEl) listEl.innerHTML = '<p class="empty-hint">加载中...</p>';
            var params = [];
            if (status) params.push('status=' + encodeURIComponent(status));
            // 不按 AI 排课页的学期输入框过滤：教师提交时使用的是当前学期配置名，
            // 可能与排课页手填的学期写法不同，过滤会漏掉待处理要求；列表内已展示每条所属学期。
            fetch('/admin/schedule-constraints/list' + (params.length ? '?' + params.join('&') : ''))
                .then(function (r) { return r.json(); })
                .then(function (data) {
                    if (!data || !data.success) {
                        if (listEl) listEl.innerHTML = '<p class="empty-hint">' + escapeHtml((data && data.message) || '加载失败') + '</p>';
                        return;
                    }
                    asReqRows = data.requests || [];
                    asSetReqBadge(data.pendingCount || 0);
                    asRenderConstraintRequests();
                    asUpdateReqSelHint();
                })
                .catch(function () {
                    if (listEl) listEl.innerHTML = '<p class="empty-hint">加载失败：网络异常</p>';
                });
        }

        function asRenderConstraintRequests() {
            var listEl = document.getElementById('asReqList');
            var statsEl = document.getElementById('asReqStats');
            if (!listEl) return;
            if (!asReqRows.length) {
                listEl.innerHTML = '<p class="empty-hint">暂无排课要求</p>';
                if (statsEl) statsEl.textContent = '';
                return;
            }
            listEl.innerHTML = asReqRows.map(function (r) {
                var meta = AS_REQ_STATUS[r.status] || { label: r.status || '', cls: '' };
                var selectable = (r.status === 'PENDING' || r.status === 'ADOPTED');
                var items = asParseReqItems(r.parsedJson).map(function (it) {
                    return '<li>' + escapeHtml(it.description || '') + '</li>';
                }).join('');
                var scope = r.className ? escapeHtml(r.className) : '不限定班级';
                return '<div class="as-req-item">'
                    + '<div class="as-req-item-head">'
                    + '<label class="as-req-check">'
                    + (selectable
                        ? '<input type="checkbox" class="as-req-pick" value="' + r.id + '" data-status="' + escapeHtml(r.status) + '" onchange="asUpdateReqSelHint()">'
                        : '<span class="as-req-check-ph"></span>')
                    + '<span class="as-req-teacher">' + escapeHtml(r.teacherName || '') + '</span>'
                    + '</label>'
                    + '<span class="as-req-status as-req-' + escapeHtml(meta.cls) + '">' + escapeHtml(meta.label) + '</span>'
                    + '<span class="as-req-meta">' + scope + ' · ' + escapeHtml(r.semester || '未设置学期')
                    + (r.createTime ? ' · ' + escapeHtml(asFormatReqTime(r.createTime)) : '') + '</span>'
                    + '</div>'
                    + '<div class="as-req-text">' + escapeHtml(r.constraintText || '') + '</div>'
                    + (items ? '<div class="as-req-parsed"><span class="as-req-parsed-title">AI 预解析</span><ul>' + items + '</ul></div>'
                        : '<div class="as-req-parsed as-req-parsed-empty">AI 预解析：未识别出结构化约束（将按原文提交）</div>')
                    + (r.adminNote ? '<div class="as-req-note">备注：' + escapeHtml(r.adminNote) + '</div>' : '')
                    + (r.applyResult ? '<div class="as-req-note">结果：' + escapeHtml(r.applyResult) + '</div>' : '')
                    + '</div>';
            }).join('');
            if (statsEl) {
                var pending = asReqRows.filter(function (r) { return r.status === 'PENDING'; }).length;
                var adopted = asReqRows.filter(function (r) { return r.status === 'ADOPTED'; }).length;
                statsEl.textContent = '当前列表共 ' + asReqRows.length + ' 条（待处理 ' + pending + ' 条，已采纳 ' + adopted + ' 条）';
            }
        }

        function asSelectedReqIds() {
            var ids = [];
            document.querySelectorAll('#asReqList .as-req-pick:checked').forEach(function (el) {
                var v = parseInt(el.value, 10);
                if (!isNaN(v)) ids.push(v);
            });
            return ids;
        }

        function asUpdateReqSelHint() {
            var el = document.getElementById('asReqSelHint');
            if (el) el.textContent = '已选 ' + asSelectedReqIds().length + ' 条';
        }

        function asReqNoteValue() {
            var el = document.getElementById('asReqNote');
            return el ? (el.value || '').trim() : '';
        }

        function asAfterReqHandled() {
            var noteEl = document.getElementById('asReqNote');
            if (noteEl) noteEl.value = '';
            asLoadConstraintRequests();
            asLoadConstraintRequestCount();
        }

        function asPostReqJson(url, body, onOk) {
            fetch(url, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            })
                .then(function (r) { return r.json(); })
                .then(function (data) {
                    if (!data || !data.success) {
                        showProviderToast((data && data.message) || '操作失败', 'error');
                        return;
                    }
                    showProviderToast(data.message || '操作成功', 'success');
                    if (onOk) onOk(data);
                    asAfterReqHandled();
                })
                .catch(function () { showProviderToast('操作失败：网络异常', 'error'); });
        }

        function asAdoptSelected() {
            var ids = asSelectedReqIds();
            if (!ids.length) { showProviderToast('请先勾选要采纳的排课要求', 'error'); return; }
            asPostReqJson('/admin/schedule-constraints/adopt', { ids: ids, note: asReqNoteValue() }, function () {
                // 审核通过后流程回到第一步按顺序重新流转，已采纳约束在第 3 步统一导入
                asCloseConstraintRequests();
                asShowPanel(1);
                asSetStep(1);
            });
        }

        function asRejectSelected() {
            var ids = asSelectedReqIds();
            if (!ids.length) { showProviderToast('请先勾选要驳回的排课要求', 'error'); return; }
            var reason = asReqNoteValue();
            if (!reason) { showProviderToast('请先在备注框填写驳回原因（会通知教师）', 'error'); return; }
            asPostReqJson('/admin/schedule-constraints/reject', { ids: ids, reason: reason });
        }

        /** 预览/失败提示中的未生效约束：展示并允许一键通知相关教师 */
        function handleAsUnmatched(messages) {
            var box = document.getElementById('asUnmatchedBox');
            var listEl = document.getElementById('asUnmatchedList');
            var msgEl = document.getElementById('asNotifyMsg');
            var btn = document.getElementById('asNotifyUnmatchedBtn');
            var seen = {};
            var list = [];
            (messages || []).forEach(function (m) {
                var s = (m == null ? '' : String(m)).trim();
                if (!s || seen[s]) return;
                seen[s] = true;
                list.push(s);
            });
            asReqUnmatchedMessages = list;
            if (!box || !listEl) return;
            if (!list.length) {
                box.style.display = 'none';
                listEl.innerHTML = '';
                if (msgEl) msgEl.textContent = '';
                return;
            }
            listEl.innerHTML = list.map(function (s) { return '<li>' + escapeHtml(s) + '</li>'; }).join('');
            box.style.display = 'block';
            if (msgEl) msgEl.textContent = '';
            if (btn) { btn.disabled = false; btn.textContent = '一键通知教师'; }
        }

        function asNotifyUnmatched() {
            if (!asReqUnmatchedMessages.length) {
                showProviderToast('没有可通知的未生效约束信息', 'error');
                return;
            }
            var btn = document.getElementById('asNotifyUnmatchedBtn');
            var msgEl = document.getElementById('asNotifyMsg');
            if (btn) { btn.disabled = true; btn.textContent = '通知中...'; }
            fetch('/admin/schedule-constraints/notify-unmatched', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ messages: asReqUnmatchedMessages, semester: '' })
            })
                .then(function (r) { return r.json(); })
                .then(function (data) {
                    if (btn) { btn.disabled = false; btn.textContent = '一键通知教师'; }
                    if (!data || !data.success) {
                        if (msgEl) msgEl.textContent = (data && data.message) || '通知失败';
                        showProviderToast((data && data.message) || '通知失败', 'error');
                        return;
                    }
                    if (msgEl) msgEl.textContent = data.message || '已通知相关教师';
                    showProviderToast(data.message || '已通知相关教师', 'success');
                    asLoadConstraintRequestCount();
                    var modal = document.getElementById('asReqModal');
                    if (modal && modal.classList.contains('show')) asLoadConstraintRequests();
                })
                .catch(function () {
                    if (btn) { btn.disabled = false; btn.textContent = '一键通知教师'; }
                    if (msgEl) msgEl.textContent = '通知失败：网络异常';
                });
        }
