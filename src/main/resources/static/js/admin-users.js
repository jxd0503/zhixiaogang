/********** 用户管理：用户列表、筛选、详情与编辑弹窗、删除用户 **********/
        let allUsers = [];
        let userPage = 1;
        let userTotal = 0;
        const USER_PAGE_SIZE = 10;

        function buildUserQuery(page) {
            const params = new URLSearchParams();
            params.set('page', page);
            params.set('size', USER_PAGE_SIZE);
            const keyword = (document.getElementById('userSearch').value || '').trim();
            const department = document.getElementById('filterDepartment').value;
            const grade = document.getElementById('filterGrade').value;
            const major = document.getElementById('filterMajor').value;
            if (keyword) params.set('keyword', keyword);
            if (department) params.set('department', department);
            if (grade) params.set('grade', grade);
            if (major) params.set('major', major);
            return params.toString();
        }

        function loadUsers(page) {
            const p = page || userPage || 1;
            fetch('/admin/users?' + buildUserQuery(p))
                .then(response => response.json())
                .then(data => {
                    if (data.success) {
                        userPage = data.page || p;
                        userTotal = data.total || 0;
                        allUsers = data.users || [];
                        // 删除末页最后一条后回退到上一页
                        if (!allUsers.length && userTotal > 0 && userPage > 1) {
                            loadUsers(userPage - 1);
                            return;
                        }
                        renderUsers(allUsers);
                        renderUserPager();
                    }
                });
        }

        function renderUserPager() {
            if (typeof renderPager !== 'function') return;
            renderPager('userPager', userPage, USER_PAGE_SIZE, userTotal, function (p) {
                userPage = p;
                loadUsers(p);
            });
        }
        
        function renderUsers(users) {
            const tbody = document.getElementById('userTableBody');
            tbody.innerHTML = '';
            if (!users || users.length === 0) {
                tbody.innerHTML = '<tr><td colspan="7" style="text-align:center;color:#999;padding:24px;">暂无符合条件的用户</td></tr>';
                return;
            }
            users.forEach(user => {
                const roleText = user.role === 'admin' ? '管理员' : (user.role === 'teacher' ? '教师' : '学生');
                tbody.innerHTML += `
                    <tr>
                        <td>${escapeHtml(user.studentId)}</td>
                        <td>${escapeHtml(user.name)}</td>
                        <td>${escapeHtml(roleText)}</td>
                        <td>${escapeHtml(user.department || '-')}</td>
                        <td>${escapeHtml(user.major || '-')}</td>
                        <td>${escapeHtml(user.grade || '-')}</td>
                        <td>
                            <button class="action-btn edit" data-user-action="view" data-sid="${escapeHtml(user.studentId)}">详情</button>
                            <button class="action-btn delete" data-user-action="delete" data-sid="${escapeHtml(user.studentId)}">删除</button>
                        </td>
                    </tr>
                `;
            });
        }

        // 用户操作事件委托：学号等字段只作为 data-* 数据，不拼接进内联 onclick
        document.addEventListener('click', function (e) {
            const btn = e.target.closest('[data-user-action]');
            if (!btn) return;
            const sid = btn.getAttribute('data-sid');
            if (btn.getAttribute('data-user-action') === 'view') {
                viewUser(sid);
            } else if (btn.getAttribute('data-user-action') === 'delete') {
                deleteUser(sid);
            }
        });
        
        function filterUsers() {
            userPage = 1;
            loadUsers(1);
        }
        
        function resetFilters() {
            document.getElementById('filterDepartment').value = '';
            document.getElementById('filterGrade').value = '';
            document.getElementById('filterMajor').value = '';
            document.getElementById('userSearch').value = '';
            userPage = 1;
            loadUsers(1);
        }
        // 用户弹窗中除学号、角色外的可编辑字段
        var USER_EDITABLE_FIELDS = ['editUserName', 'editUserPhone', 'editUserEmail',
            'editUserDepartment', 'editUserMajor', 'editUserGrade'];

        function roleTextOf(role) {
            return role === 'admin' ? '管理员' : (role === 'teacher' ? '教师' : '学生');
        }

        // 用当前数据填充弹窗（统一入口，保证详情/取消时展示最新值）
        function fillUserModal(user) {
            document.getElementById('editUserId').value = user.studentId;
            document.getElementById('editUserStudentId').value = user.studentId || '';
            document.getElementById('editUserName').value = user.name || '';
            document.getElementById('editUserPhone').value = user.phone || '';
            document.getElementById('editUserEmail').value = user.email || '';
            document.getElementById('editUserRole').value = roleTextOf(user.role);
            document.getElementById('editUserDepartment').value = user.department || '';
            document.getElementById('editUserMajor').value = user.major || '';
            document.getElementById('editUserGrade').value = user.grade || '';
            document.getElementById('editUserStatus').value = String(user.status ?? 1);
        }

        // 切换弹窗形态：detail=只读详情，edit=可编辑
        function setUserModalMode(mode) {
            var editing = mode === 'edit';
            document.getElementById('userModalBody').classList.toggle('editing', editing);
            document.getElementById('userModalTitle').textContent = editing ? '编辑用户信息' : '用户详情';
            USER_EDITABLE_FIELDS.forEach(function (id) {
                document.getElementById(id).readOnly = !editing;
            });
            document.getElementById('editUserStatus').disabled = !editing;
            document.getElementById('userEditBtn').style.display = editing ? 'none' : '';
            document.getElementById('userCloseBtn').style.display = editing ? 'none' : '';
            document.getElementById('userSaveBtn').style.display = editing ? '' : 'none';
            document.getElementById('userCancelBtn').style.display = editing ? '' : 'none';
        }

        function viewUser(studentId) {
            var user = allUsers.find(function (u) { return u.studentId === studentId; });
            if (!user) return;
            fillUserModal(user);
            setUserModalMode('detail');
            document.getElementById('userEditModal').classList.add('show');
        }

        function startUserEdit() {
            setUserModalMode('edit');
        }

        function cancelUserEdit() {
            var studentId = document.getElementById('editUserId').value;
            var user = allUsers.find(function (u) { return u.studentId === studentId; });
            if (user) fillUserModal(user);
            setUserModalMode('detail');
        }

        function closeUserEditModal() {
            document.getElementById('userEditModal').classList.remove('show');
            setUserModalMode('detail');
        }

        function saveUserEdit() {
            var studentId = document.getElementById('editUserId').value;
            var body = {
                name: document.getElementById('editUserName').value,
                phone: document.getElementById('editUserPhone').value,
                email: document.getElementById('editUserEmail').value,
                department: document.getElementById('editUserDepartment').value,
                major: document.getElementById('editUserMajor').value,
                grade: document.getElementById('editUserGrade').value,
                status: parseInt(document.getElementById('editUserStatus').value)
            };
            fetch(`/admin/users/${studentId}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            })
            .then(r => r.json())
            .then(data => {
                if (data.success) {
                    showToast('修改成功', 'success');
                    closeUserEditModal();
                    loadUsers();
                } else {
                    showToast(data.message || '修改失败', 'error');
                }
            })
            .catch(() => showToast('网络异常', 'error'));
        }
        
        async function deleteUser(studentId) {
            const ok = await showConfirm({
                title: '删除用户',
                message: '确定删除该用户？',
                confirmText: '删除',
                danger: true
            });
            if (ok) {
                fetch(`/admin/users/${studentId}`, { method: 'DELETE' })
                    .then(response => response.json())
                    .then(data => {
                        if (data.success) {
                            showToast('删除成功', 'success');
                            loadUsers();
                        } else {
                            showToast('删除失败', 'error');
                        }
                    });
            }
        }
