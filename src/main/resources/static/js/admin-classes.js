/********** 班级管理：班级列表与详情、学生列表、添加学生弹窗 **********/
        const STUDENT_STATUS_TEXT = { approved: '已加入', pending: '申请中', withdraw_pending: '退班待审核', withdrawn: '已退出' };
        const STUDENT_STATUS_CLASS = { approved: 'approved', pending: 'pending', withdraw_pending: 'rejected', withdrawn: 'closed' };

        let classList = [];
        let classPage = 1;
        let classTotal = 0;
        const CLASS_PAGE_SIZE = 10;
        let currentClass = null;       // 当前详情班级
        let detailSchedule = [];       // 当前班级课表
        let detailStudents = [];       // 当前班级学生（含状态）
        let previewItems = [];         // AI 导入预览条目
        let teacherOptions = null;     // 教师下拉缓存
        let classroomOptions = null;   // 教室下拉缓存
        let scheduleViewMode = 'list'; // 课表区域视图：list | grid
        let scheduleSelectedIds = new Set(); // 列表模式已勾选课表条目 id
        let scheduleGridPress = null;  // 课程卡片按下状态（移动端防误触）
        let studentPickerAll = [];     // 添加学生弹窗：全部学生（含可选标记）
        let studentPickerSelected = new Set(); // 添加学生弹窗：已勾选学号

        function loadClasses(page) {
            const p = page || classPage || 1;
            const tbody = document.getElementById('classTableBody');
            tbody.innerHTML = '<tr><td colspan="7" class="empty-hint">加载中...</td></tr>';
            fetch('/admin/classes?page=' + p + '&size=' + CLASS_PAGE_SIZE)
                .then(r => r.json())
                .then(data => {
                    if (data.success) {
                        classPage = data.page || p;
                        classTotal = data.total || 0;
                        classList = data.classes || [];
                        // 删除末页最后一条后回退到上一页
                        if (!classList.length && classTotal > 0 && classPage > 1) {
                            loadClasses(classPage - 1);
                            return;
                        }
                        renderClasses();
                        renderClassPager();
                    } else {
                        tbody.innerHTML = `<tr><td colspan="7" class="empty-hint">${escapeHtml(data.message || '加载失败')}</td></tr>`;
                    }
                })
                .catch(() => { tbody.innerHTML = '<tr><td colspan="7" class="empty-hint">加载失败：网络异常</td></tr>'; });
        }

        function renderClassPager() {
            if (typeof renderPager !== 'function') return;
            renderPager('classPager', classPage, CLASS_PAGE_SIZE, classTotal, function (p) {
                classPage = p;
                loadClasses(p);
            });
        }

        function renderClasses() {
            const tbody = document.getElementById('classTableBody');
            if (!classList.length) {
                tbody.innerHTML = '<tr><td colspan="7" class="empty-hint">暂无班级，点击右上角「添加班级」创建。</td></tr>';
                return;
            }
            tbody.innerHTML = classList.map(c => `
                <tr>
                    <td>${escapeHtml(c.className)}</td>
                    <td>${escapeHtml(c.grade || '-')}</td>
                    <td>${escapeHtml(c.department || '-')}</td>
                    <td>${escapeHtml(c.teacherName || (c.teacherId || '未指定'))}</td>
                    <td>${c.studentCount}</td>
                    <td>${c.scheduleCount}</td>
                    <td style="white-space:nowrap;">
                        <button class="action-btn edit" style="padding:6px 12px;" onclick="showClassDetail(${c.id})">详情</button>
                        <button class="action-btn edit" style="padding:6px 12px;" onclick="openClassModal(${c.id})">编辑</button>
                        <button class="action-btn delete" style="padding:6px 12px;" onclick="deleteClass(${c.id}, this)">删除</button>
                    </td>
                </tr>`).join('');
        }

        function fetchTeachers() {
            if (teacherOptions) return Promise.resolve(teacherOptions);
            return fetch('/admin/users?role=teacher&page=1&size=100')
                .then(r => r.json())
                .then(data => {
                    teacherOptions = (data.success ? data.users : []).filter(u => u.role === 'teacher');
                    return teacherOptions;
                });
        }

        function fetchClassrooms() {
            if (classroomOptions) return Promise.resolve(classroomOptions);
            return fetch('/admin/classrooms')
                .then(r => r.json())
                .then(data => {
                    classroomOptions = data.success ? (data.classrooms || []) : [];
                    return classroomOptions;
                });
        }

        function openClassModal(id) {
            document.getElementById('classFormId').value = id || '';
            const teacherSelect = document.getElementById('classFormTeacher');
            fetchTeachers().then(teachers => {
                const current = id ? (classList.find(c => c.id === id) || {}) : {};
                teacherSelect.innerHTML = '<option value="">暂不指定</option>' + teachers.map(t =>
                    `<option value="${escapeHtml(t.studentId)}">${escapeHtml(t.name)}（${escapeHtml(t.studentId)}）</option>`).join('');
                if (id) {
                    document.getElementById('classModalTitle').textContent = '编辑班级';
                    document.getElementById('classFormName').value = current.className || '';
                    document.getElementById('classFormGrade').value = current.grade || '';
                    document.getElementById('classFormDepartment').value = current.department || '';
                    teacherSelect.value = current.teacherId || '';
                } else {
                    document.getElementById('classModalTitle').textContent = '添加班级';
                    document.getElementById('classFormName').value = '';
                    document.getElementById('classFormGrade').value = '';
                    document.getElementById('classFormDepartment').value = '';
                    teacherSelect.value = '';
                }
                document.getElementById('classEditModal').classList.add('show');
            });
        }

        function closeClassModal() {
            document.getElementById('classEditModal').classList.remove('show');
        }

        function submitClassModal() {
            const idText = document.getElementById('classFormId').value;
            const payload = {
                className: document.getElementById('classFormName').value.trim(),
                grade: document.getElementById('classFormGrade').value,
                department: document.getElementById('classFormDepartment').value,
                teacherId: document.getElementById('classFormTeacher').value
            };
            if (!payload.className) {
                showProviderToast('请填写班级名称', 'error');
                return;
            }
            const url = idText ? '/admin/classes/' + idText : '/admin/classes';
            fetch(url, {
                method: idText ? 'PUT' : 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            })
                .then(r => r.json())
                .then(data => {
                    showProviderToast(data.message || (data.success ? '保存成功' : '保存失败'), data.success ? 'success' : 'error');
                    if (data.success) {
                        closeClassModal();
                        loadClasses();
                    }
                })
                .catch(() => showProviderToast('保存失败：网络异常', 'error'));
        }

        async function deleteClass(id) {
            const cls = classList.find(c => c.id === id) || {};
            const ok = await showConfirm({
                title: '删除班级',
                message: `确定删除班级「${cls.className || id}」吗？将同时删除该班级的全部成员关系与课表条目，不可恢复。`,
                confirmText: '删除',
                danger: true
            });
            if (!ok) return;
            fetch('/admin/classes/' + id, { method: 'DELETE' })
                .then(r => r.json())
                .then(data => {
                    showProviderToast(data.message || (data.success ? '删除成功' : '删除失败'), data.success ? 'success' : 'error');
                    if (data.success) loadClasses();
                })
                .catch(() => showProviderToast('删除失败：网络异常', 'error'));
        }

        function showClassDetail(id) {
            document.querySelectorAll('.sidebar-item').forEach(item => item.classList.remove('active'));
            const sidebarBtns = document.querySelectorAll('.sidebar-item');
            for (let i = 0; i < sidebarBtns.length; i++) {
                if (sidebarBtns[i].getAttribute('onclick') && sidebarBtns[i].getAttribute('onclick').includes("'classes'")) {
                    sidebarBtns[i].classList.add('active');
                    break;
                }
            }
            document.getElementById('dashboardPage').style.display = 'none';
            document.getElementById('usersPage').style.display = 'none';
            document.getElementById('classroomsPage').style.display = 'none';
            document.getElementById('classesPage').style.display = 'none';
            document.getElementById('classDetailPage').style.display = 'none';
            document.getElementById('reservationsPage').style.display = 'none';
            document.getElementById('aiConfigPage').style.display = 'none';
            document.getElementById('classDetailPage').style.display = 'block';
            document.getElementById('scheduleDayFilter').value = '';
            document.getElementById('studentStatusFilter').value = '';
            scheduleViewMode = 'list';
            scheduleSelectedIds.clear();
            loadClassDetail(id);
        }

        function backToClassList() {
            showClassListAndLoad();
        }

        function showClassListAndLoad() {
            document.querySelectorAll('.sidebar-item').forEach(item => item.classList.remove('active'));
            const sidebarBtns = document.querySelectorAll('.sidebar-item');
            for (let i = 0; i < sidebarBtns.length; i++) {
                const oc = sidebarBtns[i].getAttribute('onclick') || '';
                if (oc.includes("'classes'")) { sidebarBtns[i].classList.add('active'); break; }
            }
            document.getElementById('dashboardPage').style.display = 'none';
            document.getElementById('usersPage').style.display = 'none';
            document.getElementById('classroomsPage').style.display = 'none';
            document.getElementById('classDetailPage').style.display = 'none';
            document.getElementById('reservationsPage').style.display = 'none';
            document.getElementById('aiConfigPage').style.display = 'none';
            document.getElementById('classesPage').style.display = 'block';
            loadClasses();
        }

        function loadClassDetail(id) {
            fetch('/admin/classes/' + id)
                .then(r => r.json())
                .then(data => {
                    if (!data.success) {
                        showProviderToast(data.message || '班级详情加载失败', 'error');
                        return;
                    }
                    currentClass = data.classInfo;
                    detailSchedule = currentClass.schedule || [];
                    detailStudents = currentClass.students || [];
                    document.getElementById('classDetailTitle').textContent = currentClass.className;
                    document.getElementById('classDetailInfo').textContent =
                        `年级：${currentClass.grade || '-'}　院系：${currentClass.department || '-'}　管理教师：${currentClass.teacherName || (currentClass.teacherId || '未指定')}　学生数：${detailStudents.filter(s => s.status === 'approved' || s.status === 'withdraw_pending').length}　课表条目：${detailSchedule.length} 条`;
                    scheduleSelectedIds.clear();
                    renderScheduleView();
                    renderStudentTable();
                    refreshImageImportAvailability();
                })
                .catch(() => showProviderToast('班级详情加载失败：网络异常', 'error'));
        }

        // P2-3 进入班级详情即判定多模态能力：不支持时识图按钮置灰并提示
        function refreshImageImportAvailability() {
            const btn = document.getElementById('btnImportImage');
            if (!btn) return;
            fetch('/admin/ai/multimodal-status')
                .then(r => r.json())
                .then(data => {
                    if (data.success && data.supported) {
                        btn.disabled = false;
                        btn.style.opacity = '';
                        btn.style.cursor = '';
                        btn.title = '';
                    } else {
                        btn.disabled = true;
                        btn.style.opacity = '0.45';
                        btn.style.cursor = 'not-allowed';
                        btn.title = `当前生效模型不支持识图（当前模型：${data.model || '未配置'}），请在模型提供商管理中切换多模态模型`;
                    }
                })
                .catch(() => {
                    btn.disabled = true;
                    btn.style.opacity = '0.45';
                    btn.style.cursor = 'not-allowed';
                    btn.title = '多模态能力检测失败，请稍后重试';
                });
        }

        // ---------- 课表区域：列表 / 课程表双视图（同一数据源 detailSchedule） ----------

        // 列表与课程表共用同一份过滤结果，保证两种模式显示一致
        function getVisibleSchedule() {
            const sel = document.getElementById('scheduleDayFilter');
            const dayFilter = sel ? sel.value : '';
            return dayFilter ? detailSchedule.filter(s => String(s.weekDay) === dayFilter) : detailSchedule;
        }

        function onScheduleDayFilterChange() {
            // 切换星期筛选时清空勾选，避免误删被筛掉的条目
            scheduleSelectedIds.clear();
            renderScheduleView();
        }

        function switchScheduleView(mode) {
            if (mode !== 'list' && mode !== 'grid') return;
            if (scheduleViewMode === mode) return;
            scheduleViewMode = mode;
            closeScheduleCardPopover();
            renderScheduleView();
        }

        function renderScheduleView() {
            const listView = document.getElementById('scheduleListView');
            const gridView = document.getElementById('scheduleGridView');
            const listBtn = document.getElementById('scheduleViewListBtn');
            const gridBtn = document.getElementById('scheduleViewGridBtn');
            const batchBar = document.getElementById('scheduleBatchBar');
            if (!listView || !gridView) return;
            const isList = scheduleViewMode !== 'grid';
            listView.style.display = isList ? '' : 'none';
            gridView.style.display = isList ? 'none' : '';
            if (batchBar) batchBar.style.display = isList ? '' : 'none';
            if (listBtn) {
                listBtn.classList.toggle('active', isList);
                listBtn.setAttribute('aria-selected', isList ? 'true' : 'false');
            }
            if (gridBtn) {
                gridBtn.classList.toggle('active', !isList);
                gridBtn.setAttribute('aria-selected', isList ? 'false' : 'true');
            }
            if (isList) renderScheduleTable();
            else renderScheduleGrid();
        }

        function renderScheduleTable() {
            const tbody = document.getElementById('scheduleTableBody');
            const list = getVisibleSchedule();
            if (!list.length) {
                tbody.innerHTML = '<tr><td colspan="9" class="empty-hint">暂无课表条目，可手动添加或使用 AI 文字/识图导入。</td></tr>';
                updateScheduleBatchBar();
                return;
            }
            tbody.innerHTML = list.map(s => {
                const checked = scheduleSelectedIds.has(s.id);
                return `
                <tr class="${checked ? 'row-selected' : ''}">
                    <td class="col-check">
                        <input type="checkbox" class="sched-row-check" data-sid="${s.id}" ${checked ? 'checked' : ''}
                               onchange="toggleScheduleRow(${s.id}, this.checked)" aria-label="选择课程${escapeHtml(s.courseName || '')}">
                    </td>
                    <td>${escapeHtml(s.courseName || '-')}</td>
                    <td>${escapeHtml(s.teacherName || '-')}</td>
                    <td>${escapeHtml(s.classroomNo || '-')}</td>
                    <td><span class="weekday-tag">${WEEKDAY_NAMES_CN[s.weekDay] || s.weekDay}</span></td>
                    <td>${s.startPeriod}-${s.endPeriod}节</td>
                    <td>${escapeHtml(s.weekRange || '-')}</td>
                    <td>${escapeHtml(s.semester || '-')}</td>
                    <td style="white-space:nowrap;">
                        <button class="action-btn edit" style="padding:6px 12px;" onclick="openScheduleModal(${s.id})">编辑</button>
                        <button class="action-btn delete" style="padding:6px 12px;" onclick="deleteScheduleItem(${s.id})">删除</button>
                    </td>
                </tr>`;
            }).join('');
            updateScheduleBatchBar();
        }

        function toggleScheduleRow(sid, checked) {
            if (checked) scheduleSelectedIds.add(sid);
            else scheduleSelectedIds.delete(sid);
            const checkbox = document.querySelector(`#scheduleTableBody .sched-row-check[data-sid="${sid}"]`);
            if (checkbox && checkbox.closest('tr')) {
                checkbox.closest('tr').classList.toggle('row-selected', checked);
            }
            updateScheduleBatchBar();
        }

        function toggleScheduleSelectAll(checked) {
            const list = getVisibleSchedule();
            list.forEach(s => {
                if (checked) scheduleSelectedIds.add(s.id);
                else scheduleSelectedIds.delete(s.id);
            });
            renderScheduleTable();
        }

        function updateScheduleBatchBar() {
            const list = getVisibleSchedule();
            const visibleSelected = list.filter(s => scheduleSelectedIds.has(s.id)).length;
            const btn = document.getElementById('scheduleBatchDeleteBtn');
            const hint = document.getElementById('scheduleBatchHint');
            const all = document.getElementById('scheduleSelectAll');
            if (btn) btn.disabled = visibleSelected === 0;
            if (hint) hint.textContent = `已选 ${visibleSelected} 条`;
            if (all) {
                all.disabled = list.length === 0;
                all.checked = list.length > 0 && visibleSelected === list.length;
                all.indeterminate = visibleSelected > 0 && visibleSelected < list.length;
            }
        }

        async function batchDeleteSchedule() {
            if (!currentClass) return;
            const items = detailSchedule.filter(s => scheduleSelectedIds.has(s.id));
            if (!items.length) {
                showProviderToast('请先勾选要删除的课程', 'error');
                return;
            }
            const ok = await showConfirm({
                title: '批量删除课程',
                message: `将删除 ${items.length} 条课程记录及对应课表条目，删除后不可恢复，确定继续吗？`,
                confirmText: `删除 ${items.length} 条`,
                danger: true
            });
            if (!ok) return;

            const btn = document.getElementById('scheduleBatchDeleteBtn');
            const batchSids = items.map(s => s.id);
            batchSids.forEach(sid => scheduleSelectedIds.delete(sid));
            if (btn) btn.disabled = true;

            let successCount = 0;
            const failures = [];
            try {
                const resp = await fetch(`/admin/classes/${currentClass.id}/schedule/batch-delete`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ ids: batchSids })
                });
                const data = await resp.json();
                if (data.success) {
                    successCount = batchSids.length;
                } else {
                    successCount = data.deleted || 0;
                    failures.push(data.message || '部分条目删除失败');
                }
            } catch (e) {
                failures.push('网络异常');
            }

            if (failures.length) {
                showProviderToast(`已删除 ${successCount} 条，${failures.length} 条失败：${failures.join('；')}`, 'error');
            } else {
                showProviderToast(`已删除 ${successCount} 条课程`, 'success');
            }
            loadClassDetail(currentClass.id);
        }

        // ---------- 课程表模式：星期 × 节次网格 ----------
        function renderStudentTable() {
            const tbody = document.getElementById('studentTableBody');
            const statusFilter = document.getElementById('studentStatusFilter').value;
            const list = statusFilter ? detailStudents.filter(s => s.status === statusFilter) : detailStudents;
            if (!list.length) {
                tbody.innerHTML = '<tr><td colspan="5" class="empty-hint">暂无学生记录。</td></tr>';
                return;
            }
            tbody.innerHTML = list.map(s => `
                <tr>
                    <td>${escapeHtml(s.student_id)}</td>
                    <td>${escapeHtml(s.name)}</td>
                    <td>${escapeHtml(s.department || '-')}</td>
                    <td>${escapeHtml(s.major || '-')}</td>
                    <td><span class="status-badge ${STUDENT_STATUS_CLASS[s.status] || 'closed'}">${STUDENT_STATUS_TEXT[s.status] || s.status}</span></td>
                </tr>`).join('');
        }

        // ---------- 班级详情：直接选择学生加入班级 ----------

        function openStudentPicker() {
            if (!currentClass) return;
            studentPickerAll = [];
            studentPickerSelected.clear();
            document.getElementById('studentPickerTitle').textContent = `添加学生到「${currentClass.className}」`;
            document.getElementById('studentPickerSearch').value = '';
            document.getElementById('studentPickerHint').textContent = '加载中...';
            document.getElementById('studentPickerList').innerHTML = '';
            updateStudentPickerConfirm();
            document.getElementById('studentPickerModal').classList.add('show');

            fetch('/admin/classes/' + currentClass.id + '/addable-students')
                .then(r => r.json())
                .then(data => {
                    if (!data.success) {
                        document.getElementById('studentPickerHint').textContent = data.message || '学生列表加载失败';
                        showProviderToast(data.message || '学生列表加载失败', 'error');
                        return;
                    }
                    studentPickerAll = data.students || [];
                    renderStudentPickerList();
                })
                .catch(() => {
                    document.getElementById('studentPickerHint').textContent = '加载失败：网络异常';
                    showProviderToast('学生列表加载失败：网络异常', 'error');
                });
        }

        function closeStudentPicker() {
            document.getElementById('studentPickerModal').classList.remove('show');
            studentPickerSelected.clear();
            studentPickerAll = [];
        }

        function getVisiblePickerStudents() {
            const kw = (document.getElementById('studentPickerSearch').value || '').trim().toLowerCase();
            if (!kw) return studentPickerAll.slice();
            return studentPickerAll.filter(s =>
                [s.studentId, s.name, s.department, s.major, s.grade]
                    .some(v => (v || '').toLowerCase().includes(kw)));
        }

        function renderStudentPickerList() {
            const listEl = document.getElementById('studentPickerList');
            const hintEl = document.getElementById('studentPickerHint');
            const visible = getVisiblePickerStudents();
            const eligibleAll = studentPickerAll.filter(s => s.eligible);
            const eligibleVisible = visible.filter(s => s.eligible);

            if (!studentPickerAll.length) {
                hintEl.textContent = '暂无可添加的学生';
                listEl.innerHTML = '<div class="student-picker-empty">暂无在读学生账号可添加</div>';
                updateStudentPickerConfirm();
                return;
            }

            const selectedCount = studentPickerSelected.size;
            hintEl.textContent = `共 ${studentPickerAll.length} 人，可选 ${eligibleAll.length} 人` +
                (selectedCount ? `，已选 ${selectedCount} 人` : '');

            if (!visible.length) {
                listEl.innerHTML = '<div class="student-picker-empty">没有匹配的学生</div>';
                updateStudentPickerConfirm();
                return;
            }

            listEl.innerHTML = visible.map(s => {
                const checked = studentPickerSelected.has(s.studentId);
                return `<label class="student-picker-row${s.eligible ? '' : ' disabled'}">
                    <input type="checkbox" data-sid="${escapeHtml(s.studentId)}" ${s.eligible ? '' : 'disabled'} ${checked ? 'checked' : ''}
                        onchange="toggleStudentPickerRow(this)">
                    <span class="sp-main">
                        <span class="sp-name">${escapeHtml(s.name || '-')}</span>
                        <span class="sp-meta">${escapeHtml(s.studentId)} · ${escapeHtml(s.department || '-')} · ${escapeHtml(s.major || '-')}</span>
                    </span>
                    ${s.reason ? `<span class="sp-tag">${escapeHtml(s.reason)}</span>` : ''}
                </label>`;
            }).join('');

            updateStudentPickerConfirm(eligibleVisible);
        }

        function toggleStudentPickerRow(input) {
            const studentId = input.dataset.sid;
            if (!studentId) return;
            if (input.checked) studentPickerSelected.add(studentId);
            else studentPickerSelected.delete(studentId);
            renderStudentPickerList();
        }

        function toggleStudentPickerSelectAll() {
            const eligibleVisible = getVisiblePickerStudents().filter(s => s.eligible);
            const allSelected = eligibleVisible.length > 0 && eligibleVisible.every(s => studentPickerSelected.has(s.studentId));
            eligibleVisible.forEach(s => {
                if (allSelected) studentPickerSelected.delete(s.studentId);
                else studentPickerSelected.add(s.studentId);
            });
            renderStudentPickerList();
        }

        function updateStudentPickerConfirm(eligibleVisible) {
            const btn = document.getElementById('studentPickerConfirmBtn');
            const btnAll = document.getElementById('studentPickerSelectAllBtn');
            const visible = eligibleVisible || getVisiblePickerStudents().filter(s => s.eligible);
            const allSelected = visible.length > 0 && visible.every(s => studentPickerSelected.has(s.studentId));
            if (btnAll) {
                btnAll.textContent = allSelected ? '取消全选' : '全选可选';
                btnAll.disabled = visible.length === 0;
                btnAll.style.opacity = visible.length === 0 ? '0.45' : '';
                btnAll.style.cursor = visible.length === 0 ? 'not-allowed' : '';
            }
            if (!btn) return;
            const n = studentPickerSelected.size;
            btn.disabled = n === 0;
            btn.textContent = n ? `添加（${n}）` : '添加';
        }

        function submitStudentPicker() {
            if (!currentClass || studentPickerSelected.size === 0) return;
            const btn = document.getElementById('studentPickerConfirmBtn');
            if (btn.disabled && btn.textContent.includes('…')) return;
            btn.disabled = true;
            btn.textContent = '添加中…';

            fetch('/admin/classes/' + currentClass.id + '/members', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ studentIds: Array.from(studentPickerSelected) })
            })
                .then(r => r.json())
                .then(data => {
                    let msg = data.message || (data.success ? '添加成功' : '添加失败');
                    if (data.skipped && data.skipped.length) {
                        msg += '；未添加：' + data.skipped.map(x =>
                            `${x.name || x.studentId}（${x.reason}）`).join('、');
                    }
                    showProviderToast(msg, data.success ? 'success' : 'error');
                    if (data.success) {
                        closeStudentPicker();
                        loadClassDetail(currentClass.id);
                    } else {
                        btn.disabled = false;
                        btn.textContent = studentPickerSelected.size ? `添加（${studentPickerSelected.size}）` : '添加';
                    }
                })
                .catch(() => {
                    showProviderToast('添加学生失败：网络异常', 'error');
                    btn.disabled = false;
                    btn.textContent = `添加（${studentPickerSelected.size}）`;
                });
        }
