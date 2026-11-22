/********** AI 自动排课（一）：选班、课程清单、时间约束与生成请求 **********/
        // ===================== AI 自动排课 =====================

        let asInitialized = false;
        let asClassList = [];
        let asTeacherList = [];
        let asClassroomList = [];           // {id, classroomNo, building, capacity, roomType, description, status}
        let asLibraryCourses = [];
        let asCourseItems = [];    // {courseName, weekHours, teacherName, classroomNo, weekRange}
        let asPreviewItems = [];
        let asPreviewMeta = null;   // 上次生成的约束摘要等元信息（自然语言调整后仍保留展示）
        let asLastGenPayload = null;    // 最近一次成功生成时的完整参数（约束删除后需携带原参数重新生成）
        let asSubmittedConstraints = '';    // 第三步填写的自然语言约束原文（预览页原样展示）
        let asPreviewViewMode = 'list';     // 预览课表视图：list（表格）/ grid（周课表矩阵）

        function initAiSchedule() {
            if (!asInitialized) {
                asInitialized = true;
                asSetStep(1);
                asOnModeChange();
                asCheckMultimodal();
            }
            asLoadClasses();
            asLoadTeachers();
            asLoadClassrooms();
            asLoadConstraintRequestCount();
        }

        function asSetStep(n) {
            document.querySelectorAll('#asSteps .as-step').forEach(li => {
                const s = parseInt(li.getAttribute('data-step'), 10);
                li.classList.toggle('active', s === n);
                li.classList.toggle('done', s < n);
            });
        }

        function asShowPanel(n) {
            for (let i = 1; i <= 4; i++) {
                const p = document.getElementById('asPanel' + i);
                if (p) p.style.display = (i === n) ? 'block' : 'none';
            }
            // 进入第 3 步时刷新已审核通过约束，保证采纳结果立即可选
            if (n === 3 && typeof asLoadAdoptedConstraints === 'function') asLoadAdoptedConstraints();
        }

        function asGetMode() {
            const el = document.querySelector('input[name="asMode"]:checked');
            return el ? el.value : 'single';
        }

        function asOnModeChange() {
            const mode = asGetMode();
            const hint = document.getElementById('asClassHint');
            if (hint) hint.textContent = mode === 'single'
                ? '（单个班级模式仅取勾选的一个班级）'
                : '（整学期模式将对所有勾选班级统一排课）';
            if (mode === 'single') {
                const cbs = Array.from(document.querySelectorAll('.as-class-cb:checked'));
                cbs.forEach((cb, i) => { if (i > 0) cb.checked = false; });
            }
        }

        function asOnClassChange(el) {
            if (asGetMode() === 'single' && el.checked) {
                document.querySelectorAll('.as-class-cb').forEach(cb => { if (cb !== el) cb.checked = false; });
            }
        }

        function asGetSelectedClasses() {
            return Array.from(document.querySelectorAll('.as-class-cb:checked'))
                .map(cb => parseInt(cb.value, 10)).filter(n => !isNaN(n));
        }

        function asLoadClasses() {
            const box = document.getElementById('asClassList');
            if (!box) return;
            fetch('/admin/classes?page=1&size=100')
                .then(r => r.json())
                .then(data => {
                    if (!data.success) {
                        box.innerHTML = `<p class="empty-hint">${escapeHtml(data.message || '加载失败')}</p>`;
                        return;
                    }
                    asClassList = data.classes || [];
                    if (!asClassList.length) {
                        box.innerHTML = '<p class="empty-hint">暂无班级，请先在「班级管理」中创建班级</p>';
                        return;
                    }
                    box.innerHTML = asClassList.map(c => `
                        <label class="as-check">
                            <input type="checkbox" class="as-class-cb" value="${c.id}" onchange="asOnClassChange(this)">
                            <span class="as-check-main">${escapeHtml(c.className)}</span>
                            <span class="as-check-sub">${escapeHtml(c.department || '-')} · ${escapeHtml(c.grade || '-')} · ${c.studentCount || 0} 人 · 课表 ${c.scheduleCount || 0} 条</span>
                        </label>`).join('');
                })
                .catch(() => { box.innerHTML = '<p class="empty-hint">加载失败：网络异常</p>'; });
        }

        function asLoadTeachers() {
            fetch('/admin/users?role=teacher&page=1&size=100')
                .then(r => r.json())
                .then(data => {
                    if (!data.success) return;
                    asTeacherList = (data.users || []).filter(u => u.role === 'teacher');
                })
                .catch(() => {});
        }

        function asLoadClassrooms() {
            fetch('/admin/classrooms')
                .then(r => r.json())
                .then(data => {
                    if (!data.success) return;
                    asClassroomList = (data.classrooms || []).filter(r => !r.status || r.status === 'available');
                })
                .catch(() => {});
        }

        function asCheckMultimodal() {
            fetch('/admin/ai/multimodal-status')
                .then(r => r.json())
                .then(data => {
                    const supported = !!(data && data.supported);
                    const tab = document.getElementById('asTabImage');
                    const btn = document.getElementById('asImageParseBtn');
                    const hint = document.getElementById('asImageHint');
                    if (!supported) {
                        if (tab) { tab.disabled = true; tab.classList.add('as-tab-disabled'); }
                        if (btn) btn.disabled = true;
                        if (hint) hint.textContent = '当前生效模型（' + ((data && data.model) || '未知')
                            + '）不支持识图，请在「模型提供商」中切换到多模态模型后使用。';
                    } else {
                        if (tab) { tab.disabled = false; tab.classList.remove('as-tab-disabled'); }
                        if (btn) btn.disabled = false;
                        if (hint) hint.textContent = '支持 jpg/png/webp，大小不超过 5MB。';
                    }
                })
                .catch(() => {});
        }

        function asGoStep(n) {
            if (n >= 2) {
                const ids = asGetSelectedClasses();
                if (!ids.length) { showProviderToast('请至少选择一个班级', 'error'); return; }
                if (asGetMode() === 'single' && ids.length > 1) { showProviderToast('单个班级模式只能选择一个班级', 'error'); return; }
            }
            if (n >= 3) {
                asCollectCourseItems();
                if (!asCourseItems.length) { showProviderToast('课程清单为空，请先加入课程', 'error'); return; }
                const noTeacher = asCourseItems.find(c => !c.teacherName);
                if (noTeacher) { showProviderToast('课程「' + noTeacher.courseName + '」未分配教师，请先指定授课教师', 'error'); return; }
                const noWeek = asCourseItems.find(c => !(c.weekRange || '').trim());
                if (noWeek) {
                    showProviderToast('课程「' + noWeek.courseName + '」未填写周次，请填写后继续（如 1-18）', 'error');
                    asFocusWeekInput(asCourseItems.indexOf(noWeek));
                    return;
                }
                const badWeek = asFindInvalidWeekItem();
                if (badWeek) {
                    showProviderToast('课程「' + badWeek.item.courseName + '」周次无法解析：'
                        + (badWeek.error || WeekRange.FORMAT_HINT), 'error');
                    asFocusWeekInput(badWeek.idx);
                    return;
                }
                if (!asCheckFixedRooms()) return;
            }
            asShowPanel(n);
            asSetStep(n);
            if (n === 2 && !asLibraryCourses.length) asLoadCourseLibrary();
        }

        function asSwitchSource(source) {
            document.querySelectorAll('.as-tab').forEach(t => t.classList.toggle('active', t.getAttribute('data-source') === source));
            ['library', 'text', 'excel', 'image'].forEach(s => {
                const el = document.getElementById('asSource' + s.charAt(0).toUpperCase() + s.slice(1));
                if (el) el.style.display = (s === source) ? 'block' : 'none';
            });
            if (source === 'library' && !asLibraryCourses.length) asLoadCourseLibrary();
        }

        function asLoadCourseLibrary() {
            const box = document.getElementById('asCourseLibrary');
            if (!box) return;
            box.innerHTML = '<p class="empty-hint">加载中...</p>';
            fetch('/admin/ai-schedule/courses')
                .then(r => r.json())
                .then(data => {
                    if (!data.success) {
                        box.innerHTML = `<p class="empty-hint">${escapeHtml(data.message || '加载失败')}</p>`;
                        return;
                    }
                    asLibraryCourses = data.courses || [];
                    if (!asLibraryCourses.length) {
                        box.innerHTML = '<p class="empty-hint">课程库为空，可先用文字 / Excel / 图片导入课程</p>';
                        return;
                    }
                    box.innerHTML = asLibraryCourses.map((c, i) => `
                        <label class="as-check">
                            <input type="checkbox" class="as-lib-cb" value="${i}" onchange="asFilterLibrary()">
                            <span class="as-check-main">${escapeHtml(c.courseName)}</span>
                            <span class="as-check-sub">周学时 ${c.weekHours || 2} · ${escapeHtml(c.teacherName || '未指定教师')} · ${escapeHtml(c.classroomNo || '不限教室')}</span>
                            <button type="button" class="action-btn delete as-lib-del" data-id="${c.courseId}" data-name="${escapeHtml(c.courseName)}" title="从课程库删除">删除</button>
                        </label>`).join('')
                        + '<p class="empty-hint as-lib-empty" id="asLibraryEmpty" style="display:none;">没有匹配的课程</p>';
                    box.querySelectorAll('.as-lib-del').forEach(btn => {
                        btn.addEventListener('click', function (e) {
                            e.preventDefault();
                            e.stopPropagation();
                            deleteLibraryCourse(btn.getAttribute('data-id'), btn.getAttribute('data-name'));
                        });
                    });
                    asFilterLibrary();
                })
                .catch(() => { box.innerHTML = '<p class="empty-hint">加载失败：网络异常</p>'; });
        }

        /** 课程库检索：按课程名称 / 教师 / 教室过滤，勾选状态保持不变 */
        function asFilterLibrary() {
            const box = document.getElementById('asCourseLibrary');
            if (!box) return;
            const input = document.getElementById('asLibrarySearch');
            const countEl = document.getElementById('asLibraryCount');
            const emptyEl = document.getElementById('asLibraryEmpty');
            const kw = ((input && input.value) || '').trim().toLowerCase();
            const items = Array.from(box.querySelectorAll('.as-check'));
            let matched = 0;
            items.forEach(el => {
                const cb = el.querySelector('.as-lib-cb');
                const c = asLibraryCourses[parseInt(cb ? cb.value : '-1', 10)] || {};
                const hay = [c.courseName, c.teacherName, c.classroomNo, c.classroomType]
                    .filter(Boolean).join(' ').toLowerCase();
                const hit = !kw || hay.indexOf(kw) >= 0;
                el.style.display = hit ? '' : 'none';
                if (hit) matched++;
            });
            if (emptyEl) emptyEl.style.display = (items.length && !matched) ? '' : 'none';
            if (countEl) {
                const checked = box.querySelectorAll('.as-lib-cb:checked').length;
                let txt = kw ? ('匹配 ' + matched + '/' + items.length + ' 门') : ('共 ' + items.length + ' 门');
                if (checked) txt += ' · 已选 ' + checked;
                countEl.textContent = txt;
            }
        }

        /** 从课程库删除课程（仅未被课表引用的课程可删，后端校验） */
        async function deleteLibraryCourse(courseId, courseName) {
            if (!courseId) { showProviderToast('该课程缺少 id，无法删除', 'error'); return; }
            const ok = await showConfirm({
                title: '删除课程',
                message: '确定从课程库删除「' + courseName + '」？仅未被课表引用的课程可删除。',
                confirmText: '删除',
                danger: true
            });
            if (!ok) return;
            fetch('/admin/courses/' + courseId, { method: 'DELETE' })
                .then(r => r.json())
                .then(data => {
                    if (data.success) {
                        showProviderToast('课程「' + courseName + '」已删除', 'success');
                        asLoadCourseLibrary();
                    } else {
                        showProviderToast(data.message || '删除失败', 'error');
                    }
                })
                .catch(() => showProviderToast('删除失败：网络异常', 'error'));
        }

        function asAddSelectedLibrary() {
            if (!asLibraryCourses.length) { showProviderToast('请先点击「刷新课程库」加载课程', 'error'); return; }
            const cbs = Array.from(document.querySelectorAll('.as-lib-cb:checked'));
            if (!cbs.length) { showProviderToast('请先勾选要加入的课程', 'error'); return; }
            const picked = cbs.map(cb => asLibraryCourses[parseInt(cb.value, 10)]).filter(Boolean);
            asAddCourses(picked.map(c => ({
                courseName: c.courseName, weekHours: c.weekHours,
                teacherName: c.teacherName, classroomNo: c.classroomNo,
                classroomType: c.classroomType, weekRange: ''
            })));
            cbs.forEach(cb => { cb.checked = false; });
            asFilterLibrary();
            showProviderToast('已加入 ' + picked.length + ' 门课程', 'success');
        }

        function asParseText() {
            const text = (document.getElementById('asTextInput').value || '').trim();
            if (!text) { showProviderToast('请输入课程文字描述', 'error'); return; }
            const btn = document.getElementById('asTextParseBtn');
            btn.disabled = true;
            fetchAi('/admin/ai-schedule/parse-text', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ text: text })
            })
                .then(r => r.json())
                .then(data => {
                    btn.disabled = false;
                    if (!data.success) { showProviderToast(data.message || '解析失败', 'error'); return; }
                    const list = data.courses || [];
                    if (!list.length) { showProviderToast('未解析出有效课程', 'error'); return; }
                    asAddCourses(list);
                    showProviderToast('已解析并加入 ' + list.length + ' 门课程', 'success');
                })
                .catch(error => { btn.disabled = false; showProviderToast(aiFetchErrorText(error, '解析失败：网络异常'), 'error'); });
        }

        function asParseExcel() {
            const input = document.getElementById('asExcelFile');
            const file = input.files && input.files[0];
            if (!file) { showProviderToast('请选择 xlsx 文件', 'error'); return; }
            if (!/\.xlsx$/i.test(file.name)) { showProviderToast('仅支持 .xlsx 格式', 'error'); return; }
            const fd = new FormData();
            fd.append('file', file);
            const btn = document.getElementById('asExcelParseBtn');
            btn.disabled = true;
            fetchAi('/admin/ai-schedule/parse-excel', { method: 'POST', body: fd })
                .then(r => r.json())
                .then(data => {
                    btn.disabled = false;
                    if (!data.success) { showProviderToast(data.message || '解析失败', 'error'); return; }
                    const list = data.courses || [];
                    asAddCourses(list);
                    showProviderToast('已从 Excel 加入 ' + list.length + ' 门课程', 'success');
                })
                .catch(error => { btn.disabled = false; showProviderToast(aiFetchErrorText(error, '解析失败：网络异常'), 'error'); });
        }

        function asParseImage() {
            const input = document.getElementById('asImageFile');
            const file = input.files && input.files[0];
            if (!file) { showProviderToast('请选择图片', 'error'); return; }
            if (!/\.(jpg|jpeg|png|webp)$/i.test(file.name)) { showProviderToast('仅支持 jpg/png/webp 格式', 'error'); return; }
            if (file.size > 5 * 1024 * 1024) { showProviderToast('图片大小超过 5MB 限制，请压缩后重新上传', 'error'); return; }
            const fd = new FormData();
            fd.append('image', file);
            const btn = document.getElementById('asImageParseBtn');
            btn.disabled = true;
            fetchAi('/admin/ai-schedule/parse-image', { method: 'POST', body: fd }, AI_IMAGE_TIMEOUT_MS)
                .then(r => r.json())
                .then(data => {
                    btn.disabled = false;
                    if (!data.success) { showProviderToast(data.message || '识图失败', 'error'); return; }
                    const list = data.courses || [];
                    if (!list.length) { showProviderToast('未从图片中识别出有效课程', 'error'); return; }
                    asAddCourses(list);
                    showProviderToast('已从图片识别并加入 ' + list.length + ' 门课程', 'success');
                })
                .catch(error => { btn.disabled = false; showProviderToast(aiFetchErrorText(error, '识图失败：网络异常'), 'error'); });
        }

        // 教室类型枚举：与「教室管理」保持一致，并自动合并教室管理中新增的类型
        const AS_ROOM_TYPES = ['普通教室', '多媒体教室', '微机室', '实验室', '语音室', '室外场地'];
        const AS_SPORTS_RE = /体育|田径|篮球|足球|排球|羽毛球|乒乓球|网球|健美操|体操|武术|游泳|军训|体测|形体/;

        function asAllRoomTypes() {
            const list = AS_ROOM_TYPES.slice();
            (asClassroomList || []).forEach(r => {
                const t = (r.roomType || '').trim();
                if (t && list.indexOf(t) < 0) list.push(t);
            });
            return list;
        }

        function asGuessRoomType(courseName) {
            const n = courseName || '';
            if (AS_SPORTS_RE.test(n)) return '室外场地';
            if (/计算机|程序|编程|软件|网络|数据库|信息|网页|java|python|算法|人工智能|大数据|实训|上机|操作/i.test(n)) return '微机室';
            if (n.indexOf('实验') >= 0) return '实验室';
            if (/语音|口语|听力|听说/.test(n)) return '语音室';
            if (/美术|音乐|舞蹈|设计|影视|多媒体/.test(n)) return '多媒体教室';
            return '普通教室';
        }

        /** 文本 → 教室类型（枚举名优先，其次关键词） */
        function asMatchRoomType(text) {
            const t = (text || '').trim();
            if (!t) return '';
            const types = asAllRoomTypes();
            for (let i = 0; i < types.length; i++) {
                if (types[i] === t) return types[i];
            }
            if (/机房|电脑|计算机|微机|实训/.test(t)) return '微机室';
            if (/多媒体|投影/.test(t)) return '多媒体教室';
            if (/实验/.test(t)) return '实验室';
            if (/语音/.test(t)) return '语音室';
            if (/室外|户外|操场|体育|田径/.test(t)) return '室外场地';
            if (/普通/.test(t)) return '普通教室';
            for (let i = 0; i < types.length; i++) {
                if (t.indexOf(types[i]) >= 0) return types[i];
            }
            return '';
        }

        /**
         * 导入教室字段归一：具体教室号 → 指定教室；类型文本 → 按类型自动推荐；
         * 空 → 按课程名推荐类型；「无教室」等标记 → 无需教室。
         */
        function asClassifyRoom(rawRoom, rawType, courseName) {
            const no = (rawRoom || '').trim();
            const type = (rawType || '').trim();
            if (['无教室', '室外', '操场', '体育场', '体育馆', '无'].indexOf(no) >= 0) {
                return { roomMode: 'none', classroomType: '', classroomNo: '' };
            }
            const exact = (asClassroomList || []).find(r => (r.classroomNo || '').trim().toLowerCase() === no.toLowerCase());
            if (exact) {
                return {
                    roomMode: 'fixed',
                    classroomType: (exact.roomType || '').trim() || asGuessRoomType(courseName),
                    classroomNo: exact.classroomNo
                };
            }
            const text = type || no;
            if (text) {
                const matchedType = asMatchRoomType(text);
                if (matchedType) return { roomMode: 'auto', classroomType: matchedType, classroomNo: '' };
                const fuzzy = (asClassroomList || []).find(r =>
                    ((r.classroomNo || '') + ' ' + (r.building || '') + ' ' + (r.description || '')).indexOf(text) >= 0);
                if (fuzzy && no) {
                    return {
                        roomMode: 'fixed',
                        classroomType: (fuzzy.roomType || '').trim() || asGuessRoomType(courseName),
                        classroomNo: fuzzy.classroomNo
                    };
                }
            }
            return { roomMode: 'auto', classroomType: asGuessRoomType(courseName), classroomNo: '' };
        }

        function asAddCourses(list) {
            (list || []).forEach(c => {
                const name = (c.courseName || '').trim();
                if (!name) return;
                const room = asClassifyRoom(c.classroomNo, c.classroomType, name);
                const exist = asCourseItems.find(x => x.courseName === name);
                if (exist) {
                    exist.weekHours = c.weekHours ? parseInt(c.weekHours, 10) : exist.weekHours;
                    if (c.teacherName) exist.teacherName = c.teacherName;
                    exist.roomMode = room.roomMode;
                    exist.classroomType = room.classroomType;
                    exist.classroomNo = room.classroomNo;
                    if (c.weekRange) exist.weekRange = c.weekRange;
                } else {
                    asCourseItems.push({
                        courseName: name,
                        weekHours: parseInt(c.weekHours, 10) || 2,
                        teacherName: c.teacherName || '',
                        roomMode: room.roomMode,
                        classroomType: room.classroomType,
                        classroomNo: room.classroomNo,
                        allowAutoChange: false,
                        weekRange: c.weekRange || '1-18'
                    });
                }
            });
            asRenderCourseList();
        }

        function asRemoveCourse(idx) {
            asCourseItems.splice(idx, 1);
            asRenderCourseList();
        }

        /* ---------------- 可搜索下拉（教师 / 教室 / 教室类型） ---------------- */
        function asComboOptions(kind) {
            if (kind === 'teacher') {
                return (asTeacherList || []).map(t => ({
                    value: t.name,
                    label: t.name,
                    sub: [t.department || '', t.title || ''].filter(Boolean).join(' · ')
                }));
            }
            if (kind === 'room') {
                return (asClassroomList || []).map(r => ({
                    value: r.classroomNo,
                    label: r.classroomNo,
                    sub: [r.building || '', r.roomType || '普通教室', r.capacity ? r.capacity + ' 人' : '']
                        .filter(Boolean).join(' · ')
                }));
            }
            return asAllRoomTypes().map(t => ({ value: t, label: t, sub: '' }));
        }

        function asComboHtml(kind, idx, key, value, placeholder) {
            const v = value || '';
            return `<div class="as-combo${v ? ' has-value' : ''}" data-combo="${kind}" data-idx="${idx}">`
                + `<input class="as-cell as-combo-input" data-idx="${idx}" data-key="${key}" value="${escapeHtml(v)}"`
                + ` placeholder="${escapeHtml(placeholder)}" autocomplete="off">`
                + `<button type="button" class="as-combo-clear" tabindex="-1" aria-label="清空">×</button>`
                + `<div class="as-combo-menu" role="listbox" hidden></div>`
                + `</div>`;
        }

        function asComboRender(box, keyword) {
            const list = asComboOptions(box.getAttribute('data-combo'));
            const kw = (keyword || '').trim().toLowerCase();
            const filtered = kw
                ? list.filter(o => o.label.toLowerCase().indexOf(kw) >= 0
                    || (o.sub || '').toLowerCase().indexOf(kw) >= 0)
                : list;
            box._items = filtered;
            box._active = Math.max(0, Math.min(box._active || 0, filtered.length - 1));
            const menu = box.querySelector('.as-combo-menu');
            if (!menu) return;
            if (!filtered.length) {
                menu.innerHTML = '<div class="as-combo-empty">无匹配结果</div>';
                return;
            }
            menu.innerHTML = filtered.map((o, i) =>
                `<div class="as-combo-item${i === box._active ? ' active' : ''}" data-i="${i}">`
                + `<span>${escapeHtml(o.label)}</span>`
                + (o.sub ? `<span class="as-combo-sub">${escapeHtml(o.sub)}</span>` : '')
                + `</div>`).join('');
        }

        function asComboOpen(box) {
            const menu = box.querySelector('.as-combo-menu');
            if (!menu) return;
            box._active = 0;
            asComboRender(box, '');
            menu.hidden = false;
            box.classList.add('open');
        }

        function asComboClose(box) {
            const menu = box.querySelector('.as-combo-menu');
            if (menu) menu.hidden = true;
            box.classList.remove('open');
        }

        function asComboPick(box, i) {
            const opt = (box._items || [])[i];
            if (!opt) return;
            const input = box.querySelector('.as-combo-input');
            input.value = opt.value;
            box.classList.toggle('has-value', !!opt.value);
            const idx = parseInt(box.getAttribute('data-idx'), 10);
            if (asCourseItems[idx]) asCourseItems[idx][input.getAttribute('data-key')] = opt.value;
            asComboClose(box);
        }

        function asBindCombos(root) {
            root.querySelectorAll('.as-combo').forEach(box => {
                if (box._bound) return;
                box._bound = true;
                box._active = 0;
                const input = box.querySelector('.as-combo-input');
                const menu = box.querySelector('.as-combo-menu');
                const clear = box.querySelector('.as-combo-clear');
                const idx = parseInt(box.getAttribute('data-idx'), 10);
                const key = input.getAttribute('data-key');

                input.addEventListener('focus', () => asComboOpen(box));
                input.addEventListener('input', () => {
                    box.classList.toggle('has-value', !!input.value);
                    box._active = 0;
                    asComboRender(box, input.value);
                    menu.hidden = false;
                    box.classList.add('open');
                    if (asCourseItems[idx]) asCourseItems[idx][key] = input.value.trim();
                });
                input.addEventListener('keydown', e => {
                    if (e.key === 'ArrowDown') {
                        e.preventDefault();
                        if (menu.hidden) { asComboOpen(box); return; }
                        const total = (box._items || []).length;
                        if (!total) return;
                        box._active = (box._active + 1) % total;
                        asComboRender(box, input.value);
                    } else if (e.key === 'ArrowUp') {
                        e.preventDefault();
                        const total = (box._items || []).length;
                        if (!total) return;
                        box._active = (box._active - 1 + total) % total;
                        asComboRender(box, input.value);
                    } else if (e.key === 'Enter') {
                        if (menu.hidden || !(box._items || []).length) return;
                        e.preventDefault();
                        asComboPick(box, box._active || 0);
                    } else if (e.key === 'Escape') {
                        asComboClose(box);
                    }
                });
                input.addEventListener('blur', () => {
                    setTimeout(() => {
                        if (!box.contains(document.activeElement)) asComboClose(box);
                    }, 120);
                });
                menu.addEventListener('mousedown', e => {
                    const el = e.target.closest('.as-combo-item');
                    if (!el) return;
                    e.preventDefault();
                    asComboPick(box, parseInt(el.getAttribute('data-i'), 10));
                });
                if (clear) {
                    clear.addEventListener('mousedown', e => {
                        e.preventDefault();
                        e.stopPropagation();
                        input.value = '';
                        box.classList.remove('has-value');
                        if (asCourseItems[idx]) asCourseItems[idx][key] = '';
                        input.focus();
                        asComboOpen(box);
                    });
                }
            });
        }

        // 点击下拉外部自动收起
        if (!window.__asComboOutsideBound) {
            window.__asComboOutsideBound = true;
            document.addEventListener('mousedown', function (e) {
                document.querySelectorAll('.as-combo.open').forEach(box => {
                    if (!box.contains(e.target)) asComboClose(box);
                });
            });
        }

        /** 教室单元格：分段控件切换三种互斥方式，内容随模式动态显隐 */
        function asRoomCell(c, idx) {
            const mode = (c.roomMode === 'fixed' || c.roomMode === 'none') ? c.roomMode : 'auto';
            const show = m => (mode === m ? '' : 'display:none;');
            const seg = (m, text) => `<button type="button" class="as-seg-btn${mode === m ? ' active' : ''}"`
                + ` data-idx="${idx}" data-room-mode="${m}" onclick="asOnRoomModeChange(${idx},'${m}')">${text}</button>`;
            const typeVal = (c.classroomType || '').trim() || asGuessRoomType(c.courseName);
            return `<div class="as-room-cell" data-idx="${idx}">`
                + `<div class="as-seg" role="group" aria-label="教室方式">`
                + seg('auto', 'AI 推荐') + seg('fixed', '指定教室') + seg('none', '无需教室')
                + `</div>`
                + `<div class="as-room-block" data-room-block="auto" style="${show('auto')}">`
                + asComboHtml('roomType', idx, 'classroomType', typeVal, '教室类型')
                + `</div>`
                + `<div class="as-room-block" data-room-block="fixed" style="${show('fixed')}">`
                + asComboHtml('room', idx, 'classroomNo', c.classroomNo, '搜索教室')
                + `<label class="as-room-auto-change" title="该教室被占用时允许自动换到同类型的其他教室">`
                + `<input type="checkbox" class="as-room-ctrl" data-idx="${idx}" data-key="allowAutoChange"`
                + `${c.allowAutoChange ? ' checked' : ''}>占用可换</label>`
                + `</div>`
                + `<div class="as-room-block" data-room-block="none" style="${show('none')}"></div>`
                + `</div>`;
        }

        function asOnRoomModeChange(idx, mode) {
            const item = asCourseItems[idx];
            if (item) {
                item.roomMode = mode;
                if (mode === 'auto' && !(item.classroomType || '').trim()) {
                    item.classroomType = asGuessRoomType(item.courseName);
                }
            }
            const cell = document.querySelector('#asCourseBody .as-room-cell[data-idx="' + idx + '"]');
            if (!cell) return;
            cell.querySelectorAll('.as-seg-btn').forEach(b => {
                b.classList.toggle('active', b.getAttribute('data-room-mode') === mode);
            });
            cell.querySelectorAll('[data-room-block]').forEach(b => {
                b.style.display = (b.getAttribute('data-room-block') === mode) ? '' : 'none';
            });
            const typeInput = cell.querySelector('[data-room-block="auto"] .as-combo-input');
            if (typeInput && !typeInput.value) {
                typeInput.value = asGuessRoomType((asCourseItems[idx] || {}).courseName);
            }
        }

        function asRenderCourseList() {
            const tbody = document.getElementById('asCourseBody');
            if (!tbody) return;
            if (!asCourseItems.length) {
                tbody.innerHTML = '<tr><td colspan="6" class="empty-hint">暂无课程，请从上方四种来源加入课程</td></tr>';
                return;
            }
            tbody.innerHTML = asCourseItems.map((c, idx) => `
                <tr>
                    <td data-label="课程名称"><input class="as-cell" data-idx="${idx}" data-key="courseName" value="${escapeHtml(c.courseName)}" placeholder="课程名称"></td>
                    <td data-label="周学时"><input class="as-cell" type="number" min="1" max="20" data-idx="${idx}" data-key="weekHours" value="${c.weekHours || 2}"></td>
                    <td data-label="授课教师">${asComboHtml('teacher', idx, 'teacherName', c.teacherName, '选择教师')}</td>
                    <td data-label="教室">${asRoomCell(c, idx)}</td>
                    <td data-label="周次"><input class="as-cell" data-week-range data-idx="${idx}" data-key="weekRange" value="${escapeHtml(c.weekRange || '')}" placeholder="如 1-18、单周、前8周"><div class="week-hint" style="display:none;"></div></td>
                    <td data-label="操作" class="as-col-actions"><button class="action-btn delete" style="padding:6px 10px;" onclick="asRemoveCourse(${idx})">删除</button></td>
                </tr>`).join('');
            asBindCombos(tbody);
            // 周次输入框按统一解析规则实时提示并写回数字格式
            WeekRange.attachAll(tbody);
        }

        /** 周次解析失败的课程（空周次由调用方单独提示） */
        function asFindInvalidWeekItem() {
            for (let i = 0; i < asCourseItems.length; i++) {
                const raw = (asCourseItems[i].weekRange || '').trim();
                if (!raw) continue;
                const parsed = WeekRange.parse(raw);
                if (!parsed.valid) return { idx: i, item: asCourseItems[i], error: parsed.error };
            }
            return null;
        }

        /** 定位到指定课程的周次输入框 */
        function asFocusWeekInput(idx) {
            const el = document.querySelector('#asCourseBody input[data-key="weekRange"][data-idx="' + idx + '"]');
            if (el) { el.scrollIntoView({ block: 'center' }); el.focus(); }
        }

        function asCollectCourseItems() {
            document.querySelectorAll('#asCourseBody .as-cell').forEach(el => {
                const idx = parseInt(el.getAttribute('data-idx'), 10);
                const key = el.getAttribute('data-key');
                if (asCourseItems[idx]) asCourseItems[idx][key] = (el.value || '').trim();
            });
            document.querySelectorAll('#asCourseBody .as-room-ctrl').forEach(el => {
                const idx = parseInt(el.getAttribute('data-idx'), 10);
                const key = el.getAttribute('data-key');
                const item = asCourseItems[idx];
                if (item && el.type === 'checkbox') item[key] = !!el.checked;
            });
            asCourseItems.forEach(c => {
                const n = parseInt(c.weekHours, 10);
                c.weekHours = (isNaN(n) || n < 1) ? 2 : n;
                // 周次按统一规则归一化为数字格式，解析失败时保留原文以便定位提示
                const parsedWeek = WeekRange.parse(c.weekRange || '');
                if (parsedWeek.valid) c.weekRange = parsedWeek.normalized;
                if (['auto', 'fixed', 'none'].indexOf(c.roomMode) < 0) c.roomMode = 'auto';
                if (c.roomMode === 'auto' && !(c.classroomType || '').trim()) {
                    c.classroomType = asGuessRoomType(c.courseName);
                }
                if (c.roomMode === 'fixed' && !(c.classroomType || '').trim()) {
                    c.classroomType = asGuessRoomType(c.courseName);
                }
                if (c.roomMode !== 'fixed') { c.classroomNo = ''; c.allowAutoChange = false; }
                if (c.roomMode === 'none') c.classroomType = '';
            });
        }

        /** 指定教室模式必须填写教室，否则提示用户改为自动推荐 */
        function asCheckFixedRooms() {
            const bad = asCourseItems.find(c => c.roomMode === 'fixed' && !(c.classroomNo || '').trim());
            if (bad) {
                showProviderToast('课程「' + bad.courseName
                    + '」选择了「指定教室」但未填写教室，请选择教室或改为「AI 自动推荐」', 'error');
                return false;
            }
            return true;
        }

        function asFillConstraintExample() {
            var ta = document.getElementById('asConstraints');
            ta.value = '李老师周二不能上课\n24级软件1班周三下午不排课\nB3-201教室不安排晚自习';
        }
