/********** 数据概览：概览数字加载与错误态、AI 开关状态与切换 **********/
        // 概览数字在加载完成前显示占位符，加载失败时显示重试按钮，避免误导为 0
        const DASHBOARD_PLACEHOLDER = '—';

        function setDashboardNumbers(users, classrooms, pending, pendingAdj) {
            document.getElementById('totalUsers').textContent = users;
            document.getElementById('totalClassrooms').textContent = classrooms;
            document.getElementById('pendingReservations').textContent = pending;
            document.getElementById('pendingAdjustments').textContent = pendingAdj;
        }

        function showDashboardError(show) {
            const box = document.getElementById('dashboardLoadError');
            if (box) box.style.display = show ? 'flex' : 'none';
            if (show) setDashboardNumbers(DASHBOARD_PLACEHOLDER, DASHBOARD_PLACEHOLDER, DASHBOARD_PLACEHOLDER, DASHBOARD_PLACEHOLDER);
        }

        function loadDashboard() {
            showDashboardError(false);
            const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
            const timer = controller ? setTimeout(() => controller.abort(), 8000) : null;
            fetch('/admin/dashboard', { cache: 'no-store', signal: controller ? controller.signal : undefined })
                .then(response => {
                    // 非 2xx 仍按 JSON 读取，展示后端业务提示
                    if (response.redirected) throw new Error('auth');
                    return response.json();
                })
                .then(data => {
                    if (!data || data.success !== true) {
                        throw new Error((data && data.message) || '加载失败');
                    }
                    const pick = v => (v === null || v === undefined ? 0 : v);
                    setDashboardNumbers(pick(data.totalUsers), pick(data.totalClassrooms),
                        pick(data.pendingReservations), pick(data.pendingAdjustments));
                })
                .catch(() => showDashboardError(true))
                .finally(() => { if (timer) clearTimeout(timer); });
            checkAIStatus();
        }
        
        function checkAIStatus() {
            fetch('/admin/ai/status')
                .then(response => response.json())
                .then(data => {
                    if (data.success) {
                        updateAIButton(data.disabled);
                    }
                });
        }
        
        function updateAIButton(disabled) {
            const btn = document.getElementById('aiToggleBtn');
            const statusText = document.getElementById('aiStatusText');
            if (disabled) {
                btn.textContent = '启用智能AI';
                btn.className = 'action-btn edit';
                statusText.textContent = '智能AI状态：已断开';
                statusText.style.color = '#C62828';
            } else {
                btn.textContent = '断开智能AI';
                btn.className = 'action-btn delete';
                statusText.textContent = '智能AI状态：运行中';
                statusText.style.color = '#2E7D32';
            }
        }
        
        function toggleAI() {
            const btn = document.getElementById('aiToggleBtn');
            const isDisabled = btn.textContent === '启用智能AI';
            
            const url = isDisabled ? '/admin/ai/enable' : '/admin/ai/disable';
            fetch(url, { method: 'POST' })
                .then(response => response.json())
                .then(data => {
                    if (data.success) {
                        showToast(data.message, 'success');
                        updateAIButton(!isDisabled);
                    } else {
                        showToast('操作失败', 'error');
                    }
                });
        }
        // 首屏即加载概览数据：DOM 就绪就请求，不依赖 window.load（个别资源加载慢时也能及时显示）
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', loadDashboard);
        } else {
            loadDashboard();
        }
