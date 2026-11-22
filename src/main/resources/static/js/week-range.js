/**
 * 周次范围统一解析（与后端 com.zhixiaogang.util.WeekRangeParser 同规则）。
 * 支持数字格式 1-16 / 3-6 / 1-8,10-12，也支持 单周、双周、全部、前8周、后4周、
 * 第3周到第6周、第3、5、8周 与混合写法（单周前8周 取交集），解析结果统一归一化为数字格式。
 */
(function (global) {
    'use strict';

    var DEFAULT_TOTAL_WEEKS = 20;
    var MAX_WEEK = 99;
    var FORMAT_HINT = '支持 1-16、3-6、1-8,10-12，也支持 单周、双周、全部、前8周、后4周、第3周到第6周、单周前8周';

    function toHalfWidth(text) {
        return String(text)
            .replace(/[\uFF01-\uFF5E]/g, function (ch) {
                return String.fromCharCode(ch.charCodeAt(0) - 0xFEE0);
            })
            .replace(/\u3000/g, ' ');
    }

    /** 学期总周数：优先管理端学期配置输入框，其次教师/学生课表加载的全局值，最后默认 20 */
    function resolveTotalWeeks() {
        var el = document.getElementById('semesterWeeks');
        if (el) {
            var n = parseInt(el.value, 10);
            if (n >= 1) return n;
        }
        if (global.__weekRangeTotalWeeks >= 1) return global.__weekRangeTotalWeeks;
        try {
            if (typeof teacherTotalWeeks === 'number' && teacherTotalWeeks >= 1) return teacherTotalWeeks;
        } catch (e) {
            // 变量未声明时忽略
        }
        try {
            if (typeof scheduleTotalWeeks === 'number' && scheduleTotalWeeks >= 1) return scheduleTotalWeeks;
        } catch (e) {
            // 变量未声明时忽略
        }
        return DEFAULT_TOTAL_WEEKS;
    }

    function rangeSet(from, to) {
        var list = [];
        for (var w = Math.max(from, 1); w <= to; w++) list.push(w);
        return list;
    }

    function stepSet(from, to, step) {
        var list = [];
        for (var w = from; w <= to; w += step) list.push(w);
        return list;
    }

    function intersect(left, right) {
        if (!left) return right.slice();
        return left.filter(function (w) {
            return right.indexOf(w) >= 0;
        });
    }

    function expandPlain(text) {
        var set = [];
        var parts = String(text).split(',');
        for (var i = 0; i < parts.length; i++) {
            var p = parts[i].trim();
            if (!p) return null;
            if (p.indexOf('-') >= 0) {
                var se = p.split('-');
                if (se.length !== 2) return null;
                var a = parseInt(se[0], 10);
                var b = parseInt(se[1], 10);
                if (isNaN(a) || isNaN(b)) return null;
                var lo = Math.min(a, b);
                var hi = Math.max(a, b);
                if (lo < 1 || hi > MAX_WEEK) return null;
                for (var w = lo; w <= hi; w++) set.push(w);
            } else {
                var v = parseInt(p, 10);
                if (isNaN(v) || v < 1 || v > MAX_WEEK) return null;
                set.push(v);
            }
        }
        return set;
    }

    /** 连续 3 个及以上压缩为区间，其余逐个列出 */
    function format(set) {
        var sorted = set.slice().sort(function (x, y) { return x - y; });
        var uniq = [];
        sorted.forEach(function (w) {
            if (uniq[uniq.length - 1] !== w) uniq.push(w);
        });
        var out = [];
        var i = 0;
        while (i < uniq.length) {
            var j = i;
            while (j + 1 < uniq.length && uniq[j + 1] === uniq[j] + 1) j++;
            if (j - i + 1 >= 3) {
                out.push(uniq[i] + '-' + uniq[j]);
            } else {
                for (var k = i; k <= j; k++) out.push(String(uniq[k]));
            }
            i = j + 1;
        }
        return out.join(',');
    }

    function ok(set, label) {
        var normalized = format(set);
        var weeks = normalized ? expandPlain(normalized) || [] : [];
        var sorted = weeks.slice().sort(function (x, y) { return x - y; });
        return { valid: true, normalized: normalized, error: '', weeks: sorted, label: label || '' };
    }

    function fail(error) {
        return { valid: false, normalized: '', error: error, weeks: [], label: '' };
    }

    function parse(raw, totalWeeks) {
        if (raw == null) return { valid: true, normalized: '', error: '', weeks: [], label: '' };
        var text = String(raw).trim();
        if (!text) return { valid: true, normalized: '', error: '', weeks: [], label: '' };
        var tw = totalWeeks >= 1 ? totalWeeks : resolveTotalWeeks();
        var work = toHalfWidth(text).replace(/\s+/g, '');
        if (!work) return { valid: true, normalized: '', error: '', weeks: [], label: '' };
        if (/^\d{1,2}(-\d{1,2})?(,\d{1,2}(-\d{1,2})?)*$/.test(work)) {
            var plain = expandPlain(work);
            if (!plain) return fail('周次数字写法有误，需为 1-' + MAX_WEEK + ' 之间的整数或区间');
            return ok(plain, '');
        }

        var constraint = null;
        var hasModifier = false;
        var label = '';

        if (/全部|整学期|全学期|所有周|所有|每周|全周/.test(work)) {
            constraint = intersect(constraint, rangeSet(1, tw));
            hasModifier = true;
            work = work.replace(/全部|整学期|全学期|所有周|所有|每周|全周/g, '');
        }
        work = work.replace(/前(\d{1,2})周/g, function (m, n) {
            var count = Math.min(Math.max(parseInt(n, 10), 1), tw);
            constraint = intersect(constraint, rangeSet(1, count));
            hasModifier = true;
            return '';
        });
        work = work.replace(/后(\d{1,2})周/g, function (m, n) {
            var count = Math.min(Math.max(parseInt(n, 10), 1), tw);
            constraint = intersect(constraint, rangeSet(tw - count + 1, tw));
            hasModifier = true;
            return '';
        });
        if (/单周|奇数周|逢单/.test(work)) {
            constraint = intersect(constraint, stepSet(1, tw, 2));
            hasModifier = true;
            label = '单周';
            work = work.replace(/单周|奇数周|逢单/g, '');
        }
        if (/双周|偶数周|逢双/.test(work)) {
            constraint = intersect(constraint, stepSet(2, tw, 2));
            hasModifier = true;
            label = '双周';
            work = work.replace(/双周|偶数周|逢双/g, '');
        }

        work = work.replace(/第/g, '')
            .replace(/周/g, '')
            .replace(/[到至~～—－–]/g, '-')
            .replace(/[、，;；]/g, ',');

        if (work) {
            if (!/^[0-9,\-]+$/.test(work)) {
                return fail('无法识别的写法，请参考：' + FORMAT_HINT);
            }
            var base = expandPlain(work);
            if (!base) return fail('周次数字写法有误，需为 1-' + MAX_WEEK + ' 之间的整数或区间');
            constraint = intersect(constraint, base);
        }

        if (!hasModifier && !constraint) {
            return fail('无法识别的写法，请参考：' + FORMAT_HINT);
        }
        if (!constraint || constraint.length === 0) {
            return fail('周次条件没有交集（例如同时填写单周与双周），请检查后重新输入');
        }
        return ok(constraint, label);
    }

    function normalize(raw) {
        var r = parse(raw);
        return r.valid ? r.normalized : '';
    }

    var LANGUAGE_RE = /全部|整学期|全学期|所有周|所有|每周|全周|前\d{1,2}周|后\d{1,2}周|单周|奇数周|逢单|双周|偶数周|逢双/;

    function hasNaturalLanguage(text) {
        return LANGUAGE_RE.test(toHalfWidth(String(text)).replace(/\s+/g, ''));
    }

    /**
     * 调课场景：以原课表生效周次为上界展开自然语言周次，结果限定在原课表周次内。
     * 原课表 1-18：双周 → 2,4,6,8,10,12,14,16,18；单周 → 1,3,...,17；全部 → 1-18。
     * 纯数字写法不做钳制，仍按原规则校验是否越界。
     */
    function parseWithin(raw, baseRange) {
        if (raw == null || !String(raw).trim()) return parse(raw);
        var base = parse(baseRange);
        if (!base.valid || !base.weeks.length || !hasNaturalLanguage(raw)) return parse(raw);
        var cap = base.weeks[base.weeks.length - 1];
        var r = parse(raw, cap);
        if (!r.valid) return r;
        var kept = r.weeks.filter(function (w) {
            return base.weeks.indexOf(w) >= 0;
        });
        if (!kept.length) return fail('周次条件与原课表生效周次没有交集，请按原课表周次重新输入');
        return ok(kept, r.label);
    }

    /** 解析失败时按旧规则只保留数字、逗号、短横线，避免历史数据被清空 */
    function normalizeOrFallback(raw) {
        var r = parse(raw);
        if (r.valid) return r.normalized;
        if (raw == null) return '';
        var kept = toHalfWidth(raw).replace(/[^0-9,\-]/g, '');
        var fallback = parse(kept);
        return fallback.valid ? fallback.normalized : '';
    }

    function describe(r) {
        return (r.label ? r.label + ' ' : '') + r.normalized;
    }

    /** 给输入框绑定实时解析：输入时提示、失焦时写回标准数字格式 */
    function attach(input, opts) {
        if (!input) return null;
        if (input.__weekRangeValidate) return { validate: input.__weekRangeValidate };
        opts = opts || {};
        var hintEl = opts.hintEl || (input.parentNode ? input.parentNode.querySelector('.week-hint') : null);
        function show(message, valid) {
            input.classList.toggle('week-range-invalid', !!message && !valid);
            if (hintEl) {
                hintEl.textContent = message || '';
                hintEl.className = 'week-hint' + (message ? (valid ? ' ok' : ' err') : '');
                hintEl.style.display = message ? '' : 'none';
            }
            if (typeof opts.onHint === 'function') opts.onHint(message || '', valid);
        }
        function validate(commit) {
            var raw = (input.value || '').trim();
            var baseRange = opts.baseRange || input.__weekRangeBaseRange;
            var r = baseRange ? parseWithin(raw, baseRange) : parse(raw);
            if (!r.valid) {
                show(r.error, false);
                return false;
            }
            if (commit && raw && r.normalized !== raw) input.value = r.normalized;
            // hideParsedHint：合法值不展示「已解析为」提示，只保留非法值错误提示
            show(raw && !opts.hideParsedHint ? ('已解析为：' + describe(r)) : '', true);
            if (typeof opts.onValid === 'function') opts.onValid(r);
            return true;
        }
        input.addEventListener('input', function () { validate(false); });
        input.addEventListener('change', function () { validate(true); });
        input.addEventListener('blur', function () { validate(true); });
        input.__weekRangeValidate = validate;
        return { validate: validate };
    }

    /** 批量绑定容器内所有 input[data-week-range] */
    function attachAll(root) {
        var scope = root || document;
        var nodes = scope.querySelectorAll('input[data-week-range]');
        return Array.prototype.map.call(nodes, function (el) {
            return attach(el, {});
        });
    }

    /** 校验并归一化输入框的值（用于提交前拦截），返回 null 表示非法 */
    function readInput(input) {
        if (!input) return '';
        var raw = (input.value || '').trim();
        var r = input.__weekRangeBaseRange ? parseWithin(raw, input.__weekRangeBaseRange) : parse(raw);
        if (!r.valid) return null;
        input.value = r.normalized;
        return r.normalized;
    }

    /** 设置输入框的解析基准（调课场景传原课表周次，自然语言展开不越出该范围） */
    function setBaseRange(input, baseRange) {
        if (!input) return;
        input.__weekRangeBaseRange = baseRange || '';
    }

    global.WeekRange = {
        DEFAULT_TOTAL_WEEKS: DEFAULT_TOTAL_WEEKS,
        FORMAT_HINT: FORMAT_HINT,
        parse: parse,
        parseWithin: parseWithin,
        isValid: function (raw) { return parse(raw).valid; },
        normalize: normalize,
        normalizeOrFallback: normalizeOrFallback,
        describe: describe,
        readInput: readInput,
        attach: attach,
        attachAll: attachAll,
        setBaseRange: setBaseRange,
        getTotalWeeks: resolveTotalWeeks,
        setTotalWeeks: function (n) {
            if (n >= 1) global.__weekRangeTotalWeeks = n;
        }
    };
})(window);
