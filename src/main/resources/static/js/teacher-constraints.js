/********** teacher-constraints：排课要求提交、试解析与我的要求 **********/

        const CS_STATUS_LABELS = { PENDING: '待处理', ADOPTED: '已采纳', REJECTED: '已驳回', CANCELLED: '已撤销', EXPIRED: '已失效' };
        const CS_STATUS_CLASSES = { PENDING: 'cs-pending', ADOPTED: 'cs-adopted', REJECTED: 'cs-rejected', CANCELLED: 'cs-cancelled', EXPIRED: 'cs-expired' };
        let csSemesterName = '';
        let csClassesLoaded = false;
        let csMyCache = [];

        function initConstraintPanel() {
            const box = document.getElementById('csSubmitResult');
            if (box) { box.className = 'import-result'; box.innerHTML = ''; }
            const pv = document.getElementById('csPreviewBox');
            if (pv) { pv.style.display = 'none'; pv.innerHTML = ''; }
            loadCsSemester();
            loadCsClasses();
            csLoadMine();
        }

        function loadCsSemester() {
            fetch('/api/semester/current')
                .then(r => r.json())
                .then(data => {
                    if (data && data.success && data.configured) {
                        csSemesterName = data.semesterName || '';
                        const tag = document.getElementById('csSemesterTag');
                        if (tag) tag.textContent = csSemesterName || '当前学期';
                    }
                })
                .catch(() => {});
        }

        function loadCsClasses() {
            if (csClassesLoaded) return;
            fetch('/api/schedule-constraints/classes')
                .then(r => r.json())
                .then(data => {
                    if (!data || !data.success) return;
                    const sel = document.getElementById('csClassScope');
                    if (!sel) return;
                    const list = data.classes || [];
                    sel.innerHTML = '<option value="">全部班级（不限定）</option>' +
                        list.map(c => `<option value="${c.id}">${escapeHtml(c.className || '')}</option>`).join('');
                    csClassesLoaded = true;
                })
                .catch(() => {});
        }

        function csTextValue() {
            const el = document.getElementById('csText');
            return el ? el.value.trim() : '';
        }

        // 试解析：复用 AI 约束解析能力，展示解析出的条目 / 未识别原文 / 提示
        function csPreview() {
            const text = csTextValue();
            const box = document.getElementById('csPreviewBox');
            if (!text) {
                box.style.display = 'block';
                box.innerHTML = '<span class="cs-preview-bad">请先填写排课要求</span>';
                return;
            }
            const btn = document.getElementById('csPreviewBtn');
            btn.disabled = true;
            box.style.display = 'block';
            box.innerHTML = '<span class="cs-preview-title">正在解析...</span>';
            fetchAi('/api/schedule-constraints/preview', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ text: text })
            })
                .then(r => r.json())
                .then(data => renderCsPreview(data))
                .catch(error => { box.innerHTML = '<span class="cs-preview-bad">' + aiFetchErrorText(error, '解析失败：网络异常') + '</span>'; })
                .finally(() => { btn.disabled = false; });
        }

        function renderCsPreview(data) {
            const box = document.getElementById('csPreviewBox');
            box.style.display = 'block';
            const items = (data && data.items) || [];
            const unrecognized = (data && data.unrecognized) || [];
            const warnings = (data && data.warnings) || [];
            const removed = (data && data.removed) || [];
            const normalized = (data && data.normalizedText) || '';
            let html = '<div class="cs-preview-title">试解析结果</div>';
            if (items.length) {
                html += '<ul class="cs-preview-list">' +
                    items.map(it => `<li>${escapeHtml(it.description || '')}</li>`).join('') + '</ul>';
            } else if (!removed.length) {
                html += '<div class="cs-preview-bad">未解析出可用约束条目</div>';
            }
            if (removed.length) {
                html += '<div class="cs-preview-warn">已移除涉及其他教师的条目：'
                    + removed.map(t => '「' + escapeHtml(t) + '」').join('、') + '。排课要求只能填写本人约束。</div>';
            }
            if (removed.length && normalized) {
                html += '<div class="cs-preview-warn">仅本人约束部分：' + escapeHtml(normalized) + '</div>';
            }
            if (unrecognized.length) {
                html += '<div class="cs-preview-warn">未识别原文：' + unrecognized.map(t => '「' + escapeHtml(t) + '」').join('、') + '</div>';
            }
            if (warnings.length) {
                html += '<div class="cs-preview-warn">提示：' + warnings.map(t => escapeHtml(t)).join('；') + '</div>';
            }
            if (data && data.success) {
                html += '<div class="cs-preview-ok">解析成功，共 ' + items.length + ' 条约束，可确认后提交</div>';
            } else {
                html += '<div class="cs-preview-warn">' + escapeHtml((data && data.message) || '解析未完成')
                    + (removed.length ? '，请修改后重新提交' : '，仍可按原文提交') + '</div>';
            }
            box.innerHTML = html;
        }

        function csSubmit() {
            const text = csTextValue();
            const resultBox = document.getElementById('csSubmitResult');
            if (!text) {
                resultBox.className = 'import-result err show';
                resultBox.innerHTML = '请填写排课要求';
                return;
            }
            const sel = document.getElementById('csClassScope');
            const classId = sel && sel.value ? sel.value : null;
            const btn = document.getElementById('csSubmitBtn');
            btn.disabled = true;
            fetchAi('/api/schedule-constraints/submit', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ text: text, classId: classId, semester: csSemesterName })
            })
                .then(r => r.json())
                .then(data => {
                    const ok = data && data.success;
                    resultBox.className = 'import-result show ' + (ok ? 'ok' : 'err');
                    resultBox.innerHTML = escapeHtml((data && data.message) || (ok ? '提交成功' : '提交失败'));
                    if (ok) {
                        document.getElementById('csText').value = '';
                        document.getElementById('csPreviewBox').style.display = 'none';
                        csLoadMine();
                    }
                })
                .catch(error => {
                    resultBox.className = 'import-result show err';
                    resultBox.innerHTML = escapeHtml(aiFetchErrorText(error, '提交失败：网络异常'));
                })
                .finally(() => { btn.disabled = false; });
        }

        function csLoadMine() {
            const onlyEffective = document.getElementById('csOnlyEffective').checked;
            const listBox = document.getElementById('csMineList');
            listBox.innerHTML = '<div class="empty-hint">加载中...</div>';
            const url = onlyEffective
                ? '/api/schedule-constraints/effective'
                : '/api/schedule-constraints/my';
            fetch(url)
                .then(r => r.json())
                .then(data => {
                    if (!data || !data.success) {
                        listBox.innerHTML = `<div class="empty-hint">${escapeHtml((data && data.message) || '加载失败')}</div>`;
                        return;
                    }
                    csMyCache = data.requests || [];
                    renderCsMine(csMyCache, onlyEffective);
                })
                .catch(() => { listBox.innerHTML = '<div class="empty-hint">加载失败，请稍后重试</div>'; });
        }

        function csFormatTime(v) {
            if (!v) return '';
            return String(v).replace('T', ' ').substring(0, 16);
        }

        function renderCsMine(list, onlyEffective) {
            const listBox = document.getElementById('csMineList');
            if (!list.length) {
                listBox.innerHTML = `<div class="empty-hint">${onlyEffective ? '本学期暂无已采纳的排课约束' : '暂无排课要求记录'}</div>`;
                return;
            }
            listBox.innerHTML = list.map(req => {
                const status = req.status || 'PENDING';
                const statusLabel = CS_STATUS_LABELS[status] || status;
                const statusClass = CS_STATUS_CLASSES[status] || 'cs-cancelled';
                const meta = [];
                if (req.semester) meta.push('学期：' + escapeHtml(req.semester));
                if (req.className) meta.push('范围：' + escapeHtml(req.className));
                if (req.createTime) meta.push('提交：' + escapeHtml(csFormatTime(req.createTime)));
                if (req.applyResult) meta.push('结果：' + escapeHtml(req.applyResult));
                const note = req.adminNote && req.adminNote.trim()
                    ? `<div class="cs-item-note">管理员说明：${escapeHtml(req.adminNote)}</div>` : '';
                const selectable = status === 'PENDING';
                const checkbox = selectable
                    ? `<input type="checkbox" class="cs-pick" value="${req.id}">` : '';
                const cancelBtn = selectable
                    ? `<button class="cs-item-btn danger" onclick="csCancel(${req.id})">撤销</button>` : '';
                const resubmitBtn = (status === 'REJECTED' || status === 'CANCELLED' || status === 'EXPIRED')
                    ? `<button class="cs-item-btn" onclick="csPrefill(${req.id})">修改后重新提交</button>` : '';
                return `<div class="cs-item">
                    <div class="cs-item-line">
                        <div class="cs-item-text">${checkbox} ${escapeHtml(req.constraintText || '')}</div>
                        <span class="cs-status ${statusClass}">${statusLabel}</span>
                    </div>
                    ${meta.length ? `<div class="cs-item-meta">${meta.join(' · ')}</div>` : ''}
                    ${note}
                    <div class="cs-item-foot">${cancelBtn}${resubmitBtn}</div>
                </div>`;
            }).join('');
        }

        async function csCancel(id) {
            const ok = await showConfirm({
                title: '撤销排课要求',
                message: '确定撤销该排课要求？撤销后管理端将不再看到它。',
                confirmText: '撤销'
            });
            if (!ok) return;
            fetch('/api/schedule-constraints/' + id + '/cancel', { method: 'POST' })
                .then(r => r.json())
                .then(data => {
                    const box = document.getElementById('csSubmitResult');
                    box.className = 'import-result show ' + (data && data.success ? 'ok' : 'err');
                    box.innerHTML = escapeHtml((data && data.message) || '处理完成');
                    csLoadMine();
                })
                .catch(() => {});
        }

        async function csBatchCancel() {
            const ids = Array.from(document.querySelectorAll('#csMineList .cs-pick:checked')).map(el => el.value);
            if (!ids.length) {
                const box = document.getElementById('csSubmitResult');
                box.className = 'import-result show err';
                box.innerHTML = '请先勾选要撤销的待处理排课要求';
                return;
            }
            const ok = await showConfirm({
                title: '批量撤销排课要求',
                message: '确定撤销所选 ' + ids.length + ' 条排课要求？',
                confirmText: '撤销'
            });
            if (!ok) return;
            fetch('/api/schedule-constraints/batch-cancel', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ ids: ids })
            })
                .then(r => r.json())
                .then(data => {
                    const box = document.getElementById('csSubmitResult');
                    box.className = 'import-result show ' + (data && data.success ? 'ok' : 'err');
                    box.innerHTML = escapeHtml((data && data.message) || '处理完成');
                    csLoadMine();
                })
                .catch(() => {});
        }

        // 一键修改后重新提交：把原文与班级范围回填到提交表单（提交将生成新记录，保留历史）
        function csPrefill(id) {
            const req = (csMyCache || []).find(r => String(r.id) === String(id));
            if (!req) return;
            document.getElementById('csText').value = req.constraintText || '';
            const sel = document.getElementById('csClassScope');
            if (sel) sel.value = req.classId == null ? '' : String(req.classId);
            const box = document.getElementById('csSubmitResult');
            box.className = 'import-result show';
            box.innerHTML = '已回填原要求，修改后点击「提交排课要求」将生成新的待处理记录';
            const page = document.getElementById('constraintPage');
            if (page) page.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
