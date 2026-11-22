/********** teacher-adjust：调课申请三步向导与我的调课申请 **********/

        // ============ 调课申请（教师端） ============
        const ADJ_STATUS_TEXT = { pending: '待审核', approved: '已批准', rejected: '已拒绝', cancelled: '已撤销' };
        let teacherCoursesCache = null;
        let myAdjustPage = 1;
        let myAdjustTotal = 0;
        const MY_ADJUST_PAGE_SIZE = 10;

        function ensureTeacherCourses() {
            if (teacherCoursesCache) return Promise.resolve(teacherCoursesCache);
            return fetch('/api/teacher/courses')
                .then(r => r.json())
                .then(data => {
                    teacherCoursesCache = (data && data.success) ? (data.courses || []) : [];
                    return teacherCoursesCache;
                })
                .catch(() => { teacherCoursesCache = []; return teacherCoursesCache; });
        }

        function periodRangeText(s, e) {
            return Number(s) === Number(e) ? ('第' + s + '节') : ('第' + s + '-' + e + '节');
        }

        function courseOptionLabel(c) {
            const day = WEEKDAY_NAMES[c.weekDay] || ('周' + c.weekDay);
            const room = c.classroomNo || '室外（体育课）';
            return `${c.courseName || '未命名'} · ${c.className || '-'} · ${room} · ${day} ${periodRangeText(c.startPeriod, c.endPeriod)}`;
        }

        // ============ 调课三步向导：① 选课程 → ② 选目标 → ③ 确认提交 ============
        let adjState = null;        // 当前选中的课程与目标时段
        let adjClassrooms = null;   // 可选教室缓存
        let adjSuggestSeq = 0;      // AI 推荐请求序号（丢弃乱序响应）
        let adjSuggestLoading = false; // AI 推荐请求进行中（防重复点击）
        let adjLastDegraded = false;   // 上一次推荐是否已降级为本地规则
        let adjLastReason = null;      // 上一次降级原因（timeout / disabled / invalid / empty）
        let adjBatchItems = [];        // 批量提交清单（每条含 courseName/className 仅用于展示）
        let adjDraftAttachments = [];  // 本次提交的凭证附件（跨课程/批量保留，提交成功后清空）
        let adjSubmitting = false;     // 提交中标记（防重复提交）

        // 统一维护 AI 推荐按钮的加载态：进行中禁用并显示「AI 分析中…」，结束后恢复
        function setAdjSuggestLoading(loading) {
            adjSuggestLoading = loading;
            const btn = document.getElementById('adjAiBtn');
            const refreshBtn = document.getElementById('adjRefreshBtn');
            [btn, refreshBtn].forEach(function (el) {
                if (!el) return;
                el.disabled = loading;
                el.setAttribute('aria-busy', loading ? 'true' : 'false');
                if (loading) el.title = '正在分析，请勿重复点击';
                else el.removeAttribute('title');
            });
            if (btn) btn.innerHTML = loading
                ? '<span class="btn-spinner"></span>AI 分析中…'
                : 'AI 智能推荐时间';
            if (refreshBtn) refreshBtn.innerHTML = loading
                ? '<span class="btn-spinner"></span>AI 分析中…'
                : '刷新推荐';
        }

        // 目标周次下拉：覆盖第1周至学期总周数，首项表示"不跨周"（沿用原周次）
        function fillAdjTargetWeekOptions() {
            const sel = document.getElementById('adjTargetWeek');
            if (!sel) return;
            const total = Math.max(1, Number(WeekRange.getTotalWeeks()) || 20);
            const current = sel.value;
            let html = '<option value="">与原周次相同</option>';
            for (let w = 1; w <= total; w++) {
                html += '<option value="' + w + '">第' + w + '周</option>';
            }
            sel.innerHTML = html;
            if (current && Number(current) <= total) sel.value = current;
        }

        // 读取目标周次：1..N 为跨周调课，空值表示只调星期/节次
        function readAdjTargetWeek() {
            const sel = document.getElementById('adjTargetWeek');
            const val = sel ? Number(sel.value) : 0;
            return val >= 1 ? val : null;
        }

        // 目标周次变化：同步状态、提示源周次需为单周，已有目标时段时重新渲染并校验
        function onAdjTargetWeekChange() {
            if (!adjState) return;
            adjState.targetWeek = readAdjTargetWeek();
            const hint = document.getElementById('adjTargetWeekHint');
            if (hint) {
                if (adjState.targetWeek) {
                    const parsed = WeekRange.parse(adjState.weekRange || '');
                    const single = parsed.valid && parsed.weeks && parsed.weeks.length === 1;
                    hint.style.display = '';
                    hint.className = 'week-hint' + (single ? ' ok' : ' err');
                    hint.textContent = single
                        ? '跨周调课：第 ' + parsed.weeks[0] + ' 周 → 第 ' + adjState.targetWeek + ' 周'
                        : '跨周调课需将「周次范围」填写为被调整的那一周（如 10）';
                } else {
                    hint.style.display = 'none';
                    hint.textContent = '';
                }
            }
            if (adjState.target) showAdjStep3();
        }

        // 进入调课申请页：重置向导并加载课表条目与我的调课申请
        function initAdjustPanel() {
            adjState = null;
            adjBatchItems = [];
            adjDraftAttachments = [];
            adjSubmitting = false;
            renderAdjBatchList();
            renderAdjAttachList();
            adjLastDegraded = false;
            adjLastReason = null;
            if (adjSuggestLoading) { adjSuggestSeq++; setAdjSuggestLoading(false); }
            attachAdjWeekRange();
            fillAdjTargetWeekOptions();
            const targetWeekSel = document.getElementById('adjTargetWeek');
            if (targetWeekSel) targetWeekSel.value = '';
            const targetWeekHint = document.getElementById('adjTargetWeekHint');
            if (targetWeekHint) { targetWeekHint.style.display = 'none'; targetWeekHint.textContent = ''; }
            const resultBox = document.getElementById('adjustResult');
            resultBox.className = 'import-result';
            resultBox.innerHTML = '';
            document.getElementById('adjustReason').value = '';
            document.getElementById('adjEmptyHint').style.display = '';
            document.getElementById('adjStep2Wrap').style.display = 'none';
            document.getElementById('adjStep3Wrap').style.display = 'none';
            const subWrapInit = document.getElementById('adjSubstituteWrap');
            if (subWrapInit) subWrapInit.style.display = 'none';
            document.getElementById('adjAiMsg').textContent = '';
            document.getElementById('adjCandidateList').innerHTML = '';
            document.getElementById('adjManualBox').style.display = 'none';
            document.getElementById('adjRefreshBtn').style.display = 'none';
            ensureTeacherCourses().then(renderAdjCourseList);
            ensureTeacherCourses().then(renderSwapCourseOptions);
            ensureClassrooms();
            loadAdjTeacherOptions();
            loadMyAdjustments();
        }

        // ============ 教师互换课程：选我的课 → 选对方课 → 对方确认 → 管理员审批 ============
        let swapSubmitting = false;

        function renderSwapCourseOptions() {
            const sel = document.getElementById('swapMyCourse');
            if (!sel) return;
            const courses = teacherCoursesCache || [];
            sel.innerHTML = '<option value="">请选择我的课程</option>' + courses.map(c =>
                `<option value="${escapeHtml(String(c.id))}">${escapeHtml(courseOptionLabel(c))}</option>`).join('');
            const peerSel = document.getElementById('swapPeerCourse');
            if (peerSel) peerSel.innerHTML = '<option value="">请先选择我的课程</option>';
            const box = document.getElementById('swapResult');
            if (box) { box.className = 'import-result'; box.innerHTML = ''; }
            const reason = document.getElementById('swapReason');
            if (reason) reason.value = '';
        }

        function onSwapMyCourseChange(scheduleId) {
            const peerSel = document.getElementById('swapPeerCourse');
            if (!peerSel) return;
            if (!scheduleId) {
                peerSel.innerHTML = '<option value="">请先选择我的课程</option>';
                return;
            }
            peerSel.innerHTML = '<option value="">加载中...</option>';
            fetch('/api/teacher/adjustments/swap/peers?scheduleId=' + encodeURIComponent(scheduleId))
                .then(r => r.json())
                .then(data => {
                    if (!data || !data.success) {
                        peerSel.innerHTML = '<option value="">' + escapeHtml((data && data.message) || '加载失败') + '</option>';
                        return;
                    }
                    const list = data.courses || [];
                    peerSel.innerHTML = list.length
                        ? '<option value="">请选择对方课程</option>' + list.map(c =>
                            `<option value="${escapeHtml(String(c.scheduleId))}">${escapeHtml(c.teacherName || c.teacherId || '')} · ${escapeHtml(c.courseName || '未命名')} · ${escapeHtml(c.className || '-')} · ${escapeHtml(WEEKDAY_NAMES[c.weekDay] || ('周' + c.weekDay))} ${escapeHtml(periodRangeText(c.startPeriod, c.endPeriod))}</option>`).join('')
                        : '<option value="">同周次暂无可互换的其他教师课程</option>';
                })
                .catch(() => { peerSel.innerHTML = '<option value="">加载失败：网络异常</option>'; });
        }

        function submitTeacherSwap() {
            if (swapSubmitting) return;
            const scheduleId = document.getElementById('swapMyCourse').value;
            const peerScheduleId = document.getElementById('swapPeerCourse').value;
            const reason = (document.getElementById('swapReason').value || '').trim();
            const box = document.getElementById('swapResult');
            const btn = document.getElementById('swapSubmitBtn');
            if (!scheduleId) { showToast('请选择我的课程', 'error'); return; }
            if (!peerScheduleId) { showToast('请选择对方课程', 'error'); return; }
            if (!reason) { showToast('请填写互换原因', 'error'); return; }
            swapSubmitting = true;
            if (btn) btn.disabled = true;
            box.className = 'import-result';
            box.innerHTML = '提交中...';
            fetch('/api/teacher/adjustments/swap', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ scheduleId: Number(scheduleId), peerScheduleId: Number(peerScheduleId), reason: reason })
            })
                .then(r => r.json())
                .then(data => {
                    box.className = 'import-result ' + (data.success ? 'success' : 'error');
                    box.innerHTML = escapeHtml(data.message || (data.success ? '提交成功' : '提交失败'))
                        + (data.conflicts && data.conflicts.length ? '<br>' + data.conflicts.map(escapeHtml).join('<br>') : '');
                    showToast(data.message || (data.success ? '提交成功' : '提交失败'), data.success ? 'success' : 'error');
                    if (data.success) {
                        renderSwapCourseOptions();
                        myAdjustPage = 1;
                        loadMyAdjustments(1);
                    }
                })
                .catch(() => { box.className = 'import-result error'; box.innerHTML = '网络异常，请稍后重试'; })
                .finally(() => { swapSubmitting = false; if (btn) btn.disabled = false; });
        }

        function confirmTeacherSwap(id) {
            showConfirm({ title: '确认教师互换', message: '确认后互换申请将进入管理员审批，批准后两门课程同步换人。确定确认？', confirmText: '确认互换' })
                .then(ok => {
                    if (!ok) return;
                    fetch('/api/teacher/adjustments/' + id + '/confirm-swap', { method: 'POST' })
                        .then(r => r.json())
                        .then(data => {
                            showToast(data.message || (data.success ? '已确认' : '确认失败'), data.success ? 'success' : 'error');
                            if (data.success) loadMyAdjustments(myAdjustPage);
                        })
                        .catch(() => showToast('网络异常', 'error'));
                });
        }

        // ============ 代课教师（可选）：时间教室不变，仅更换授课教师 ============
        let adjTeachers = null;

        function loadAdjTeacherOptions() {
            if (adjTeachers) return Promise.resolve(adjTeachers);
            return fetch('/api/teacher/teacher-options')
                .then(r => r.json())
                .then(data => {
                    adjTeachers = (data && data.success) ? (data.teachers || []) : [];
                    const sel = document.getElementById('adjSubstituteTeacher');
                    if (sel) {
                        sel.innerHTML = '<option value="">不指定代课教师</option>' + adjTeachers.map(t =>
                            `<option value="${escapeHtml(t.id)}">${escapeHtml(t.name)}（${escapeHtml(t.id)}）</option>`).join('');
                    }
                    return adjTeachers;
                })
                .catch(() => { adjTeachers = []; return adjTeachers; });
        }

        // 选择代课教师：未选目标时段时自动按"原时段 + 代课"进入确认步骤
        function onAdjSubstituteChange() {
            if (!adjState) return;
            const sel = document.getElementById('adjSubstituteTeacher');
            const id = sel ? sel.value : '';
            const t = (adjTeachers || []).find(x => String(x.id) === String(id));
            adjState.substituteId = t ? String(t.id) : null;
            adjState.substituteName = t ? t.name : null;
            if (!t) {
                if (adjState.substituteAutoTarget) {
                    adjState.substituteAutoTarget = false;
                    adjState.target = null;
                    document.getElementById('adjStep3Wrap').style.display = 'none';
                }
                return;
            }
            if (!adjState.target) {
                const c = adjState.course;
                adjState.target = {
                    weekDay: Number(c.weekDay),
                    startPeriod: Number(c.startPeriod),
                    endPeriod: Number(c.endPeriod),
                    classroomId: null,
                    classroomName: c.classroomNo || '无教室',
                    classroomRoomType: c.roomType || '',
                    statusText: '',
                    source: 'manual'
                };
                adjState.substituteAutoTarget = true;
            }
            showAdjStep3();
        }

        // 教室名称：classroomNo 已含楼栋前缀时不再重复拼接，避免出现「B3 B3-201」
        function classroomLabel(c) {
            const no = (c && c.classroomNo) || '';
            const building = (c && c.building) || '';
            if (!no) return building;
            if (!building || no.indexOf(building) === 0) return no;
            return building + ' ' + no;
        }

        function ensureClassrooms() {
            if (adjClassrooms) return Promise.resolve(adjClassrooms);
            return fetch('/api/teacher/classrooms')
                .then(r => r.json())
                .then(data => {
                    adjClassrooms = (data && data.success) ? (data.classrooms || []) : [];
                    const sel = document.getElementById('adjManualRoom');
                    sel.innerHTML = '<option value="">沿用原教室</option>' + adjClassrooms.map(c =>
                        `<option value="${c.id}">${escapeHtml(classroomLabel(c))}</option>`
                    ).join('');
                    return adjClassrooms;
                })
                .catch(() => { adjClassrooms = []; return adjClassrooms; });
        }

        function renderAdjCourseList() {
            const list = document.getElementById('adjCourseList');
            const sel = document.getElementById('adjCourseSelect');
            const courses = teacherCoursesCache || [];
            if (!courses.length) {
                list.innerHTML = '<div class="empty-hint">暂无授课安排</div>';
                sel.innerHTML = '<option value="">请选择要调的课程</option>';
                return;
            }
            list.innerHTML = courses.map(c => `
                <div class="adj-course-item${c.isSubstitute ? ' is-substitute' : ''}" data-id="${c.id}" onclick="onAdjCourseSelect('${c.id}')">
                    <div class="adj-course-name">${escapeHtml(c.courseName || '-')}${c.isSubstitute ? ' <span class="adj-sub-badge">代课</span>' : ''}${c.substituteTeacherName && !c.isSubstitute ? ' <span class="adj-sub-badge">代课：' + escapeHtml(c.substituteTeacherName) + '</span>' : ''}</div>
                    <div class="adj-course-meta">${escapeHtml(c.className || '-')} · ${escapeHtml(WEEKDAY_NAMES[c.weekDay] || '-')} ${escapeHtml(periodRangeText(c.startPeriod, c.endPeriod))}</div>
                    <div class="adj-course-meta">原教室：${escapeHtml(c.classroomNo || '无')} · 周次 ${escapeHtml(String(c.weekRange || '-'))}</div>
                </div>`).join('');
            sel.innerHTML = '<option value="">请选择要调的课程</option>' + courses.filter(c => !c.isSubstitute).map(c =>
                `<option value="${c.id}">${escapeHtml(courseOptionLabel(c))}</option>`).join('');
        }

        function onAdjCourseSelect(scheduleId) {
            const course = (teacherCoursesCache || []).find(c => String(c.id) === String(scheduleId));
            if (!course) return;
            if (course.isSubstitute) {
                showToast('代课安排由原授课教师发起，如需调整请联系管理员', 'error');
                return;
            }
            document.getElementById('adjCourseSelect').value = String(scheduleId);
            document.querySelectorAll('.adj-course-item').forEach(el =>
                el.classList.toggle('active', el.dataset.id === String(scheduleId)));

            adjState = {
                course: course,
                changeRoom: false,
                weekRange: course.weekRange || '1-16',
                targetWeek: null,
                target: null,
                candidates: [],
                substituteId: null,
                substituteName: null,
                substituteAutoTarget: false,
                selectedWeeks: [],
                attachments: adjDraftAttachments
            };
            // 切换课程时作废进行中的推荐请求，避免旧响应覆盖新状态
            if (adjSuggestLoading) { adjSuggestSeq++; setAdjSuggestLoading(false); }
            adjLastDegraded = false;
            adjLastReason = null;
            document.getElementById('adjChangeRoom').checked = false;
            fillAdjTargetWeekOptions();
            document.getElementById('adjTargetWeek').value = '';
            const targetWeekHint = document.getElementById('adjTargetWeekHint');
            if (targetWeekHint) { targetWeekHint.style.display = 'none'; targetWeekHint.textContent = ''; }
            document.getElementById('adjWeekRange').value = adjState.weekRange;
            // 周次解析以该课程原课表周次为上界，单周/双周不会扩到原课表范围之外
            WeekRange.setBaseRange(document.getElementById('adjWeekRange'), course.weekRange || '');
            document.getElementById('adjManualRoom').disabled = true;
            document.getElementById('adjManualRoom').value = '';
            document.getElementById('adjManualWeekDay').value = String(course.weekDay || 1);
            document.getElementById('adjManualStart').value = String(course.startPeriod || 1);
            document.getElementById('adjManualEnd').value = String(course.endPeriod || 2);
            document.getElementById('adjManualBox').style.display = 'none';
            document.getElementById('adjEmptyHint').style.display = 'none';
            document.getElementById('adjStep2Wrap').style.display = '';
            document.getElementById('adjStep3Wrap').style.display = 'none';
            document.getElementById('adjCandidateList').innerHTML = '';
            document.getElementById('adjRefreshBtn').style.display = 'none';
            const subWrap = document.getElementById('adjSubstituteWrap');
            if (subWrap) subWrap.style.display = '';
            const subSel = document.getElementById('adjSubstituteTeacher');
            if (subSel) subSel.value = '';
            renderAdjAttachList();
            renderAdjWeekChips();
            const resultBox = document.getElementById('adjustResult');
            resultBox.className = 'import-result';
            resultBox.innerHTML = '';
            setAdjAiMsg('可点击「AI 智能推荐时间」获取候选时段，也可直接「手动选择」', '');
            ensureClassrooms();
        }

        // 换教室开关：开启后推荐按教室维度给出，手动选择时教室必选
        function onAdjChangeRoomChange() {
            if (!adjState) return;
            adjState.changeRoom = document.getElementById('adjChangeRoom').checked;
            adjacentResetTarget();
            const roomSel = document.getElementById('adjManualRoom');
            roomSel.disabled = !adjState.changeRoom;
            roomSel.value = '';
            setAdjAiMsg(adjState.changeRoom
                ? '已开启「不在原教室上课」：推荐将按教室维度给出；手动选择时请指定目标教室'
                : '已关闭「不在原教室上课」：目标教室固定为原教室', '');
        }

        // 原教室类型：优先取推荐接口返回值，其次取课程数据（历史数据可能为空）
        function adjOrigRoomType() {
            if (!adjState) return '';
            return adjState.origRoomType || (adjState.course && adjState.course.roomType) || '';
        }

        function onAdjWeekRangeChange() {
            if (!adjState) return;
            const input = document.getElementById('adjWeekRange');
            const raw = (input.value || '').trim();
            if (!raw) {
                adjState.weekRange = adjState.course.weekRange || '1-16';
                input.value = adjState.weekRange;
                adjacentResetTarget();
                setAdjAiMsg('周次范围已更新，请重新获取推荐或手动选择目标时段', '');
                return;
            }
            const parsed = WeekRange.parseWithin(raw, adjState.course.weekRange || '');
            if (!parsed.valid || !parsed.normalized) {
                adjacentResetTarget();
                setAdjAiMsg(parsed.error || WeekRange.FORMAT_HINT, 'err');
                return;
            }
            adjState.weekRange = parsed.normalized;
            input.value = parsed.normalized;
            adjacentResetTarget();
            setAdjAiMsg('周次范围已更新为 ' + WeekRange.describe(parsed)
                + '，请重新获取推荐或手动选择目标时段', '');
        }

        // 绑定周次输入框的实时解析提示（与后端同一套规则）；调课申请不展示「已解析为」提示
        function attachAdjWeekRange() {
            const input = document.getElementById('adjWeekRange');
            if (!input || input.__weekRangeBound) return;
            input.__weekRangeBound = true;
            WeekRange.attach(input, { hideParsedHint: true });
        }

        function adjacentResetTarget() {
            adjState.target = null;
            adjState.substituteAutoTarget = false;
            adjState.candidates = [];
            document.getElementById('adjStep3Wrap').style.display = 'none';
            document.getElementById('adjCandidateList').innerHTML = '';
            setAdjCapabilityNote(false);
            document.querySelectorAll('.adj-candidate-card').forEach(el => el.classList.remove('active'));
            // 仅代课场景：目标时段固定为原时段，重置后自动恢复
            if (adjState.substituteId) onAdjSubstituteChange();
        }

        // 候选区顶部能力说明：仅在已有候选时展示，避免空白状态下误导
        function setAdjCapabilityNote(show) {
            const note = document.getElementById('adjCapabilityNote');
            if (!note) return;
            note.hidden = !show;
            if (show) {
                const reasonText = adjLastReason === 'disabled' ? '（AI 模型未启用）'
                    : adjLastReason === 'timeout' ? '（模型响应超时）'
                    : adjLastReason ? '（模型返回异常）' : '';
                note.textContent = adjLastDegraded
                    ? '当前按本地规则推荐' + reasonText + '，如需 AI 智能推荐请在管理端配置或恢复模型'
                    : '已按当前模型能力生成推荐，若需更智能的推荐请在管理端切换模型';
            }
        }

        function setAdjAiMsg(text, type) {
            const el = document.getElementById('adjAiMsg');
            el.className = 'adj-ai-msg' + (type ? ' ' + type : '');
            el.textContent = text || '';
        }

        function toggleAdjManual() {
            const box = document.getElementById('adjManualBox');
            const show = box.style.display === 'none';
            box.style.display = show ? '' : 'none';
            if (show && adjState) applyManualSelection();
        }

        // 手动选择目标时段：换教室时教室必选，未开启则沿用原教室
        function applyManualSelection() {
            if (!adjState) return;
            const day = Number(document.getElementById('adjManualWeekDay').value);
            let st = Number(document.getElementById('adjManualStart').value);
            let en = Number(document.getElementById('adjManualEnd').value);
            if (en < st) { en = st; document.getElementById('adjManualEnd').value = String(st); }
            const roomVal = document.getElementById('adjManualRoom').value;
            if (adjState.changeRoom && !roomVal) {
                adjState.target = null;
                document.getElementById('adjStep3Wrap').style.display = 'none';
                setAdjAiMsg('已开启「不在原教室上课」，请在手动选择中指定目标教室', 'warn');
                return;
            }
            const room = (adjClassrooms || []).find(r => String(r.id) === String(roomVal));
            adjState.substituteAutoTarget = false;
            adjState.target = {
                weekDay: day,
                startPeriod: st,
                endPeriod: en,
                classroomId: adjState.changeRoom && room ? room.id : null,
                classroomName: adjState.changeRoom && room
                    ? classroomLabel(room)
                    : (adjState.course.classroomNo || '无教室'),
                classroomRoomType: adjState.changeRoom && room
                    ? (room.roomType || '')
                    : (adjState.course.roomType || ''),
                statusText: '',
                source: 'manual'
            };
            document.querySelectorAll('.adj-candidate-card').forEach(el => el.classList.remove('active'));
            showAdjStep3();
        }

        // 调课来源文案：AI 推荐 / 本地规则推荐 / 手动选择
        function adjSourceLabel(src, aiSuggested) {
            if (src === 'ai') return 'AI 推荐';
            if (src === 'local') return '本地规则推荐';
            if (src === 'manual') return '手动选择';
            return Number(aiSuggested) === 1 ? 'AI 推荐' : '手动选择';
        }

        function showAdjStep3() {
            if (!adjState || !adjState.target) return;
            const c = adjState.course;
            const t = adjState.target;
            const roomBefore = c.classroomNo || '无教室';
            const roomAfter = adjState.changeRoom ? (t.classroomName || '未指定') : roomBefore;
            const origRoomType = adjOrigRoomType();
            const targetRoomType = adjState.changeRoom ? (t.classroomRoomType || '') : origRoomType;
            const roomAfterText = roomAfter + (targetRoomType ? ' · ' + targetRoomType : '');
            // 目标教室类型与原教室不一致（仅手动指定教室时可能出现）：醒目提示，不阻断提交
            const typeMismatch = adjState.changeRoom && targetRoomType && origRoomType
                && targetRoomType !== origRoomType;
            const srcText = adjSourceLabel(t.source);
            document.getElementById('adjStep3Wrap').style.display = '';
            renderAdjWeekChips();
            document.getElementById('adjSummary').innerHTML =
                '<div class="adj-sum-row"><span>原时段</span><b>' + escapeHtml(WEEKDAY_NAMES[c.weekDay] || '-') + ' ' + escapeHtml(periodRangeText(c.startPeriod, c.endPeriod)) + ' · ' + escapeHtml(roomBefore) + '</b></div>'
                + '<div class="adj-sum-row"><span>目标时段</span><b>' + escapeHtml(WEEKDAY_NAMES[t.weekDay] || '-') + ' ' + escapeHtml(periodRangeText(t.startPeriod, t.endPeriod)) + ' · ' + escapeHtml(roomAfterText) + '</b></div>'
                + '<div class="adj-sum-row"><span>教室变化</span><b>' + (adjState.changeRoom
                    ? escapeHtml(roomBefore) + ' → ' + escapeHtml(roomAfterText)
                    : '教室不变（' + escapeHtml(roomBefore) + '）') + '</b></div>'
                + '<div class="adj-sum-row"><span>受影响范围</span><b>' + escapeHtml(c.className || '-') + ' · 已入班 ' + (Number(c.studentCount) || 0) + ' 名学生受影响</b></div>'
                + (adjState.substituteName
                    ? '<div class="adj-sum-row"><span>代课教师</span><b>' + escapeHtml(adjState.substituteName) + '（原授课教师：' + escapeHtml(c.teacherName || '-') + '）</b></div>'
                    : '')
                + '<div class="adj-sum-row"><span>周次范围</span><b>' + escapeHtml(adjState.weekRange || '-') + '</b></div>'
                + '<div class="adj-sum-row"><span>目标周次</span><b>' + (adjState.targetWeek
                    ? ('第' + adjState.targetWeek + '周')
                    : '与原周次相同') + '</b></div>'
                + '<div class="adj-sum-row"><span>时段来源</span><b>' + escapeHtml(srcText) + '</b></div>'
                + (t.statusText ? '<div class="adj-sum-row"><span>推荐说明</span><b>' + escapeHtml(t.statusText) + '</b></div>' : '')
                + (typeMismatch
                    ? '<div class="adj-type-warn">目标教室类型（' + escapeHtml(targetRoomType) + '）与原教室（'
                        + escapeHtml(origRoomType) + '）不一致，是否继续？提交后由管理端审批裁决</div>'
                    : '');
            checkAdjTarget();
        }

        // 获取 AI 推荐（首次推荐与「刷新推荐」共用，请求期间按钮禁用）
        function requestAiSuggest() {
            if (!adjState) return;
            // 防重复点击：请求未结束前忽略后续点击，避免并发请求相互冲突
            if (adjSuggestLoading) return;
            // 先校验周次格式，非法输入不发起请求，避免被误报为网络异常
            const weekInput = document.getElementById('adjWeekRange');
            const weekVal = weekInput ? weekInput.value.trim() : String(adjState.weekRange || '').trim();
            const weekParsed = WeekRange.parseWithin(weekVal, adjState.course.weekRange || '');
            if (!weekParsed.valid) {
                setAdjAiMsg(weekParsed.error || WeekRange.FORMAT_HINT, 'err');
                if (weekInput) weekInput.focus();
                return;
            }
            adjState.weekRange = weekParsed.normalized || adjState.course.weekRange || '1-16';
            if (weekInput) weekInput.value = adjState.weekRange;
            const seq = ++adjSuggestSeq;
            setAdjSuggestLoading(true);
            document.getElementById('adjCandidateList').innerHTML = '';
            setAdjCapabilityNote(false);
            setAdjAiMsg(adjLastDegraded
                ? '正在按本地规则计算空闲时段…'
                : 'AI 分析中，正在计算最优时段，最多约 180 秒（超时自动重试一次），请稍候…', '');
            fetchAi('/api/teacher/adjustments/ai-suggest', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    scheduleId: Number(adjState.course.id),
                    changeClassroom: adjState.changeRoom,
                    semester: adjState.course.semester,
                    weekRange: adjState.weekRange
                })
            })
            .then(r => r.json())
            .then(data => {
                if (seq !== adjSuggestSeq) return;
                setAdjSuggestLoading(false);
                if (!data.success) {
                    setAdjAiMsg(data.message || '推荐失败', 'err');
                    return;
                }
                adjState.candidates = data.candidates || [];
                if (data.origRoomType) adjState.origRoomType = data.origRoomType;
                document.getElementById('adjRefreshBtn').style.display = '';
                const count = adjState.candidates.length;
                const reason = data.aiUnavailableReason || null;
                adjLastDegraded = !!reason;
                adjLastReason = reason;
                if (!reason && count > 0) {
                    const ms = Number(data.aiLatencyMs);
                    const sec = isFinite(ms) && ms > 0 ? (ms / 1000).toFixed(1) : '';
                    setAdjAiMsg('已按 AI 推荐排序' + (sec
                        ? '（AI 分析用时 ' + sec + ' 秒，共 ' + count + ' 个候选）'
                        : '（共 ' + count + ' 个候选）'), '');
                } else if (!reason) {
                    setAdjAiMsg(data.message || '暂无可推荐的空闲时段', 'warn');
                } else if (reason === 'timeout') {
                    const wait = Number(data.aiTimeoutSeconds) > 0 ? '单次等待 ' + Number(data.aiTimeoutSeconds) + ' 秒' : '等待超时';
                    setAdjAiMsg((data.aiUnavailableText
                        || ('模型响应超时（' + wait + (data.aiRetried ? '，已自动重试 1 次' : '') + '），本次已用本地规则推荐'))
                        + '（共 ' + count + ' 个候选），可点击“刷新推荐”重试', 'warn');
                } else if (reason === 'disabled') {
                    setAdjAiMsg('当前未启用 AI 模型，已用本地规则推荐；如需 AI 智能推荐请在管理端配置模型', 'warn');
                } else {
                    setAdjAiMsg((data.aiUnavailableText
                        || (data.aiRepeatFail
                            ? '模型连续返回异常，已自动使用本地规则推荐'
                            : '模型返回异常，已用本地规则推荐'))
                        + '（共 ' + count + ' 个候选）', 'warn');
                }
                renderAdjCandidates();
            })
            .catch(error => {
                if (seq !== adjSuggestSeq) return;
                setAdjSuggestLoading(false);
                setAdjAiMsg(aiFetchErrorText(error, '网络异常，请稍后重试'), 'err');
            });
        }

        function renderAdjCandidates() {
            const box = document.getElementById('adjCandidateList');
            const list = adjState ? (adjState.candidates || []) : [];
            setAdjCapabilityNote(list.length > 0);
            if (!list.length) {
                box.innerHTML = '<div class="empty-hint">暂无可推荐的空闲时段</div>';
                return;
            }
            box.innerHTML = list.map((c, i) => `
                <div class="adj-candidate-card" data-idx="${i}" onclick="selectAdjCandidate(${i})">
                    <div class="adj-cand-head">
                        <b>${escapeHtml(WEEKDAY_NAMES[c.weekDay] || '-')} ${escapeHtml(periodRangeText(c.startPeriod, c.endPeriod))}</b>
                        <span class="adj-cand-src">${escapeHtml(adjSourceLabel(c.source))}</span>
                    </div>
                    <div class="adj-cand-room">教室：${escapeHtml(c.classroomName || '无教室')}${c.roomType ? '<span class="adj-cand-type">' + escapeHtml(c.roomType) + '</span>' : ''}</div>
                    <div class="adj-cand-status">${escapeHtml(c.statusText || '')}</div>
                </div>`).join('');
        }

        function selectAdjCandidate(idx) {
            if (!adjState || !adjState.candidates || !adjState.candidates[idx]) return;
            const c = adjState.candidates[idx];
            adjState.substituteAutoTarget = false;
            adjState.target = {
                weekDay: Number(c.weekDay),
                startPeriod: Number(c.startPeriod),
                endPeriod: Number(c.endPeriod),
                classroomId: c.classroomId == null ? null : Number(c.classroomId),
                classroomName: c.classroomName,
                classroomRoomType: c.roomType || '',
                statusText: c.statusText || '',
                source: c.source || 'ai'
            };
            document.querySelectorAll('.adj-candidate-card').forEach((el, i) => el.classList.toggle('active', i === idx));
            showAdjStep3();
        }

        // 调课实时校验请求序号：自增，仅最新一次请求返回时更新状态，丢弃乱序的旧响应，避免误显示
        let adjCheckSeq = 0;

        // 单条目标时段三方校验：红字提示冲突但不阻止提交（最终由管理端裁决）
        function checkAdjTarget(cb) {
            if (!adjState || !adjState.target) return;
            const c = adjState.course;
            const t = adjState.target;
            const hint = document.getElementById('adjCheckHint');
            hint.className = 'adj-check-hint';
            hint.textContent = '校验中...';
            const seq = ++adjCheckSeq;
            const payload = {
                scheduleId: Number(c.id),
                targetWeekDay: t.weekDay,
                targetStartPeriod: t.startPeriod,
                targetEndPeriod: t.endPeriod,
                weekRange: adjState.weekRange,
                targetWeek: adjState.targetWeek || null,
                changeClassroom: adjState.changeRoom
            };
            if (adjState.changeRoom && t.classroomId) payload.targetClassroomId = Number(t.classroomId);
            if (adjState.substituteId) payload.substituteTeacherId = adjState.substituteId;
            fetch('/api/teacher/adjustments/check', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            })
            .then(r => r.json())
            .then(data => {
                if (seq !== adjCheckSeq) return;
                if (data.success) {
                    hint.className = 'adj-check-hint ok';
                    hint.textContent = '该时段无冲突，可提交';
                } else {
                    hint.className = 'adj-check-hint err';
                    hint.textContent = data.blocking
                        ? (data.message || '该课程不可发起调课')
                        : ((data.message || '存在冲突') + '（仍可提交，由管理端裁决）');
                }
                if (typeof cb === 'function') cb(data);
            })
            .catch(() => {
                if (seq !== adjCheckSeq) return;
                hint.className = 'adj-check-hint err';
                hint.textContent = '校验失败：网络异常（仍可提交，由管理端裁决）';
                if (typeof cb === 'function') cb(null);
            });
        }

        function setAdjustResult(box, ok, msg) {
            box.className = 'import-result show ' + (ok ? 'ok' : 'err');
            box.innerHTML = escapeHtml(msg);
        }

        // ============ 调课凭证附件：先上传拿 id，提交时随申请绑定 ============
        const ADJ_ATTACH_MAX = 3;
        const ADJ_ATTACH_MAX_SIZE = 5 * 1024 * 1024;

        function formatAdjFileSize(size) {
            const n = Number(size) || 0;
            if (n >= 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + 'MB';
            if (n >= 1024) return Math.round(n / 1024) + 'KB';
            return n + 'B';
        }

        function renderAdjAttachList() {
            const box = document.getElementById('adjAttachList');
            if (!box) return;
            const list = adjDraftAttachments || [];
            box.innerHTML = list.map(f =>
                '<span class="adj-attach-item">'
                + '<span class="adj-attach-name">' + escapeHtml(f.name || '') + '</span>'
                + '<span class="adj-attach-size">' + formatAdjFileSize(f.size) + '</span>'
                + '<button type="button" class="adj-attach-remove" onclick="removeAdjAttachment(' + Number(f.id) + ')">移除</button>'
                + '</span>').join('');
        }

        function removeAdjAttachment(id) {
            adjDraftAttachments = adjDraftAttachments.filter(f => Number(f.id) !== Number(id));
            if (adjState) adjState.attachments = adjDraftAttachments;
            renderAdjAttachList();
        }

        // 客户端先校验数量/类型/大小，逐个上传，成功后仅保留附件 id 供提交绑定
        async function uploadAdjAttachment(input) {
            if (!input || !input.files || !input.files.length) return;
            const files = Array.from(input.files);
            input.value = '';
            if (!adjState) { showToast('请先选择要调整的课程', 'error'); return; }
            const remain = ADJ_ATTACH_MAX - adjDraftAttachments.length;
            if (remain <= 0) { showToast('最多上传 ' + ADJ_ATTACH_MAX + ' 个附件', 'error'); return; }
            const queue = files.slice(0, remain);
            if (files.length > remain) {
                showToast('最多 ' + ADJ_ATTACH_MAX + ' 个附件，本次仅上传前 ' + remain + ' 个', 'error');
            }
            for (const f of queue) {
                const okType = /^image\//.test(f.type || '') || f.type === 'application/pdf';
                if (!okType) { showToast('仅支持图片或 PDF：' + f.name, 'error'); continue; }
                if (f.size > ADJ_ATTACH_MAX_SIZE) { showToast('单个附件不能超过 5MB：' + f.name, 'error'); continue; }
                const form = new FormData();
                form.append('file', f);
                try {
                    const r = await fetch('/api/teacher/adjustments/attachments', { method: 'POST', body: form });
                    const data = await r.json();
                    if (data && data.success) {
                        adjDraftAttachments.push({ id: data.id, name: data.name || f.name, size: data.size || f.size });
                        if (adjState) adjState.attachments = adjDraftAttachments;
                        renderAdjAttachList();
                    } else {
                        showToast((data && data.message) || ('附件上传失败：' + f.name), 'error');
                    }
                } catch (e) {
                    showToast('附件上传失败：网络异常', 'error');
                }
            }
        }

        // ============ 批量调课：多周逐条展开 + 多门课加入清单 ============
        function renderAdjWeekChips() {
            const wrap = document.getElementById('adjBatchWeeksWrap');
            const box = document.getElementById('adjWeekChips');
            const hint = document.getElementById('adjWeekChipsHint');
            if (!wrap || !box) return;
            if (!adjState || !adjState.course) {
                wrap.style.display = 'none';
                box.innerHTML = '';
                return;
            }
            wrap.style.display = '';
            const parsed = WeekRange.parse(adjState.course.weekRange || '1-18');
            const weeks = (parsed.valid && parsed.weeks) ? parsed.weeks : [];
            adjState.selectedWeeks = (adjState.selectedWeeks || []).filter(w => weeks.indexOf(Number(w)) >= 0);
            const crossWeek = !!adjState.targetWeek;
            box.innerHTML = weeks.map(w => {
                const checked = adjState.selectedWeeks.indexOf(Number(w)) >= 0;
                return '<label class="adj-week-chip' + (checked ? ' active' : '') + (crossWeek ? ' disabled' : '') + '">'
                    + '<input type="checkbox" ' + (checked ? 'checked ' : '') + (crossWeek ? 'disabled ' : '')
                    + 'onchange="toggleAdjWeek(' + w + ', this.checked)">第' + w + '周</label>';
            }).join('');
            if (!hint) return;
            if (crossWeek) {
                hint.className = 'week-hint err';
                hint.textContent = '跨周调课一次只能调整一个周次，已停用多周批量';
            } else if (adjState.selectedWeeks.length > 1) {
                hint.className = 'week-hint ok';
                hint.textContent = '已选择 ' + adjState.selectedWeeks.length + ' 周，提交后将生成 '
                    + adjState.selectedWeeks.length + ' 条独立申请';
            } else if (adjState.selectedWeeks.length === 1) {
                hint.className = 'week-hint';
                hint.textContent = '仅调整第 ' + adjState.selectedWeeks[0] + ' 周';
            } else {
                hint.className = 'week-hint';
                hint.textContent = '不选则按上方「周次范围」整体调整';
            }
        }

        function toggleAdjWeek(week, checked) {
            if (!adjState) return;
            const w = Number(week);
            adjState.selectedWeeks = adjState.selectedWeeks || [];
            if (checked) {
                if (adjState.selectedWeeks.indexOf(w) < 0) adjState.selectedWeeks.push(w);
            } else {
                adjState.selectedWeeks = adjState.selectedWeeks.filter(x => Number(x) !== w);
            }
            renderAdjWeekChips();
            const resultBox = document.getElementById('adjustResult');
            if (resultBox) { resultBox.className = 'import-result'; resultBox.innerHTML = ''; }
        }

        // 当前向导配置转明细：勾选多周时每周展开为一条独立申请（用户已确认该口径）
        function currentAdjDetails() {
            if (!adjState || !adjState.target) return [];
            const c = adjState.course;
            const t = adjState.target;
            const base = {
                scheduleId: Number(c.id),
                targetWeekDay: t.weekDay,
                targetStartPeriod: t.startPeriod,
                targetEndPeriod: t.endPeriod,
                weekRange: adjState.weekRange,
                targetWeek: adjState.targetWeek || null,
                changeClassroom: adjState.changeRoom,
                aiSuggested: t.source !== 'manual',
                sourceType: t.source === 'ai' || t.source === 'local' ? t.source : 'manual',
                statusText: t.statusText || ''
            };
            if (adjState.changeRoom && t.classroomId) base.targetClassroomId = Number(t.classroomId);
            if (adjState.substituteId) base.substituteTeacherId = adjState.substituteId;
            const weeks = (adjState.selectedWeeks || []).slice().sort((a, b) => a - b);
            if (!weeks.length) {
                return [{ detail: base, courseName: c.courseName, className: c.className }];
            }
            return weeks.map(w => ({
                detail: Object.assign({}, base, { weekRange: String(w) }),
                courseName: c.courseName,
                className: c.className
            }));
        }

        function adjBatchKey(it) {
            const d = it.detail;
            return [d.scheduleId, d.weekRange, d.targetWeek || '', d.targetWeekDay,
                d.targetStartPeriod, d.targetEndPeriod, d.targetClassroomId || ''].join('|');
        }

        function adjBatchItemText(it) {
            const d = it.detail;
            return '《' + escapeHtml(it.courseName || '未命名') + '》' + escapeHtml(it.className || '-')
                + ' · ' + escapeHtml(WEEKDAY_NAMES[d.targetWeekDay] || '')
                + ' ' + escapeHtml(periodRangeText(d.targetStartPeriod, d.targetEndPeriod))
                + ' · 第' + escapeHtml(String(d.weekRange)) + '周'
                + (d.targetWeek ? ' → 第' + escapeHtml(String(d.targetWeek)) + '周' : '');
        }

        function renderAdjBatchList() {
            const wrap = document.getElementById('adjBatchListWrap');
            const box = document.getElementById('adjBatchList');
            if (!wrap || !box) return;
            if (!adjBatchItems.length) {
                wrap.style.display = 'none';
                box.innerHTML = '';
                return;
            }
            wrap.style.display = '';
            box.innerHTML = adjBatchItems.map((it, i) =>
                '<div class="adj-batch-item"><span class="adj-batch-text">' + adjBatchItemText(it) + '</span>'
                + '<button type="button" class="adj-batch-remove" onclick="removeAdjBatchItem(' + i + ')">移除</button></div>'
            ).join('');
        }

        function removeAdjBatchItem(index) {
            adjBatchItems.splice(Number(index), 1);
            renderAdjBatchList();
        }

        // 加入批量：附件与原因保留，向导重置后继续选择下一门课
        function addAdjBatchItem() {
            if (!adjState || !adjState.target) {
                showToast('请先选择目标时段', 'error');
                return;
            }
            if (adjState.targetWeek && (adjState.selectedWeeks || []).length) {
                showToast('跨周调课不支持多周批量', 'error');
                return;
            }
            const items = currentAdjDetails();
            if (!items.length) return;
            const existing = {};
            adjBatchItems.forEach(it => { existing[adjBatchKey(it)] = true; });
            let added = 0;
            items.forEach(it => {
                const key = adjBatchKey(it);
                if (existing[key]) return;
                adjBatchItems.push(it);
                existing[key] = true;
                added++;
            });
            renderAdjBatchList();
            if (!added) {
                showToast('该调课已在批量清单中', 'error');
                return;
            }
            resetAdjWizardForNextCourse();
            showToast('已加入批量清单，请继续选择下一门课程', 'success');
        }

        function resetAdjWizardForNextCourse() {
            adjState = null;
            if (adjSuggestLoading) { adjSuggestSeq++; setAdjSuggestLoading(false); }
            document.getElementById('adjCourseSelect').value = '';
            document.querySelectorAll('.adj-course-item').forEach(el => el.classList.remove('active'));
            document.getElementById('adjEmptyHint').style.display = '';
            document.getElementById('adjStep2Wrap').style.display = 'none';
            document.getElementById('adjStep3Wrap').style.display = 'none';
            const chips = document.getElementById('adjWeekChips');
            if (chips) chips.innerHTML = '';
            const subWrap = document.getElementById('adjSubstituteWrap');
            if (subWrap) subWrap.style.display = 'none';
            const resultBox = document.getElementById('adjustResult');
            resultBox.className = 'import-result';
            resultBox.innerHTML = '';
        }

        // 确认提交：先自动校验一次（仅提示，不阻止），再调 apply 提交
        function submitAdjustment() {
            const resultBox = document.getElementById('adjustResult');
            if (adjSubmitting) return;
            // 当前向导明细：多周勾选时按周展开为多条独立申请
            let currentItems = [];
            if (adjState && adjState.target) {
                const weekInput = document.getElementById('adjWeekRange');
                const weekParsed = WeekRange.parseWithin(
                    weekInput ? weekInput.value.trim() : adjState.weekRange,
                    adjState.course ? (adjState.course.weekRange || '') : '');
                if (!weekParsed.valid) {
                    setAdjustResult(resultBox, false, weekParsed.error || WeekRange.FORMAT_HINT);
                    if (weekInput) weekInput.focus();
                    return;
                }
                if (weekParsed.normalized) {
                    adjState.weekRange = weekParsed.normalized;
                    if (weekInput) weekInput.value = weekParsed.normalized;
                }
                if (adjState.targetWeek && (adjState.selectedWeeks || []).length > 1) {
                    setAdjustResult(resultBox, false, '跨周调课一次只能调整一个周次，请取消多周勾选');
                    return;
                }
                currentItems = currentAdjDetails();
            }
            const allItems = adjBatchItems.concat(currentItems);
            if (!allItems.length) {
                setAdjustResult(resultBox, false, '请先选择目标时段');
                return;
            }
            const reason = document.getElementById('adjustReason').value.trim();
            if (!reason) {
                setAdjustResult(resultBox, false, '请填写调课原因');
                return;
            }
            // 请求内去重：同一课程 + 同一源周次 + 同一目标时段只提交一次
            const seen = {};
            const details = [];
            allItems.forEach(it => {
                const key = adjBatchKey(it);
                if (seen[key]) return;
                seen[key] = true;
                details.push(it.detail);
            });

            const btn = document.getElementById('adjustSubmitBtn');
            const addBtn = document.getElementById('adjAddBatchBtn');
            adjSubmitting = true;
            btn.disabled = true;
            btn.textContent = '提交中...';
            if (addBtn) addBtn.disabled = true;
            resultBox.className = 'import-result';
            resultBox.innerHTML = '';

            const finish = function () {
                adjSubmitting = false;
                btn.disabled = false;
                btn.textContent = '提交申请';
                if (addBtn) addBtn.disabled = false;
            };

            const doApply = function () {
                fetch('/api/teacher/adjustments/apply', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        reason: reason,
                        details: details,
                        attachmentIds: adjDraftAttachments.map(f => Number(f.id))
                    })
                })
                .then(r => r.json())
                .then(data => {
                    finish();
                    if (data.success) {
                        const ids = data.adjustments || [];
                        const failed = data.failed || [];
                        let msg = '已提交 ' + (data.count || ids.length) + ' 条调课申请，等待管理端审核';
                        if (ids.length) msg += '（申请编号：' + ids.join('、') + '）';
                        if (data.conflicts && data.conflicts.length) {
                            msg += '。注意：部分时段存在占用，管理端将按冲突情况裁决';
                        }
                        if (failed.length) {
                            msg += '。失败 ' + failed.length + ' 条：'
                                + failed.map(f => f.message).join('；');
                        }
                        setAdjustResult(resultBox, true, msg);
                        document.getElementById('adjustReason').value = '';
                        adjDraftAttachments = [];
                        adjBatchItems = [];
                        renderAdjBatchList();
                        renderAdjAttachList();
                        myAdjustPage = 1;
                        loadMyAdjustments(1);
                        loadSchedule();
                        if (adjState && adjState.course) {
                            const keepId = adjState.course.id;
                            ensureTeacherCourses().then(() => {
                                onAdjCourseSelect(keepId);
                                // onAdjCourseSelect 会清空结果区，提交成功提示需在其后重新写入
                                setAdjustResult(resultBox, true, msg);
                            });
                        } else {
                            resetAdjWizardForNextCourse();
                            setAdjustResult(resultBox, true, msg);
                        }
                    } else {
                        setAdjustResult(resultBox, false, data.message || '提交失败');
                    }
                })
                .catch(() => {
                    finish();
                    setAdjustResult(resultBox, false, '网络异常，请稍后重试');
                });
            };

            // 单条明细沿用前端预检；批量提交交由后端逐条校验并返回失败明细
            if (details.length === 1 && adjState && adjState.target) {
                checkAdjTarget(function (data) {
                    // 结构性拦截（如该课程已发起过调课申请）在前端直接阻止提交
                    if (data && data.blocking) {
                        finish();
                        setAdjustResult(resultBox, false, data.message || '该课程已发起过调课申请，不允许再次发起');
                        return;
                    }
                    doApply();
                });
            } else {
                doApply();
            }
        }

        function loadMyAdjustments(page) {
            const p = page || myAdjustPage || 1;
            const box = document.getElementById('myAdjustList');
            box.innerHTML = '<div class="empty-hint">加载中...</div>';
            fetch('/api/teacher/adjustments?page=' + p + '&size=' + MY_ADJUST_PAGE_SIZE)
                .then(r => r.json())
                .then(data => {
                    if (!data.success) {
                        box.innerHTML = '<div class="empty-hint">' + escapeHtml(data.message || '加载失败') + '</div>';
                        return;
                    }
                    myAdjustPage = data.page || p;
                    myAdjustTotal = data.total || 0;
                    const list = data.adjustments || [];
                    // 末页最后一条被撤销后回退到上一页
                    if (!list.length && myAdjustTotal > 0 && myAdjustPage > 1) {
                        loadMyAdjustments(myAdjustPage - 1);
                        return;
                    }
                    box.innerHTML = list.length
                        ? list.map(a => renderMyAdjustItem(a)).join('')
                        : '<div class="empty-hint">暂无调课申请</div>';
                    renderMyAdjustPager();
                })
                .catch(() => { box.innerHTML = '<div class="empty-hint">加载失败：网络异常</div>'; });
        }

        function renderMyAdjustPager() {
            if (typeof renderPager !== 'function') return;
            renderPager('myAdjustPager', myAdjustPage, MY_ADJUST_PAGE_SIZE, myAdjustTotal, function (p) {
                myAdjustPage = p;
                loadMyAdjustments(p);
            });
        }

        function renderMyAdjustItem(a) {
            const st = a.status || 'pending';
            const stText = ADJ_STATUS_TEXT[st] || st;
            const cancelPending = a.cancelStatus === 'PENDING' && st === 'approved';
            const isSwap = Number(a.swapFlag) === 1;
            const origDay = WEEKDAY_NAMES[a.origWeekDay] || (a.origWeekDay || '-');
            const tgtDay = WEEKDAY_NAMES[a.targetWeekDay] || (a.targetWeekDay || '-');
            // 撤销入口：待审批可直接撤销；已批准则发起撤销审批；撤销审批中不再显示按钮
            let withdrawBtn = '';
            if (st === 'pending') {
                withdrawBtn = '<button class="adj-withdraw-btn" onclick="cancelAdjustment(' + a.id + ', \'pending\')">'
                    + (isSwap ? '撤销互换申请' : '撤销调课申请') + '</button>';
            } else if (st === 'approved' && !cancelPending) {
                withdrawBtn = '<button class="adj-withdraw-btn" onclick="cancelAdjustment(' + a.id + ', \'approved\')">撤销调课申请</button>';
            }
            // 互换：对方教师确认入口
            const confirmBtn = (isSwap && a.canConfirm)
                ? '<button class="btn-confirm" style="margin-top:8px" onclick="confirmTeacherSwap(' + a.id + ')">确认互换</button>'
                : '';
            const swapLine = isSwap
                ? '<div class="adj-sub">教师互换：与 ' + escapeHtml(a.peerTeacherName || a.peerTeacherId || '-')
                    + ' 对调课程；对方确认：' + (Number(a.peerConfirmed) === 1 ? '已确认' : '待确认')
                    + (a.peerConfirmTime ? '（' + escapeHtml(formatTime(a.peerConfirmTime)) + '）' : '') + '</div>'
                : '';
            // 紧急回滚：已批准且未回滚过，填原因后立即恢复原课表，管理员事后补审确认知悉
            const emergencyRolled = Number(a.emergencyFlag) === 1;
            if (st === 'approved' && !emergencyRolled) {
                withdrawBtn += '<button class="adj-withdraw-btn" onclick="emergencyRollbackAdjustment(' + a.id + ')">紧急回滚</button>';
            }
            const emergencyLine = emergencyRolled
                ? '<div class="adj-sub adj-cancel-pending">已紧急回滚' + (a.emergencyReason ? '，原因：' + escapeHtml(a.emergencyReason) : '')
                    + '；' + (isSwap ? '两门课程已恢复原授课教师' : '课表已恢复原时段') + '，管理员补审：'
                    + (a.emergencyReviewBy
                        ? escapeHtml(a.emergencyReviewBy) + ' 已确认知悉' + (a.emergencyReviewTime ? '（' + escapeHtml(formatTime(a.emergencyReviewTime)) + '）' : '')
                        : '待确认')
                    + '</div>'
                : '';
            const cancelPendingLine = cancelPending
                ? '<div class="adj-sub adj-cancel-pending">撤销申请待管理员审批'
                    + (a.cancelApplyTime ? '（提交于 ' + escapeHtml(formatTime(a.cancelApplyTime)) + '）' : '') + '</div>'
                : '';
            const cancelRejectedLine = (st === 'approved' && a.cancelStatus === 'REJECTED')
                ? '<div class="adj-sub">撤销申请被拒：' + escapeHtml(a.cancelRejectReason || '未说明') + '</div>'
                : '';
            const reasonLine = a.rejectReason ? '<div class="adj-sub">处理说明：' + escapeHtml(a.rejectReason) + '</div>' : '';
            const srcText = adjSourceLabel(a.sourceType, a.aiSuggested);
            const roomLine = a.changeClassroom
                ? '<div class="adj-sub">换教室：' + escapeHtml(a.origClassroomNo || '无') + ' → ' + escapeHtml(a.targetClassroomName || '未指定') + '</div>'
                : '';
            const statusLine = a.statusText ? '<div class="adj-sub">推荐说明：' + escapeHtml(a.statusText) + '</div>' : '';
            return '<div class="adjust-item">'
                + '<div class="adj-line"><strong>' + escapeHtml(a.courseName || '-') + '</strong>'
                + '<span class="adj-status adj-' + st + '">' + escapeHtml(stText) + '</span>'
                + (cancelPending ? '<span class="adj-status adj-pending">撤销待审批</span>' : '')
                + '</div>'
                + '<div class="adj-sub">班级：' + escapeHtml(a.className || '-') + ' · 周次：' + escapeHtml(String(a.weekRange || '-')) + ' · 来源：' + escapeHtml(srcText) + '</div>'
                + '<div class="adj-sub">' + escapeHtml(origDay) + ' ' + escapeHtml(periodRangeText(a.origStartPeriod, a.origEndPeriod))
                + ' → ' + (a.targetWeek ? ('第' + a.targetWeek + '周 ') : '')
                + escapeHtml(tgtDay) + ' ' + escapeHtml(periodRangeText(a.targetStartPeriod, a.targetEndPeriod)) + '</div>'
                + swapLine + roomLine + statusLine
                + '<div class="adj-sub">原因：' + escapeHtml(a.reason || '-') + '</div>'
                + '<div class="adj-sub">申请时间：' + escapeHtml(formatTime(a.applyTime) || '-')
                + (a.approver ? ' · 处理人：' + escapeHtml(a.approver) : '') + '</div>'
                + cancelPendingLine + cancelRejectedLine + emergencyLine + reasonLine + confirmBtn + withdrawBtn
                + '</div>';
        }

        // 撤销调课申请：待审批直接撤销；已批准生成撤销审批申请（管理员同意后才恢复课表）
        async function cancelAdjustment(id, status) {
            const approved = status === 'approved';
            const ok = await showConfirm({
                title: '撤销调课申请',
                message: approved
                    ? '该调课已批准生效，撤销需提交管理员审批，审批通过后课表恢复原时段。确定提交撤销申请？'
                    : '确定撤销该调课申请？撤销后状态变为已撤销。',
                confirmText: '提交撤销'
            });
            if (!ok) return;
            fetch('/api/teacher/adjustments/' + id + '/cancel-request', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ reason: approved ? '教师申请撤销已批准调课' : '教师撤销待审批调课申请' })
            })
            .then(r => r.json())
            .then(data => {
                showToast(data.message || (data.success ? '操作成功' : '操作失败'), data.success ? 'success' : 'error');
                if (data.success) {
                    myAdjustPage = 1;
                    loadMyAdjustments(1);
                    loadSchedule();
                }
            })
            .catch(() => showToast('网络异常', 'error'));
        }

        // 紧急回滚：填写原因后立即恢复原课表并留痕，管理员事后补审确认知悉
        async function emergencyRollbackAdjustment(id) {
            const reason = await showPrompt({
                title: '紧急回滚调课',
                message: '紧急回滚会立即恢复原课表，不需管理员审批，但会写入操作日志并由管理员事后确认知悉。请填写回滚原因：',
                placeholder: '例如：临时接到紧急任务，无法按调课时间授课',
                confirmText: '确认回滚'
            });
            if (reason === null) return;
            const note = String(reason).trim();
            if (!note) {
                showToast('请填写紧急回滚原因', 'error');
                return;
            }
            fetch('/api/teacher/adjustments/' + id + '/emergency-rollback', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ reason: note })
            })
            .then(r => r.json())
            .then(data => {
                showToast(data.message || (data.success ? '操作成功' : '操作失败'), data.success ? 'success' : 'error');
                if (data.success) {
                    myAdjustPage = 1;
                    loadMyAdjustments(1);
                    loadSchedule();
                }
            })
            .catch(() => showToast('网络异常', 'error'));
        }
