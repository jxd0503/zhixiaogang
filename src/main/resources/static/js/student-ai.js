/********** student-ai：AI 助手对话与快捷查询 **********/

        let aiDisabled = false;
        
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
                            botMessage.innerHTML = `<div class="bot-avatar"></div><p>智能 AI 服务暂不可用，请稍后重试。</p>`;
                            chatMessages.appendChild(botMessage);
                            chatMessages.scrollTop = chatMessages.scrollHeight;
                        }
                    }
                })
                .catch(() => {
                    // 网络异常时保持默认启用，不打扰用户
                });
        }
        
        // 加载班级课表（只读）
        function handleKeyDown(event) {
            if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                sendMessage();
            }
        }
        
        function toggleDropdown(dropdownId) {
            const dropdown = document.getElementById(dropdownId);
            const allDropdowns = document.querySelectorAll('.dropdown-menu');
            
            allDropdowns.forEach(d => {
                if (d.id !== dropdownId) {
                    d.classList.remove('show');
                }
            });
            
            dropdown.classList.toggle('show');
        }
        
        document.addEventListener('click', function(event) {
            if (!event.target.closest('.dropdown-wrapper')) {
                document.querySelectorAll('.dropdown-menu').forEach(d => {
                    d.classList.remove('show');
                });
            }
        });

        function sendScheduleQuery(week, day) {
            sendMessage(`${day}课表`);
            closeAllDropdowns();
        }
        
        function sendClassroomQuery(building) {
            sendMessage(`${building}楼有哪些空教室？`);
            closeAllDropdowns();
        }
        
        function closeAllDropdowns() {
            document.querySelectorAll('.dropdown-menu').forEach(d => {
                d.classList.remove('show');
            });
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
                    botMessage.innerHTML = `<div class="bot-avatar"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="8" width="16" height="12" rx="2"/><path d="M12 8V4M8 4h8"/><circle cx="9" cy="14" r="1"/><circle cx="15" cy="14" r="1"/></svg></div><pre style="white-space: pre-wrap; margin: 0;">${escapeHtml(response)}</pre>`;
                    chatMessages.appendChild(botMessage);
                } else {
                    const errorMsg = data.message || '抱歉，出现了一些问题，请稍后重试。';
                    const errorMessage = document.createElement('div');
                    errorMessage.className = 'message bot';
                    errorMessage.innerHTML = `<div class="bot-avatar"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="8" width="16" height="12" rx="2"/><path d="M12 8V4M8 4h8"/><circle cx="9" cy="14" r="1"/><circle cx="15" cy="14" r="1"/></svg></div><p>${escapeHtml(errorMsg)}</p>`;
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
                errorMessage.innerHTML = `<div class="bot-avatar"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="8" width="16" height="12" rx="2"/><path d="M12 8V4M8 4h8"/><circle cx="9" cy="14" r="1"/><circle cx="15" cy="14" r="1"/></svg></div><p>${aiFetchErrorText(error, '网络连接异常，请检查网络后重试。')}</p>`;
                chatMessages.appendChild(errorMessage);
                
                chatMessages.scrollTop = chatMessages.scrollHeight;
                document.getElementById('sendBtn').disabled = false;
            });
        }
