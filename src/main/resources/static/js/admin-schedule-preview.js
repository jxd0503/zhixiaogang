/********** AI 自动排课（二）：预览确认、列表 / 课表双视图、自然语言调整与入库 **********/
        function asGenerate() {
            asCollectCourseItems();
            if (!asCourseItems.length) { showProviderToast('课程清单为空，请先加入课程', 'error'); return; }
            const noTeacher = asCourseItems.find(c => !c.teacherName);
            if (noTeacher) { showProviderToast('课程「' + noTeacher.courseName + '」未分配教师，请先指定授课教师', 'error'); return; }
            const badWeekItem = asFindInvalidWeekItem();
            if (badWeekItem) {
                showProviderToast('课程「' + badWeekItem.item.courseName + '」周次无法解析：'
                    + (badWeekItem.error || WeekRange.FORMAT_HINT), 'error');
                asFocusWeekInput(badWeekItem.idx);
                return;
            }
            if (!asCheckFixedRooms()) return;
            let classIds = asGetSelectedClasses();
            const mode = asGetMode();
            if (mode === 'single') classIds = classIds.slice(0, 1);
            if (!classIds.length) { showProviderToast('请先选择班级', 'error'); return; }

            const payload = {
                mode: mode,
                classIds: classIds,
                semester: (document.getElementById('asSemester').value || '').trim(),
                minPeriod: parseInt(document.getElementById('asMinPeriod').value, 10) || 1,
                maxPeriod: parseInt(document.getElementById('asMaxPeriod').value, 10) || 8,
                allowWeekend: document.getElementById('asAllowWeekend').checked,
                constraints: (document.getElementById('asConstraints').value || '').trim(),
                courses: asCourseItems.map(c => ({
                    courseName: c.courseName, weekHours: c.weekHours,
                    teacherName: c.teacherName,
                    roomMode: c.roomMode || 'auto',
                    classroomType: c.classroomType || '',
                    classroomNo: c.classroomNo || '',
                    allowAutoChange: !!c.allowAutoChange,
                    weekRange: c.weekRange
                }))
            };

            const btn = document.getElementById('asGenerateBtn');
            const msg = document.getElementById('asGenMsg');
            asSubmittedConstraints = payload.constraints;
            btn.disabled = true;
            msg.className = 'as-msg';
            msg.textContent = '正在生成排课方案...';
            fetchAi('/admin/ai-schedule/generate', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            }, 240000)
                .then(r => r.json())
                .then(data => {
                    btn.disabled = false;
                    if (!data.success) {
                        msg.className = 'as-msg error';
                        // 前置阻断：目标班级已有课程表，弹窗告知并终止本次排课流程
                        if (data.blocked) {
                            msg.textContent = data.message || '该班级当前已存在课程表';
                            showAlert({
                                title: '无法执行排课',
                                message: data.message || '该班级当前已存在课程表，请先清理已有课程数据后再执行排课操作',
                                confirmText: '知道了'
                            });
                            return;
                        }
                        var failParts = [data.message || '生成失败'];
                        var fcList = (data && data.constraintSummary) || [];
                        var fcCount = (data && data.constraintCount) || fcList.length;
                        if (fcCount > 0 && fcList.length > 0) {
                            failParts.push('本次已识别约束 ' + fcCount + ' 条：' + fcList.join('；'));
                        }
                        var fcUnrec = (data && data.constraintUnrecognized) || [];
                        if (fcUnrec.length) {
                            failParts.push('未能识别的约束：' + fcUnrec.join('；'));
                        }
                        msg.textContent = failParts.join('\n');
                        // 排课失败：把失败信息 / 未识别约束带到预览区，便于一键通知相关教师
                        var fMsgs = [];
                        var fUnmatched = (data && data.constraintUnmatched) || [];
                        var fUnrec = (data && data.constraintUnrecognized) || [];
                        for (var fi = 0; fi < fUnmatched.length; fi++) { fMsgs.push(String(fUnmatched[fi])); }
                        for (var fj = 0; fj < fUnrec.length; fj++) { fMsgs.push('未能识别的约束：' + fUnrec[fj]); }
                        if (data && data.message) { fMsgs.push(String(data.message).replace(/\r?\n/g, '；')); }
                        if (fMsgs.length) {
                            asPreviewItems = [];
                            asPreviewMeta = null;
                            asRenderPreview({
                                totalCount: 0, conflictCount: 0, classCount: 0,
                                constraintCount: 0, constraintList: [],
                                constraintUnmatched: fMsgs, constraintUnrecognized: []
                            });
                            var sumEl = document.getElementById('asPreviewSummary');
                            if (sumEl) sumEl.textContent = '排课未成功：' + String(data.message || '生成失败').replace(/\r?\n/g, '；');
                            asShowPanel(4);
                            asSetStep(4);
                        }
                        return;
                    }
                    msg.textContent = '';
                    asLastGenPayload = payload;
                    asPreviewItems = (data.items || []).map(i => Object.assign({
                        className: '', courseName: '', classroomNo: '', classroomDisplay: '',
                        teacherName: '', weekDay: 1, startPeriod: 1, endPeriod: 2,
                        weekRange: '', semester: '', conflict: false, conflictReason: '', note: ''
                    }, i));
                    asRenderPreview(data);
                    if (data.failureCount > 0) {
                        showProviderToast(data.failureCount + ' 门课程未能排入，其余课程已正常排好', 'error');
                    }
                    asShowPanel(4);
                    asSetStep(4);
                })
                .catch(error => {
                    btn.disabled = false;
                    msg.className = 'as-msg error';
                    msg.textContent = aiFetchErrorText(error, '生成失败：网络异常');
                });
        }

        function asRenderPreview(data) {
            // 调整 / 删除约束后的响应只带部分元信息，浅合并保留仍有效的字段（如未排入课程原因）
            if (data && data.constraintCount !== undefined) asPreviewMeta = Object.assign({}, asPreviewMeta, data);
            const meta = asPreviewMeta || {};
            const total = (data && data.totalCount != null) ? data.totalCount : asPreviewItems.length;
            const conflict = (data && data.conflictCount) || 0;
            const classCount = (data && data.classCount) || 0;
            // 未能排入的课程（后端逐门课程排课，失败课程整体回滚并在此提示，其余课程正常保留）
            const failures = (data && data.failures !== undefined)
                ? (data.failures || [])
                : ((meta && meta.failures) || []);
            document.getElementById('asPreviewSummary').textContent = total === 0
                ? (failures.length
                    ? `没有可排入的课程，${failures.length} 门课程未能排入（详见下方原因）`
                    : '暂无排课结果')
                : `共 ${total} 条课表，涉及 ${classCount} 个班级`
                    + (failures.length ? `，另有 ${failures.length} 门课程未能排入（详见下方原因）` : '')
                    + (conflict > 0
                        ? `，已自动剔除 ${conflict} 条与现有课表冲突的课次（详见下方原因）`
                        : '，无冲突');
            const fbox = document.getElementById('asFailureBox');
            const flist = document.getElementById('asFailureList');
            if (failures.length) {
                document.getElementById('asFailureTitle').textContent = total > 0
                    ? `以下 ${failures.length} 门课程未能排入，其余课程已正常排出`
                    : `以下 ${failures.length} 门课程均未能排入`;
                flist.innerHTML = failures.map(function (m) {
                    return '<li>' + escapeHtml(String(m)).replace(/\r?\n/g, '<br>') + '</li>';
                }).join('');
                fbox.style.display = 'block';
            } else {
                flist.innerHTML = '';
                fbox.style.display = 'none';
            }
            var cs = document.getElementById('asPreviewConstraintSummary');
            var cCount = (meta && meta.constraintCount) || 0;
            var cItems = (meta && meta.constraintList) || [];
            var cUnmatched = (data && data.constraintUnmatched) || (meta && meta.constraintUnmatched) || [];
            var cUnrecognized = (data && data.constraintUnrecognized) || (meta && meta.constraintUnrecognized) || [];
            var extras = [];
            if (cUnmatched.length) {
                extras.push(cUnmatched.join('；'));
            }
            if (cUnrecognized.length) {
                for (var u = 0; u < cUnrecognized.length; u++) {
                    extras.push('未能识别的约束：' + cUnrecognized[u]);
                }
            }
            // 第三步填写的自然语言约束原文，按换行 / 分号拆成多条原样展示
            var rawLines = (asSubmittedConstraints || '').split(/\r?\n|；|;/)
                .map(function (s) { return s.trim(); })
                .filter(function (s) { return s.length > 0; });
            var rawHtml = '';
            if (rawLines.length) {
                rawHtml = '<div class="as-clist-raw"><div class="as-clist-title">自然语言约束（原文）</div><div class="as-clist">'
                    + rawLines.map(function (d) {
                        return '<div class="as-clist-row"><span class="as-clist-desc">' + escapeHtml(d) + '</span></div>';
                    }).join('')
                    + '</div></div>';
            }
            var recognizedHtml = '';
            if ((meta && meta.constraintCount !== undefined) || cItems.length) {
                // 每条约束（含自然语言调整合并后的）均单行展示，且都可单独删除
                var rows = cItems.map(function (c) {
                    var del = '<button type="button" class="as-cdelete" onclick="asDeleteConstraint(' + c.id + ', this)">删除</button>';
                    return '<div class="as-clist-row"><span class="as-clist-desc">' + escapeHtml(c.description || '') + '</span>' + del + '</div>';
                }).join('');
                recognizedHtml = '<div class="as-clist-title">已识别约束（' + cCount + ' 条）</div>'
                    + (cCount > 0 ? '<div class="as-clist">' + rows + '</div>' : '');
            }
            if (recognizedHtml || rawHtml || extras.length) {
                cs.innerHTML = rawHtml + recognizedHtml
                    + (extras.length ? '<div class="as-clist-extras">' + escapeHtml(extras.join('；')) + '</div>' : '');
                cs.style.display = 'block';
            } else {
                cs.style.display = 'none';
            }
            // 预览/失败提示中的未生效约束 → 一键通知教师
            var notifyMsgs = cUnmatched.slice();
            for (var nu = 0; nu < cUnrecognized.length; nu++) {
                notifyMsgs.push('未能识别的约束：' + cUnrecognized[nu]);
            }
            handleAsUnmatched(notifyMsgs);
            document.getElementById('asPreviewBody').innerHTML = asPreviewItems.map((it, idx) => {
                let status;
                if (it.conflict) {
                    status = `<span class="as-conflict-tag">冲突：${escapeHtml(it.conflictReason || '')}</span>`;
                } else if (it.note) {
                    status = `<span class="as-note-tag">${escapeHtml(it.note)}</span>`;
                } else {
                    status = '<span class="preview-ok">正常</span>';
                }
                return `
                <tr${it.conflict ? ' class="as-row-conflict"' : ''}>
                    <td>${idx + 1}</td>
                    <td>${escapeHtml(it.className || '')}</td>
                    <td>${escapeHtml(it.courseName || '')}</td>
                    <td>${escapeHtml(it.classroomDisplay || it.classroomNo || '')}</td>
                    <td>${escapeHtml(it.teacherName || '')}</td>
                    <td>${WEEKDAY_NAMES_CN[it.weekDay] || it.weekDay}</td>
                    <td>${it.startPeriod}-${it.endPeriod}</td>
                    <td><input class="preview-input as-preview-week" data-week-range data-pw="${idx}" value="${escapeHtml(it.weekRange || '')}" placeholder="如 1-18、单周" style="width:110px;"><div class="week-hint" style="display:none;"></div></td>
                    <td>${status}</td>
                </tr>`;
            }).join('');
            // 周次单元格可直接编辑，按统一解析规则实时提示并写回数字格式
            WeekRange.attachAll(document.getElementById('asPreviewBody'));
            asRenderPreviewView();
        }

        /** 同步预览表中手工修改的周次，返回首个无法解析的条目 */
        function asSyncPreviewWeeks() {
            document.querySelectorAll('#asPreviewBody [data-pw]').forEach(el => {
                const idx = parseInt(el.getAttribute('data-pw'), 10);
                const it = asPreviewItems[idx];
                if (!it) return;
                const parsed = WeekRange.parse(el.value || '');
                if (parsed.valid) {
                    it.weekRange = parsed.normalized;
                    el.value = parsed.normalized;
                } else {
                    it.weekRange = (el.value || '').trim();
                }
            });
            for (let i = 0; i < asPreviewItems.length; i++) {
                const parsed = WeekRange.parse(asPreviewItems[i].weekRange || '');
                if (!parsed.valid) return { idx: i, error: parsed.error };
            }
            return null;
        }

        // ---------- 预览课表：列表 / 课表双视图（同一份 asPreviewItems，切换不改变数据） ----------

        function asSwitchPreviewView(mode) {
            if (mode !== 'list' && mode !== 'grid') return;
            asPreviewViewMode = mode;
            asRenderPreviewView();
        }

        function asRenderPreviewView() {
            const listView = document.getElementById('asPreviewListView');
            const gridView = document.getElementById('asPreviewGridView');
            const listBtn = document.getElementById('asPreviewListBtn');
            const gridBtn = document.getElementById('asPreviewGridBtn');
            if (!listView || !gridView) return;
            const isList = asPreviewViewMode !== 'grid';
            listView.style.display = isList ? '' : 'none';
            gridView.style.display = isList ? 'none' : '';
            if (listBtn) {
                listBtn.classList.toggle('active', isList);
                listBtn.setAttribute('aria-selected', isList ? 'true' : 'false');
            }
            if (gridBtn) {
                gridBtn.classList.toggle('active', !isList);
                gridBtn.setAttribute('aria-selected', isList ? 'false' : 'true');
            }
            if (!isList) asRenderPreviewGrid();
        }

        function asRenderPreviewGrid() {
            const wrap = document.getElementById('asPreviewGridWrap');
            if (!wrap) return;
            const list = asPreviewItems || [];
            const bucket = {};
            list.forEach(it => {
                const seg = segmentIndexOfPeriod(it.startPeriod);
                const key = seg + '-' + it.weekDay;
                if (!bucket[key]) bucket[key] = [];
                bucket[key].push(it);
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
                        if (c.classroomDisplay || c.classroomNo) metaParts.push(escapeHtml(c.classroomDisplay || c.classroomNo));
                        const cls = 'sched-card as-sched-card' + (c.conflict ? ' as-sched-conflict' : '');
                        return `
                        <div class="${cls}" title="${escapeHtml((c.className || '') + ' ' + (c.courseName || ''))}">
                            <span class="sc-course">${escapeHtml(c.courseName || '未命名课程')}</span>
                            <span class="sc-sub">${metaParts.join(' · ') || '&nbsp;'}</span>
                            <span class="sc-week">${escapeHtml(c.weekRange || '') || '&nbsp;'}</span>
                        </div>`;
                    }).join('');
                    cells += `<td class="sg-cell sg-busy">${cards}</td>`;
                }
                return `<tr>${cells}</tr>`;
            }).join('');
            const emptyHint = list.length ? '' : '<div class="sg-empty-hint">暂无排课结果，请先生成排课预览。</div>';
            wrap.innerHTML = `<table class="sg-table as-preview-sg-table">
                <thead><tr><th class="sg-corner">节次</th>${headCells}</tr></thead>
                <tbody>${bodyRows}</tbody>
            </table>${emptyHint}`;
        }

        function asRegenerate() {
            asShowPanel(3);
            asSetStep(3);
            showProviderToast('可调整参数后重新点击「生成排课预览」', 'success');
        }

        // 逐条对比调整前后的课表，统计星期 / 节次发生变化的条目数（接口未返回重排条数时的兜底）
        function asCountMoved(beforeItems, afterItems) {
            if (!Array.isArray(beforeItems) || !Array.isArray(afterItems)) return 0;
            const keyOf = function (it) {
                return [it.className || '', it.courseName || '', it.teacherName || '', it.weekRange || ''].join('|');
            };
            const pool = {};
            beforeItems.forEach(function (it) {
                const k = keyOf(it);
                (pool[k] = pool[k] || []).push(it);
            });
            let moved = 0;
            afterItems.forEach(function (it) {
                const list = pool[keyOf(it)];
                if (!list || !list.length) return;
                const old = list.shift();
                if (Number(old.weekDay) !== Number(it.weekDay)
                    || Number(old.startPeriod) !== Number(it.startPeriod)
                    || Number(old.endPeriod) !== Number(it.endPeriod)) {
                    moved++;
                }
            });
            return moved;
        }

        function asDeleteConstraint(id, btn) {
            if (!asLastGenPayload) { showProviderToast('没有可用的生成参数，请回第 3 步重新生成', 'error'); return; }
            const current = (asPreviewMeta && asPreviewMeta.constraintList) || [];
            if (!current.length) { showProviderToast('当前约束列表无法定位，请回第 3 步重新生成后再删除', 'error'); return; }
            // 删除单条约束：仅回传剩余的结构化条目，其余条目原样保留
            const remaining = current.filter(function (c) { return c.id !== id; });
            const area = document.getElementById('asPreviewConstraintSummary');
            const allBtns = area.querySelectorAll('.as-cdelete');
            allBtns.forEach(b => { b.disabled = true; });
            if (btn) btn.textContent = '正在重新生成...';
            // 重新生成前的课表快照：用于统计本次删除约束导致的课表变动条数
            const beforeItems = asPreviewItems.slice();
            const payload = Object.assign({}, asLastGenPayload, {
                constraints: '',
                structuredConstraints: remaining
            });
            fetchAi('/admin/ai-schedule/generate', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            }, 240000)
                .then(r => r.json())
                .then(data => {
                    if (!data.success) {
                        showProviderToast(data.message || '删除约束后重新生成失败', 'error');
                        const m = document.getElementById('asModifyMsg');
                        if (m) { m.className = 'as-msg error'; m.textContent = data.message || '删除约束后重新生成失败'; }
                        return;
                    }
                    asPreviewItems = (data.items || []).map(i => Object.assign({
                        classId: null, className: '', courseName: '', classroomNo: '', classroomDisplay: '',
                        teacherName: '', weekDay: 1, startPeriod: 1, endPeriod: 2,
                        weekRange: '', semester: '', conflict: false, conflictReason: '', note: ''
                    }, i));
                    const m2 = document.getElementById('asModifyMsg');
                    if (m2) {
                        m2.className = 'as-msg';
                        const moved = (data.movedCount != null) ? data.movedCount : asCountMoved(beforeItems, data.items);
                        m2.textContent = '已删除该约束：课表 ' + (data.totalCount || 0) + ' 条、约束 ' + (data.constraintCount || 0) + ' 条'
                            + '，已重排 ' + moved + ' 条课表'
                            + ((data.failureCount || 0) > 0 ? '，' + data.failureCount + ' 门课程未能排入（详见下方原因）' : '')
                            + ((data.conflictCount || 0) > 0 ? '，已自动剔除 ' + data.conflictCount + ' 条与现有课表冲突的课次' : (data.totalCount > 0 ? '，无冲突' : ''));
                    }
                    asRenderPreview(data);
                })
                .catch(error => {
                    showProviderToast(aiFetchErrorText(error, '删除约束后重新生成失败：网络异常'), 'error');
                })
                .finally(() => {
                    allBtns.forEach(b => { b.disabled = false; b.textContent = '删除'; });
                });
        }

        function asApplyModify() {
            if (!asPreviewItems.length) { showProviderToast('没有可调整的课表预览', 'error'); return; }
            const badWeek = asSyncPreviewWeeks();
            if (badWeek) {
                showProviderToast('第 ' + (badWeek.idx + 1) + ' 条周次无法解析：'
                    + (badWeek.error || WeekRange.FORMAT_HINT), 'error');
                return;
            }
            const input = document.getElementById('asModifyInput');
            const instruction = (input.value || '').trim();
            if (!instruction) { showProviderToast('请输入调整指令', 'error'); return; }
            const btn = document.getElementById('asModifyBtn');
            const msg = document.getElementById('asModifyMsg');
            msg.className = 'as-msg';
            msg.textContent = '正在应用调整...';
            btn.disabled = true;
            btn.textContent = '正在应用调整...';
            // 调整前的课表快照：接口未返回重排条数时用于本地逐条对比
            const beforeItems = asPreviewItems.slice();
            const payload = {
                items: asPreviewItems.map(it => ({
                    classId: it.classId,
                    className: it.className || '',
                    courseName: it.courseName || '',
                    classroomNo: it.classroomNo || '',
                    classroomDisplay: it.classroomDisplay || '',
                    teacherName: it.teacherName || '',
                    weekDay: it.weekDay,
                    startPeriod: it.startPeriod,
                    endPeriod: it.endPeriod,
                    weekRange: it.weekRange || '',
                    semester: it.semester || '',
                    note: it.note || ''
                })),
                instruction: instruction,
                constraints: '',
                // 当前已识别约束（结构化条目）作为合并基线：新识别约束与同主体时间片段合并，避免重复或临时条目
                structuredConstraints: ((asPreviewMeta && asPreviewMeta.constraintList) || []),
                semester: (document.getElementById('asSemester').value || '').trim(),
                minPeriod: parseInt(document.getElementById('asMinPeriod').value, 10) || 1,
                maxPeriod: parseInt(document.getElementById('asMaxPeriod').value, 10) || 8,
                // 与生成时保持一致的可排日期范围，重排候选项不得越界
                allowWeekend: (asLastGenPayload && asLastGenPayload.allowWeekend != null)
                    ? asLastGenPayload.allowWeekend
                    : !!(document.getElementById('asAllowWeekend') && document.getElementById('asAllowWeekend').checked)
            };
            fetchAi('/admin/ai-schedule/modify', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            })
                .then(r => r.json())
                .then(data => {
                    btn.disabled = false;
                    btn.textContent = '应用调整';
                    if (!data.success) {
                        msg.className = 'as-msg error';
                        msg.textContent = data.message || '调整失败';
                        return;
                    }
                    msg.className = 'as-msg';
                    // 优先完整展示接口 message（含「已按新约束重排 N 条课表」）；缺失时按前后课表对比兜底
                    const moved = (data.movedCount != null) ? data.movedCount : asCountMoved(beforeItems, data.items);
                    msg.textContent = data.message
                        || ('调整完成：课表 ' + (data.totalCount || 0) + ' 条、约束 ' + (data.constraintCount || 0) + ' 条'
                            + '，已重排 ' + moved + ' 条课表'
                            + ((data.failureCount || 0) > 0 ? '，' + data.failureCount + ' 门课程未能排入（详见下方原因）' : '')
                            + ((data.conflictCount || 0) > 0 ? '，已自动剔除 ' + data.conflictCount + ' 条与现有课表冲突的课次' : (data.totalCount > 0 ? '，无冲突' : '')));
                    input.value = instruction;   // 保留最近一次指令，便于继续叠加调整
                    asPreviewItems = (data.items || []).map(i => Object.assign({
                        classId: null, className: '', courseName: '', classroomNo: '', classroomDisplay: '',
                        teacherName: '', weekDay: 1, startPeriod: 1, endPeriod: 2,
                        weekRange: '', semester: '', conflict: false, conflictReason: '', note: ''
                    }, i));
                    asRenderPreview(data);
                })
                .catch(error => {
                    btn.disabled = false;
                    btn.textContent = '应用调整';
                    msg.className = 'as-msg error';
                    msg.textContent = aiFetchErrorText(error, '调整失败：网络异常');
                });
        }

        async function asCancelPreview() {
            const ok = await showConfirm({
                title: '取消排课预览',
                message: '取消将丢弃本次生成的排课预览（尚未写入课表），确定取消吗？',
                confirmText: '确定取消'
            });
            if (!ok) return;
            asPreviewItems = [];
            asPreviewMeta = null;
            asLastGenPayload = null;
            asSubmittedConstraints = '';
            document.getElementById('asModifyInput').value = '';
            document.getElementById('asModifyMsg').textContent = '';
            asRenderPreview({ totalCount: 0, conflictCount: 0, classCount: 0 });
            asShowPanel(1);
            asSetStep(1);
        }

        function asConfirmInsert() {
            if (!asPreviewItems.length) { showProviderToast('没有可入库的课表', 'error'); return; }
            const badWeek = asSyncPreviewWeeks();
            if (badWeek) {
                showProviderToast('第 ' + (badWeek.idx + 1) + ' 条周次无法解析：'
                    + (badWeek.error || WeekRange.FORMAT_HINT), 'error');
                return;
            }
            const conflictCount = asPreviewItems.filter(it => it.conflict).length;
            if (conflictCount > 0) {
                showProviderToast('存在 ' + conflictCount + ' 条冲突课表，请先自然语言调整或重新生成后再确认入库', 'error');
                return;
            }
            const items = asPreviewItems.map(it => ({
                className: it.className || '',
                courseName: it.courseName || '',
                classroomNo: it.classroomNo || '',
                teacherName: it.teacherName || '',
                weekDay: it.weekDay,
                startPeriod: it.startPeriod,
                endPeriod: it.endPeriod,
                weekRange: it.weekRange || '',
                semester: it.semester || ''
            }));
            const semester = (document.getElementById('asSemester').value || '').trim();
            const btn = document.getElementById('asConfirmBtn');
            btn.disabled = true;
            fetch('/admin/ai-schedule/confirm', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ items: items, semester: semester })
            })
                .then(r => r.json())
                .then(data => {
                    btn.disabled = false;
                    if (!data.success) { showProviderToast(data.message || '入库失败', 'error'); return; }
                    let m = data.message || '入库完成';
                    if (data.failures && data.failures.length) m += '；失败项：' + data.failures.join('；');
                    showProviderToast(m, data.failCount > 0 ? 'error' : 'success');
                    asPreviewItems = [];
                    asPreviewMeta = null;
                    asLastGenPayload = null;
                    asSubmittedConstraints = '';
                    asRenderPreview({ totalCount: 0, conflictCount: 0, classCount: 0 });
                    asShowPanel(1);
                    asSetStep(1);
                })
                .catch(() => { btn.disabled = false; showProviderToast('入库失败：网络异常', 'error'); });
        }
