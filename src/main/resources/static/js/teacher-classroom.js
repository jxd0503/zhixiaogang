/********** teacher-classroom：空教室查询与教室预约 **********/

        // ============ 空教室查询（与学生端一致：教学楼 × 日期 × 节次） ============
        let currentReserveClassroomId = null;

        function calculateNextPeriod() {
            const now = new Date();
            const hour = now.getHours();
            const minute = now.getMinutes();
            if (hour < 8 || (hour === 8 && minute < 30)) return 1;
            if (hour < 10 || (hour === 10 && minute < 25)) return 2;
            if (hour < 14) return 3;
            if (hour < 15 || (hour === 15 && minute < 55)) return 4;
            if (hour < 18 || (hour === 18 && minute < 30)) return 5;
            return 0;
        }

        function initEmptyClassroomQuery() {
            const dateInput = document.getElementById('ecDate');
            if (dateInput && !dateInput.value) {
                const today = new Date();
                const y = today.getFullYear();
                const m = String(today.getMonth() + 1).padStart(2, '0');
                const d = String(today.getDate()).padStart(2, '0');
                dateInput.value = `${y}-${m}-${d}`;
            }
            loadEmptyClassrooms();
        }

        function loadEmptyClassrooms() {
            const building = document.getElementById('ecBuilding').value;
            const date = document.getElementById('ecDate').value;
            const period = document.getElementById('ecPeriod').value;

            const params = new URLSearchParams();
            if (building) params.append('building', building);
            if (date) params.append('date', date);
            if (period) params.append('period', period);

            const grid = document.getElementById('classroomGrid');
            grid.innerHTML = '<div class="empty-hint">查询中...</div>';

            fetch('/api/services/classrooms/available?' + params.toString())
                .then(r => r.json())
                .then(data => {
                    const label = document.getElementById('ecQueryLabel');
                    if (data.success) {
                        document.getElementById('ecQueryLabelText').textContent = data.periodName
                            ? `查询: ${data.date} ${data.periodName}` : `查询: ${data.date} 全部节次`;
                        label.style.display = 'block';
                        renderEmptyClassroomList(data.data);
                    } else {
                        label.style.display = 'none';
                        grid.innerHTML = `<div class="empty-hint">${escapeHtml(data.message || '查询失败')}</div>`;
                    }
                })
                .catch(err => {
                    grid.innerHTML = '<div class="empty-hint">查询失败：网络异常</div>';
                });
        }

        function refreshEmptyClassrooms() { loadEmptyClassrooms(); }

        function renderEmptyClassroomList(classrooms) {
            const grid = document.getElementById('classroomGrid');
            if (!classrooms || classrooms.length === 0) {
                grid.innerHTML = '<div class="empty-state"><p>当前时间段没有空教室</p></div>';
                return;
            }
            grid.innerHTML = classrooms.map(c => {
                const roomType = c.room_type || '普通教室';
                const isOccupied = c.available === false;
                const statusBadge = isOccupied
                    ? `<span class="classroom-status occupied">不可预约</span>`
                    : `<span class="classroom-status available">可预约</span>`;
                const reserveBtn = isOccupied
                    ? `<button class="btn-reserve" disabled>不可预约</button>`
                    : `<button class="btn-reserve" onclick="openReserveModal(${c.id}, '${escapeHtml(c.classroom_no)}')">预约</button>`;
                const occupiedInfo = isOccupied && c.occupiedReason
                    ? `<div class="classroom-occupied-reason">${escapeHtml(c.occupiedReason)}</div>` : '';
                const cardCls = isOccupied ? 'classroom-card classroom-occupied' : 'classroom-card';
                return `<div class="${cardCls}">
                    <div class="classroom-header">
                        <div>
                            <div class="classroom-no">${escapeHtml(c.classroom_no || '-')}</div>
                            <div class="classroom-building">${escapeHtml(c.building || '-')}楼 ${escapeHtml(c.floor || '')}层</div>
                        </div>
                        ${statusBadge}
                    </div>
                    ${occupiedInfo}
                    <div class="classroom-details">
                        <div class="detail-item"><span class="icon"></span> ${formatCapacity(c.capacity)}</div>
                        <div class="detail-item"><span class="icon"></span> ${escapeHtml(roomType)}</div>
                    </div>
                    <div class="classroom-actions">
                        ${reserveBtn}
                        <button class="btn-info" onclick="showEmptyClassroomDetail(${c.id})">详情</button>
                    </div>
                </div>`;
            }).join('');
            grid.querySelectorAll('.classroom-no').forEach(el => checkMarquee(el));
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

        function showEmptyClassroomDetail(classroomId) {
            fetch(`/api/services/classrooms?id=${classroomId}`)
                .then(r => r.json())
                .then(data => {
                    const body = document.getElementById('classroomDetailBody');
                    if (data.success && data.data && data.data.length) {
                        const c = data.data[0];
                        const statusText = c.status === 'available' ? '可用' : c.status === 'maintenance' ? '维修中' : '占用';
                        const rows = [
                            ['教室编号', c.classroom_no || '-'],
                            ['楼栋', (c.building || '-') + '楼'],
                            ['楼层', (c.floor || '-') + '层'],
                            ['容纳人数', formatCapacity(c.capacity)],
                            ['教室类型', c.room_type || '普通教室'],
                            ['状态', statusText],
                            ['描述', c.description || '无']
                        ];
                        body.innerHTML = rows.map(([k, v]) =>
                            `<span style="color:#8a8a86;">${escapeHtml(k)}</span><span>${escapeHtml(String(v))}</span>`
                        ).join('');
                    } else {
                        body.innerHTML = '<span></span><span>获取详情失败，请稍后重试</span>';
                    }
                    document.getElementById('classroomDetailModal').classList.add('show');
                })
                .catch(() => {
                    document.getElementById('classroomDetailBody').innerHTML = '<span></span><span>获取详情失败，请稍后重试</span>';
                    document.getElementById('classroomDetailModal').classList.add('show');
                });
        }

        function closeClassroomDetailModal() {
            document.getElementById('classroomDetailModal').classList.remove('show');
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
            const now = new Date();
            const pad = n => String(n).padStart(2, '0');
            picker.value = normalizeDateText(input.value) ||
                (now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate()));
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

        function openReserveModal(classroomId, classroomNo) {
            currentReserveClassroomId = classroomId;
            document.getElementById('reserveClassroomNo').value = classroomNo;
            setReserveDateError('');
            // 继承当前查询的日期与节次；查询未选节次时按最近可预约时段兜底
            const now = new Date();
            const queryDate = (document.getElementById('ecDate') || {}).value || '';
            const queryPeriod = parseInt((document.getElementById('ecPeriod') || {}).value, 10);
            let targetDate;
            let nextPeriod;
            if (queryDate) {
                const parts = queryDate.split('-').map(Number);
                targetDate = parts.length === 3 && !parts.some(isNaN)
                    ? new Date(parts[0], parts[1] - 1, parts[2])
                    : new Date(now.getFullYear(), now.getMonth(), now.getDate());
                if (queryPeriod > 0) {
                    nextPeriod = queryPeriod;
                } else {
                    nextPeriod = calculateNextPeriod();
                    if (nextPeriod === 0) nextPeriod = 1;
                }
            } else {
                // 无查询条件时：默认今天之后最近的可预约时段（全部节次已过则顺延到明天第 1 节）
                targetDate = new Date(now.getFullYear(), now.getMonth(), now.getDate());
                nextPeriod = calculateNextPeriod();
                if (nextPeriod === 0) {
                    targetDate.setDate(targetDate.getDate() + 1);
                    nextPeriod = 1;
                }
            }
            const pad = n => String(n).padStart(2, '0');
            document.getElementById('reserveDate').value =
                targetDate.getFullYear() + '-' + pad(targetDate.getMonth() + 1) + '-' + pad(targetDate.getDate());
            document.getElementById('reserveStartPeriod').value = nextPeriod;
            document.getElementById('reserveEndPeriod').value = nextPeriod;
            document.getElementById('reserveReason').value = '';
            document.getElementById('reserveResult').className = 'import-result';
            document.getElementById('reserveResult').innerHTML = '';
            document.getElementById('reserveModal').classList.add('show');
        }

        function closeReserveModal() {
            document.getElementById('reserveModal').classList.remove('show');
            currentReserveClassroomId = null;
        }

        function submitReserve() {
            let date = document.getElementById('reserveDate').value;
            const startPeriod = parseInt(document.getElementById('reserveStartPeriod').value);
            const endPeriod = parseInt(document.getElementById('reserveEndPeriod').value);
            const reason = document.getElementById('reserveReason').value.trim();
            const resultBox = document.getElementById('reserveResult');
            const btn = document.getElementById('reserveConfirmBtn');

            if (!currentReserveClassroomId) {
                resultBox.className = 'import-result show err';
                resultBox.innerHTML = '请选择教室';
                return;
            }
            if (!checkReserveDate(true)) {
                return;
            }
            date = document.getElementById('reserveDate').value;
            if (startPeriod > endPeriod) {
                resultBox.className = 'import-result show err';
                resultBox.innerHTML = '开始节次不能大于结束节次';
                return;
            }
            if (!reason) {
                resultBox.className = 'import-result show err';
                resultBox.innerHTML = '请填写预约原因';
                return;
            }

            btn.disabled = true;
            btn.textContent = '提交中...';
            resultBox.className = 'import-result';
            resultBox.innerHTML = '';

            fetch('/api/teacher/reserve-classroom', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    classroomId: currentReserveClassroomId,
                    reservationDate: date,
                    startPeriod: startPeriod,
                    endPeriod: endPeriod,
                    reason: reason
                })
            })
            .then(r => r.json())
            .then(data => {
                btn.disabled = false;
                btn.textContent = '提交预约';
                if (data.success) {
                    resultBox.className = 'import-result show ok';
                    resultBox.innerHTML = escapeHtml(data.message || '预约提交成功');
                    setTimeout(() => closeReserveModal(), 1500);
                } else {
                    resultBox.className = 'import-result show err';
                    resultBox.innerHTML = escapeHtml(data.message || '预约失败');
                }
            })
            .catch(err => {
                btn.disabled = false;
                btn.textContent = '提交预约';
                resultBox.className = 'import-result show err';
                resultBox.innerHTML = '网络异常，请稍后重试';
            });
        }
