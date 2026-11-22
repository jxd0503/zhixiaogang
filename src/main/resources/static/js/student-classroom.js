/********** student-classroom：空教室查询、教室预约与我的预约 **********/

        let currentReserveClassroomId = null;
        let currentReserveClassroomNo = null;
        
        const PERIOD_NAMES = {
            1: '1-2节 (8:30-10:10)',
            2: '3-4节 (10:25-12:00)',
            3: '5-6节 (14:00-15:40)',
            4: '7-8节 (15:55-17:30)',
            5: '9-10节 (18:30-20:15)'
        };
        
        function updateTime() {
            const now = new Date();
            const hours = String(now.getHours()).padStart(2, '0');
            const minutes = String(now.getMinutes()).padStart(2, '0');
            const seconds = String(now.getSeconds()).padStart(2, '0');
            document.getElementById('currentTime').textContent = `${hours}:${minutes}:${seconds}`;
            
            const currentPeriod = calculateCurrentPeriod();
            const nextPeriod = calculateNextPeriod();
            document.getElementById('currentPeriod').textContent = currentPeriod > 0 ? PERIOD_NAMES[currentPeriod] : '无课程';
            renderNextPeriod(nextPeriod);
        }

        /** 渲染「下一节次」：全部节次结束后给出明天引导；已切到明天时收起引导条 */
        function renderNextPeriod(nextPeriod) {
            const nextPeriodEl = document.getElementById('nextPeriod');
            const banner = document.getElementById('periodEndedBanner');
            if (!nextPeriodEl) return;

            const dateInput = document.getElementById('classroomDate');
            const queryingToday = !dateInput || !dateInput.value || dateInput.value === formatLocalDate(new Date());
            const periodKey = nextPeriod > 0 ? 'period-' + nextPeriod : 'ended';

            if (nextPeriodEl.dataset.key !== periodKey) {
                nextPeriodEl.textContent = nextPeriod > 0 ? PERIOD_NAMES[nextPeriod] : '今天已无上课时段';
                nextPeriodEl.classList.toggle('next-period-empty', nextPeriod === 0);
                nextPeriodEl.dataset.key = periodKey;
            }
            if (banner) banner.hidden = !(nextPeriod === 0 && queryingToday);
        }

        /** 一键把教室查询日期切到明天并重新查询 */
        function switchClassroomDateToTomorrow() {
            const dateInput = document.getElementById('classroomDate');
            if (!dateInput) return;
            const tomorrow = new Date();
            tomorrow.setDate(tomorrow.getDate() + 1);
            dateInput.value = formatLocalDate(tomorrow);
            queryClassrooms();
        }
        
        function calculateCurrentPeriod() {
            const now = new Date();
            const hour = now.getHours();
            const minute = now.getMinutes();
            
            if (hour >= 8 && hour < 10 || (hour === 10 && minute <= 10)) return 1;
            if (hour >= 10 && hour < 12 || (hour === 12 && minute === 0)) return 2;
            if (hour >= 14 && hour < 15 || (hour === 15 && minute <= 40)) return 3;
            if (hour >= 15 && hour < 17 || (hour === 17 && minute <= 30)) return 4;
            if (hour >= 18 && hour < 20 || (hour === 20 && minute <= 15)) return 5;
            return 0;
        }
        
        function calculateNextPeriod() {
            const now = new Date();
            const hour = now.getHours();
            const minute = now.getMinutes();
            
            if (hour < 8 || (hour === 8 && minute < 30)) return 1;
            if (hour < 10 || (hour === 10 && minute < 25)) return 2;
            if (hour < 12 || (hour === 12 && minute < 30)) return 3;
            if (hour < 14) return 3;
            if (hour < 15 || (hour === 15 && minute < 55)) return 4;
            if (hour < 17 || (hour === 17 && minute < 30)) return 5;
            if (hour < 18 || (hour === 18 && minute < 30)) return 5;
            return 0;
        }
        
        function initClassroomQuery() {
            const dateInput = document.getElementById('classroomDate');
            if (dateInput && !dateInput.value) {
                const today = new Date();
                const y = today.getFullYear();
                const m = String(today.getMonth() + 1).padStart(2, '0');
                const d = String(today.getDate()).padStart(2, '0');
                dateInput.value = `${y}-${m}-${d}`;
            }
            queryClassrooms();
        }

        function queryClassrooms() {
            const building = document.getElementById('classroomBuilding').value;
            const date = document.getElementById('classroomDate').value;
            const period = document.getElementById('classroomPeriod').value;
            // 日期变化后立即刷新「下一节次」引导（切到非今天时收起）
            renderNextPeriod(calculateNextPeriod());
            const params = new URLSearchParams();
            if (building) params.append('building', building);
            if (date) params.append('date', date);
            if (period) params.append('period', period);
            const qs = params.toString();
            const url = '/api/services/classrooms/available' + (qs ? '?' + qs : '');

            fetch(url)
                .then(response => response.json())
                .then(data => {
                    if (data.success) {
                        const periodLabel = document.getElementById('periodDisplayLabel');
                        const periodContainer = document.getElementById('currentPeriodLabel');
                        periodLabel.textContent = data.periodName
                            ? `查询: ${data.date} ${data.periodName}`
                            : `查询: ${data.date} 全部节次`;
                        periodContainer.style.display = 'block';

                        renderClassroomList(data.data);
                    } else {
                        document.getElementById('currentPeriodLabel').style.display = 'none';
                        showEmptyState(data.message);
                    }
                })
                .catch(error => {
                    console.error('查询失败:', error);
                    showEmptyState('查询失败，请稍后重试');
                });
        }

        function refreshClassrooms() {
            queryClassrooms();
        }
        
        function renderClassroomList(classrooms) {
            const list = document.getElementById('classroomList');
            
            if (!classrooms || classrooms.length === 0) {
                showEmptyState('当前时间段没有空教室');
                return;
            }
            
            list.innerHTML = '';
            
            classrooms.forEach(classroom => {
                const card = document.createElement('div');
                const isOccupied = classroom.available === false;
                card.className = 'classroom-card' + (isOccupied ? ' classroom-occupied' : '');
                
                const roomType = classroom.room_type || '普通教室';
                const statusBadge = isOccupied
                    ? `<span class="classroom-status occupied">不可预约</span>`
                    : `<span class="classroom-status available">可预约</span>`;
                const reserveBtn = isOccupied
                    ? `<button class="btn-reserve" disabled>不可预约</button>`
                    : `<button class="btn-reserve" onclick="showReserveModal(${classroom.id}, '${classroom.classroom_no}')">预约</button>`;
                const occupiedInfo = isOccupied && classroom.occupiedReason
                    ? `<div class="classroom-occupied-reason">${classroom.occupiedReason}</div>` : '';
                
                card.innerHTML = `
                    <div class="classroom-header">
                        <div>
                            <div class="classroom-no">${classroom.classroom_no}</div>
                            <div class="classroom-building">${classroom.building}楼 ${classroom.floor || ''}层</div>
                        </div>
                        ${statusBadge}
                    </div>
                    ${occupiedInfo}
                    <div class="classroom-details">
                        <div class="detail-item"><span class="icon"></span> ${formatCapacity(classroom.capacity)}</div>
                        <div class="detail-item"><span class="icon"></span> ${roomType}</div>
                    </div>
                    <div class="classroom-actions">
                        ${reserveBtn}
                        <button class="btn-info" onclick="showClassroomDetail(${classroom.id})">详情</button>
                    </div>
                `;
                
                list.appendChild(card);
                checkMarquee(card.querySelector('.classroom-no'));
            });
        }

        function checkMarquee(el) {
            if (!el) return;
            requestAnimationFrame(() => {
                const full = el.textContent.trim();
                if (full) el.title = full;
                const win = el.parentElement;
                const textW = el.offsetWidth;
                const winW = win.clientWidth;
                if (textW > winW + 2) {
                    const dist = textW - winW;
                    const dur = Math.min(16, Math.max(8, dist / 20));
                    el.style.setProperty('--marquee-distance', dist + 'px');
                    el.style.setProperty('--marquee-dur', dur + 's');
                    el.classList.add('scroll-marquee');
                }
            });
        }

        function showEmptyState(message) {
            const list = document.getElementById('classroomList');
            list.innerHTML = `
                <div class="empty-state">
                    <div class="empty-icon"></div>
                    <p>${message}</p>
                </div>
            `;
        }
        
        /** 将输入的日期规范为 YYYY-MM-DD；格式错误或日期不存在返回 null */
        function normalizeDateText(raw) {
            const s = String(raw || '').trim().replace(/[./]/g, '-');
            const m = s.match(/^(\d{4})-(\d{1,2})-(\d{1,2})$/);
            if (!m) return null;
            const y = Number(m[1]), mo = Number(m[2]), d = Number(m[3]);
            const dt = new Date(y, mo - 1, d);
            if (dt.getFullYear() !== y || dt.getMonth() !== mo - 1 || dt.getDate() !== d) return null;
            return y + '-' + String(mo).padStart(2, '0') + '-' + String(d).padStart(2, '0');
        }

        function setReserveDateError(msg) {
            const input = document.getElementById('reserveDate');
            const err = document.getElementById('reserveDateError');
            if (!input || !err) return;
            if (msg) {
                input.classList.add('invalid');
                err.textContent = msg;
                err.classList.add('show');
            } else {
                input.classList.remove('invalid');
                err.textContent = '';
                err.classList.remove('show');
            }
        }

        /**
         * 校验预约日期输入
         * @param {boolean} requireValue 为 true 时空值也提示（提交时用），为 false 时空值不打扰用户（失焦时用）
         */
        function checkReserveDate(requireValue) {
            const input = document.getElementById('reserveDate');
            const raw = input.value.trim();
            if (!raw) {
                setReserveDateError(requireValue ? '请输入预约日期，格式如 2026-10-24' : '');
                return !requireValue;
            }
            const normalized = normalizeDateText(raw);
            if (!normalized) {
                setReserveDateError('日期格式不正确，请按 YYYY-MM-DD 输入，如 2026-10-24');
                return false;
            }
            input.value = normalized;
            setReserveDateError('');
            return true;
        }

        function openReserveDatePicker() {
            const input = document.getElementById('reserveDate');
            const picker = document.getElementById('reserveDatePicker');
            if (!picker) { input.focus(); return; }
            picker.value = normalizeDateText(input.value) || formatLocalDate(new Date());
            if (typeof picker.showPicker === 'function') {
                try { picker.showPicker(); return; } catch (e) { /* 浏览器不支持时回退到手动输入 */ }
            }
            input.focus();
        }

        function applyReserveDateFromPicker(picker) {
            if (!picker.value) return;
            document.getElementById('reserveDate').value = picker.value;
            setReserveDateError('');
        }

        function showReserveModal(classroomId, classroomNo) {
            currentReserveClassroomId = classroomId;
            currentReserveClassroomNo = classroomNo;
            
            document.getElementById('reserveClassroom').value = classroomNo;
            // 每次打开弹窗重置表单，避免残留上次填写的原因
            document.getElementById('reserveReason').value = '';
            updateReserveReasonHint();
            setReserveDateError('');
            
            const now = new Date();
            // 继承当前查询的日期与节次；查询未选节次时按最近可预约时段兜底
            const queryDate = (document.getElementById('classroomDate') || {}).value || '';
            const queryPeriod = parseInt((document.getElementById('classroomPeriod') || {}).value, 10);
            let targetDate;
            let nextPeriod;
            if (queryDate) {
                targetDate = parseLocalDate(queryDate);
                if (queryPeriod > 0) {
                    nextPeriod = queryPeriod;
                } else if (queryDate === formatLocalDate(now)) {
                    nextPeriod = calculateNextPeriod();
                    if (nextPeriod === 0) nextPeriod = 1;
                } else {
                    nextPeriod = 1;
                }
            } else {
                // 无查询条件时：默认日期今天；今天已过全部节次则推到明天
                targetDate = new Date(now.getFullYear(), now.getMonth(), now.getDate());
                nextPeriod = calculateNextPeriod();
                if (nextPeriod === 0) {
                    targetDate.setDate(targetDate.getDate() + 1);
                    nextPeriod = 1;
                }
            }
            document.getElementById('reserveDate').value = formatLocalDate(targetDate);
            document.getElementById('reserveStartPeriod').value = nextPeriod;
            document.getElementById('reserveEndPeriod').value = nextPeriod;
            
            const hh = String(now.getHours()).padStart(2, '0');
            const mm = String(now.getMinutes()).padStart(2, '0');
            const hint = document.getElementById('reserveTimeHint');
            if (hint) {
                hint.textContent = `当前时间 ${hh}:${mm}，已默认选择之后最近的可预约时段；今天已过去的节次不可预约`;
            }
            
            document.getElementById('reserveModal').classList.add('show');
        }
        
        /** 按本地时区格式化为 yyyy-MM-dd（避免 toISOString 的 UTC 偏移导致日期错一天） */
        function formatLocalDate(date) {
            const y = date.getFullYear();
            const m = String(date.getMonth() + 1).padStart(2, '0');
            const d = String(date.getDate()).padStart(2, '0');
            return `${y}-${m}-${d}`;
        }

        /** 把 yyyy-MM-dd 解析为本地时区日期，避免 new Date('yyyy-MM-dd') 按 UTC 解析 */
        function parseLocalDate(text) {
            const parts = String(text || '').split('-').map(Number);
            if (parts.length !== 3 || parts.some(isNaN)) return new Date();
            return new Date(parts[0], parts[1] - 1, parts[2]);
        }
        
        function closeReserveModal() {
            document.getElementById('reserveModal').classList.remove('show');
            currentReserveClassroomId = null;
            currentReserveClassroomNo = null;
        }
        
        // 预约原因长度：上限 100 字，实时提示已输入字数
        const RESERVE_REASON_MAX = 100;

        function updateReserveReasonHint() {
            const hint = document.getElementById('reserveReasonHint');
            const input = document.getElementById('reserveReason');
            if (!hint || !input) return;
            const len = input.value.length;
            if (len > RESERVE_REASON_MAX) {
                hint.textContent = '最多 100 字，已超出 ' + (len - RESERVE_REASON_MAX) + ' 字';
                hint.classList.add('is-over');
            } else {
                hint.textContent = '最多 100 字（已输入 ' + len + '/' + RESERVE_REASON_MAX + '）';
                hint.classList.remove('is-over');
            }
        }

        // 粘贴超长内容时直接拦截，避免静默截断后误提交
        function handleReserveReasonPaste(event) {
            const input = event.target;
            const pasted = (event.clipboardData || window.clipboardData).getData('text') || '';
            if (input.value.length + pasted.length > RESERVE_REASON_MAX) {
                event.preventDefault();
                showToast('预约原因最多 100 字，请精简后再粘贴', 'error', 4000);
                updateReserveReasonHint();
            }
        }

        function submitReservation() {
            if (!currentReserveClassroomId) {
                showToast('请选择要预约的教室', 'warning');
                return;
            }
            
            let reservationDate = document.getElementById('reserveDate').value;
            const startPeriod = parseInt(document.getElementById('reserveStartPeriod').value);
            const endPeriod = parseInt(document.getElementById('reserveEndPeriod').value);
            const reason = document.getElementById('reserveReason').value.trim();
            
            if (!checkReserveDate(true)) {
                showToast('预约日期格式不正确，请按 YYYY-MM-DD 输入', 'warning');
                return;
            }
            reservationDate = document.getElementById('reserveDate').value;
            
            if (startPeriod > endPeriod) {
                showToast('开始节次不能大于结束节次', 'warning');
                return;
            }
            
            if (!reason) {
                showToast('请输入预约原因', 'warning');
                return;
            }

            if (reason.length > RESERVE_REASON_MAX) {
                updateReserveReasonHint();
                showToast('预约原因过长，请控制在 100 字以内', 'error', 4000);
                return;
            }
            
            fetch('/api/services/classrooms/reserve', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    classroomId: currentReserveClassroomId,
                    reservationDate: reservationDate,
                    startPeriod: startPeriod,
                    endPeriod: endPeriod,
                    reason: reason
                })
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    showToast((data.message || '预约申请已提交') + '，可在「我的预约」查看', 'success');
                    closeReserveModal();
                    queryClassrooms();
                } else {
                    showToast('预约失败：' + data.message, 'error', 4000);
                }
            })
            .catch(error => {
                console.error('预约失败:', error);
                showToast('预约失败，请稍后重试', 'error', 4000);
            });
        }
        
        const RESV_STATUS_TEXT = { pending: '待审核', approved: '已通过', rejected: '已拒绝', cancelled: '已取消' };
        
        function formatResvPeriod(start, end) {
            const label = p => {
                const name = PERIOD_NAMES[p];
                return name ? name.split(' ')[0] : (p ? p + '节' : '-');
            };
            return start === end ? label(start) : label(start) + ' ~ ' + label(end);
        }
        
        let myReservationPage = 1;
        const MY_RESERVATION_PAGE_SIZE = 10;

        function loadMyReservations(page) {
            if (page != null) myReservationPage = page;
            const tbody = document.getElementById('myReservationBody');
            if (!tbody) return;
            tbody.innerHTML = '<tr><td colspan="6"><div class="empty-state"><p>加载中...</p></div></td></tr>';
            const params = new URLSearchParams({ page: myReservationPage, size: MY_RESERVATION_PAGE_SIZE });
            fetch('/api/services/classrooms/my-reservations?' + params.toString())
                .then(response => response.json())
                .then(data => {
                    if (!data.success) {
                        tbody.innerHTML = `<tr><td colspan="6"><div class="empty-state"><p>${escapeHtml(data.message || '加载失败')}</p></div></td></tr>`;
                        return;
                    }
                    const total = Number(data.total) || 0;
                    // 取消预约后当前页可能越界，回退到最后一页
                    if (myReservationPage > 1 && (myReservationPage - 1) * MY_RESERVATION_PAGE_SIZE >= total) {
                        myReservationPage = Math.max(1, Math.ceil(total / MY_RESERVATION_PAGE_SIZE));
                        loadMyReservations();
                        return;
                    }
                    renderMyReservations(data.reservations || []);
                    renderPager('myReservationPager', data.page, data.size, total, function (p) {
                        loadMyReservations(p);
                    });
                })
                .catch(error => {
                    console.error('加载我的预约失败:', error);
                    tbody.innerHTML = '<tr><td colspan="6"><div class="empty-state"><p>加载失败，请稍后重试</p></div></td></tr>';
                });
        }
        
        function renderMyReservations(list) {
            const tbody = document.getElementById('myReservationBody');
            if (!tbody) return;
            if (!list.length) {
                tbody.innerHTML = '<tr><td colspan="6"><div class="empty-state"><p>暂无预约记录，可在「教室查询」中预约教室</p></div></td></tr>';
                return;
            }
            tbody.innerHTML = list.map(item => {
                const status = item.status || '';
                const statusText = RESV_STATUS_TEXT[status] || status || '-';
                const action = status === 'pending'
                    ? `<button class="btn-cancel-resv" onclick="cancelMyReservation(${item.id})">取消申请</button>`
                    : '<span class="resv-noop">—</span>';
                return `<tr>
                    <td class="resv-strong">${escapeHtml(item.classroomNo || '-')}</td>
                    <td>${escapeHtml(item.reservationDate || '-')}</td>
                    <td>${formatResvPeriod(item.startPeriod, item.endPeriod)}</td>
                    <td class="resv-reason">${escapeHtml(item.reason || '-')}</td>
                    <td><span class="resv-status ${escapeHtml(status)}">${statusText}</span></td>
                    <td>${action}</td>
                </tr>`;
            }).join('');
        }
        
        function cancelMyReservation(id) {
            showConfirm({
                title: '取消预约申请',
                message: '确定要取消该预约申请吗？取消后不可恢复。',
                confirmText: '取消申请',
                cancelText: '再想想',
                danger: true
            }).then(ok => {
                if (!ok) return;
                fetch(`/api/services/classrooms/my-reservations/${id}/cancel`, { method: 'POST' })
                    .then(response => response.json())
                    .then(data => {
                        if (data.success) {
                            showToast('已取消预约，可在「我的预约」查看', 'success');
                        } else {
                            showToast(data.message || '取消失败', 'error');
                        }
                        loadMyReservations();
                    })
                    .catch(error => {
                        console.error('取消预约失败:', error);
                        showToast('网络异常，请稍后重试', 'error');
                    });
            });
        }
        
        function showClassroomDetail(classroomId) {
            fetch(`/api/services/classrooms?id=${classroomId}`)
                .then(response => response.json())
                .then(data => {
                    if (data.success && data.data) {
                        const classroom = data.data[0];
                        const info = `
                            教室编号：${classroom.classroom_no}
                            楼栋：${classroom.building}楼
                            楼层：${classroom.floor}层
                            容纳人数：${formatCapacity(classroom.capacity)}
                            教室类型：${classroom.room_type || '普通教室'}
                            状态：${classroom.status === 'available' ? '可用' : classroom.status === 'maintenance' ? '维修中' : '占用'}
                            描述：${classroom.description || '无'}
                        `;
                        showToast(info, 'info', 3000);
                    }
                })
                .catch(error => {
                    console.error('获取详情失败:', error);
                    showToast('获取详情失败，请稍后重试', 'error');
                });
        }
        
        
        setInterval(updateTime, 1000);
        updateTime();

        // ===================== 教室课表（三端只读，管理端统一维护） =====================
