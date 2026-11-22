/********** student-classes：班级加入 / 退班与班级列表 **********/

        let myCurrentClassId = null;
        let allClassList = [];
        let classPage = 1;
        let classTotal = 0;
        const CLASS_PAGE_SIZE = 8;

        // 检查是否已加入班级（用于课表提示与只读课表显隐）
        function checkClassMembership() {
            fetch('/api/class/my')
                .then(response => response.json())
                .then(data => {
                    const hint = document.getElementById('noClassHint');
                    const viewArea = document.getElementById('scheduleViewArea');
                    const joined = data.success && data.data;
                    if (hint) hint.style.display = joined ? 'none' : 'block';
                    if (viewArea) {
                        viewArea.style.display = joined ? 'block' : 'none';
                    }
                    if (joined) {
                        loadSchedule();
                    }
                })
                .catch(error => console.error('检查班级状态失败:', error));
        }

        // 加载我的班级
        function loadMyClass() {
            fetch('/api/class/my')
                .then(response => response.json())
                .then(data => {
                    const myInfo = document.getElementById('myClassInfo');
                    const noInfo = document.getElementById('noClassInfo');
                    if (data.success && data.data) {
                        myCurrentClassId = data.data.classId;
                        document.getElementById('myClassName').textContent = data.data.className;
                        document.getElementById('myClassMeta').textContent = `${data.data.department || ''} · ${data.data.grade || ''}`;
                        document.getElementById('myClassTeacher').textContent = `班主任：${data.data.teacherName || '-'}`;
                        myInfo.style.display = 'block';
                        noInfo.style.display = 'none';
                        // 退班待审核：隐藏申请按钮，显示状态与取消按钮
                        const wp = !!data.data.withdrawPending;
                        document.getElementById('withdrawBtn').style.display = wp ? 'none' : 'inline-block';
                        document.getElementById('cancelWithdrawBtn').style.display = wp ? 'inline-block' : 'none';
                        document.getElementById('withdrawStatus').style.display = wp ? 'block' : 'none';
                    } else {
                        myCurrentClassId = null;
                        myInfo.style.display = 'none';
                        noInfo.style.display = 'block';
                    }
                })
                .catch(error => console.error('加载我的班级失败:', error));
        }

        // 加载可加入班级列表（后端分页；关键词支持班级名/班主任/学院/年级）
        function loadClassList(page) {
            if (page != null) classPage = page;
            const kwInput = document.getElementById('classSearchInput');
            const keyword = kwInput ? kwInput.value.trim() : '';
            const params = new URLSearchParams({ page: classPage, size: CLASS_PAGE_SIZE });
            if (keyword) params.set('keyword', keyword);
            fetch('/api/class/list?' + params.toString())
                .then(response => response.json())
                .then(data => {
                    if (!data.success) return;
                    classTotal = Number(data.total) || 0;
                    // 退班/加入后当前页可能越界，回退到最后一页
                    if (classPage > 1 && (classPage - 1) * CLASS_PAGE_SIZE >= classTotal) {
                        classPage = Math.max(1, Math.ceil(classTotal / CLASS_PAGE_SIZE));
                        loadClassList();
                        return;
                    }
                    allClassList = data.data || [];
                    renderClassList(keyword);
                    updateClassSearchHint(keyword, classTotal);
                    renderPager('classPager', data.page, data.size, classTotal, function (p) {
                        loadClassList(p);
                    });
                })
                .catch(error => console.error('加载班级列表失败:', error));
        }

        // 班级搜索：输入防抖，避免每次按键都重绘列表
        const CLASS_SEARCH_DEBOUNCE = 250;
        let classSearchTimer = null;

        function onClassSearchInput(value) {
            const clearBtn = document.getElementById('classSearchClear');
            if (clearBtn) clearBtn.hidden = !value;
            clearTimeout(classSearchTimer);
            classSearchTimer = setTimeout(function () { applyClassSearch(value); }, CLASS_SEARCH_DEBOUNCE);
        }

        // 回车立即筛选，不必等待防抖
        function applyClassSearchNow() {
            clearTimeout(classSearchTimer);
            const input = document.getElementById('classSearchInput');
            applyClassSearch(input ? input.value : '');
        }

        // 关键词交给后端分页：条件变化后回到第一页
        function applyClassSearch(value) {
            const input = document.getElementById('classSearchInput');
            if (input && typeof value === 'string' && input.value !== value) {
                input.value = value;
            }
            classPage = 1;
            loadClassList();
        }

        function updateClassSearchHint(value, count) {
            const hint = document.getElementById('classSearchHint');
            if (!hint) return;
            const kw = (value || '').trim();
            if (!kw) {
                hint.textContent = '输入关键词即可实时筛选，无需回车';
                return;
            }
            hint.textContent = count > 0 ? ('匹配到 ' + count + ' 个班级') : '未找到匹配的班级，可换个关键词试试';
        }

        function clearClassSearch() {
            const input = document.getElementById('classSearchInput');
            if (input) {
                input.value = '';
                input.focus();
            }
            const clearBtn = document.getElementById('classSearchClear');
            if (clearBtn) clearBtn.hidden = true;
            clearTimeout(classSearchTimer);
            applyClassSearch('');
        }

        // 渲染可加入班级列表：排除已加入(approved)的班级；检索已在后端完成
        function renderClassList(keyword) {
            const container = document.getElementById('classListContainer');
            container.innerHTML = '';
            const kw = (keyword || '').trim().toLowerCase();
            let list = allClassList.filter(c => c.myStatus !== 'approved');
            if (list.length === 0) {
                container.innerHTML = '<div class="class-list-empty">' + (kw ? '未找到匹配的班级' : '暂无可加入的班级') + '</div>';
                return 0;
            }
            list.forEach(c => {
                const card = document.createElement('div');
                card.className = 'join-class-card';

                let btnHtml = '';
                if (c.myStatus === 'pending') {
                    btnHtml = `<button class="modal-btn" disabled>待审核</button>
                               <button class="modal-btn modal-btn-ghost" onclick="cancelJoin(${c.id})">取消申请</button>`;
                } else if (c.myStatus === 'withdraw_pending') {
                    btnHtml = `<button class="modal-btn" disabled>退班审核中</button>`;
                } else if (c.myStatus === 'rejected' || c.myStatus === 'withdrawn') {
                    btnHtml = `<button class="modal-btn" onclick="joinClass(${c.id})">申请加入</button>`;
                } else {
                    btnHtml = `<button class="modal-btn" onclick="joinClass(${c.id})">申请加入</button>`;
                }

                card.innerHTML = `
                    <div class="join-class-name">${c.className}</div>
                    <div class="join-class-meta">${c.department || ''} · ${c.grade || ''}</div>
                    <div class="join-class-meta">班主任：${c.teacherName || '-'}</div>
                    ${btnHtml}
                `;
                container.appendChild(card);
            });
            return list.length;
        }

        // 申请加入班级
        function joinClass(classId) {
            fetch('/api/class/join', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ classId: classId })
            })
                .then(response => response.json())
                .then(data => {
                    showToast(data.message, data.success ? 'success' : 'error');
                    loadClassList();
                    loadMyClass();
                })
                .catch(error => console.error('申请加入失败:', error));
        }

        // 取消加入申请
        async function cancelJoin(classId) {
            const ok = await showConfirm({
                title: '取消加入申请',
                message: '确定要取消该班级的加入申请吗？'
            });
            if (!ok) {
                return;
            }
            fetch('/api/class/cancel-join', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ classId: classId })
            })
                .then(response => response.json())
                .then(data => {
                    showToast(data.message, data.success ? 'success' : 'error');
                    loadClassList();
                })
                .catch(error => console.error('取消申请失败:', error));
        }

        // 申请退班
        async function withdrawClass() {
            if (!myCurrentClassId) {
                showToast('您尚未加入班级', 'warning');
                return;
            }
            const ok = await showConfirm({
                title: '申请退班',
                message: '确定要申请退班吗？退班需经班主任审核。'
            });
            if (!ok) {
                return;
            }
            fetch('/api/class/withdraw', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ classId: myCurrentClassId })
            })
                .then(response => response.json())
                .then(data => {
                    showToast(data.message, data.success ? 'success' : 'error');
                    if (data.success) {
                        document.getElementById('withdrawBtn').style.display = 'none';
                        document.getElementById('withdrawStatus').style.display = 'block';
                    }
                    loadClassList();
                })
                .catch(error => console.error('申请退班失败:', error));
        }

        // 取消退班申请
        async function cancelWithdraw() {
            if (!myCurrentClassId) {
                showToast('您尚未加入班级', 'warning');
                return;
            }
            const ok = await showConfirm({
                title: '取消退班申请',
                message: '确定要取消退班申请吗？您将继续保留在该班级中。'
            });
            if (!ok) {
                return;
            }
            fetch('/api/class/cancel-withdraw', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ classId: myCurrentClassId })
            })
                .then(response => response.json())
                .then(data => {
                    showToast(data.message, data.success ? 'success' : 'error');
                    loadMyClass();
                    loadClassList();
                })
                .catch(error => console.error('取消退班申请失败:', error));
        }
        // ================================

        // 聊天功能
