/********** 操作日志：日志查询、分页、导出与保留策略配置 **********/
        // ==================== 操作日志（审计） ====================
        var opsPage = 1;
        var opsTotal = 0;
        var OPS_PAGE_SIZE = 20;

        var OPS_ROLE_NAMES = { student: '学生', teacher: '教师', admin: '管理员' };
        var OPS_ACTION_NAMES = {
            login: '登录', logout: '登出', register: '注册', apply: '申请', approve: '批准',
            'force-approve': '强制放行',
            reject: '拒绝', cancel: '取消', import: '导入', export: '导出', generate: '生成',
            confirm: '确认', create: '新增', update: '修改', delete: '删除', read: '已读',
            send: '发送', reserve: '预约', query: '查询'
        };
        var OPS_TARGET_NAMES = {
            user: '用户', class: '班级', classroom: '教室', course: '课程', schedule: '课表',
            reservation: '教室预约', adjustment: '调课申请', class_application: '入班申请',
            class_member: '班级成员', notification: '通知', semester: '学期设置',
            schedule_constraint: '排课要求', operation_log: '操作日志',
            operation_log_config: '日志配置', ai_provider: '模型提供商'
        };

        function initOperationLogs() {
            loadOperationLogConfig();
            loadOperationLogs(1);
        }

        function buildOpsQuery() {
            var params = new URLSearchParams();
            var role = document.getElementById('opsRole').value;
            var username = document.getElementById('opsUsername').value.trim();
            var action = document.getElementById('opsAction').value;
            var result = document.getElementById('opsResult').value;
            var startTime = document.getElementById('opsStartTime').value;
            var endTime = document.getElementById('opsEndTime').value;
            if (role) params.set('role', role);
            if (username) params.set('username', username);
            if (action) params.set('action', action);
            if (result) params.set('result', result);
            if (startTime) params.set('startTime', startTime);
            if (endTime) params.set('endTime', endTime);
            return params;
        }

        function loadOperationLogs(page) {
            var target = page && page > 0 ? page : 1;
            var tbody = document.getElementById('opsTableBody');
            if (!tbody) return;
            tbody.innerHTML = '<tr><td colspan="9" class="empty-hint">加载中...</td></tr>';
            var params = buildOpsQuery();
            params.set('page', target);
            params.set('size', OPS_PAGE_SIZE);
            fetch('/admin/operation-logs?' + params.toString())
                .then(function (r) { return r.json(); })
                .then(function (data) {
                    if (!data.success) {
                        tbody.innerHTML = '<tr><td colspan="9" class="empty-hint">'
                            + escapeHtml(data.message || '加载失败') + '</td></tr>';
                        return;
                    }
                    opsPage = data.page || target;
                    opsTotal = data.total || 0;
                    renderOperationLogs(data.data || []);
                    renderOpsPager();
                })
                .catch(function () {
                    tbody.innerHTML = '<tr><td colspan="9" class="empty-hint">网络异常，加载失败</td></tr>';
                });
        }

        function renderOperationLogs(list) {
            var tbody = document.getElementById('opsTableBody');
            if (!list.length) {
                tbody.innerHTML = '<tr><td colspan="9" class="empty-hint">暂无符合条件的操作日志</td></tr>';
                return;
            }
            var html = '';
            list.forEach(function (log) {
                var targetText = OPS_TARGET_NAMES[log.targetType] || log.targetType || '-';
                if (log.targetId) targetText += ' #' + log.targetId;
                var resultText = String(log.result || '');
                var ok = resultText.indexOf('failed') !== 0;
                var resultLabel = ok ? '成功' : '失败';
                var resultDetail = ok ? '' : resultText.replace(/^failed[:\s]*/, '');
                var cost = (log.costMs === null || log.costMs === undefined) ? '-' : log.costMs + ' ms';
                html += '<tr>'
                    + '<td class="ops-time">' + escapeHtml(formatTime(log.createTime)) + '</td>'
                    + '<td>' + escapeHtml(log.username || '-') + '</td>'
                    + '<td>' + escapeHtml(OPS_ROLE_NAMES[log.role] || log.role || '-') + '</td>'
                    + '<td>' + escapeHtml(OPS_ACTION_NAMES[log.action] || log.action || '-') + '</td>'
                    + '<td>' + escapeHtml(targetText) + '</td>'
                    + '<td class="ops-detail" title="' + escapeHtml(log.detail || '') + '">'
                    + escapeHtml(log.detail || '-') + '</td>'
                    + '<td><span class="status-badge ' + (ok ? 'approved' : 'rejected') + '"'
                    + (resultDetail ? ' title="' + escapeHtml(resultDetail) + '"' : '') + '>'
                    + resultLabel + '</span></td>'
                    + '<td>' + escapeHtml(log.ip || '-') + '</td>'
                    + '<td>' + escapeHtml(cost) + '</td>'
                    + '</tr>';
            });
            tbody.innerHTML = html;
        }

        function renderOpsPager() {
            var pages = Math.max(1, Math.ceil(opsTotal / OPS_PAGE_SIZE));
            document.getElementById('opsPageInfo').textContent =
                '共 ' + opsTotal + ' 条 · 第 ' + opsPage + ' / ' + pages + ' 页';
            document.getElementById('opsPrevBtn').disabled = opsPage <= 1;
            document.getElementById('opsNextBtn').disabled = opsPage >= pages;
        }

        function resetOperationLogFilters() {
            document.getElementById('opsRole').value = '';
            document.getElementById('opsUsername').value = '';
            document.getElementById('opsAction').value = '';
            document.getElementById('opsResult').value = '';
            document.getElementById('opsStartTime').value = '';
            document.getElementById('opsEndTime').value = '';
            loadOperationLogs(1);
        }

        function exportOperationLogs() {
            var params = buildOpsQuery();
            window.open('/admin/operation-logs/export?' + params.toString(), '_blank');
            showToast('正在导出 CSV', 'success');
        }

        function loadOperationLogConfig() {
            fetch('/admin/operation-logs/config')
                .then(function (r) { return r.json(); })
                .then(function (data) {
                    if (!data.success) return;
                    document.getElementById('opsRetentionDays').value = data.retentionDays;
                    document.getElementById('opsFileKeepDays').value = data.fileKeepDays;
                    document.getElementById('opsRecordAllReads').checked = !!data.recordAllReads;
                    document.getElementById('opsFileEnabled').checked = !!data.fileEnabled;
                    document.getElementById('opsArchiveEnabled').checked = !!data.archiveEnabled;
                })
                .catch(function () { /* 配置读取失败不阻塞日志列表 */ });
        }

        function saveOperationLogConfig() {
            var body = {
                retentionDays: parseInt(document.getElementById('opsRetentionDays').value, 10),
                fileKeepDays: parseInt(document.getElementById('opsFileKeepDays').value, 10),
                recordAllReads: document.getElementById('opsRecordAllReads').checked,
                fileEnabled: document.getElementById('opsFileEnabled').checked,
                archiveEnabled: document.getElementById('opsArchiveEnabled').checked
            };
            if (!body.retentionDays || !body.fileKeepDays) {
                showToast('请填写有效的保留天数', 'error');
                return;
            }
            fetch('/admin/operation-logs/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            })
                .then(function (r) { return r.json(); })
                .then(function (data) {
                    showOpsConfigResult(!!data.success, data.message || (data.success ? '配置已保存' : '保存失败'));
                    if (data.success) loadOperationLogs(1);
                })
                .catch(function () { showOpsConfigResult(false, '网络异常，保存失败'); });
        }

        function showOpsConfigResult(ok, msg) {
            var el = document.getElementById('opsConfigResult');
            el.style.display = 'block';
            el.textContent = msg;
            el.style.color = ok ? '#111111' : '#c0392b';
            el.style.background = ok ? '#f5f5f5' : '#fdf0ef';
            el.style.border = ok ? '1px solid #dddddd' : '1px solid #e8b4ae';
            el.style.padding = '10px 12px';
            el.style.borderRadius = '8px';
            el.style.fontSize = '13px';
            el.style.marginTop = '10px';
        }
