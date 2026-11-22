/********** 通知管理：发送班级 / 全校通知、通知历史列表 **********/
        // ===================== 通知管理 =====================
        function initNotificationsAdmin() {
            loadNotifClassOptions();
            loadNotifHistory();
        }

        function loadNotifClassOptions() {
            const select = document.getElementById('notifClassSelect');
            if (!select || select.options.length > 1) return;
            fetch('/admin/classes?page=1&size=100')
                .then(response => response.json())
                .then(data => {
                    select.innerHTML = '<option value="">请选择班级</option>';
                    const list = (data && data.success && data.classes) ? data.classes : [];
                    list.forEach(c => {
                        const opt = document.createElement('option');
                        opt.value = c.id;
                        opt.textContent = c.className || c.class_name || ('班级' + c.id);
                        select.appendChild(opt);
                    });
                })
                .catch(() => { select.innerHTML = '<option value="">班级加载失败</option>'; });
        }

        function onNotifScopeChange() {
            const scope = document.getElementById('notifScope').value;
            document.getElementById('notifClassGroup').style.display = scope === 'class' ? 'block' : 'none';
        }

        function setNotifResult(ok, text) {
            const box = document.getElementById('notifSendResult');
            box.style.display = 'block';
            box.textContent = text;
            box.style.color = ok ? '#2E7D32' : '#C62828';
        }

        function sendAdminNotification() {
            const title = document.getElementById('notifTitle').value.trim();
            const content = document.getElementById('notifContent').value.trim();
            const scope = document.getElementById('notifScope').value;
            const classId = document.getElementById('notifClassSelect').value;
            if (!title) { setNotifResult(false, '请填写通知标题'); return; }
            if (!content) { setNotifResult(false, '请填写通知内容'); return; }
            if (scope === 'class' && !classId) { setNotifResult(false, '请选择目标班级'); return; }

            const btn = document.getElementById('notifSendBtn');
            btn.disabled = true;
            btn.textContent = '发送中...';
            const body = { title: title, content: content, scope: scope };
            if (scope === 'class') body.classId = classId;

            fetch('/admin/notifications', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            })
                .then(response => response.json())
                .then(data => {
                    btn.disabled = false;
                    btn.textContent = '发送通知';
                    setNotifResult(!!data.success, data.message || (data.success ? '发送成功' : '发送失败'));
                    if (data.success) {
                        document.getElementById('notifTitle').value = '';
                        document.getElementById('notifContent').value = '';
                        loadNotifHistory();
                    }
                })
                .catch(() => {
                    btn.disabled = false;
                    btn.textContent = '发送通知';
                    setNotifResult(false, '网络异常，请稍后重试');
                });
        }

        function notifScopeText(scope, className) {
            if (scope === 'all_users') return '全部用户';
            if (scope === 'all_students') return '全部学生';
            if (scope === 'class') return '指定班级' + (className ? '：' + className : '');
            return scope || '-';
        }

        function loadNotifHistory() {
            const tbody = document.getElementById('notifHistoryBody');
            if (!tbody) return;
            tbody.innerHTML = '<tr><td colspan="7" style="text-align:center;color:#999999;">加载中...</td></tr>';
            fetch('/admin/notifications/history')
                .then(response => response.json())
                .then(data => {
                    if (!data.success) {
                        tbody.innerHTML = '<tr><td colspan="7" style="text-align:center;color:#C62828;">' + escapeHtml(data.message || '加载失败') + '</td></tr>';
                        return;
                    }
                    const list = data.history || [];
                    if (!list.length) {
                        tbody.innerHTML = '<tr><td colspan="7" style="text-align:center;color:#999999;">暂无通知发送记录，系统自动通知与管理端发送的通知会显示在这里</td></tr>';
                        return;
                    }
                    tbody.innerHTML = list.map(l => `
                        <tr>
                            <td>${escapeHtml(l.title || '-')}</td>
                            <td class="notif-history-content">${escapeHtml(l.content || '-')}</td>
                            <td>${escapeHtml(l.scopeText || notifScopeText(l.scope, l.className))}</td>
                            <td>${escapeHtml(formatTime(l.createTime))}</td>
                            <td>${l.receiverCount == null ? '-' : l.receiverCount}</td>
                            <td>${notifReadText(l)}</td>
                            <td>${escapeHtml(l.sender || '-')}</td>
                        </tr>
                    `).join('');
                })
                .catch(() => {
                    tbody.innerHTML = '<tr><td colspan="7" style="text-align:center;color:#C62828;">加载失败：网络异常</td></tr>';
                });
        }

        function notifReadText(l) {
            const total = l.receiverCount == null ? 0 : l.receiverCount;
            const read = l.readCount == null ? 0 : l.readCount;
            const unread = l.unreadCount == null ? Math.max(total - read, 0) : l.unreadCount;
            return read + ' / ' + unread;
        }
