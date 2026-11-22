/********** 公共模块：全局状态、页面切换、通用工具（转义 / 时间格式化 / 提示 / 课表节次分段） **********/
        const adminName = window.ADMIN_NAME || '';

        // 将 ISO 时间（如 2026-09-25T10:41:20）格式化为 yyyy-MM-dd HH:mm:ss
        function formatTime(t) {
            if (!t) return '-';
            return String(t).replace('T', ' ').replace(/\.\d+$/, '');
        }

        function showPage(pageName) {
            document.querySelectorAll('.sidebar-item').forEach(item => item.classList.remove('active'));

            event.target.classList.add('active');

            document.getElementById('dashboardPage').style.display = 'none';
            document.getElementById('semesterSettingPage').style.display = 'none';
            document.getElementById('usersPage').style.display = 'none';
            document.getElementById('classroomsPage').style.display = 'none';
            document.getElementById('classSchedulePage').style.display = 'none';
            document.getElementById('aiSchedulePage').style.display = 'none';
            document.getElementById('classesPage').style.display = 'none';
            document.getElementById('classDetailPage').style.display = 'none';
            document.getElementById('reservationsPage').style.display = 'none';
            document.getElementById('aiConfigPage').style.display = 'none';
            document.getElementById('adjustmentsPage').style.display = 'none';
            document.getElementById('notificationsPage').style.display = 'none';
            document.getElementById('operationLogsPage').style.display = 'none';

            document.getElementById(pageName + 'Page').style.display = 'block';

            if (pageName === 'dashboard') loadDashboard();
            else if (pageName === 'semesterSetting') loadSemesterSetting();
            else if (pageName === 'users') loadUsers();
            else if (pageName === 'classrooms') loadClassrooms();
            else if (pageName === 'classSchedule') initAdminClassSchedule();
            else if (pageName === 'aiSchedule') initAiSchedule();
            else if (pageName === 'classes') loadClasses();
            else if (pageName === 'reservations') loadReservations();
            else if (pageName === 'adjustments') loadAdjustments();
            else if (pageName === 'notifications') initNotificationsAdmin();
            else if (pageName === 'aiConfig') loadProviders();
            else if (pageName === 'operationLogs') initOperationLogs();
        }

        // 概览卡片跳转：委托给侧边栏按钮，保证侧边栏选中态与页面加载逻辑一致
        function openPageFromCard(pageName) {
            const target = "showPage('" + pageName + "')";
            const btn = Array.from(document.querySelectorAll('.sidebar-item'))
                .find(b => b.getAttribute('onclick') === target);
            if (btn) btn.click();
        }
        
        function escapeHtml(text) {
            return String(text == null ? '' : text)
                .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
        }

        function showProviderToast(message, type) {
            showToast(message, type || 'success');
        }
        const WEEKDAY_NAMES_CN = { 1: '周一', 2: '周二', 3: '周三', 4: '周四', 5: '周五', 6: '周六', 7: '周日' };
        const SCHEDULE_SEGMENTS = [
            { label: '第1-2节', from: 1, to: 2 },
            { label: '第3-4节', from: 3, to: 4 },
            { label: '第5-6节', from: 5, to: 6 },
            { label: '第7-8节', from: 7, to: 8 },
            { label: '第9-10节', from: 9, to: 10 }
        ];

        function segmentIndexOfPeriod(period) {
            const p = Number(period);
            if (!p || isNaN(p)) return 1;
            return Math.min(SCHEDULE_SEGMENTS.length, Math.max(1, Math.ceil(p / 2)));
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
