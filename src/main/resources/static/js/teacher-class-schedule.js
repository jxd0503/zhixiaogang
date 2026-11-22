/********** teacher-class-schedule：教室课表（只读矩阵） **********/

        // ===================== 教室课表（三端只读，管理端统一维护） =====================
        const CS_DAY_NAMES = { 1: '周一', 2: '周二', 3: '周三', 4: '周四', 5: '周五', 6: '周六', 7: '周日' };
        const CS_PERIOD_NAMES = { 1: '1-2节', 2: '3-4节', 3: '5-6节', 4: '7-8节', 5: '9-10节' };
        let tcsOverviewLoaded = false;

        function initTeacherClassSchedule() {
            if (tcsOverviewLoaded) {
                const sel = document.getElementById('tcsClassroomSelect');
                if (sel.value) loadTeacherClassSchedule();
                return;
            }
            const sel = document.getElementById('tcsClassroomSelect');
            const wrap = document.getElementById('tcsMatrixWrap');
            wrap.innerHTML = '<div class="empty-hint">加载中...</div>';
            fetch('/api/classrooms/occupancy')
                .then(r => r.json())
                .then(data => {
                    if (!data.success) {
                        wrap.innerHTML = `<div class="empty-hint">${escapeHtml(data.message || '加载失败')}</div>`;
                        return;
                    }
                    const list = data.data || [];
                    sel.innerHTML = list.map(c => {
                        const occ = c.schedulePeriods > 0 ? `本周 ${c.schedulePeriods} 节` : '空闲';
                        return `<option value="${c.id}">${escapeHtml(c.classroom_no || '')}${c.building ? ' · ' + escapeHtml(c.building) : ''}（${occ}）</option>`;
                    }).join('');
                    tcsOverviewLoaded = true;
                    if (list.length > 0) loadTeacherClassSchedule();
                    else wrap.innerHTML = '<div class="empty-hint">暂无教室数据</div>';
                })
                .catch(() => { wrap.innerHTML = '<div class="empty-hint">加载失败，请稍后重试</div>'; });
        }

        function loadTeacherClassSchedule() {
            const sel = document.getElementById('tcsClassroomSelect');
            const wrap = document.getElementById('tcsMatrixWrap');
            const id = sel.value;
            if (!id) { wrap.innerHTML = '<div class="empty-hint">请选择教室</div>'; return; }
            wrap.innerHTML = '<div class="empty-hint">加载中...</div>';
            fetch(`/api/classrooms/${id}/occupancy`)
                .then(r => r.json())
                .then(data => {
                    if (!data.success) {
                        wrap.innerHTML = `<div class="empty-hint">${escapeHtml(data.message || '加载失败')}</div>`;
                        return;
                    }
                    renderTeacherClassScheduleMatrix(wrap, data);
                })
                .catch(() => { wrap.innerHTML = '<div class="empty-hint">加载失败，请稍后重试</div>'; });
        }

        function renderTeacherClassScheduleMatrix(wrap, data) {
            const classroom = data.classroom || {};
            const scheduleItems = (data.occupancy || []).filter(o => o.source === 'schedule');
            const cellMap = {};
            scheduleItems.forEach(o => {
                const day = o.weekDay != null ? Number(o.weekDay) : null;
                const start = Number(o.startPeriod);
                const seg = Math.min(5, Math.max(1, Math.ceil(start / 2)));
                if (day == null || day < 1 || day > 7) return;
                const key = day + '-' + seg;
                if (!cellMap[key]) cellMap[key] = [];
                cellMap[key].push(o);
            });

            let matrixHtml;
            if (scheduleItems.length === 0) {
                matrixHtml = '<div class="empty-hint cs-empty">本周无课表占用</div>';
            } else {
                let headRow = '<tr><th class="cs-corner">节次</th>';
                for (let d = 1; d <= 7; d++) headRow += `<th>${CS_DAY_NAMES[d]}</th>`;
                headRow += '</tr>';
                let bodyRows = '';
                for (let seg = 1; seg <= 5; seg++) {
                    bodyRows += `<tr><td class="cs-period-label">${CS_PERIOD_NAMES[seg]}</td>`;
                    for (let d = 1; d <= 7; d++) {
                        const entries = cellMap[d + '-' + seg];
                        if (entries && entries.length) {
                            const cells = entries.map(o => {
                                const week = o.weekRange ? `<span class="cs-week">第 ${escapeHtml(String(o.weekRange))} 周</span>` : '';
                                return `<span class="cs-course">${escapeHtml(o.courseName || '未命名课程')}</span>${o.className ? '<span class="cs-sub">' + escapeHtml(o.className) + '</span>' : ''}${week}`;
                            }).join('');
                            const adjusted = entries.some(o => o.isAdjustment) ? ' cs-cell-adjust' : '';
                            bodyRows += `<td class="cs-cell-busy${adjusted}"><div class="cs-cell-inner">${cells}</div></td>`;
                        } else {
                            bodyRows += '<td class="cs-cell-free"></td>';
                        }
                    }
                    bodyRows += '</tr>';
                }
                matrixHtml = `<div class="table-wrap cs-matrix-wrap"><table class="cs-matrix"><thead>${headRow}</thead><tbody>${bodyRows}</tbody></table></div>`;
            }

            wrap.innerHTML = `
                <div class="cs-classroom-meta">
                    <span class="cs-meta-label">教室号</span><span class="cs-meta-value">${escapeHtml(String(classroom.classroom_no || '-'))}</span>
                    <span class="cs-meta-label">楼栋</span><span class="cs-meta-value">${escapeHtml(String(classroom.building || '-'))}</span>
                </div>
                ${matrixHtml}
            `;
        }

        // 移动端横向导航：点击后自动滚动使被点击项完全可见（交互增强，不影响原有逻辑）
        (function () {
            document.addEventListener('click', function (e) {
                var item = e.target && e.target.closest ? e.target.closest('.sidebar-item') : null;
                if (!item) return;
                var menu = item.closest('.sidebar-menu') || item.closest('.sidebar');
                if (!menu || menu.scrollWidth <= menu.clientWidth + 1) return;
                item.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
            });
        })();

        // ===================== 排课要求 =====================
