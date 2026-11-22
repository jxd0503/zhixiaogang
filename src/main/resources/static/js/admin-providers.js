/********** 模型提供商：提供商列表渲染、新增 / 编辑弹窗、默认模型与启用状态 **********/
        let providerList = [];

        function loadProviders() {
            fetch('/admin/ai/providers')
                .then(response => response.json())
                .then(data => {
                    if (data.success) {
                        providerList = data.providers || [];
                        const notice = document.getElementById('providerKeyNotice');
                        if (notice) {
                            notice.style.display = data.keyEncryptionEnabled ? 'none' : 'block';
                        }
                        renderProviders();
                    } else {
                        showProviderToast('提供商列表加载失败', 'error');
                    }
                })
                .catch(() => showProviderToast('提供商列表加载失败', 'error'));
        }

        function renderProviders() {
            const grid = document.getElementById('providerGrid');
            const activeText = document.getElementById('activeProviderText');
            const activeBar = document.getElementById('activeProviderBar');
            const active = providerList.find(p => p.isDefault);

            if (active) {
                activeText.textContent = active.name + ' \u00B7 ' + active.currentModel;
                activeBar.className = 'active-provider-bar';
            } else {
                activeText.textContent = '暂无生效提供商（AI 模式将不可用）';
                activeBar.className = 'active-provider-bar no-active';
            }

            if (providerList.length === 0) {
                grid.innerHTML = '<div class="provider-empty">暂无提供商，点击右上角「新增提供商」添加。</div>';
                return;
            }

            grid.innerHTML = providerList.map(providerCardHtml).join('');
        }

        function providerCardHtml(p) {
            const badge = p.isDefault
                ? '<span class="provider-badge badge-default">生效中</span>'
                : (p.enabled ? '<span class="provider-badge badge-enabled">已启用</span>'
                             : '<span class="provider-badge badge-disabled">已停用</span>');

            const modelOptions = (p.models || []).map(function (m) {
                return '<option value="' + escapeHtml(m) + '"' + (m === p.currentModel ? ' selected' : '') + '>' + escapeHtml(m) + '</option>';
            }).join('');

            const keyText = p.hasKey ? ('当前密钥：' + escapeHtml(p.apiKey)) : '未配置 API Key';

            const defaultBtn = p.isDefault ? '' :
                '<button class="provider-btn provider-btn-outline" onclick="setProviderDefault(' + p.id + ')">设为生效</button>';
            const toggleBtn = p.isDefault
                ? '<button class="provider-btn" disabled>' + (p.enabled ? '停用' : '启用') + '</button>'
                : '<button class="provider-btn" onclick="toggleProviderState(' + p.id + ')">' + (p.enabled ? '停用' : '启用') + '</button>';
            const deleteBtn = p.isDefault
                ? '<button class="provider-btn provider-btn-danger" disabled>删除</button>'
                : '<button class="provider-btn provider-btn-danger" onclick="deleteProvider(' + p.id + ')">删除</button>';

            return ''
                + '<div class="provider-card' + (p.isDefault ? ' is-default' : '') + '">'
                +   '<div class="provider-card-head">'
                +     '<div class="provider-name">' + escapeHtml(p.name) + '</div>'
                +     badge
                +   '</div>'
                +   '<div class="provider-card-body">'
                +     '<div class="provider-field">'
                +       '<label class="provider-field-label">生效模型</label>'
                +       '<select class="provider-model-select" onchange="setProviderModel(' + p.id + ', this.value)">' + modelOptions + '</select>'
                +     '</div>'
                +     '<div class="provider-meta"><span class="provider-meta-label">API Key</span><span class="provider-meta-value ' + (p.hasKey ? '' : 'no-key') + '">' + keyText + '</span></div>'
                +     '<div class="provider-meta"><span class="provider-meta-label">API 地址</span><span class="provider-meta-value api-url" title="' + escapeHtml(p.apiUrl) + '">' + escapeHtml(p.apiUrl) + '</span></div>'
                +   '</div>'
                +   '<div class="provider-card-foot">'
                +     defaultBtn
                +     toggleBtn
                +     '<button class="provider-btn" onclick="openProviderModal(' + p.id + ')">编辑</button>'
                +     deleteBtn
                +   '</div>'
                + '</div>';
        }

        function openProviderModal(id) {
            const modal = document.getElementById('providerEditModal');
            document.getElementById('providerFormId').value = id || '';
            document.getElementById('providerFormApiKey').value = '';

            if (id) {
                const p = providerList.find(function (item) { return item.id === id; });
                if (!p) return;
                document.getElementById('providerModalTitle').textContent = '编辑提供商';
                document.getElementById('providerFormName').value = p.name || '';
                document.getElementById('providerFormApiUrl').value = p.apiUrl || '';
                document.getElementById('providerFormModels').value = (p.models || []).join(', ');
                document.getElementById('providerFormMultimodal').value = (p.multimodalModels || []).join(', ');
                document.getElementById('providerFormEnabled').checked = !!p.enabled;
                document.getElementById('providerKeyHint').textContent = p.hasKey
                    ? '当前密钥：' + p.apiKey + '，留空则保留原 Key'
                    : '尚未配置 Key，启用 AI 对话前请填写';
            } else {
                document.getElementById('providerModalTitle').textContent = '新增提供商';
                document.getElementById('providerFormName').value = '';
                document.getElementById('providerFormApiUrl').value = '';
                document.getElementById('providerFormModels').value = '';
                document.getElementById('providerFormMultimodal').value = '';
                document.getElementById('providerFormEnabled').checked = true;
                document.getElementById('providerKeyHint').textContent = '';
            }
            modal.classList.add('show');
        }

        function closeProviderModal() {
            document.getElementById('providerEditModal').classList.remove('show');
        }

        function saveProviderForm() {
            const idText = document.getElementById('providerFormId').value;
            const payload = {
                name: document.getElementById('providerFormName').value.trim(),
                apiUrl: document.getElementById('providerFormApiUrl').value.trim(),
                apiKey: document.getElementById('providerFormApiKey').value.trim(),
                models: document.getElementById('providerFormModels').value.trim(),
                multimodalModels: document.getElementById('providerFormMultimodal').value.trim(),
                enabled: document.getElementById('providerFormEnabled').checked
            };
            if (idText) payload.id = idText;

            fetch('/admin/ai/providers', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            })
                .then(response => response.json())
                .then(data => {
                    if (data.success) {
                        showProviderToast(data.message || '保存成功');
                        closeProviderModal();
                        loadProviders();
                    } else {
                        showProviderToast(data.message || '保存失败', 'error');
                    }
                })
                .catch(() => showProviderToast('保存失败', 'error'));
        }

        function setProviderDefault(id) {
            fetch('/admin/ai/providers/' + id + '/default', { method: 'POST' })
                .then(response => response.json())
                .then(data => {
                    showProviderToast(data.message, data.success ? 'success' : 'error');
                    if (data.success) loadProviders();
                });
        }

        function toggleProviderState(id) {
            fetch('/admin/ai/providers/' + id + '/toggle', { method: 'POST' })
                .then(response => response.json())
                .then(data => {
                    showProviderToast(data.message, data.success ? 'success' : 'error');
                    if (data.success) loadProviders();
                });
        }

        function setProviderModel(id, model) {
            fetch('/admin/ai/providers/' + id + '/model', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ model: model })
            })
                .then(response => response.json())
                .then(data => {
                    showProviderToast(data.message, data.success ? 'success' : 'error');
                    if (data.success) loadProviders();
                });
        }

        async function deleteProvider(id) {
            const ok = await showConfirm({
                title: '删除提供商',
                message: '确定删除该提供商吗？删除后不可恢复。',
                confirmText: '删除',
                danger: true
            });
            if (!ok) return;
            fetch('/admin/ai/providers/' + id, { method: 'DELETE' })
                .then(response => response.json())
                .then(data => {
                    showProviderToast(data.message, data.success ? 'success' : 'error');
                    if (data.success) loadProviders();
                });
        }

        // ===================== 班级管理（含班级详情 / 课表管理 / AI 导入） =====================
