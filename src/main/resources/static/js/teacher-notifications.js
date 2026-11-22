/********** teacher-notifications：通知中心（未读轮询、列表、已读） **********/

        // ==================== 通知中心 ====================
        function toggleNotifPanel(e) {
            e.stopPropagation();
            const panel = document.getElementById('notifPanel');
            const isOpen = panel.classList.contains('show');
            if (isOpen) {
                panel.classList.remove('show');
                return;
            }
            panel.classList.add('show');
            loadNotifications();
            loadNotifCount();
        }

        function closeNotifPanel() {
            const panel = document.getElementById('notifPanel');
            if (panel) panel.classList.remove('show');
        }

        // 未读小红点定时轮询：每 12 秒刷新一次，标签页隐藏时暂停、可见时立即刷新并恢复
        let notifPollTimer = null;
        const NOTIF_POLL_INTERVAL = 12000;

        function startNotifPolling() {
            if (notifPollTimer) clearInterval(notifPollTimer);
            notifPollTimer = setInterval(loadNotifCount, NOTIF_POLL_INTERVAL);
        }

        function stopNotifPolling() {
            if (notifPollTimer) { clearInterval(notifPollTimer); notifPollTimer = null; }
        }

        document.addEventListener('visibilitychange', function () {
            if (document.hidden) {
                stopNotifPolling();
            } else {
                loadNotifCount();
                startNotifPolling();
            }
        });

        function loadNotifCount() {
            fetch('/api/notifications/unread-count')
                .then(r => { if (r.redirected) throw new Error('auth'); return r.json(); })
                .then(data => {
                    const badge = document.getElementById('notifBadge');
                    if (!badge) return;
                    const count = data.success ? (data.count || 0) : 0;
                    if (count > 0) {
                        badge.textContent = count > 99 ? '99+' : count;
                        badge.style.display = 'flex';
                    } else {
                        badge.style.display = 'none';
                    }
                })
                .catch(() => {});
        }

        function loadNotifications() {
            const box = document.getElementById('notifList');
            box.innerHTML = '<div class="notif-empty">加载中...</div>';
            fetch('/api/notifications')
                .then(r => { if (r.redirected) throw new Error('auth'); return r.json(); })
                .then(data => {
                    if (!data.success) {
                        box.innerHTML = '<div class="notif-empty">' + escapeHtml(data.message || '加载失败') + '</div>';
                        return;
                    }
                    const list = data.notifications || [];
                    if (!list.length) {
                        box.innerHTML = '<div class="notif-empty">暂无通知</div>';
                        return;
                    }
                    box.innerHTML = list.map(renderNotifItem).join('');
                })
                .catch(() => { box.innerHTML = '<div class="notif-empty">加载失败：网络异常</div>'; });
        }

        function notifTimeText(t) {
            if (!t) return '';
            return String(t).replace('T', ' ').replace(/\.\d+$/, '');
        }

        function renderNotifItem(n) {
            const unread = !n.isRead;
            return '<div class="notif-item' + (unread ? ' unread' : '') + '"'
                + (unread ? ' onclick="markNotifRead(' + n.id + ', event)"' : '')
                + '>'
                + '<div class="notif-item-title">' + escapeHtml(n.title || '') + '</div>'
                + '<div class="notif-item-content">' + escapeHtml(n.content || '') + '</div>'
                + '<div class="notif-item-time">' + escapeHtml(notifTimeText(n.createTime)) + '</div>'
                + '</div>';
        }

        function markNotifRead(id, e) {
            if (e) e.stopPropagation();
            fetch('/api/notifications/' + id + '/read', { method: 'POST' })
                .then(r => r.json())
                .then(() => { loadNotifications(); loadNotifCount(); })
                .catch(() => {});
        }

        function markAllNotifRead(e) {
            if (e) e.stopPropagation();
            fetch('/api/notifications/read-all', { method: 'POST' })
                .then(r => r.json())
                .then(() => { loadNotifications(); loadNotifCount(); })
                .catch(() => {});
        }

        document.addEventListener('click', function (e) {
            const wrap = e.target && e.target.closest ? e.target.closest('.notif-wrap') : null;
            if (!wrap) closeNotifPanel();
        });

        loadNotifCount();
        startNotifPolling();
