/********** 调课审批：申请列表与筛选、详情弹窗、冲突复查、批准 / 拒绝 **********/
        // ===================== 调课审批 =====================
        let allAdjustments = [];
        let adjustmentPage = 1;
        let adjustmentTotal = 0;
        const ADJUSTMENT_PAGE_SIZE = 10;
        let adjustmentReasonTarget = null;   // { id, action }

        const ADJ_STATUS_TEXT = { pending: '待审核', approved: '已批准', rejected: '已拒绝', cancelled: '已撤销' };
        const ADJ_STATUS_CLASS = { pending: 'pending', approved: 'approved', rejected: 'rejected', cancelled: 'closed' };
        const ADJ_DAY_NAMES = { 1: '周一', 2: '周二', 3: '周三', 4: '周四', 5: '周五', 6: '周六', 7: '周日' };

        function adjPeriodText(start, end) {
            const s = Number(start), e = Number(end);
            if (!s && !e) return '-';
            return '第' + s + '-' + e + '节';
        }

        // 调课来源文案：AI 推荐 / AI 暂不可用（本地规则）/ 手动选择
        function adjSourceLabel(src, aiSuggested) {
            if (src === 'ai') return 'AI 推荐';
            if (src === 'local') return 'AI 暂不可用，已用本地规则推荐';
            if (src === 'manual') return '手动选择';
            return Number(aiSuggested) === 1 ? 'AI 推荐' : '手动选择';
        }

        function loadAdjustments(page) {
            const p = page || adjustmentPage || 1;
            const tbody = document.getElementById('adjustmentTableBody');
            const statusEl = document.getElementById('filterAdjustmentStatus');
            const status = statusEl ? statusEl.value : '';
            if (tbody) tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;color:#999;">加载中...</td></tr>';
            let url = '/admin/adjustments?page=' + p + '&size=' + ADJUSTMENT_PAGE_SIZE;
            if (status) url += '&status=' + encodeURIComponent(status);
            fetch(url)
                .then(response => response.json())
                .then(data => {
                    if (!data.success) {
                        tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;color:#C62828;">' + escapeHtml(data.message || '加载失败') + '</td></tr>';
                        return;
                    }
                    adjustmentPage = data.page || p;
                    adjustmentTotal = data.total || 0;
                    allAdjustments = data.adjustments || [];
                    // 删除末页最后一条后回退到上一页
                    if (!allAdjustments.length && adjustmentTotal > 0 && adjustmentPage > 1) {
                        loadAdjustments(adjustmentPage - 1);
                        return;
                    }
                    renderAdjustments(allAdjustments);
                    renderAdjustmentPager();
                })
                .catch(() => {
                    tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;color:#C62828;">加载失败：网络异常</td></tr>';
                });
        }

        function renderAdjustmentPager() {
            if (typeof renderPager !== 'function') return;
            renderPager('adjustmentPager', adjustmentPage, ADJUSTMENT_PAGE_SIZE, adjustmentTotal, function (p) {
                adjustmentPage = p;
                loadAdjustments(p);
            });
        }

        function filterAdjustments() {
            adjustmentPage = 1;
            loadAdjustments(1);
        }

        // 手动清理调课附件：无任何记录引用的磁盘文件 + 已关闭超期的申请附件（批量共用文件按引用计数保护）
        function cleanupAdjustmentAttachments() {
            var ask = (typeof showConfirm === 'function')
                ? showConfirm({ title: '清理孤儿附件', message: '将删除无任何调课记录引用的附件文件，以及已拒绝/已撤销超过保留期的附件。是否继续？', danger: true })
                : Promise.resolve(window.confirm('将删除无任何调课记录引用的附件文件，以及已拒绝/已撤销超过保留期的附件。是否继续？'));
            ask.then(function (ok) {
                if (!ok) return;
                fetch('/admin/adjustments/attachments/cleanup', { method: 'POST' })
                    .then(function (response) { return response.json(); })
                    .then(function (data) {
                        if (data && data.success) {
                            if (typeof showToast === 'function') showToast(data.message || '清理完成', 'success');
                            else window.alert(data.message || '清理完成');
                        } else {
                            if (typeof showToast === 'function') showToast((data && data.message) || '清理失败', 'error');
                            else window.alert((data && data.message) || '清理失败');
                        }
                        loadAdjustments();
                    })
                    .catch(function () {
                        if (typeof showToast === 'function') showToast('清理失败：网络异常', 'error');
                        else window.alert('清理失败：网络异常');
                    });
            });
        }

        function adjConflictBadge(a) {
            const r = a.conflictResult;
            if (!r) return '<span style="color:#999;">未校验</span>';
            if (r === 'none') return '<span style="display:inline-block;padding:2px 8px;border-radius:10px;background:#E7F6EC;color:#1E7B45;font-size:12px;">无冲突</span>';
            return '<span style="display:inline-block;padding:2px 8px;border-radius:10px;background:#FDECEA;color:#C62828;font-size:12px;">存在冲突</span>';
        }

        // 状态徽标：列表状态列与详情状态行共用；已批准且存在待审批撤销申请时显示撤销进度
        function adjStatusBadgeHtml(a) {
            const st = a.status || 'pending';
            if (st === 'approved' && a.cancelStatus === 'PENDING') {
                return '<span class="status-badge pending">撤销待审批</span>';
            }
            const stText = ADJ_STATUS_TEXT[st] || st;
            const stClass = ADJ_STATUS_CLASS[st] || 'processing';
            return '<span class="status-badge ' + stClass + '">' + escapeHtml(stText) + '</span>';
        }

        // 待审批挂起时长：超过 SLA（48 小时）标红提示，便于优先处理
        const ADJ_SLA_HOURS = 48;

        function adjPendingHtml(a) {
            if ((a.status || '') !== 'pending' || a.pendingHours === null || a.pendingHours === undefined) return '';
            const hours = Number(a.pendingHours) || 0;
            const over = hours >= ADJ_SLA_HOURS;
            return '<div class="adj-pending-time' + (over ? ' over' : '') + '">已挂起 '
                + (hours >= 24 ? Math.floor(hours / 24) + ' 天 ' + (hours % 24) + ' 小时' : hours + ' 小时')
                + (over ? ' · 已超时' : '') + '</div>';
        }

        function renderAdjustments(list) {
            const tbody = document.getElementById('adjustmentTableBody');
            if (!list.length) {
                tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;color:#999;">暂无调课记录</td></tr>';
                return;
            }
            // 列表展示课程、班级、教师、申请时间、状态；时段、冲突与理由进入「处理」后的详情弹窗查看
            tbody.innerHTML = list.map(a => '<tr>'
                + '<td>' + escapeHtml(a.courseName || '-') + '</td>'
                + '<td>' + escapeHtml(a.className || '-') + '</td>'
                + '<td>' + escapeHtml(a.teacherName || '-')
                + (a.substituteTeacherName ? '<div class="adj-sub-text">代课：' + escapeHtml(a.substituteTeacherName) + '</div>' : '')
                + (Number(a.swapFlag) === 1 ? '<div class="adj-sub-text">互换：' + escapeHtml(a.peerTeacherName || a.peerTeacherId || '-')
                    + '（' + (Number(a.peerConfirmed) === 1 ? '对方已确认' : '待对方确认') + '）</div>' : '')
                + '</td>'
                + '<td>' + escapeHtml(formatTime(a.applyTime)) + adjPendingHtml(a) + '</td>'
                + '<td style="white-space:nowrap;">' + adjStatusBadgeHtml(a) + '</td>'
                + '<td style="white-space:nowrap;">'
                + '<button class="action-btn" onclick="openAdjustmentDetail(' + a.id + ')">处理</button>'
                + '</td>'
                + '</tr>').join('');
        }

        function findAdjustment(id) {
            return (allAdjustments || []).find(x => String(x.id) === String(id)) || null;
        }

        function adjDetailRow(label, value) {
            return '<div class="adj-detail-row"><span class="adj-detail-label">' + escapeHtml(label)
                + '</span><span class="adj-detail-value">' + (value || '-') + '</span></div>';
        }

        function adjConflictBlockHtml(a) {
            const detail = (a.conflictDetail || '').trim();
            const r = a.conflictResult;
            if (!r) {
                return '<div class="adj-check-block none">尚未校验。点击下方「重新校验冲突」查询教师 / 班级 / 教室三方占用。</div>';
            }
            if (r === 'none') {
                return '<div class="adj-check-block ok"><strong>无冲突，可批准</strong>'
                    + (detail ? '<div class="adj-check-detail">' + escapeHtml(detail) + '</div>' : '') + '</div>';
            }
            return '<div class="adj-check-block err"><strong>存在冲突，请谨慎批准</strong>'
                + (detail ? '<div class="adj-check-detail">' + escapeHtml(detail) + '</div>' : '') + '</div>';
        }

        let adjDetailId = null;

        /** 审批动作流水：谁、何时、什么动作、看到的冲突、强制放行 / 紧急回滚理由 */
        const ADJ_LOG_ACTION_TEXT = {
            'apply': '教师提交申请',
            'approve': '管理员批准',
            'force-approve': '强制放行并批准',
            'approve-blocked': '批准被拦截',
            'reject': '管理员拒绝',
            'withdraw': '教师撤回',
            'cancel-request': '教师申请撤销',
            'cancel-approve': '同意撤销',
            'cancel-reject': '拒绝撤销',
            'cancel': '管理员撤销',
            'emergency-rollback': '教师紧急回滚',
            'emergency-review': '管理员确认知悉'
        };

        function loadAdjustmentLogs(id) {
            const box = document.getElementById('adjustmentLogBox');
            if (!box) return;
            box.innerHTML = '';
            fetch('/admin/adjustments/' + id + '/logs')
                .then(r => r.json())
                .then(data => {
                    if (String(adjDetailId) !== String(id)) return;
                    const list = (data && data.success) ? (data.logs || []) : [];
                    if (!list.length) {
                        box.innerHTML = '<div class="adj-detail-row"><span class="adj-detail-label">审批流水</span>'
                            + '<span class="adj-detail-value" style="color:#999;">暂无记录</span></div>';
                        return;
                    }
                    const rows = list.map(function (l) {
                        const action = ADJ_LOG_ACTION_TEXT[l.action] || l.action || '-';
                        const who = (l.operator_name || l.operator_id || '-')
                            + (l.operator_role ? '（' + l.operator_role + '）' : '');
                        let line = '<div class="adj-log-item">'
                            + '<div><strong>' + escapeHtml(action) + '</strong> · ' + escapeHtml(who)
                            + ' · ' + escapeHtml(formatTime(l.create_time) || '-') + '</div>';
                        if (l.conflict_snapshot) {
                            line += '<div style="color:#999;font-size:12px;">冲突：' + escapeHtml(l.conflict_snapshot) + '</div>';
                        }
                        if (l.reason) {
                            line += '<div style="color:#999;font-size:12px;">理由：' + escapeHtml(l.reason) + '</div>';
                        }
                        if (l.detail) {
                            line += '<div style="color:#999;font-size:12px;">' + escapeHtml(l.detail) + '</div>';
                        }
                        return line + '</div>';
                    }).join('');
                    box.innerHTML = '<div class="adj-detail-row"><span class="adj-detail-label">审批流水</span>'
                        + '<span class="adj-detail-value"><div class="adj-log-list">' + rows + '</div></span></div>';
                })
                .catch(() => {
                    if (String(adjDetailId) === String(id)) {
                        box.innerHTML = '<div class="adj-detail-row"><span class="adj-detail-label">审批流水</span>'
                            + '<span class="adj-detail-value" style="color:#999;">加载失败</span></div>';
                    }
                });
        }

        /** 凭证附件：审批详情内展示，图片/PDF 新窗口内联预览，其他按下载 */
        function loadAdjustmentAttachments(id) {
            const box = document.getElementById('adjustmentAttachmentBox');
            if (!box) return;
            box.innerHTML = '';
            fetch('/admin/adjustments/' + id + '/attachments')
                .then(r => r.json())
                .then(data => {
                    if (String(adjDetailId) !== String(id)) return;
                    const list = (data && data.success) ? (data.attachments || []) : [];
                    if (!list.length) {
                        box.innerHTML = '<div class="adj-detail-row"><span class="adj-detail-label">凭证附件</span>'
                            + '<span class="adj-detail-value" style="color:#999;">未上传</span></div>';
                        return;
                    }
                    box.innerHTML = '<div class="adj-detail-row"><span class="adj-detail-label">凭证附件</span>'
                        + '<span class="adj-detail-value"><div class="adj-attach-list">'
                        + list.map(function (f) {
                            const size = Number(f.size) || 0;
                            const sizeText = size >= 1024 * 1024
                                ? (size / 1024 / 1024).toFixed(1) + 'MB'
                                : Math.max(1, Math.round(size / 1024)) + 'KB';
                            return '<a class="adj-attach-link" href="' + escapeHtml(f.previewUrl || '#')
                                + '" target="_blank" rel="noopener">' + escapeHtml(f.name || '附件')
                                + '（' + sizeText + '）</a>';
                        }).join('') + '</div></span></div>';
                })
                .catch(() => {
                    if (String(adjDetailId) === String(id)) {
                        box.innerHTML = '<div class="adj-detail-row"><span class="adj-detail-label">凭证附件</span>'
                            + '<span class="adj-detail-value" style="color:#999;">加载失败</span></div>';
                    }
                });
        }

        function openAdjustmentDetail(id) {
            const a = findAdjustment(id);
            if (!a) return;
            adjDetailId = a.id;
            const st = a.status || 'pending';
            const origDay = ADJ_DAY_NAMES[a.origWeekDay] || (a.origWeekDay || '-');
            const tgtDay = ADJ_DAY_NAMES[a.targetWeekDay] || (a.targetWeekDay || '-');
            const orig = origDay + ' ' + adjPeriodText(a.origStartPeriod, a.origEndPeriod);
            const tgt = (a.targetWeek ? ('第' + a.targetWeek + '周 ') : '')
                + tgtDay + ' ' + adjPeriodText(a.targetStartPeriod, a.targetEndPeriod);
            const changeRoom = Number(a.changeClassroom) === 1;
            const roomFrom = a.origClassroomNo || '未安排';
            const roomTo = changeRoom ? (a.targetClassroomName || '待定') : roomFrom;
            const sameRoom = roomFrom === roomTo;

            let html = '';
            html += adjDetailRow('课程', escapeHtml(a.courseName || '-') + '（' + escapeHtml(a.className || '-') + '）');
            html += adjDetailRow('任课教师', escapeHtml(a.teacherName || '-')
                + (a.substituteTeacherName ? ' → 代课：<strong>' + escapeHtml(a.substituteTeacherName) + '</strong>' : ''));
            html += adjDetailRow('原时段', escapeHtml(orig));
            html += adjDetailRow('目标时段', '<strong>' + escapeHtml(tgt) + '</strong>');
            html += adjDetailRow('周次范围', a.targetWeek
                ? ('源周次 ' + escapeHtml(String(a.weekRange || '全学期')) + ' → 目标第 ' + a.targetWeek + ' 周')
                : escapeHtml(String(a.weekRange || '全学期')));
            html += adjDetailRow('教室变化', changeRoom
                ? ('更换教室：' + escapeHtml(roomFrom) + ' → <strong>' + escapeHtml(roomTo) + '</strong>')
                : ('不更换教室（' + escapeHtml(roomFrom) + '）' + (sameRoom ? '' : '')));
            html += adjDetailRow('教室类型', changeRoom
                ? (escapeHtml(a.origRoomType || '未标注') + ' → ' + escapeHtml(a.targetRoomType || '未标注'))
                : escapeHtml(a.origRoomType || '未标注'));
            if (a.roomTypeMismatch) {
                html += '<div class="adj-check-block err" style="margin-top:8px;"><strong>教室类型不一致，请确认是否继续</strong>'
                    + '<div class="adj-check-detail">目标教室类型（' + escapeHtml(a.targetRoomType || '-')
                    + '）与原教室（' + escapeHtml(a.origRoomType || '-') + '）不一致。本提示不阻断批准，由管理员裁决；'
                    + '批准后将按目标教室生效。</div></div>';
            }
            html += adjDetailRow('申请来源', escapeHtml(adjSourceLabel(a.sourceType, a.aiSuggested)));
            html += adjDetailRow('推荐说明', escapeHtml(a.statusText || '-'));
            html += adjDetailRow('调课原因', escapeHtml(a.reason || '-'));
            if (Number(a.swapFlag) === 1) {
                html += adjDetailRow('教师互换', '与 ' + escapeHtml(a.peerTeacherName || a.peerTeacherId || '-')
                    + ' 对调课程 · 对方确认：' + (Number(a.peerConfirmed) === 1
                        ? '<span class="status-badge approved">已确认</span>'
                        : '<span class="status-badge pending">待对方确认</span>')
                    + (a.peerConfirmTime ? '（' + escapeHtml(formatTime(a.peerConfirmTime)) + '）' : ''));
            }
            html += adjDetailRow('申请时间', escapeHtml(formatTime(a.applyTime)));
            if ((a.status || '') === 'pending' && a.pendingHours !== null && a.pendingHours !== undefined) {
                const hours = Number(a.pendingHours) || 0;
                html += adjDetailRow('挂起时长', '<span class="' + (hours >= ADJ_SLA_HOURS ? 'adj-pending-over' : '')
                    + '">' + (hours >= 24 ? Math.floor(hours / 24) + ' 天 ' + (hours % 24) + ' 小时' : hours + ' 小时')
                    + (hours >= ADJ_SLA_HOURS ? '（已超过 ' + ADJ_SLA_HOURS + ' 小时审批时限）' : '') + '</span>');
            }
            html += adjDetailRow('当前状态', adjStatusBadgeHtml(a) + ' ' + adjConflictBadge(a) + (a.rejectReason
                ? '<div style="font-size:12px;color:#999;margin-top:4px;">' + escapeHtml(a.rejectReason) + '</div>' : ''));
            html += adjDetailRow('最近校验', (a.checkTime ? escapeHtml(formatTime(a.checkTime)) + ' · ' + escapeHtml(a.checkBy || '-') : '未校验'));
            // 撤销申请进度：待审批 / 已拒绝 / 已完成
            if (a.cancelStatus) {
                let cancelHtml = '';
                if (a.cancelStatus === 'PENDING') {
                    cancelHtml = '<span class="status-badge pending">撤销待审批</span>';
                    if (a.cancelApplyTime) {
                        cancelHtml += '<div style="font-size:12px;color:#999;margin-top:4px;">申请时间：'
                            + escapeHtml(formatTime(a.cancelApplyTime)) + '</div>';
                    }
                    if (a.cancelReason) {
                        cancelHtml += '<div style="font-size:12px;color:#999;">撤销原因：' + escapeHtml(a.cancelReason) + '</div>';
                    }
                } else if (a.cancelStatus === 'REJECTED') {
                    cancelHtml = '<span class="status-badge rejected">撤销被拒</span>'
                        + '<div style="font-size:12px;color:#999;margin-top:4px;">拒绝原因：'
                        + escapeHtml(a.cancelRejectReason || '未说明') + '</div>'
                        + (a.cancelApprover ? '<div style="font-size:12px;color:#999;">审批人：' + escapeHtml(a.cancelApprover) + '</div>' : '');
                } else if (a.cancelStatus === 'DONE') {
                    cancelHtml = '<span class="status-badge approved">撤销已完成</span>'
                        + (a.cancelApprover ? '<div style="font-size:12px;color:#999;margin-top:4px;">审批人：'
                            + escapeHtml(a.cancelApprover) + '</div>' : '');
                }
                if (cancelHtml) {
                    html += adjDetailRow('撤销申请', cancelHtml);
                }
            }
            // 紧急回滚：教师绕过审批直接恢复原课表，管理员事后仅确认知悉并写流水
            if (Number(a.emergencyFlag) === 1) {
                let emgHtml = '<span class="status-badge pending">教师紧急回滚</span>'
                    + '<div style="font-size:12px;color:#999;margin-top:4px;">回滚原因：'
                    + escapeHtml(a.emergencyReason || '未说明') + '</div>'
                    + '<div style="font-size:12px;color:#999;">'
                    + (Number(a.swapFlag) === 1 ? '两门课程已恢复原授课教师' : '课表已恢复原时段') + '</div>';
                emgHtml += a.emergencyReviewBy
                    ? '<div style="font-size:12px;color:#999;">补审：' + escapeHtml(a.emergencyReviewBy)
                        + ' 已确认知悉' + (a.emergencyReviewTime ? '（' + escapeHtml(formatTime(a.emergencyReviewTime)) + '）' : '') + '</div>'
                    : '<div style="font-size:12px;color:#c0392b;">待管理员确认知悉</div>';
                html += adjDetailRow('紧急回滚', emgHtml);
            }
            document.getElementById('adjustmentDetailBody').innerHTML = html;
            document.getElementById('adjustmentConflictBox').innerHTML = adjConflictBlockHtml(a);
            document.getElementById('adjustmentDetailTitle').textContent = '调课申请详情 · #' + a.id;
            loadAdjustmentAttachments(a.id);
            loadAdjustmentLogs(a.id);

            // 待审核：批准/拒绝/重新校验；已批准：直接撤销，或对撤销申请同意/拒绝
            const isPending = st === 'pending';
            const cancelWaiting = st === 'approved' && a.cancelStatus === 'PENDING';
            const approveBtn = document.getElementById('adjustmentDetailApprove');
            approveBtn.style.display = isPending ? '' : 'none';
            approveBtn.setAttribute('onclick', 'approveAdjustment(' + a.id + ')');
            const rejectBtn = document.getElementById('adjustmentDetailReject');
            rejectBtn.style.display = isPending ? '' : 'none';
            rejectBtn.setAttribute('onclick', "openAdjustmentReasonModal(" + a.id + ", 'reject')");
            document.getElementById('adjustmentDetailRecheck').style.display = isPending ? '' : 'none';
            const cancelBtn = document.getElementById('adjustmentDetailCancel');
            cancelBtn.style.display = (st === 'approved' && !cancelWaiting) ? '' : 'none';
            cancelBtn.setAttribute('onclick', "openAdjustmentReasonModal(" + a.id + ", 'cancel')");
            const cancelApproveBtn = document.getElementById('adjustmentDetailCancelApprove');
            cancelApproveBtn.style.display = cancelWaiting ? '' : 'none';
            cancelApproveBtn.setAttribute('onclick', 'approveAdjustmentCancel(' + a.id + ')');
            const cancelRejectBtn = document.getElementById('adjustmentDetailCancelReject');
            cancelRejectBtn.style.display = cancelWaiting ? '' : 'none';
            cancelRejectBtn.setAttribute('onclick', "openAdjustmentReasonModal(" + a.id + ", 'cancel-reject')");
            // 紧急回滚补审：仅待确认知悉时显示
            const emgReviewBtn = document.getElementById('adjustmentDetailEmergencyReview');
            emgReviewBtn.style.display = (Number(a.emergencyFlag) === 1 && !a.emergencyReviewBy) ? '' : 'none';
            emgReviewBtn.setAttribute('onclick', 'acknowledgeEmergencyAdjustment(' + a.id + ')');
            document.getElementById('adjustmentDetailModal').classList.add('show');
        }

        /** 管理端同意教师撤销申请：撤销待审批→已撤销，课表恢复原时段 */
        function approveAdjustmentCancel(id) {
            showConfirm({
                title: '同意撤销调课',
                message: '同意后该调课立即撤销，课表恢复原时段，并通知教师与班级学生。确定同意？',
                confirmText: '同意撤销'
            }).then(function (ok) {
                if (!ok) return;
                fetch('/admin/adjustments/' + id + '/cancel-approve', { method: 'POST' })
                    .then(r => r.json())
                    .then(data => {
                        showProviderToast(data.message || (data.success ? '已同意撤销' : '操作失败'), data.success ? 'success' : 'error');
                        if (data.success) {
                            closeAdjustmentDetailModal();
                            loadAdjustments();
                        }
                    })
                    .catch(() => showProviderToast('操作失败：网络异常', 'error'));
            });
        }

        /** 管理员确认知悉教师的紧急回滚：不改状态与课表，仅写审批流水留痕 */
        function acknowledgeEmergencyAdjustment(id) {
            showConfirm({
                title: '确认紧急回滚',
                message: '该调课已被教师紧急回滚，课表已恢复原时段。确认知悉后将写入审批流水留痕。确定确认？',
                confirmText: '确认知悉'
            }).then(function (ok) {
                if (!ok) return;
                fetch('/admin/adjustments/' + id + '/emergency-review', { method: 'POST' })
                    .then(r => r.json())
                    .then(data => {
                        showProviderToast(data.message || (data.success ? '已确认知悉' : '操作失败'), data.success ? 'success' : 'error');
                        if (data.success) {
                            closeAdjustmentDetailModal();
                            loadAdjustments();
                        }
                    })
                    .catch(() => showProviderToast('操作失败：网络异常', 'error'));
            });
        }

        function closeAdjustmentDetailModal() {
            document.getElementById('adjustmentDetailModal').classList.remove('show');
            document.getElementById('adjustmentAttachmentBox').innerHTML = '';
            document.getElementById('adjustmentLogBox').innerHTML = '';
            adjDetailId = null;
        }

        function recheckAdjustmentConflict() {
            if (!adjDetailId) return;
            const btn = document.getElementById('adjustmentDetailRecheck');
            btn.disabled = true;
            btn.textContent = '校验中...';
            fetch('/admin/adjustments/' + adjDetailId + '/conflict-check')
                .then(response => response.json())
                .then(data => {
                    btn.disabled = false;
                    btn.textContent = '重新校验冲突';
                    if (!data.success) {
                        showProviderToast(data.message || '校验失败', 'error');
                        return;
                    }
                    const a = findAdjustment(adjDetailId) || {};
                    a.conflictResult = data.conflictResult;
                    a.conflictDetail = data.conflictDetail;
                    a.checkTime = data.checkTime || new Date().toISOString();
                    a.checkBy = data.checkBy || '';
                    // 重新渲染详情，刷新「最近校验」时间与校验人
                    openAdjustmentDetail(a.id);
                    showProviderToast(data.message || '校验完成', data.hasConflict ? 'error' : 'success');
                    loadAdjustments();
                })
                .catch(() => {
                    btn.disabled = false;
                    btn.textContent = '重新校验冲突';
                    showProviderToast('校验失败：网络异常', 'error');
                });
        }

        function approveAdjustment(id, force, forceReason) {
            const body = { force: force === true };
            if (force === true && forceReason) body.reason = forceReason;
            fetch('/admin/adjustments/' + id + '/approve', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            })
                .then(response => response.json())
                .then(async data => {
                    // 教师 / 班级硬冲突：禁止强制放行，只能驳回或要求教师改期
                    if (data.hardConflict) {
                        showProviderToast(data.message || '存在教师/班级硬冲突，不可强制批准', 'error');
                        loadAdjustments();
                        return;
                    }
                    if (data.needConfirm) {
                        const lines = (data.conflicts || [])
                            .map(x => (typeof x === 'string' ? x : (x && x.text) || ''))
                            .filter(Boolean).join('\n');
                        const ok = await showConfirm({
                            title: '存在教室冲突',
                            message: '该教室在目标时段已被占用，确认仍要批准？' + (lines ? '\n\n' + lines : ''),
                            confirmText: '仍然批准',
                            danger: true
                        });
                        if (!ok) {
                            showProviderToast('已取消批准', 'error');
                            return;
                        }
                        const reason = await showPrompt({
                            title: '强制放行理由',
                            message: '请填写强制放行理由（将记录到操作日志，便于追溯）：',
                            placeholder: '如：已与冲突课程教师协商，教室临时共用',
                            confirmText: '确认放行'
                        });
                        if (reason === null || !String(reason).trim()) {
                            showProviderToast('未填写理由，已取消强制放行', 'error');
                            return;
                        }
                        approveAdjustment(id, true, String(reason).trim());
                        return;
                    }
                    showProviderToast(data.message || (data.success ? '已批准' : '操作失败'), data.success ? 'success' : 'error');
                    if (data.success) {
                        closeAdjustmentDetailModal();
                        loadAdjustments();
                    }
                })
                .catch(() => showProviderToast('操作失败：网络异常', 'error'));
        }

        function openAdjustmentReasonModal(id, action) {
            adjustmentReasonTarget = { id: id, action: action };
            const isReject = action === 'reject' || action === 'cancel-reject';
            const titles = { reject: '拒绝调课申请', cancel: '撤销已批准调课', 'cancel-reject': '拒绝撤销调课申请' };
            const labels = { reject: '拒绝原因', cancel: '撤销原因', 'cancel-reject': '拒绝撤销原因' };
            document.getElementById('adjustmentReasonTitle').textContent = titles[action] || '填写原因';
            document.getElementById('adjustmentReasonLabel').textContent = labels[action] || '原因';
            document.getElementById('adjustmentReasonInput').placeholder =
                (action === 'cancel' ? '请填写撤销原因（选填）' : '请填写原因（必填）');
            document.getElementById('adjustmentReasonInput').value = '';
            const err = document.getElementById('adjustmentReasonError');
            err.textContent = '';
            err.style.display = 'none';
            document.getElementById('adjustmentReasonModal').classList.add('show');
        }

        function closeAdjustmentReasonModal() {
            document.getElementById('adjustmentReasonModal').classList.remove('show');
            adjustmentReasonTarget = null;
        }

        function confirmAdjustmentReason() {
            if (!adjustmentReasonTarget) return;
            const input = document.getElementById('adjustmentReasonInput');
            const reason = input.value.trim();
            const action = adjustmentReasonTarget.action;
            const id = adjustmentReasonTarget.id;
            const err = document.getElementById('adjustmentReasonError');
            // 拒绝调课与拒绝撤销都必须写明理由，避免无据可查
            if ((action === 'reject' || action === 'cancel-reject') && !reason) {
                err.textContent = '请填写拒绝原因';
                err.style.display = '';
                input.focus();
                return;
            }
            err.style.display = 'none';
            const btn = document.getElementById('adjustmentReasonConfirm');
            btn.disabled = true;
            fetch('/admin/adjustments/' + id + '/' + action, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ reason: reason })
            })
                .then(response => response.json())
                .then(data => {
                    btn.disabled = false;
                    if (!data.success) {
                        err.textContent = data.message || '操作失败';
                        err.style.display = '';
                        return;
                    }
                    closeAdjustmentReasonModal();
                    closeAdjustmentDetailModal();
                    showProviderToast(data.message || '操作成功', 'success');
                    loadAdjustments();
                })
                .catch(() => {
                    btn.disabled = false;
                    showProviderToast('操作失败：网络异常', 'error');
                });
        }
