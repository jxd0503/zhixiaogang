/********** teacher-schedule：教师课表查询与周次选择 **********/

        // 教师课表查询（只读）：返回该教师授课的全部班级课表，按周次渲染格子
        function loadSchedule() {
            const week = document.getElementById('weekSelect').value;
            updateWeekTodayBtn();
            fetch('/api/teacher/schedule/load?week=' + week)
                .then(r => r.json())
                .then(data => {
                    if (!data.success) {
                        console.error('加载课表失败:', data.message);
                        return;
                    }
                    const schedule = data.schedule || [];
                    document.querySelectorAll('#teacherScheduleTable .schedule-cell').forEach(cell => {
                        const day = cell.dataset.day;
                        const period = cell.dataset.period;
                        const item = schedule.find(s => s.day == day && s.period == period);
                        if (item && item.courseName) {
                            const subParts = [];
                            if (item.className) subParts.push(item.className);
                            if (item.substituteTeacherName) subParts.push('代课：' + item.substituteTeacherName);
                            subParts.push(item.location || '室外（体育课）');
                            cell.classList.toggle('schedule-cell-adjust', !!item.isAdjustment);
                            cell.classList.toggle('schedule-cell-substitute', !!item.substituteTeacherName);
                            cell.innerHTML = `<div class="schedule-cell-content">
                                <div class="schedule-course-name">${escapeHtml(item.courseName)}</div>
                                ${subParts.length ? `<div class="schedule-location">${escapeHtml(subParts.join(' · '))}</div>` : ''}
                                ${item.substituteTeacherName ? `<div class="schedule-substitute">代课：${escapeHtml(item.substituteTeacherName)}</div>` : ''}
                            </div>`;
                        } else {
                            cell.classList.remove('schedule-cell-adjust');
                            cell.classList.remove('schedule-cell-substitute');
                            cell.innerHTML = '';
                        }
                    });
                    // 超出学期范围或该周无课程时给出说明
                    updateWeekRangeHint(week, schedule.length === 0);
                })
                .catch(err => console.error('加载课表失败:', err));
        }


        // 页面加载时检查AI状态
        let tcsWeekHintShown = false;
        let teacherCurrentWeek = null; // 当前教学周（来自学期配置，供「本周」按钮使用）
        let teacherTotalWeeks = null; // 学期总周数，用于周次越界提示
        // 按学期持续周数动态重建周下拉选项；学期未配置时保持模板初始选项
        function rebuildWeekOptions(sel, weeks) {
            weeks = parseInt(weeks, 10);
            if (!weeks || weeks < 1) return;
            teacherTotalWeeks = weeks;
            var cur = sel.value;
            // 保留已有选项数量（超出学期的周次仍可查看，但会标注），不足时补足
            var max = Math.max(weeks, sel.options.length || 0);
            sel.innerHTML = '';
            for (var w = 1; w <= max; w++) {
                var o = document.createElement('option');
                o.value = w;
                o.textContent = w > weeks ? (w + '（超出学期）') : String(w);
                if (w > weeks) o.dataset.over = '1';
                sel.appendChild(o);
            }
            if (cur && parseInt(cur, 10) >= 1 && parseInt(cur, 10) <= max) sel.value = cur;
            updateWeekTotalHint();
        }
        /** 周选择器旁的学期总周数说明 */
        function updateWeekTotalHint() {
            var el = document.getElementById('weekTotalHint');
            if (!el) return;
            el.textContent = teacherTotalWeeks ? ('本学期共 ' + teacherTotalWeeks + ' 周') : '';
        }
        /** 超出学期范围或该周无课程时，给出明确说明，避免用户误以为课表丢失 */
        function updateWeekRangeHint(week, isEmpty) {
            var el = document.getElementById('weekRangeHint');
            if (!el) return;
            var w = parseInt(week, 10) || 1;
            if (teacherTotalWeeks && w > teacherTotalWeeks) {
                el.textContent = '本学期仅到第 ' + teacherTotalWeeks + ' 周，该周无课程安排';
                el.className = 'week-range-hint is-over';
                el.style.display = 'block';
            } else if (isEmpty) {
                el.textContent = '第 ' + w + ' 周暂无课程安排'
                    + (teacherTotalWeeks ? '（本学期共 ' + teacherTotalWeeks + ' 周）' : '');
                el.className = 'week-range-hint';
                el.style.display = 'block';
            } else {
                el.className = 'week-range-hint';
                el.style.display = 'none';
            }
        }
        function autoSetCurrentWeekForTeacherSchedule() {
            var sel = document.getElementById('weekSelect');
            if (!sel) return;
            fetch('/api/semester/current')
                .then(function (r) { return r.json(); })
                .then(function (data) {
                    if (data.success && data.configured) {
                        rebuildWeekOptions(sel, data.weeks);
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
                        if (data.inRange === false && !tcsWeekHintShown) {
                            tcsWeekHintShown = true;
                            var hint = document.createElement('div');
                            hint.style.cssText = 'background:#fff3cd;color:#856404;font-size:13px;padding:8px 10px;border-radius:8px;margin-bottom:12px;border:1px solid #ffe69c;';
                            hint.textContent = '当前日期已超出本学期范围，自动显示最后一周';
                            var tableWrap = document.querySelector('#teacherScheduleTable');
                            if (tableWrap && tableWrap.parentNode) {
                                tableWrap.parentNode.insertBefore(hint, tableWrap);
                            }
                        }
                        sel.value = currentWeek;
                        teacherCurrentWeek = currentWeek;
                        updateWeekTodayBtn();
                        loadSchedule();
                    }
                })
                .catch(function () {
                    // 无配置或接口失败时保持默认值，不报错
                });
        }

        /** 周次变化时同步「本周」按钮的选中态 */
        function updateWeekTodayBtn() {
            const sel = document.getElementById('weekSelect');
            const btn = document.getElementById('weekTodayBtn');
            if (!sel || !btn) return;
            const atCurrent = teacherCurrentWeek != null && sel.value === String(teacherCurrentWeek);
            btn.classList.toggle('is-current', atCurrent);
            btn.title = atCurrent
                ? ('当前已是第 ' + teacherCurrentWeek + ' 周')
                : (teacherCurrentWeek != null ? ('回到第 ' + teacherCurrentWeek + ' 周') : '回到当前教学周');
        }

        /** 一键回到当前教学周，并短暂高亮周选择器 */
        function backToCurrentWeek() {
            const sel = document.getElementById('weekSelect');
            if (!sel) return;
            if (teacherCurrentWeek == null) {
                showToast('暂未获取到当前教学周，请稍后重试', 'warning');
                return;
            }
            const target = String(teacherCurrentWeek);
            const changed = sel.value !== target;
            sel.value = target;
            if (changed) {
                loadSchedule();
            }
            updateWeekTodayBtn();
            highlightWeekSelector();
            showToast(changed ? ('已回到本周（第 ' + target + ' 周）') : ('当前已是第 ' + target + ' 周'), changed ? 'success' : 'info');
        }

        function highlightWeekSelector() {
            const wrap = document.querySelector('.week-selector');
            if (!wrap) return;
            wrap.classList.remove('week-flash');
            // 重启动画
            void wrap.offsetWidth;
            wrap.classList.add('week-flash');
            setTimeout(function () { wrap.classList.remove('week-flash'); }, 1200);
        }

        window.onload = function () {
            checkAIStatus();
            autoSetCurrentWeekForTeacherSchedule();
        };
