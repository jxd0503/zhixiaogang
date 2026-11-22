/********** teacher-ai：AI 助手对话与快捷操作 **********/

        function checkAIStatus() {
            fetch('/api/ai/status')
                .then(response => {
                    // 只读探测接口，不经管理端鉴权，解析失败时静默降级为默认启用
                    if (!response.ok || response.redirected) return null;
                    const ct = response.headers.get('content-type') || '';
                    if (!ct.includes('application/json')) return null;
                    return response.json();
                })
                .then(data => {
                    if (data && data.success) {
                        aiDisabled = data.disabled;
                        if (aiDisabled) {
                            const chatMessages = document.getElementById('chatMessages');
                            const botMessage = document.createElement('div');
                            botMessage.className = 'message bot';
                            botMessage.innerHTML = `<p>智能 AI 服务暂不可用，请稍后重试。</p>`;
                            chatMessages.appendChild(botMessage);
                            chatMessages.scrollTop = chatMessages.scrollHeight;
                        }
                    }
                })
                .catch(() => {
                    // 网络异常时保持默认启用，不打扰用户
                });
        }

        function handleKeyDown(event) {
            if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                sendMessage();
            }
        }

        // ============ 教师快捷操作（直接调用接口） ============
        function addBotMessage(html) {
            const chatMessages = document.getElementById('chatMessages');
            const botMessage = document.createElement('div');
            botMessage.className = 'message bot';
            botMessage.innerHTML = html;
            chatMessages.appendChild(botMessage);
            chatMessages.scrollTop = chatMessages.scrollHeight;
        }

        function quickMyCourses() {
            addBotMessage('<p>正在查询您的授课安排…</p>');
            fetch('/api/teacher/courses')
                .then(r => r.json())
                .then(data => {
                    if (data.success && data.courses && data.courses.length) {
                        let rows = data.courses.map(c => {
                            const day = WEEKDAY_NAMES[c.weekDay] || (c.weekDay || '-');
                            const period = (c.startPeriod && c.endPeriod) ? (c.startPeriod + '-' + c.endPeriod + '节') : '-';
                            return `${escapeHtml(c.courseName || '-')} | ${escapeHtml(c.className || '-')} | ${day} ${period} | ${escapeHtml(c.classroomNo || '-')} | ${escapeHtml(c.semester || '-')}`;
                        }).join('\n');
                        addBotMessage(`<pre style="white-space:pre-wrap;margin:0;">您的授课安排：\n${rows}</pre>`);
                    } else {
                        addBotMessage(`<p>${escapeHtml(data.message || '暂无授课安排')}</p>`);
                    }
                })
                .catch(err => addBotMessage('<p>查询失败：网络异常</p>'));
        }

        function quickEmptyClassroom() {
            // 按「今天星期 + 当前节次」在聊天中直接应答，不再跳转页面
            const now = new Date();
            const weekDay = now.getDay() === 0 ? 7 : now.getDay();
            const minutes = now.getHours() * 60 + now.getMinutes();
            let period = null;
            if (minutes >= 510 && minutes <= 610) period = 1;        // 1-2节 8:30-10:10
            else if (minutes >= 625 && minutes <= 720) period = 2;   // 3-4节 10:25-12:00
            else if (minutes >= 840 && minutes <= 940) period = 3;   // 5-6节 14:00-15:40
            else if (minutes >= 955 && minutes <= 1050) period = 4;  // 7-8节 15:55-17:30
            else if (minutes >= 1110 && minutes <= 1215) period = 5; // 9-10节 18:30-20:15
            const periodNames = { 1: '1-2节(8:30-10:10)', 2: '3-4节(10:25-12:00)', 3: '5-6节(14:00-15:40)', 4: '7-8节(15:55-17:30)', 5: '9-10节(18:30-20:15)' };
            // 不在上课时段时无法确定节次，只有星期不足以判断「空闲」，提示用户到教室查询页选择时段
            if (!period) {
                addBotMessage('<p>当前不在上课时段，无法确定节次。请前往「教室查询」页面选择星期与节次后查询空教室。</p>');
                return;
            }
            const scopeName = '周' + '一二三四五六日'[weekDay - 1] + ' ' + periodNames[period];
            addBotMessage(`<p>正在查询${scopeName}的空教室…</p>`);
            const params = new URLSearchParams();
            params.append('weekDay', weekDay);
            params.append('startPeriod', period);
            params.append('endPeriod', period);
            fetch('/api/teacher/empty-classrooms?' + params.toString())
                .then(r => r.json())
                .then(data => {
                    if (data.success && data.data && data.data.length) {
                        const freeRooms = data.data.filter(c => c.available !== false);
                        const occupiedRooms = data.data.filter(c => c.available === false);
                        if (freeRooms.length) {
                            let rows = freeRooms.map(c => {
                                const tags = [];
                                if (c.room_type) tags.push(c.room_type);
                                return `${escapeHtml(c.classroom_no || '-')} | ${escapeHtml(c.building || '-')} | ${formatCapacity(c.capacity)}${tags.length ? ' | ' + tags.join('、') : ''}`;
                            }).join('\n');
                            let msg = `${scopeName}空教室（共${freeRooms.length}间）：\n${rows}`;
                            if (occupiedRooms.length) {
                                msg += `\n\n另有 ${occupiedRooms.length} 间教室该时段被占用（不可预约）。`;
                            }
                            addBotMessage(`<pre style="white-space:pre-wrap;margin:0;">${msg}\n\n如需按其他时段筛选或预约教室，请前往「教室查询」页面。</pre>`);
                        } else if (occupiedRooms.length) {
                            addBotMessage(`<p>${scopeName}全部教室被占用（共${occupiedRooms.length}间），如需按其他时段筛选，请前往「教室查询」页面。</p>`);
                        } else {
                            addBotMessage(`<p>${scopeName}暂无空教室。如需按其他时段筛选或预约教室，请前往「教室查询」页面。</p>`);
                        }
                    } else if (data.success) {
                        addBotMessage(`<p>${scopeName}暂无空教室。如需按其他时段筛选或预约教室，请前往「教室查询」页面。</p>`);
                    } else {
                        addBotMessage(`<p>${escapeHtml(data.message || '查询失败')}</p>`);
                    }
                })
                .catch(err => addBotMessage('<p>查询失败：网络异常</p>'));
        }

        function quickClassStudents() {
            fetch('/api/teacher/classes')
                .then(r => r.json())
                .then(data => {
                    if (data.success && data.data && data.data.length) {
                        const cls = data.data[0];
                        return fetch(`/api/teacher/classes/${cls.id}/students`).then(r => r.json()).then(d => {
                            if (d.success && d.data && d.data.length) {
                                let rows = d.data.map(s => `${escapeHtml(s.name || '-')}（${escapeHtml(s.studentId || '-')}）${escapeHtml(s.major || '')}`).join('\n');
                                addBotMessage(`<pre style="white-space:pre-wrap;margin:0;">${escapeHtml(cls.className || '班级')} 学生名单：\n${rows}</pre>`);
                            } else {
                                addBotMessage(`<p>${escapeHtml(d.message || '暂无学生')}</p>`);
                            }
                        });
                    } else {
                        addBotMessage(`<p>${escapeHtml(data.message || '您当前未管理任何班级')}</p>`);
                    }
                })
                .catch(err => addBotMessage('<p>查询失败：网络异常</p>'));
        }

        function quickWeekCourses() {
            const weekdays = ['周日','周一','周二','周三','周四','周五','周六'];
            const today = new Date().getDay();
            const todayName = weekdays[today];
            addBotMessage(`<p>正在查询今日（${todayName}）的课程…</p>`);
            fetch('/api/teacher/courses')
                .then(r => r.json())
                .then(data => {
                    if (data.success && data.courses && data.courses.length) {
                        let todayCourses = data.courses.filter(c => c.weekDay == today);
                        if (todayCourses.length) {
                            let rows = todayCourses.map(c => {
                                const period = (c.startPeriod && c.endPeriod) ? (c.startPeriod + '-' + c.endPeriod + '节') : '-';
                                return `${escapeHtml(c.courseName || '-')} | ${escapeHtml(c.className || '-')} | ${period} | ${escapeHtml(c.classroomNo || '-')}`;
                            }).join('\n');
                            addBotMessage(`<pre style="white-space:pre-wrap;margin:0;">今日（${todayName}）课程：\n${rows}</pre>`);
                        } else {
                            addBotMessage(`<p>今日（${todayName}）没有课程安排。</p>`);
                        }
                    } else {
                        addBotMessage(`<p>${escapeHtml(data.message || '暂无授课安排')}</p>`);
                    }
                })
                .catch(err => addBotMessage('<p>查询失败：网络异常</p>'));
        }

        function sendMessage(predefinedMessage) {
            const input = document.getElementById('chatInput');
            const message = predefinedMessage || input.value.trim();

            if (!message) return;

            input.value = '';
            document.getElementById('sendBtn').disabled = true;

            const chatMessages = document.getElementById('chatMessages');

            const userMessage = document.createElement('div');
            userMessage.className = 'message user';
            userMessage.innerHTML = `<p>${escapeHtml(message)}</p>`;
            chatMessages.appendChild(userMessage);

            const typingIndicator = document.createElement('div');
            typingIndicator.className = 'typing-indicator';
            typingIndicator.innerHTML = '<span></span><span></span><span></span>';
            chatMessages.appendChild(typingIndicator);

            chatMessages.scrollTop = chatMessages.scrollHeight;

            fetchAi('/api/chat', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    message: message
                })
            })
            .then(response => response.json())
            .then(data => {
                chatMessages.removeChild(typingIndicator);

                if (data.success && data.data) {
                    const response = data.data.response;
                    const botMessage = document.createElement('div');
                    botMessage.className = 'message bot';
                    botMessage.innerHTML = `<pre style="white-space: pre-wrap; margin: 0;">${escapeHtml(response)}</pre>`;
                    chatMessages.appendChild(botMessage);
                } else {
                    const errorMsg = data.message || '抱歉，出现了一些问题，请稍后重试。';
                    const errorMessage = document.createElement('div');
                    errorMessage.className = 'message bot';
                    errorMessage.innerHTML = `<p>${escapeHtml(errorMsg)}</p>`;
                    chatMessages.appendChild(errorMessage);
                }

                chatMessages.scrollTop = chatMessages.scrollHeight;
                document.getElementById('sendBtn').disabled = false;
            })
            .catch(error => {
                chatMessages.removeChild(typingIndicator);

                aiFetchLog(error, 'Chat error:');

                const errorMessage = document.createElement('div');
                errorMessage.className = 'message bot';
                errorMessage.innerHTML = `<p>${aiFetchErrorText(error, '网络连接异常，请检查网络后重试。')}</p>`;
                chatMessages.appendChild(errorMessage);

                chatMessages.scrollTop = chatMessages.scrollHeight;
                document.getElementById('sendBtn').disabled = false;
            });
        }
