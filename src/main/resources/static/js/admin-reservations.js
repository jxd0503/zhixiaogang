/********** 预约审核：教室预约列表、筛选与批准 / 拒绝 / 取消 **********/
        let reservationPage = 1;
        const RESERVATION_PAGE_SIZE = 10;
        let reservationSearchTimer = null;

        function loadReservations() {
            const keyword = document.getElementById('reservationSearch').value.trim();
            const status = document.getElementById('filterReservationStatus').value;
            const date = document.getElementById('filterReservationDate').value;
            const params = new URLSearchParams({ page: reservationPage, size: RESERVATION_PAGE_SIZE });
            if (status) params.set('status', status);
            if (date) params.set('date', date);
            if (keyword) params.set('keyword', keyword);
            fetch('/admin/reservations?' + params.toString())
                .then(response => response.json())
                .then(data => {
                    if (!data.success) return;
                    // 删除/筛选后当前页可能越界，回到最后一页
                    const total = Number(data.total) || 0;
                    if (reservationPage > 1 && (reservationPage - 1) * RESERVATION_PAGE_SIZE >= total) {
                        reservationPage = Math.max(1, Math.ceil(total / RESERVATION_PAGE_SIZE));
                        loadReservations();
                        return;
                    }
                    renderReservations(data.reservations || []);
                    renderPager('reservationPager', data.page, data.size, total, function (p) {
                        reservationPage = p;
                        loadReservations();
                    });
                });
        }

        function renderReservations(list) {
            const tbody = document.getElementById('reservationTableBody');
            tbody.innerHTML = '';
            if (!list.length) {
                tbody.innerHTML = '<tr><td colspan="9" class="empty-hint">暂无符合条件的预约记录</td></tr>';
                return;
            }
            list.forEach(r => {
                const statusClass = r.status === 'pending' ? 'pending' : (r.status === 'approved' ? 'approved' : (r.status === 'cancelled' ? 'closed' : 'rejected'));
                const statusText = r.status === 'pending' ? '待审核' : (r.status === 'approved' ? '已通过' : (r.status === 'cancelled' ? '已撤销' : '已拒绝'));
                // 预约表存节次段索引（1=1-2节…5=9-10节），展示为实际节次
                const actualStart = r.startPeriod == null ? '' : (Number(r.startPeriod) - 1) * 2 + 1;
                const actualEnd = r.endPeriod == null ? '' : Number(r.endPeriod) * 2;
                tbody.innerHTML += `
                    <tr>
                        <td>${escapeHtml(r.id)}</td>
                        <td>${escapeHtml(r.studentId)}</td>
                        <td>${escapeHtml(r.studentName)}</td>
                        <td>${escapeHtml(r.classroomNo)}</td>
                        <td>${escapeHtml(r.reservationDate)}</td>
                        <td>第${actualStart}-${actualEnd}节</td>
                        <td>${escapeHtml(r.reason)}</td>
                        <td><span class="status-badge ${statusClass}">${statusText}</span></td>
                        <td>
                            ${r.status === 'pending' ? `
                                <button class="action-btn edit" data-res-action="approve" data-res-id="${escapeHtml(r.id)}">通过</button>
                                <button class="action-btn delete" data-res-action="reject" data-res-id="${escapeHtml(r.id)}">拒绝</button>
                            ` : r.status === 'approved' ? `
                                <button class="action-btn delete" data-res-action="cancel" data-res-id="${escapeHtml(r.id)}">撤销</button>
                            ` : r.status === 'rejected' ? `
                                <button class="action-btn edit" data-res-action="approve" data-res-id="${escapeHtml(r.id)}">通过</button>
                            ` : ''}
                        </td>
                    </tr>
                `;
            });
        }

        // 预约操作事件委托：预约内容一律转义渲染，操作按钮通过 data-* 传递 id
        document.addEventListener('click', function (e) {
            const btn = e.target.closest('[data-res-action]');
            if (!btn) return;
            const id = btn.getAttribute('data-res-id');
            const action = btn.getAttribute('data-res-action');
            if (action === 'approve') approveReservation(id);
            else if (action === 'reject') rejectReservation(id);
            else if (action === 'cancel') cancelReservation(id);
        });

        function filterReservations() {
            clearTimeout(reservationSearchTimer);
            reservationSearchTimer = setTimeout(function () {
                reservationPage = 1;
                loadReservations();
            }, 250);
        }

        function resetReservationFilters() {
            document.getElementById('reservationSearch').value = '';
            document.getElementById('filterReservationStatus').value = '';
            document.getElementById('filterReservationDate').value = '';
            reservationPage = 1;
            loadReservations();
        }
        
        function approveReservation(id) {
            fetch(`/admin/reservations/${id}/approve`, { method: 'POST' })
                .then(response => response.json())
                .then(data => {
                    if (data.success) {
                        showToast('审核通过', 'success');
                        loadReservations();
                    } else {
                        showToast('操作失败', 'error');
                    }
                });
        }
        
        async function rejectReservation(id) {
            const reason = (await showPrompt({
                title: '拒绝预约申请',
                message: '请输入拒绝原因（可留空）'
            })) || '';
            fetch(`/admin/reservations/${id}/reject`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ reason: reason })
            })
                .then(response => response.json())
                .then(data => {
                    if (data.success) {
                        showToast('已拒绝', 'success');
                        loadReservations();
                    } else {
                        showToast(data.message || '操作失败', 'error');
                    }
                });
        }

        async function cancelReservation(id) {
            const ok = await showConfirm({
                title: '撤销预约',
                message: '确定撤销该已通过的预约？撤销后将通知预约发起人。',
                confirmText: '撤销',
                danger: true
            });
            if (!ok) return;
            const reason = (await showPrompt({
                title: '撤销预约',
                message: '请输入撤销原因（可留空）'
            })) || '';
            fetch(`/admin/reservations/${id}/cancel`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ reason: reason })
            })
                .then(response => response.json())
                .then(data => {
                    if (data.success) {
                        showToast(data.message || '已撤销', 'success');
                        loadReservations();
                    } else {
                        showToast(data.message || '操作失败', 'error');
                    }
                });
        }
