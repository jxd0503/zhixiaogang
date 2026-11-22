/********** teacher-classes：班级管理（学生名单、加入/退班申请） **********/

        // ============ 班级管理 ============
        let currentClassId = null;

        function loadMyClasses() {
            const grid = document.getElementById('classListGrid');
            grid.innerHTML = '<div class="empty-hint">加载中...</div>';
            fetch('/api/teacher/classes')
                .then(r => r.json())
                .then(data => {
                    if (data.success && data.data && data.data.length) {
                        grid.innerHTML = data.data.map(c => {
                            return `<div class="class-card" onclick="openClassDetail(${c.id}, '${escapeHtml(c.className || '')}')">
                                <div class="cls-name">${escapeHtml(c.className || '-')}</div>
                                <div class="cls-meta">年级：${escapeHtml(c.grade || '-')}</div>
                                <div class="cls-meta">系部：${escapeHtml(c.department || '-')}</div>
                                <div class="cls-meta">学生人数：${c.memberCount || 0} 人</div>
                            </div>`;
                        }).join('');
                    } else if (data.success) {
                        grid.innerHTML = '<div class="empty-hint">您当前未管理任何班级</div>';
                    } else {
                        grid.innerHTML = `<div class="empty-hint">${escapeHtml(data.message || '加载失败')}</div>`;
                    }
                })
                .catch(err => {
                    grid.innerHTML = '<div class="empty-hint">加载失败：网络异常</div>';
                });
        }

        function openClassDetail(classId, className) {
            currentClassId = classId;
            document.getElementById('detailClassName').textContent = (className || '班级详情');
            document.getElementById('classListView').style.display = 'none';
            document.getElementById('classDetailView').style.display = 'block';
            loadClassStudents(classId);
            loadClassApplications(classId);
        }

        function backToClassList() {
            currentClassId = null;
            document.getElementById('classDetailView').style.display = 'none';
            document.getElementById('classListView').style.display = 'block';
            loadMyClasses();
        }

        function loadClassStudents(classId) {
            const tbody = document.getElementById('studentTableBody');
            tbody.innerHTML = '<tr><td colspan="5" class="empty-hint">加载中...</td></tr>';
            fetch(`/api/teacher/classes/${classId}/students`)
                .then(r => r.json())
                .then(data => {
                    if (data.success && data.data && data.data.length) {
                        tbody.innerHTML = data.data.map(s => {
                            return `<tr>
                                <td>${escapeHtml(s.studentId || '-')}</td>
                                <td>${escapeHtml(s.name || '-')}</td>
                                <td>${escapeHtml(s.major || '-')}</td>
                                <td>${escapeHtml(s.grade || '-')}</td>
                                <td><button class="btn-reject" onclick="removeStudent(${classId}, '${escapeHtml(s.studentId || '')}', '${escapeHtml(s.name || '')}')">踢出</button></td>
                            </tr>`;
                        }).join('');
                    } else if (data.success) {
                        tbody.innerHTML = '<tr><td colspan="5" class="empty-hint">暂无学生</td></tr>';
                    } else {
                        tbody.innerHTML = `<tr><td colspan="5" class="empty-hint">${escapeHtml(data.message || '加载失败')}</td></tr>`;
                    }
                })
                .catch(err => {
                    tbody.innerHTML = '<tr><td colspan="5" class="empty-hint">加载失败：网络异常</td></tr>';
                });
        }

        // 踢出学生（强制退班）：二次确认后调用后端接口，成功后刷新名单与申请列表
        async function removeStudent(classId, studentId, name) {
            const ok = await showConfirm({
                title: '移出学生',
                message: `确定将 ${name}（${studentId}）移出班级？\n该学生将立即失去本班课表访问权限。`,
                confirmText: '移出',
                danger: true
            });
            if (!ok) return;
            fetch(`/api/teacher/classes/${classId}/students/${encodeURIComponent(studentId)}/remove`, { method: 'POST' })
                .then(r => r.json())
                .then(data => {
                    if (data.success) {
                        loadClassStudents(classId);
                        loadClassApplications(classId);
                        loadMyClasses();
                    } else {
                        showToast(data.message || '操作失败', 'error');
                    }
                })
                .catch(err => showToast('操作失败：网络异常', 'error'));
        }

        function loadClassApplications(classId) {
            const joinBox = document.getElementById('joinAppList');
            const withdrawBox = document.getElementById('withdrawAppList');
            joinBox.innerHTML = '<div class="empty-hint">加载中...</div>';
            withdrawBox.innerHTML = '<div class="empty-hint">加载中...</div>';

            fetch(`/api/teacher/classes/${classId}/applications`)
                .then(r => r.json())
                .then(data => {
                    if (!data.success) {
                        joinBox.innerHTML = `<div class="empty-hint">${escapeHtml(data.message || '加载失败')}</div>`;
                        withdrawBox.innerHTML = '';
                        return;
                    }
                    // 加入申请
                    const joins = data.joinApplications || [];
                    if (joins.length) {
                        joinBox.innerHTML = joins.map(a => {
                            return `<div class="app-item">
                                <div class="app-info">${escapeHtml(a.name || '-')}（${escapeHtml(a.studentId || '-')}）<span>${escapeHtml(a.major || '')} · ${formatTime(a.createTime)}</span></div>
                                <div class="app-actions">
                                    <button class="btn-approve" onclick="approveApp(${a.id})">同意</button>
                                    <button class="btn-reject" onclick="rejectApp(${a.id})">拒绝</button>
                                </div>
                            </div>`;
                        }).join('');
                    } else {
                        joinBox.innerHTML = '<div class="empty-hint">暂无加入申请</div>';
                    }
                    // 退班申请
                    const withdraws = data.withdrawApplications || [];
                    if (withdraws.length) {
                        withdrawBox.innerHTML = withdraws.map(a => {
                            return `<div class="app-item">
                                <div class="app-info">${escapeHtml(a.name || '-')}（${escapeHtml(a.studentId || '-')}）<span>${escapeHtml(a.major || '')} · ${formatTime(a.updateTime || a.createTime)}</span></div>
                                <div class="app-actions">
                                    <button class="btn-approve" onclick="confirmWithdrawApp(${a.id})">同意退班</button>
                                </div>
                            </div>`;
                        }).join('');
                    } else {
                        withdrawBox.innerHTML = '<div class="empty-hint">暂无退班申请</div>';
                    }
                })
                .catch(err => {
                    joinBox.innerHTML = '<div class="empty-hint">加载失败：网络异常</div>';
                    withdrawBox.innerHTML = '';
                });
        }

        function approveApp(id) {
            fetch(`/api/teacher/applications/${id}/approve`, { method: 'POST' })
                .then(r => r.json())
                .then(data => {
                    showToast(data.message || (data.success ? '已通过' : '操作失败'), data.success ? 'success' : 'error');
                    if (data.success && currentClassId) {
                        loadClassStudents(currentClassId);
                        loadClassApplications(currentClassId);
                    }
                })
                .catch(err => showToast('网络异常', 'error'));
        }

        function rejectApp(id) {
            fetch(`/api/teacher/applications/${id}/reject`, { method: 'POST' })
                .then(r => r.json())
                .then(data => {
                    showToast(data.message || (data.success ? '已拒绝' : '操作失败'), data.success ? 'success' : 'error');
                    if (data.success && currentClassId) {
                        loadClassApplications(currentClassId);
                    }
                })
                .catch(err => showToast('网络异常', 'error'));
        }

        function confirmWithdrawApp(id) {
            fetch(`/api/teacher/applications/${id}/confirm-withdraw`, { method: 'POST' })
                .then(r => r.json())
                .then(data => {
                    showToast(data.message || (data.success ? '已同意退班' : '操作失败'), data.success ? 'success' : 'error');
                    if (data.success && currentClassId) {
                        loadClassStudents(currentClassId);
                        loadClassApplications(currentClassId);
                    }
                })
                .catch(err => showToast('网络异常', 'error'));
        }
