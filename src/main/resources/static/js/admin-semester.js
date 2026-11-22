/********** 学期设置：开始日期、周数与结束日期预览、保存配置 **********/
        // ===================== 学期设置 =====================

        var semesterConfigId = null;

        function formatDateCN(dateStr) {
            if (!dateStr) return '';
            var d = new Date(dateStr + 'T00:00:00');
            return d.getFullYear() + ' 年 ' + (d.getMonth() + 1) + ' 月 ' + d.getDate() + ' 日';
        }

        function calcEndDate(startStr, weeks) {
            var start = new Date(startStr + 'T00:00:00');
            var end = new Date(start.getTime() + (weeks * 7 - 1) * 86400000);
            var y = end.getFullYear();
            var m = String(end.getMonth() + 1).padStart(2, '0');
            var day = String(end.getDate()).padStart(2, '0');
            return y + '-' + m + '-' + day;
        }

        function updateSemesterPreview() {
            var start = document.getElementById('semesterStartDate').value;
            var weeks = parseInt(document.getElementById('semesterWeeks').value, 10);
            var preview = document.getElementById('semesterPreview');
            if (start && weeks >= 1 && weeks <= 40) {
                document.getElementById('previewEndDate').textContent = formatDateCN(calcEndDate(start, weeks));
                document.getElementById('previewTotalWeeks').textContent = weeks;
                preview.style.display = 'block';
            } else {
                preview.style.display = 'none';
            }
        }

        function loadSemesterSetting() {
            var resultEl = document.getElementById('semesterResult');
            resultEl.style.display = 'none';
            fetch('/api/semester/current')
                .then(function (r) { return r.json(); })
                .then(function (data) {
                    if (data.success && data.configured) {
                        semesterConfigId = data.id || null;
                        document.getElementById('semesterName').value = data.semesterName || '';
                        document.getElementById('semesterStartDate').value = data.startDate || '';
                        document.getElementById('semesterWeeks').value = data.weeks || '';
                    } else {
                        semesterConfigId = null;
                    }
                    updateSemesterPreview();
                })
                .catch(function () { updateSemesterPreview(); });
        }

        async function saveSemesterConfig() {
            var name = document.getElementById('semesterName').value.trim();
            var start = document.getElementById('semesterStartDate').value;
            var weeks = parseInt(document.getElementById('semesterWeeks').value, 10);
            var resultEl = document.getElementById('semesterResult');

            if (!name) { showSemesterResult(false, '请填写学期名称'); return; }
            if (!start) { showSemesterResult(false, '请选择开始日期'); return; }
            if (!weeks || weeks < 1 || weeks > 40) { showSemesterResult(false, '持续周数必须为 1-40 之间的整数'); return; }

            // 保存前再次校验结束日期与预览一致
            var expectedEnd = calcEndDate(start, weeks);

            // 合理性警示：开始日期与当前日期相差超过半年时需二次确认（与后端阈值一致）
            var startD = new Date(start + 'T00:00:00');
            var now = new Date();
            var diffDays = Math.abs((startD - now) / 86400000);
            if (diffDays > 180) {
                const keepGoing = await showConfirm({
                    title: '请确认学期日期',
                    message: '提示：学期开始日期与当前日期相差较远（约 ' + Math.round(diffDays) + ' 天），请确认是否为误填。是否继续保存？',
                    confirmText: '继续保存'
                });
                if (!keepGoing) {
                    return;
                }
            }

            var body = { semesterName: name, startDate: start, weeks: weeks };
            if (semesterConfigId) body.id = semesterConfigId;

            fetch('/api/semester/save', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            })
                .then(function (r) { return r.json(); })
                .then(function (data) {
                    if (data.success && data.data && data.data.endDate === expectedEnd) {
                        semesterConfigId = data.data.id;
                        var msg = '学期配置保存成功：' + name + '，将于 ' + formatDateCN(expectedEnd) + ' 结束，共 ' + weeks + ' 周';
                        if (data.warning) showSemesterResult(true, msg + '（' + data.message.replace(/^学期配置保存成功。?/, '') + '）');
                        else showSemesterResult(true, msg);
                    } else if (data.success) {
                        semesterConfigId = data.data ? data.data.id : semesterConfigId;
                        showSemesterResult(true, data.message || '学期配置保存成功');
                    } else {
                        showSemesterResult(false, data.message || '保存失败，请重试');
                    }
                })
                .catch(function () { showSemesterResult(false, '网络异常，保存失败，请重试'); });
        }

        function showSemesterResult(ok, msg) {
            var el = document.getElementById('semesterResult');
            el.style.display = 'block';
            el.textContent = msg;
            el.style.color = ok ? '#111111' : '#c0392b';
            el.style.background = ok ? '#f5f5f5' : '#fdf0ef';
            el.style.border = ok ? '1px solid #dddddd' : '1px solid #e8b4ae';
            el.style.padding = '10px 12px';
            el.style.borderRadius = '8px';
            el.style.fontSize = '13px';
        }

        (function bindSemesterInputs() {
            document.addEventListener('DOMContentLoaded', function () {
                var s = document.getElementById('semesterStartDate');
                var w = document.getElementById('semesterWeeks');
                if (s) s.addEventListener('input', updateSemesterPreview);
                if (w) w.addEventListener('input', updateSemesterPreview);
            });
        })();
