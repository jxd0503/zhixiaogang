/********** AI 排课第 3 步：已审核通过排课约束的快捷选择与批量导入 **********/

var asAdoptedRows = [];

/** 加载已审核通过（ADOPTED）的教师排课约束并渲染到第 3 步快捷选择区 */
function asLoadAdoptedConstraints() {
    var listEl = document.getElementById('asAdoptedList');
    if (!listEl) return;
    var keep = {};
    asSelectedAdoptedIds().forEach(function (id) { keep[id] = true; });
    listEl.innerHTML = '<p class="empty-hint">加载中...</p>';
    fetch('/admin/schedule-constraints/list?status=ADOPTED')
        .then(function (r) { return r.json(); })
        .then(function (data) {
            if (!data || !data.success) {
                listEl.innerHTML = '<p class="empty-hint">' + escapeHtml((data && data.message) || '加载失败') + '</p>';
                return;
            }
            asAdoptedRows = data.requests || [];
            asRenderAdoptedConstraints(keep);
        })
        .catch(function () {
            listEl.innerHTML = '<p class="empty-hint">加载失败：网络异常</p>';
        });
}

/** 渲染约束卡片：整卡可点击勾选，展示原文与 AI 预解析结果 */
function asRenderAdoptedConstraints(keep) {
    var listEl = document.getElementById('asAdoptedList');
    if (!listEl) return;
    if (!asAdoptedRows.length) {
        listEl.innerHTML = '<p class="empty-hint">暂无已审核通过的排课约束，教师提交要求并经审核通过后会在此展示</p>';
        asUpdateAdoptedSelHint();
        return;
    }
    listEl.innerHTML = asAdoptedRows.map(function (r) {
        var picked = keep && keep[r.id] ? ' checked' : '';
        var scope = r.className ? escapeHtml(r.className) : '不限定班级';
        var items = asParseReqItems(r.parsedJson).map(function (it) {
            return '<li>' + escapeHtml(it.description || '') + '</li>';
        }).join('');
        return '<label class="as-adopted-item">'
            + '<input type="checkbox" class="as-adopted-pick" value="' + r.id + '"' + picked
            + ' onchange="asUpdateAdoptedSelHint()">'
            + '<div class="as-adopted-main">'
            + '<div class="as-adopted-item-head">'
            + '<span class="as-req-teacher">' + escapeHtml(r.teacherName || '') + '</span>'
            + '<span class="as-req-status as-req-adopted">已审核通过</span>'
            + '<span class="as-req-meta">' + scope + ' · ' + escapeHtml(r.semester || '未设置学期')
            + (r.updateTime ? ' · ' + escapeHtml(asFormatReqTime(r.updateTime)) : '') + '</span>'
            + '</div>'
            + '<div class="as-adopted-text">' + escapeHtml(r.constraintText || '') + '</div>'
            + (items ? '<div class="as-adopted-parsed"><span class="as-req-parsed-title">AI 预解析</span><ul>' + items + '</ul></div>'
                : '<div class="as-adopted-parsed as-req-parsed-empty">AI 预解析：未识别出结构化约束（将按原文导入）</div>')
            + '</div>'
            + '</label>';
    }).join('');
    asUpdateAdoptedSelHint();
}

function asSelectedAdoptedIds() {
    var ids = [];
    document.querySelectorAll('#asAdoptedList .as-adopted-pick:checked').forEach(function (el) {
        var v = parseInt(el.value, 10);
        if (!isNaN(v)) ids.push(v);
    });
    return ids;
}

function asToggleAdoptedAll(checked) {
    document.querySelectorAll('#asAdoptedList .as-adopted-pick').forEach(function (el) {
        el.checked = checked;
    });
    asUpdateAdoptedSelHint();
}

function asUpdateAdoptedSelHint() {
    var all = document.querySelectorAll('#asAdoptedList .as-adopted-pick');
    var checked = document.querySelectorAll('#asAdoptedList .as-adopted-pick:checked');
    var hint = document.getElementById('asAdoptedSelHint');
    if (hint) hint.textContent = '已选 ' + checked.length + ' 条 / 共 ' + all.length + ' 条';
    var allBox = document.getElementById('asAdoptedAll');
    if (allBox) {
        allBox.checked = all.length > 0 && checked.length === all.length;
        allBox.indeterminate = checked.length > 0 && checked.length < all.length;
    }
}

/** 批量导入所选约束：接口返回标准化文本，逐行去重后完整回填到手动约束输入框 */
function asImportAdoptedConstraints() {
    var ids = asSelectedAdoptedIds();
    var btn = document.getElementById('asAdoptedImportBtn');
    var msg = document.getElementById('asAdoptedImportMsg');
    if (!ids.length) {
        showProviderToast('请先勾选要导入的已审核通过约束', 'error');
        return;
    }
    if (btn) { btn.disabled = true; btn.textContent = '导入中...'; }
    if (msg) { msg.className = 'as-adopted-msg'; msg.textContent = ''; }
    fetch('/admin/schedule-constraints/fill', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ids: ids, semester: '' })
    })
        .then(function (r) { return r.json(); })
        .then(function (data) {
            if (btn) { btn.disabled = false; btn.textContent = '导入所选约束'; }
            if (!data || !data.success) {
                if (msg) {
                    msg.className = 'as-adopted-msg error';
                    msg.textContent = (data && data.message) || '导入失败';
                }
                showProviderToast((data && data.message) || '导入失败', 'error');
                return;
            }
            var ta = document.getElementById('asConstraints');
            if (!ta) return;
            var lines = [];
            (ta.value || '').split(/\r?\n/).forEach(function (line) {
                var s = line.trim();
                if (s && lines.indexOf(s) < 0) lines.push(s);
            });
            var added = 0;
            String(data.constraints || '').split(/\r?\n/).forEach(function (line) {
                var s = line.trim();
                if (s && lines.indexOf(s) < 0) { lines.push(s); added++; }
            });
            ta.value = lines.join('\n');
            if (msg) {
                msg.textContent = added > 0
                    ? '已导入 ' + added + ' 条约束并回填到下方约束框'
                    : '所选约束均已在约束框中，无需重复导入';
            }
            showProviderToast(added > 0 ? '已导入 ' + added + ' 条约束' : '所选约束已在约束框中', 'success');
            ta.scrollIntoView({ behavior: 'smooth', block: 'center' });
            ta.focus();
        })
        .catch(function () {
            if (btn) { btn.disabled = false; btn.textContent = '导入所选约束'; }
            if (msg) {
                msg.className = 'as-adopted-msg error';
                msg.textContent = '导入失败：网络异常';
            }
            showProviderToast('导入失败：网络异常', 'error');
        });
}
