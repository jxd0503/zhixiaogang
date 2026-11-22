/********** 教室课表：按学期 / 周次查看教室课表矩阵 **********/
        // ===================== 教室课表全页面 =====================

        let acsOverviewLoaded = false;

        let acsWeekHintShown = false;
        let acsWeekAutoSet = false;
        // 按学期持续周数动态重建周下拉选项（保留"全部"）；学期未配置时保持模板初始选项
        function acsRebuildWeekOptions(sel, weeks) {
            weeks = parseInt(weeks, 10);
            if (!weeks || weeks < 1) return;
            if (sel.options.length === weeks + 1) return;
            var cur = sel.value;
            sel.innerHTML = '';
            var all = document.createElement('option');
            all.value = '';
            all.textContent = '全部';
            sel.appendChild(all);
            for (var w = 1; w <= weeks; w++) {
                var o = document.createElement('option');
                o.value = w;
                o.textContent = '第 ' + w + ' 周';
                sel.appendChild(o);
            }
            if (cur && parseInt(cur, 10) >= 1 && parseInt(cur, 10) <= weeks) sel.value = cur;
        }
        function autoSetCurrentWeekForAdminSchedule() {
            if (acsWeekAutoSet) return; // 自动选周只执行一次，用户手动切换后不再回跳
            var sel = document.getElementById('acsWeekSelect');
            if (!sel) return;
            fetch('/api/semester/current')
                .then(function (r) { return r.json(); })
                .then(function (data) {
                    if (data.success && data.configured) {
                        acsWeekAutoSet = true;
                        acsRebuildWeekOptions(sel, data.weeks);
                        var currentWeek = data.currentWeek || 1;
                        // 容错兜底：选项不存在时钳制选最后一项
                        var opt = sel.querySelector('option[value="' + currentWeek + '"]');
                        if (!opt) {
                            var opts = sel.querySelectorAll('option');
                            if (opts.length > 0) {
                                currentWeek = parseInt(opts[opts.length - 1].value, 10);
                            }
                        }
                        // 超出学期范围（含开学前）时独立提示一次
                        if (data.inRange === false && !acsWeekHintShown) {
                            acsWeekHintShown = true;
                            var hint = document.createElement('div');
                            hint.style.cssText = 'background:#fff3cd;color:#856404;font-size:13px;padding:8px 10px;border-radius:8px;margin-bottom:12px;border:1px solid #ffe69c;';
                            hint.textContent = '当前日期已超出本学期范围，自动显示最后一周';
                            var wrap = document.getElementById('acsMatrixWrap');
                            if (wrap && wrap.parentNode) {
                                wrap.parentNode.insertBefore(hint, wrap);
                            }
                        }
                        sel.value = currentWeek;
                        loadAdminClassSchedule();
                    }
                })
                .catch(function () {
                    // 无配置或接口失败时保持默认值，不报错
                });
        }

        function initAdminClassSchedule() {
            if (acsOverviewLoaded) {
                const sel = document.getElementById('acsClassroomSelect');
                if (sel.value) {
                    autoSetCurrentWeekForAdminSchedule();
                }
                return;
            }
            const sel = document.getElementById('acsClassroomSelect');
            const wrap = document.getElementById('acsMatrixWrap');
            wrap.innerHTML = '<p class="empty-hint">加载中...</p>';
            fetch('/admin/classrooms/occupancy')
                .then(r => r.json())
                .then(data => {
                    if (!data.success) {
                        wrap.innerHTML = `<p class="empty-hint">${escapeHtml(data.message || '加载失败')}</p>`;
                        return;
                    }
                    const list = data.data || [];
                    sel.innerHTML = list.map(c => {
                        const occ = c.schedulePeriods > 0 ? `本周 ${c.schedulePeriods} 节` : '空闲';
                        return `<option value="${c.id}">${escapeHtml(c.classroom_no || '')}${c.building ? ' · ' + escapeHtml(c.building) : ''}（${occ}）</option>`;
                    }).join('');
                    acsOverviewLoaded = true;
                    if (list.length > 0) {
                        autoSetCurrentWeekForAdminSchedule();
                    } else wrap.innerHTML = '<p class="empty-hint">暂无教室数据</p>';
                })
                .catch(() => { wrap.innerHTML = '<p class="empty-hint">加载失败，请稍后重试</p>'; });
        }

        function loadAdminClassSchedule() {
            const sel = document.getElementById('acsClassroomSelect');
            const wrap = document.getElementById('acsMatrixWrap');
            const id = sel.value;
            if (!id) { wrap.innerHTML = '<p class="empty-hint">请选择教室</p>'; return; }
            wrap.innerHTML = '<p class="empty-hint">加载中...</p>';
            fetch(`/admin/classrooms/${id}/occupancy`)
                .then(r => r.json())
                .then(data => {
                    if (!data.success) {
                        wrap.innerHTML = `<p class="empty-hint">${escapeHtml(data.message || '加载失败')}</p>`;
                        return;
                    }
                    renderAdminClassScheduleMatrix(wrap, data);
                })
                .catch(() => { wrap.innerHTML = '<p class="empty-hint">加载失败，请稍后重试</p>'; });
        }

        function renderAdminClassScheduleMatrix(wrap, data) {
            const classroom = data.classroom || {};
            const scheduleItems = (data.occupancy || []).filter(o => o.source === 'schedule');
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

            let matrixHtml;
            if (scheduleItems.length === 0) {
                matrixHtml = '<p class="empty-hint cs-empty">本周无课表占用</p>';
            } else {
                let headRow = '<tr><th class="cs-corner">节次</th>';
                for (let d = 1; d <= 7; d++) headRow += `<th>${OCC_DAY_NAMES[d]}</th>`;
                headRow += '</tr>';
                let bodyRows = '';
                for (let seg = 1; seg <= 5; seg++) {
                    bodyRows += `<tr><td class="cs-period-label">${OCC_PERIOD_NAMES[seg]}</td>`;
                    for (let d = 1; d <= 7; d++) {
                        const entries = cellMap[`${d}-${seg}`];
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
                matrixHtml = `<div class="table-container cs-matrix-wrap"><table class="cs-matrix"><thead>${headRow}</thead><tbody>${bodyRows}</tbody></table></div>`;
            }

            wrap.innerHTML = `
                <div class="cs-classroom-meta">
                    <span class="cs-meta-label">教室号</span><span class="cs-meta-value">${escapeHtml(String(classroom.classroom_no || '-'))}</span>
                    <span class="cs-meta-label">楼栋</span><span class="cs-meta-value">${escapeHtml(String(classroom.building || '-'))}</span>
                </div>
                ${matrixHtml}
            `;
        }
