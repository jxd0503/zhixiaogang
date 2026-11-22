/********** 班级课表：课程表网格视图、课程卡片操作、课表新增 / 编辑、图片与文本导入预览 **********/
        function renderScheduleGrid() {
            const wrap = document.getElementById('scheduleGridWrap');
            if (!wrap) return;
            closeScheduleCardPopover();
            const list = getVisibleSchedule();

            const bucket = {};
            list.forEach(s => {
                const seg = segmentIndexOfPeriod(s.startPeriod);
                const key = seg + '-' + s.weekDay;
                if (!bucket[key]) bucket[key] = [];
                bucket[key].push(s);
            });

            const headCells = [1, 2, 3, 4, 5, 6, 7].map(d => `<th>${WEEKDAY_NAMES_CN[d]}</th>`).join('');
            const bodyRows = SCHEDULE_SEGMENTS.map((seg, idx) => {
                let cells = `<th class="sg-period">${seg.label}</th>`;
                for (let day = 1; day <= 7; day++) {
                    const courses = bucket[(idx + 1) + '-' + day] || [];
                    if (!courses.length) {
                        cells += '<td class="sg-cell sg-free"></td>';
                        continue;
                    }
                    const cards = courses.map(c => {
                        const metaParts = [];
                        if (c.teacherName) metaParts.push(escapeHtml(c.teacherName));
                        if (c.weekRange) metaParts.push(escapeHtml(c.weekRange));
                        return `
                        <div class="sched-card" data-sid="${c.id}" role="button" tabindex="0"
                             aria-label="${escapeHtml(c.courseName || '课程')} 操作">
                            <span class="sc-course">${escapeHtml(c.courseName || '未命名课程')}</span>
                            <span class="sc-sub">${escapeHtml(c.classroomNo || '无教室')}</span>
                            <span class="sc-week">${metaParts.join(' · ') || '&nbsp;'}</span>
                        </div>`;
                    }).join('');
                    cells += `<td class="sg-cell sg-busy">${cards}</td>`;
                }
                return `<tr>${cells}</tr>`;
            }).join('');

            const emptyHint = list.length ? '' : '<div class="sg-empty-hint">暂无课表条目，可手动添加或使用 AI 文字/识图导入。</div>';
            wrap.innerHTML = `<table class="sg-table">
                <thead><tr><th class="sg-corner">节次</th>${headCells}</tr></thead>
                <tbody>${bodyRows}</tbody>
            </table>${emptyHint}`;
            bindScheduleGridEvents();
        }

        // 只需绑定一次：网格容器元素在多次渲染间复用
        function bindScheduleGridEvents() {
            const wrap = document.getElementById('scheduleGridWrap');
            if (!wrap || wrap.dataset.bound === '1') return;
            wrap.dataset.bound = '1';
            wrap.addEventListener('pointerdown', onScheduleGridPointerDown);
            wrap.addEventListener('pointermove', onScheduleGridPointerMove);
            wrap.addEventListener('pointerup', onScheduleGridPointerUp);
            wrap.addEventListener('pointercancel', () => { scheduleGridPress = null; });
            // 点浮层外部 / Esc / 滚动时收起
            document.addEventListener('pointerdown', onDocumentPointerDownForPopover, true);
            document.addEventListener('keydown', e => {
                if (e.key === 'Escape') closeScheduleCardPopover();
            });
            window.addEventListener('scroll', closeScheduleCardPopover, true);
            window.addEventListener('resize', closeScheduleCardPopover);
        }

        function onScheduleGridPointerDown(e) {
            const card = e.target.closest('.sched-card');
            if (!card) return;
            if (e.pointerType === 'mouse' && e.button !== 0) return;
            scheduleGridPress = {
                card: card,
                pointerId: e.pointerId,
                x: e.clientX,
                y: e.clientY,
                moved: false,
                time: Date.now()
            };
        }

        function onScheduleGridPointerMove(e) {
            const press = scheduleGridPress;
            if (!press || press.pointerId !== e.pointerId) return;
            // 移动距离超过阈值（10px）判定为滑动页面，松开时不弹操作浮层
            if (Math.abs(e.clientX - press.x) > 10 || Math.abs(e.clientY - press.y) > 10) {
                press.moved = true;
            }
        }

        function onScheduleGridPointerUp(e) {
            const press = scheduleGridPress;
            scheduleGridPress = null;
            if (!press || press.pointerId !== e.pointerId) return;
            if (press.moved) return;
            if (Date.now() - press.time > 800) return; // 长按不视为轻点
            const card = press.card;
            if (!card || !card.isConnected) return;
            const sid = Number(card.dataset.sid);
            const item = detailSchedule.find(s => s.id === sid);
            if (!item) return;
            openScheduleCardPopover(card, item, e.clientX, e.clientY);
        }

        function onDocumentPointerDownForPopover(e) {
            if (!document.getElementById('scheduleCardPopover')) return;
            if (e.target.closest('.sched-pop') || e.target.closest('.sched-card')) return;
            closeScheduleCardPopover();
        }

        function openScheduleCardPopover(cardEl, item, clientX, clientY) {
            closeScheduleCardPopover();
            const pop = document.createElement('div');
            pop.className = 'sched-pop';
            pop.id = 'scheduleCardPopover';
            pop.innerHTML = `
                <div class="sched-pop-head">
                    <span class="sched-pop-title">${escapeHtml(item.courseName || '未命名课程')}</span>
                    <button type="button" class="sched-pop-close" aria-label="关闭" onclick="closeScheduleCardPopover()">×</button>
                </div>
                <div class="sched-pop-meta">
                    <div>${WEEKDAY_NAMES_CN[item.weekDay] || ''}　第${item.startPeriod}-${item.endPeriod}节</div>
                    <div>教室：${escapeHtml(item.classroomNo || '无教室')}</div>
                    <div>教师：${escapeHtml(item.teacherName || '-')}</div>
                    <div>周次：${escapeHtml(item.weekRange || '-')}${item.semester ? '　' + escapeHtml(item.semester) : ''}</div>
                </div>
                <div class="sched-pop-actions">
                    <button type="button" class="sched-pop-btn edit" onclick="scheduleCardEdit(${item.id})">编辑</button>
                    <button type="button" class="sched-pop-btn" onclick="openAdjustmentsForCourse(${item.id})">调课</button>
                    <button type="button" class="sched-pop-btn danger" onclick="scheduleCardDelete(${item.id})">删除</button>
                </div>`;
            document.body.appendChild(pop);

            const margin = 10;
            const rect = pop.getBoundingClientRect();
            let left = clientX + margin;
            let top = clientY + margin;
            if (left + rect.width > window.innerWidth - margin) {
                left = Math.max(margin, clientX - rect.width - margin);
            }
            if (top + rect.height > window.innerHeight - margin) {
                top = Math.max(margin, clientY - rect.height - margin);
            }
            pop.style.left = left + 'px';
            pop.style.top = top + 'px';
        }

        function closeScheduleCardPopover() {
            const pop = document.getElementById('scheduleCardPopover');
            if (pop) pop.remove();
        }

        function scheduleCardEdit(sid) {
            closeScheduleCardPopover();
            openScheduleModal(sid);
        }

        function scheduleCardDelete(sid) {
            closeScheduleCardPopover();
            deleteScheduleItem(sid);
        }

        function openAdjustmentsForCourse(sid) {
            const item = detailSchedule.find(s => s.id === sid);
            closeScheduleCardPopover();
            const btn = Array.from(document.querySelectorAll('.sidebar-item'))
                .find(b => (b.getAttribute('onclick') || '').includes("showPage('adjustments')"));
            if (btn) btn.click();
            if (item) showProviderToast(`已打开调课审批，可查看「${item.courseName || '该课程'}」相关申请`, 'success');
        }

        function fillPeriodSelects(startSel, endSel, startVal, endVal) {
            let html = '';
            for (let i = 1; i <= 10; i++) html += `<option value="${i}">第${i}节</option>`;
            startSel.innerHTML = html;
            endSel.innerHTML = html;
            if (startVal) startSel.value = startVal;
            if (endVal) endSel.value = endVal;
        }

        function openScheduleModal(sid) {
            fetchClassrooms().then(classrooms => {
                const classroomSelect = document.getElementById('scheduleFormClassroom');
                classroomSelect.innerHTML = '<option value="">无教室</option>' + classrooms.map(c =>
                    `<option value="${escapeHtml(c.classroomNo)}">${escapeHtml(c.classroomNo)}（${escapeHtml(c.building || '-')}）</option>`).join('');
                fillPeriodSelects(
                    document.getElementById('scheduleFormStart'),
                    document.getElementById('scheduleFormEnd'));
                document.getElementById('scheduleFormSid').value = sid || '';
                if (sid) {
                    const item = detailSchedule.find(s => s.id === sid);
                    if (!item) return;
                    document.getElementById('scheduleModalTitle').textContent = '编辑课表条目';
                    document.getElementById('scheduleFormWeekDay').value = item.weekDay;
                    fillPeriodSelects(
                        document.getElementById('scheduleFormStart'),
                        document.getElementById('scheduleFormEnd'),
                        item.startPeriod, item.endPeriod);
                    // 无教室条目（classroomNo 为空或为约定展示值）默认选中"无教室"
                    const rawNo = item.classroomNo;
                    classroomSelect.value = (!rawNo || rawNo === '无教室') ? '' : rawNo;
                    document.getElementById('scheduleFormCourse').value = item.courseName || '';
                    document.getElementById('scheduleFormTeacher').value = item.teacherName || '';
                    document.getElementById('scheduleFormWeekRange').value = item.weekRange || '';
                    document.getElementById('scheduleFormSemester').value = item.semester || '';
                } else {
                    document.getElementById('scheduleModalTitle').textContent = '添加课表条目';
                    document.getElementById('scheduleFormWeekDay').value = '1';
                    classroomSelect.value = '';
                    document.getElementById('scheduleFormCourse').value = '';
                    document.getElementById('scheduleFormTeacher').value = '';
                    document.getElementById('scheduleFormWeekRange').value = '';
                    document.getElementById('scheduleFormSemester').value = '2026-2027-1';
                }
                document.getElementById('scheduleEditModal').classList.add('show');
                // 周次输入框按统一解析规则实时提示并写回数字格式
                const weekInput = document.getElementById('scheduleFormWeekRange');
                WeekRange.attach(weekInput);
                const weekHint = weekInput.parentNode.querySelector('.week-hint');
                if (weekHint) { weekHint.textContent = ''; weekHint.className = 'week-hint'; weekHint.style.display = 'none'; }
                weekInput.classList.remove('week-range-invalid');
            });
        }

        function closeScheduleModal() {
            document.getElementById('scheduleEditModal').classList.remove('show');
        }

        function scheduleModalPayload() {
            return {
                weekDay: parseInt(document.getElementById('scheduleFormWeekDay').value, 10),
                startPeriod: parseInt(document.getElementById('scheduleFormStart').value, 10),
                endPeriod: parseInt(document.getElementById('scheduleFormEnd').value, 10),
                classroomNo: document.getElementById('scheduleFormClassroom').value,
                courseName: document.getElementById('scheduleFormCourse').value.trim(),
                teacherName: document.getElementById('scheduleFormTeacher').value.trim(),
                weekRange: document.getElementById('scheduleFormWeekRange').value.trim(),
                semester: document.getElementById('scheduleFormSemester').value.trim()
            };
        }

        function submitScheduleModal() {
            if (!currentClass) return;
            const weekInput = document.getElementById('scheduleFormWeekRange');
            const weekParsed = WeekRange.parse(weekInput.value);
            if (!weekParsed.valid) {
                showProviderToast('周次范围无法解析：' + (weekParsed.error || WeekRange.FORMAT_HINT), 'error');
                weekInput.focus();
                return;
            }
            weekInput.value = weekParsed.normalized;
            const sid = document.getElementById('scheduleFormSid').value;
            const payload = scheduleModalPayload();
            // 教室选填：不选表示体育课等无教室课程
            if (!payload.courseName) { showProviderToast('请填写课程名称', 'error'); return; }
            if (payload.startPeriod > payload.endPeriod) { showProviderToast('开始节次不能大于结束节次', 'error'); return; }
            const btn = document.getElementById('scheduleSubmitBtn');
            btn.disabled = true;
            const url = sid
                ? `/admin/classes/${currentClass.id}/schedule/${sid}`
                : `/admin/classes/${currentClass.id}/schedule`;
            fetch(url, {
                method: sid ? 'PUT' : 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            })
                .then(r => r.json())
                .then(data => {
                    btn.disabled = false;
                    if (!data.success && Array.isArray(data.conflicts) && data.conflicts.length) {
                        // 周次冲突明细较长，延长提示时长便于用户对照调整
                        showToast(data.message, 'error', 8000);
                        return;
                    }
                    showProviderToast(data.message || (data.success ? '保存成功' : '保存失败'), data.success ? 'success' : 'error');
                    if (data.success) {
                        closeScheduleModal();
                        loadClassDetail(currentClass.id);
                    }
                })
                .catch(() => { btn.disabled = false; showProviderToast('保存失败：网络异常', 'error'); });
        }

        async function deleteScheduleItem(sid) {
            if (!currentClass) return;
            const ok = await showConfirm({
                title: '删除课表条目',
                message: '确定删除该课表条目吗？',
                confirmText: '删除',
                danger: true
            });
            if (!ok) return;
            fetch(`/admin/classes/${currentClass.id}/schedule/${sid}`, { method: 'DELETE' })
                .then(r => r.json())
                .then(data => {
                    showProviderToast(data.message || (data.success ? '删除成功' : '删除失败'), data.success ? 'success' : 'error');
                    if (data.success) loadClassDetail(currentClass.id);
                })
                .catch(() => showProviderToast('删除失败：网络异常', 'error'));
        }

        // ---------- AI 导入（文字 / 识图）：解析预览 → 确认入库 ----------

        function openImportModal(mode) {
            if (!currentClass) return;
            document.getElementById('importMode').value = mode;
            document.getElementById('importModalTitle').textContent = mode === 'image' ? 'AI 识图导入课表' : 'AI 文字导入课表';
            document.getElementById('importTextGroup').style.display = mode === 'image' ? 'none' : '';
            document.getElementById('importImageGroup').style.display = mode === 'image' ? '' : 'none';
            document.getElementById('importText').value = '';
            document.getElementById('importImageFile').value = '';
            document.getElementById('importParseBtn').disabled = false;
            document.getElementById('importModal').classList.add('show');
        }

        function closeImportModal() {
            document.getElementById('importModal').classList.remove('show');
        }

        function parseImport() {
            if (!currentClass) return;
            const mode = document.getElementById('importMode').value;
            const semester = document.getElementById('importSemester').value.trim();
            const btn = document.getElementById('importParseBtn');

            if (mode === 'image') {
                const fileInput = document.getElementById('importImageFile');
                const file = fileInput.files && fileInput.files[0];
                if (!file) { showProviderToast('请选择课表图片', 'error'); return; }
                const okType = /\.(jpg|jpeg|png|webp)$/i.test(file.name);
                if (!okType) { showProviderToast('仅支持 jpg/png/webp 格式图片', 'error'); return; }
                if (file.size > 5 * 1024 * 1024) { showProviderToast('图片大小超过 5MB 限制，请压缩后重新上传', 'error'); return; }
                const fd = new FormData();
                fd.append('image', file);
                if (semester) fd.append('semester', semester);
                btn.disabled = true;
                fetchAi(`/admin/classes/${currentClass.id}/schedule/import-image`, { method: 'POST', body: fd }, AI_IMAGE_TIMEOUT_MS)
                    .then(r => r.json())
                    .then(data => { btn.disabled = false; handlePreviewResult(data); })
                    .catch(error => { btn.disabled = false; showProviderToast(aiFetchErrorText(error, '识图请求失败：网络异常'), 'error'); });
            } else {
                const text = document.getElementById('importText').value.trim();
                if (!text) { showProviderToast('请粘贴课表文本', 'error'); return; }
                btn.disabled = true;
                fetchAi(`/admin/classes/${currentClass.id}/schedule/import-text`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ text: text, semester: semester })
                })
                    .then(r => r.json())
                    .then(data => { btn.disabled = false; handlePreviewResult(data); })
                    .catch(error => { btn.disabled = false; showProviderToast(aiFetchErrorText(error, '解析请求失败：网络异常'), 'error'); });
            }
        }

        function handlePreviewResult(data) {
            if (!data.success) {
                showProviderToast(data.message || '解析失败', 'error');
                return;
            }
            previewItems = data.items || [];
            if (!previewItems.length) {
                showProviderToast('未解析出有效课表条目', 'error');
                return;
            }
            closeImportModal();
            renderPreviewTable();
            document.getElementById('importPreviewModal').classList.add('show');
        }

        function renderPreviewTable() {
            const validCount = previewItems.filter(i => i.valid).length;
            document.getElementById('importPreviewSummary').textContent =
                `共解析 ${previewItems.length} 条，其中 ${validCount} 条校验通过、${previewItems.length - validCount} 条校验失败（红色下划线标注原因）。可直接修改单条内容后确认导入，校验失败的条目不会入库。`;

            document.getElementById('importPreviewBody').innerHTML = previewItems.map((item, idx) => {
                const invalidClass = item.valid ? '' : ' preview-invalid';
                const dayOptions = [1, 2, 3, 4, 5, 6, 7].map(d =>
                    `<option value="${d}"${d === Number(item.weekDay) ? ' selected' : ''}>${WEEKDAY_NAMES_CN[d]}</option>`).join('');
                return `
                <tr>
                    <td>${idx + 1}</td>
                    <td><input class="preview-input${invalidClass}" data-pv="${idx}" data-key="className" value="${escapeHtml(item.className)}" placeholder="留空为当前班级"></td>
                    <td><input class="preview-input${invalidClass}" data-pv="${idx}" data-key="courseName" value="${escapeHtml(item.courseName)}"></td>
                    <td><input class="preview-input${invalidClass}" data-pv="${idx}" data-key="classroomNo" value="${escapeHtml(item.classroomNo)}"></td>
                    <td><input class="preview-input${invalidClass}" data-pv="${idx}" data-key="teacherName" value="${escapeHtml(item.teacherName)}"></td>
                    <td><select class="preview-input${invalidClass}" data-pv="${idx}" data-key="weekDay">${dayOptions}</select></td>
                    <td style="white-space:nowrap;">
                        <input class="preview-input${invalidClass}" data-pv="${idx}" data-key="startPeriod" type="number" min="1" max="10" style="width:56px;" value="${item.startPeriod == null ? '' : item.startPeriod}">
                        -
                        <input class="preview-input${invalidClass}" data-pv="${idx}" data-key="endPeriod" type="number" min="1" max="10" style="width:56px;" value="${item.endPeriod == null ? '' : item.endPeriod}">
                    </td>
                    <td><input class="preview-input${invalidClass}" data-week-range data-pv="${idx}" data-key="weekRange" value="${escapeHtml(item.weekRange)}" style="width:96px;" placeholder="如 1-16、单周"><div class="week-hint" style="display:none;"></div></td>
                    <td>${item.valid
                        ? '<span class="preview-ok">通过</span>'
                        : `<div class="preview-reason" style="max-width:160px;">${escapeHtml(item.reason)}</div>`}</td>
                </tr>`;
            }).join('');
            // 周次单元格可编辑，按统一解析规则实时提示并写回数字格式
            WeekRange.attachAll(document.getElementById('importPreviewBody'));
        }

        function closeImportPreviewModal() {
            document.getElementById('importPreviewModal').classList.remove('show');
        }

        function confirmImport() {
            if (!currentClass) return;
            const semester = document.getElementById('importSemester').value.trim();
            const inputs = document.querySelectorAll('#importPreviewBody [data-pv]');
            const items = previewItems.map(item => ({
                className: item.className || '',
                courseName: item.courseName || '',
                classroomNo: item.classroomNo || '',
                teacherName: item.teacherName || '',
                weekDay: item.weekDay,
                startPeriod: item.startPeriod,
                endPeriod: item.endPeriod,
                weekRange: item.weekRange || '',
                semester: item.semester || ''
            }));
            inputs.forEach(el => {
                const idx = parseInt(el.getAttribute('data-pv'), 10);
                const key = el.getAttribute('data-key');
                if (!items[idx]) return;
                if (key === 'weekDay') {
                    items[idx].weekDay = parseInt(el.value, 10) || items[idx].weekDay;
                } else if (key === 'startPeriod' || key === 'endPeriod') {
                    const n = parseInt(el.value, 10);
                    if (!isNaN(n)) items[idx][key] = n;
                } else {
                    items[idx][key] = el.value.trim();
                }
            });

            // 周次按统一规则归一化，无法解析的条目阻止导入
            for (let i = 0; i < items.length; i++) {
                const parsed = WeekRange.parse(items[i].weekRange || '');
                if (!parsed.valid) {
                    showProviderToast('第 ' + (i + 1) + ' 条周次无法解析：'
                        + (parsed.error || WeekRange.FORMAT_HINT), 'error');
                    return;
                }
                items[i].weekRange = parsed.normalized;
            }

            const btn = document.getElementById('importConfirmBtn');
            btn.disabled = true;
            fetch(`/admin/classes/${currentClass.id}/schedule/import-confirm`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ items: items, semester: semester })
            })
                .then(r => r.json())
                .then(data => {
                    btn.disabled = false;
                    if (!data.success) {
                        showProviderToast(data.message || '导入失败', 'error');
                        return;
                    }
                    let msg = data.message || '导入完成';
                    if (data.failures && data.failures.length) {
                        msg += '；失败项：' + data.failures.join('；');
                    }
                    showProviderToast(msg, data.failCount > 0 ? 'error' : 'success');
                    closeImportPreviewModal();
                    loadClassDetail(currentClass.id);
                })
                .catch(() => { btn.disabled = false; showProviderToast('导入失败：网络异常', 'error'); });
        }
