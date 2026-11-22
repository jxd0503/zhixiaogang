/********** student-profile：个人资料编辑 **********/

        function showEditProfile() {
            document.getElementById('editProfileModal').classList.add('show');
            document.getElementById('editNameInput').value = document.getElementById('userName').textContent;
            document.getElementById('editEmailInput').value = userEmail || '';
        }
        
        // 关闭编辑姓名弹窗
        function closeEditProfile() {
            document.getElementById('editProfileModal').classList.remove('show');
        }
        
        // 保存个人信息
        function saveProfile() {
            const newName = document.getElementById('editNameInput').value.trim();
            const newEmail = document.getElementById('editEmailInput').value.trim();
            
            if (!newName) {
                showToast('姓名不能为空', 'warning');
                return;
            }
            if (newName.length > 20) {
                showToast('姓名最长20个字符', 'warning');
                return;
            }
            
            fetch('/api/user/update-name', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: newName, email: newEmail })
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    document.getElementById('userName').textContent = newName;
                    document.querySelector('.user-avatar').textContent = newName.substring(0, 1);
                    closeEditProfile();
                } else {
                    showToast('保存失败：' + data.message, 'error');
                }
            })
            .catch(error => {
                showToast('网络错误，请稍后重试', 'error');
            });
        }
        
        // 切换板块
