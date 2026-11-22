/********** 教室管理：教室列表与筛选、教室新增 / 编辑 / 删除、占用矩阵弹窗 **********/
        let allClassrooms = [];
        let occupancyMap = {};

        function loadClassrooms() {
            Promise.all([
                fetch('/admin/classrooms').then(r => r.json()),
                fetch('/admin/classrooms/occupancy').then(r => r.json())
            ]).then(([classroomData, occupancyData]) => {
                if (classroomData.success) {
                    allClassrooms = classroomData.classrooms;
                    refreshRoomTypeOptions();
                    filterClassrooms();
                }
                occupancyMap = {};
                if (occupancyData.success && Array.isArray(occupancyData.data)) {
                    occupancyData.data.forEach(item => { occupancyMap[item.id] = item; });
                    filterClassrooms();
                }
            });
        }

        function renderClassrooms(list) {
            const tbody = document.getElementById('classroomTableBody');
            // 数据库存英文状态码，表格列显示中文（筛选与编辑弹窗仍用英文值，不受影响）
            const statusMap = { available: '可用', occupied: '占用', repair: '维修中', maintenance: '维修中' };
            tbody.innerHTML = '';
            if (!list || list.length === 0) {
                tbody.innerHTML = '<tr><td colspan="7" style="text-align:center;color:#999;padding:24px;">暂无符合条件的教室</td></tr>';
                return;
            }
            list.forEach(c => {
                const occ = occupancyMap[c.id];
                const schedulePeriods = occ ? occ.schedulePeriods : null;
                const occupancyCell = schedulePeriods == null
                    ? '<span class="occ-unknown">-</span>'
                    : (schedulePeriods > 0 ? `<span class="occ-busy">${schedulePeriods} 节</span>` : '<span class="occ-free">空闲</span>');
                tbody.innerHTML += `
                    <tr>
                        <td>${c.classroomNo}</td>
                        <td>${c.building}</td>
                        <td>${formatCapacity(c.capacity)}</td>
                        <td>${c.roomType || '普通教室'}</td>
                        <td>${statusMap[c.status] || c.status || '-'}</td>
                        <td>${occupancyCell}</td>
                        <td>
                            <button class="action-btn delete" onclick="openOccupancyModal('${c.id}')">占用情况</button>
                            <button class="action-btn edit" onclick="editClassroom('${c.id}')">编辑</button>
                            <button class="action-btn delete" onclick="deleteClassroom('${c.id}')">删除</button>
                        </td>
                    </tr>
                `;
            });
        }

        /** 教室类型筛选选项：列出当前教室中实际存在的类型 */
        function refreshRoomTypeOptions() {
            const sel = document.getElementById('filterRoomType');
            if (!sel) return;
            const current = sel.value;
            const types = [...new Set(allClassrooms.map(c => (c.roomType || '普通教室').trim()).filter(Boolean))]
                .sort((a, b) => a.localeCompare(b, 'zh-CN'));
            sel.innerHTML = '<option value="">全部教室类型</option>' +
                types.map(t => `<option value="${escapeHtml(t)}">${escapeHtml(t)}</option>`).join('');
            if (types.includes(current)) sel.value = current;
        }

        function filterClassrooms() {
            const keyword = document.getElementById('classroomSearch').value.trim().toLowerCase();
            const building = document.getElementById('filterBuilding').value;
            const roomType = document.getElementById('filterRoomType').value;
            const status = document.getElementById('filterClassroomStatus').value;
            const filtered = allClassrooms.filter(c => {
                if (building && c.building !== building) return false;
                if (roomType && (c.roomType || '普通教室') !== roomType) return false;
                if (status && c.status !== status) return false;
                if (keyword) {
                    const hit = (c.classroomNo && c.classroomNo.toLowerCase().includes(keyword)) ||
                                (c.building && c.building.toLowerCase().includes(keyword));
                    if (!hit) return false;
                }
                return true;
            });
            renderClassrooms(filtered);
        }

        function resetClassroomFilters() {
            document.getElementById('classroomSearch').value = '';
            document.getElementById('filterBuilding').value = '';
            document.getElementById('filterRoomType').value = '';
            document.getElementById('filterClassroomStatus').value = '';
            renderClassrooms(allClassrooms);
        }

        // ===================== 教室占用明细 =====================

        const OCC_DAY_NAMES = { 1: '周一', 2: '周二', 3: '周三', 4: '周四', 5: '周五', 6: '周六', 7: '周日' };
        const OCC_PERIOD_NAMES = { 1: '1-2节', 2: '3-4节', 3: '5-6节', 4: '7-8节', 5: '9-10节' };

        function openOccupancyModal(classroomId) {
            const modal = document.getElementById('occupancyModal');
            const body = document.getElementById('occupancyModalBody');
            document.getElementById('occupancyModal').querySelector('h3').textContent = '教室占用情况';
            body.innerHTML = '<p class="empty-hint">加载中...</p>';
            modal.classList.add('show');
            fetch(`/admin/classrooms/${classroomId}/occupancy`)
                .then(r => r.json())
                .then(data => {
                    if (!data.success) {
                        body.innerHTML = `<p class="empty-hint">${escapeHtml(data.message || '加载失败')}</p>`;
                        return;
                    }
                    renderOccupancyDetail(data);
                })
                .catch(() => {
                    body.innerHTML = '<p class="empty-hint">加载失败，请稍后重试</p>';
                });
        }

        function closeOccupancyModal() {
            document.getElementById('occupancyModal').classList.remove('show');
        }

        function renderOccupancyDetail(data) {
            const body = document.getElementById('occupancyModalBody');
            const classroom = data.classroom || {};
            const occupancy = data.occupancy || [];
            const scheduleItems = occupancy.filter(o => o.source === 'schedule');
            const reservationItems = occupancy.filter(o => o.source === 'reservation');

            document.getElementById('occupancyModal').querySelector('h3').textContent =
                `教室占用情况 - ${classroom.classroom_no || ''}`;

            // 一周占用矩阵：横向周一至周日，纵向 5 个节次段
            const cellMap = {};
            scheduleItems.forEach(o => {
                const day = o.weekDay != null ? Number(o.weekDay) : null;
                const start = Number(o.startPeriod);
                const seg = Math.min(5, Math.max(1, Math.ceil(start / 2)));
                if (day == null || day < 1 || day > 7) return;
                const key = `${day}-${seg}`;
                if (!cellMap[key]) cellMap[key] = [];
                cellMap[key].push(o);
            });

            let matrixHtml = '';
            if (scheduleItems.length === 0) {
                matrixHtml = '<p class="empty-hint" style="padding:16px 0;">本周无课表占用</p>';
            } else {
                let headRow = '<tr><th class="occ-corner">节次</th>';
                for (let d = 1; d <= 7; d++) headRow += `<th>${OCC_DAY_NAMES[d]}</th>`;
                headRow += '</tr>';
                let bodyRows = '';
                for (let seg = 1; seg <= 5; seg++) {
                    bodyRows += `<tr><td class="occ-period-label">${OCC_PERIOD_NAMES[seg]}</td>`;
                    for (let d = 1; d <= 7; d++) {
                        const entries = cellMap[`${d}-${seg}`];
                        if (entries && entries.length) {
                            const cells = entries.map(o => {
                                const week = o.weekRange ? `<span class="occ-week">${escapeHtml(String(o.weekRange))} 周</span>` : '';
                                return `${escapeHtml(o.courseName || '未命名课程')}${o.className ? '<span class="occ-sub">' + escapeHtml(o.className) + '</span>' : ''}${week}`;
                            }).join('');
                            bodyRows += `<td class="occ-cell-busy"><div class="occ-cell-inner">${cells}</div></td>`;
                        } else {
                            bodyRows += '<td class="occ-cell-free"></td>';
                        }
                    }
                    bodyRows += '</tr>';
                }
                matrixHtml = `<div class="table-container occ-matrix-wrap"><table class="occ-matrix"><thead>${headRow}</thead><tbody>${bodyRows}</tbody></table></div>`;
            }

            let reservationHtml = '';
            if (reservationItems.length > 0) {
                const rows = reservationItems.map(o => {
                    // 预约表存的是节次段索引（1=1-2节…5=9-10节），展示时换算为实际节次
                    const actualStart = o.startPeriod == null ? '' : (Number(o.startPeriod) - 1) * 2 + 1;
                    const actualEnd = o.endPeriod == null ? '' : Number(o.endPeriod) * 2;
                    return `
                        <tr>
                            <td>${escapeHtml(String(o.reservationDate || ''))}</td>
                            <td>第${actualStart}-${actualEnd}节</td>
                            <td>${escapeHtml(o.person || '-')}</td>
                            <td>${escapeHtml(o.reason || '-')}</td>
                            <td><span class="status-badge approved">预约</span></td>
                        </tr>
                    `;
                }).join('');
                reservationHtml = `
                    <div class="occ-section-title">已批准预约占用</div>
                    <div class="table-container">
                        <table class="data-table">
                            <thead><tr><th>日期</th><th>节次</th><th>预约人</th><th>用途</th><th>来源</th></tr></thead>
                            <tbody>${rows}</tbody>
                        </table>
                    </div>`;
            }

            body.innerHTML = `
                <div class="occ-classroom-meta">
                    <span class="occ-meta-label">教室号</span><span class="occ-meta-value">${escapeHtml(String(classroom.classroom_no || '-'))}</span>
                    <span class="occ-meta-label">楼栋</span><span class="occ-meta-value">${escapeHtml(String(classroom.building || '-'))}</span>
                </div>
                <div class="occ-section-title">一周课表占用矩阵</div>
                ${matrixHtml}
                ${reservationHtml}
            `;
        }
        /** 教室类型下拉赋值：历史自定义类型不在固定选项内时动态补一个选项 */
        function setRoomTypeSelectValue(type) {
            const sel = document.getElementById('editRoomType');
            if (!sel) return;
            const val = (type || '').trim() || '普通教室';
            if (!Array.from(sel.options).some(o => o.value === val)) {
                const opt = document.createElement('option');
                opt.value = val;
                opt.textContent = val;
                sel.appendChild(opt);
            }
            sel.value = val;
        }

        function editClassroom(id) {
            fetch('/admin/classrooms')
                .then(r => r.json())
                .then(data => {
                    if (data.success) {
                        const c = data.classrooms.find(c => String(c.id) === String(id));
                        if (!c) return;
                        document.getElementById('editClassroomId').value = c.id;
                        document.getElementById('editClassroomNo').value = c.classroomNo || '';
                        document.getElementById('editBuilding').value = c.building || '';
                        document.getElementById('editCapacity').value = c.capacity || '';
                        setRoomTypeSelectValue(c.roomType || '普通教室');
                        document.getElementById('editClassroomStatus').value = c.status || 'available';
                        document.getElementById('editDescription').value = c.description || '';
                        document.querySelector('#classroomEditModal .modal-header h3').textContent = '编辑教室信息';
                        document.getElementById('classroomEditModal').classList.add('show');
                    }
                });
        }

        /** 新增教室：复用编辑弹窗，id 置空走 POST /admin/classrooms */
        function openAddClassroom() {
            document.getElementById('editClassroomId').value = '';
            document.getElementById('editClassroomNo').value = '';
            document.getElementById('editBuilding').value = '';
            document.getElementById('editCapacity').value = '';
            document.getElementById('editRoomType').value = '普通教室';
            document.getElementById('editClassroomStatus').value = 'available';
            document.getElementById('editDescription').value = '';
            document.querySelector('#classroomEditModal .modal-header h3').textContent = '新增教室';
            document.getElementById('classroomEditModal').classList.add('show');
        }

        function closeClassroomEditModal() {
            document.getElementById('classroomEditModal').classList.remove('show');
        }

        function saveClassroomEdit() {
            const id = document.getElementById('editClassroomId').value;
            const body = {
                classroomNo: document.getElementById('editClassroomNo').value,
                building: document.getElementById('editBuilding').value,
                capacity: parseInt(document.getElementById('editCapacity').value) || 0,
                roomType: document.getElementById('editRoomType').value.trim() || '普通教室',
                status: document.getElementById('editClassroomStatus').value,
                description: document.getElementById('editDescription').value
            };
            // id 为空 → 新增（POST）；否则修改（PUT）
            const isAdd = !id;
            fetch(isAdd ? '/admin/classrooms' : `/admin/classrooms/${id}`, {
                method: isAdd ? 'POST' : 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            })
            .then(r => r.json())
            .then(data => {
                if (data.success) {
                    showToast(isAdd ? '添加成功' : '修改成功', 'success');
                    closeClassroomEditModal();
                    loadClassrooms();
                } else {
                    showToast(data.message || (isAdd ? '添加失败' : '修改失败'), 'error');
                }
            })
            .catch(() => showToast('网络异常', 'error'));
        }
        
        async function deleteClassroom(id) {
            const ok = await showConfirm({
                title: '删除教室',
                message: '确定删除该教室？',
                confirmText: '删除',
                danger: true
            });
            if (ok) {
                fetch(`/admin/classrooms/${id}`, { method: 'DELETE' })
                    .then(response => response.json())
                    .then(data => {
                        if (data.success) {
                            showToast('删除成功', 'success');
                            loadClassrooms();
                        } else {
                            showToast('删除失败', 'error');
                        }
                    });
            }
        }
