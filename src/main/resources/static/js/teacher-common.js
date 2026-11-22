/********** teacher-common：教师端共享状态与通用工具（面板切换、格式化、转义） **********/

        let aiDisabled = false;

        function showPanel(panelName) {
            document.querySelectorAll('.sidebar-item').forEach(item => item.classList.remove('active'));
            event.target.classList.add('active');

            document.querySelectorAll('.ai-page, .placeholder-page, .course-page, .classroom-page, .class-schedule-page, .adjust-page, .class-mgmt-page, .constraint-page').forEach(p => p.classList.remove('active'));
            if (panelName === 'ai') {
                document.getElementById('aiPage').classList.add('active');
            } else {
                document.getElementById(panelName + 'Page').classList.add('active');
            }

            if (panelName === 'course') loadSchedule();
            if (panelName === 'class') backToClassList(); // 每次进入班级管理都回到所有班级列表视图
            if (panelName === 'classroom') initEmptyClassroomQuery();
            if (panelName === 'classSchedule') initTeacherClassSchedule();
            if (panelName === 'adjust') initAdjustPanel(); // 进入调课申请页自动加载我的调课申请
            if (panelName === 'constraint') initConstraintPanel(); // 进入排课要求页加载班级与我的要求
        }

        const WEEKDAY_NAMES = { 1: '周一', 2: '周二', 3: '周三', 4: '周四', 5: '周五', 6: '周六', 7: '周日' };

        function formatTime(t) {
            if (!t) return '';
            // 形如 2026-09-23T18:42:28 → 2026-09-23 18:42
            return String(t).replace('T', ' ').substring(0, 16);
        }

        function escapeHtml(text) {
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }
