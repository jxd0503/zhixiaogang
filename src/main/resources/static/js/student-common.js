/********** student-common：学生端共享状态与通用工具（面板切换、转义、用户信息） **********/

        const studentId = window.USER_ID || '';
        const userEmail = window.USER_EMAIL || '';

        function showPanel(panelName) {
            // 更新导航栏高亮
            document.querySelectorAll('.sidebar-item').forEach(item => {
                item.classList.remove('active');
            });
            event.target.closest('.sidebar-item').classList.add('active');
            
            // 显示对应板块
            document.getElementById('chatPanel').classList.remove('active');
            document.getElementById('schedulePanel').classList.remove('active');
            document.getElementById('classPanel').classList.remove('active');
            document.getElementById('classroomPanel').classList.remove('active');
            document.getElementById('classSchedulePanel').classList.remove('active');
            document.getElementById('myReservationPanel').classList.remove('active');
            
            document.getElementById('chatSidebar').style.display = 'none';
            
            if (panelName === 'chat') {
                document.getElementById('chatPanel').classList.add('active');
                document.getElementById('chatSidebar').style.display = 'block';
            } else if (panelName === 'schedule') {
                document.getElementById('schedulePanel').classList.add('active');
                checkClassMembership();
            } else if (panelName === 'class') {
                document.getElementById('classPanel').classList.add('active');
                loadMyClass();
                loadClassList();
            } else if (panelName === 'classroom') {
                document.getElementById('classroomPanel').classList.add('active');
                initClassroomQuery();
            } else if (panelName === 'classSchedule') {
                document.getElementById('classSchedulePanel').classList.add('active');
                initClassSchedule();
            } else if (panelName === 'reservation') {
                document.getElementById('myReservationPanel').classList.add('active');
                loadMyReservations();
            }
        }
        
        function escapeHtml(text) {
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }
