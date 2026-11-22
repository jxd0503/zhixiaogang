package com.zhixiaogang.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 周次范围统一解析工具：数字格式与自然语言写法并存，解析结果统一归一化为标准数字格式。
 * 支持 1-16 / 3-6 / 1-8,10-12，以及 单周、双周、全部、前8周、后4周、第3周到第6周、
 * 第3、5、8周 与混合写法（单周前8周 = 取交集）。所有页面与接口共用本类，禁止各自实现。
 */
public final class WeekRangeParser {

    /** 未配置学期总周数时的默认值 */
    public static final int DEFAULT_TOTAL_WEEKS = 20;

    /** 可接受写法示例，供前后端提示文案复用 */
    public static final String FORMAT_HINT =
            "支持 1-16、3-6、1-8,10-12，也支持 单周、双周、全部、前8周、后4周、第3周到第6周、单周前8周";

    /** 星期/周次上限，超出的数字视为非法输入 */
    private static final int MAX_WEEK = 99;

    private static volatile int defaultTotalWeeks = DEFAULT_TOTAL_WEEKS;

    private static final Pattern PLAIN = Pattern.compile("\\d{1,2}(-\\d{1,2})?(,\\d{1,2}(-\\d{1,2})?)*");
    private static final Pattern FRONT_N = Pattern.compile("前(\\d{1,2})周");
    private static final Pattern BACK_N = Pattern.compile("后(\\d{1,2})周");
    private static final Pattern ALL_WORDS = Pattern.compile("全部|整学期|全学期|所有周|所有|每周|全周");
    private static final Pattern ODD_WORDS = Pattern.compile("单周|奇数周|逢单");
    private static final Pattern EVEN_WORDS = Pattern.compile("双周|偶数周|逢双");

    private WeekRangeParser() {
    }

    /** 解析结果：valid=false 时 error 给出原因；normalized 为标准数字格式（如 1-18、1,3,5,7） */
    public static final class Result {
        public final boolean valid;
        public final String normalized;
        public final String error;
        public final List<Integer> weeks;

        private Result(boolean valid, String normalized, String error, List<Integer> weeks) {
            this.valid = valid;
            this.normalized = normalized;
            this.error = error;
            this.weeks = weeks;
        }

        public boolean isEmpty() {
            return normalized.isEmpty();
        }
    }

    public static int getDefaultTotalWeeks() {
        return defaultTotalWeeks;
    }

    /** 由学期配置注入总周数（1-40），非法值忽略 */
    public static void setDefaultTotalWeeks(int totalWeeks) {
        if (totalWeeks >= 1) {
            defaultTotalWeeks = totalWeeks;
        }
    }

    public static Result parse(String raw) {
        return parse(raw, defaultTotalWeeks);
    }

    /** 自然语言与数字格式统一解析；空值视为未填写（valid=true，normalized 为空） */
    public static Result parse(String raw, int totalWeeks) {
        if (raw == null || raw.trim().isEmpty()) {
            return new Result(true, "", null, new ArrayList<>());
        }
        int tw = totalWeeks >= 1 ? totalWeeks : DEFAULT_TOTAL_WEEKS;
        String work = toHalfWidth(raw.trim()).replaceAll("\\s+", "");
        if (work.isEmpty()) {
            return new Result(true, "", null, new ArrayList<>());
        }
        if (PLAIN.matcher(work).matches()) {
            Set<Integer> plain = expandPlain(work);
            if (plain == null) {
                return invalid("周次数字写法有误，需为 1-" + MAX_WEEK + " 之间的整数或区间");
            }
            return ok(plain);
        }

        Set<Integer> constraint = null;
        boolean hasModifier = false;

        if (ALL_WORDS.matcher(work).find()) {
            constraint = intersect(constraint, rangeSet(1, tw));
            hasModifier = true;
            work = ALL_WORDS.matcher(work).replaceAll("");
        }
        Matcher front = FRONT_N.matcher(work);
        while (front.find()) {
            int n = Integer.parseInt(front.group(1));
            constraint = intersect(constraint, rangeSet(1, Math.min(Math.max(n, 1), tw)));
            hasModifier = true;
        }
        work = FRONT_N.matcher(work).replaceAll("");
        Matcher back = BACK_N.matcher(work);
        while (back.find()) {
            int n = Integer.parseInt(back.group(1));
            int count = Math.min(Math.max(n, 1), tw);
            constraint = intersect(constraint, rangeSet(tw - count + 1, tw));
            hasModifier = true;
        }
        work = BACK_N.matcher(work).replaceAll("");
        if (ODD_WORDS.matcher(work).find()) {
            constraint = intersect(constraint, stepSet(1, tw, 2));
            hasModifier = true;
            work = ODD_WORDS.matcher(work).replaceAll("");
        }
        if (EVEN_WORDS.matcher(work).find()) {
            constraint = intersect(constraint, stepSet(2, tw, 2));
            hasModifier = true;
            work = EVEN_WORDS.matcher(work).replaceAll("");
        }

        work = work.replace("第", "").replace("周", "")
                .replaceAll("[到至~～—－–]", "-")
                .replaceAll("[、，;；]", ",");

        if (!work.isEmpty()) {
            if (!work.matches("[0-9,\\-]+")) {
                return invalid("无法识别的写法，请参考：" + FORMAT_HINT);
            }
            Set<Integer> base = expandPlain(work);
            if (base == null) {
                return invalid("周次数字写法有误，需为 1-" + MAX_WEEK + " 之间的整数或区间");
            }
            constraint = intersect(constraint, base);
        }

        if (!hasModifier && constraint == null) {
            return invalid("无法识别的写法，请参考：" + FORMAT_HINT);
        }
        if (constraint == null || constraint.isEmpty()) {
            return invalid("周次条件没有交集（例如同时填写单周与双周），请检查后重新输入");
        }
        return ok(constraint);
    }

    public static boolean isValid(String raw) {
        return parse(raw).valid;
    }

    /** 归一化为标准数字格式；空值返回空串，非法返回空串 */
    public static String normalize(String raw) {
        Result r = parse(raw);
        return r.valid ? r.normalized : "";
    }

    /** 归一化并回退：解析失败时按旧规则只保留数字、逗号、短横线，尽量避免历史数据被清空 */
    public static String normalizeOrFallback(String raw) {
        Result r = parse(raw);
        if (r.valid) {
            return r.normalized;
        }
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : toHalfWidth(raw.trim()).toCharArray()) {
            if (Character.isDigit(c) || c == ',' || c == '-') {
                sb.append(c);
            }
        }
        Result fallback = parse(sb.toString());
        return fallback.valid ? fallback.normalized : "";
    }

    /** 是否包含自然语言修饰词（单周/双周/全部/前N周/后N周等） */
    public static boolean hasNaturalLanguage(String raw) {
        if (raw == null) {
            return false;
        }
        String work = toHalfWidth(raw.trim()).replaceAll("\\s+", "");
        return ALL_WORDS.matcher(work).find() || FRONT_N.matcher(work).find()
                || BACK_N.matcher(work).find() || ODD_WORDS.matcher(work).find()
                || EVEN_WORDS.matcher(work).find();
    }

    /**
     * 调课场景：以原课表生效周次（baseRange）为上界展开自然语言周次，结果限定在原课表周次内。
     * 例如原课表 1-18：双周 → 2,4,6,8,10,12,14,16,18；单周 → 1,3,...,17；全部 → 1-18。
     * 纯数字写法不做钳制，仍按原规则校验是否越界。
     */
    public static Result parseWithin(String raw, String baseRange) {
        if (raw == null || raw.trim().isEmpty()) {
            return parse(raw);
        }
        Set<Integer> base = weeks(baseRange);
        if (base.isEmpty() || !hasNaturalLanguage(raw)) {
            return parse(raw);
        }
        int cap = base.stream().mapToInt(Integer::intValue).max().orElse(defaultTotalWeeks);
        Result parsed = parse(raw, cap);
        if (!parsed.valid) {
            return parsed;
        }
        Set<Integer> kept = new TreeSet<>(parsed.weeks);
        kept.retainAll(base);
        if (kept.isEmpty()) {
            return invalid("周次条件与原课表生效周次没有交集，请按原课表周次重新输入");
        }
        return ok(kept);
    }

    /** 调课场景归一化：解析失败返回空串 */
    public static String normalizeWithin(String raw, String baseRange) {
        Result r = parseWithin(raw, baseRange);
        return r.valid ? r.normalized : "";
    }

    /** 按指定上界展开周次集合；纯数字写法不受上界影响 */
    public static Set<Integer> weeks(String weekRange, int cap) {
        Result r = parse(weekRange, cap);
        if (!r.valid || r.weeks.isEmpty()) {
            return new TreeSet<>();
        }
        return new TreeSet<>(r.weeks);
    }

    /** 判断 week 是否落在周次范围内；空值或无法解析视为整个学期 */
    public static boolean contains(String weekRange, int week) {
        Set<Integer> set = weeks(weekRange);
        return set.isEmpty() || set.contains(week);
    }

    /** 展开为周次集合；空值或无法解析返回空集合（语义为整个学期） */
    public static Set<Integer> weeks(String weekRange) {
        Result r = parse(weekRange);
        if (!r.valid || r.weeks.isEmpty()) {
            return new TreeSet<>();
        }
        return new TreeSet<>(r.weeks);
    }

    /** 展开为闭区间列表，如 1-8,10-12 → [[1,8],[10,12]] */
    public static List<int[]> ranges(String weekRange) {
        Set<Integer> set = weeks(weekRange);
        return toRanges(set);
    }

    /** 集合压缩为连续区间：1,2,3 → 1-3；1,3,5 → 1,3,5 */
    private static List<int[]> toRanges(Set<Integer> set) {
        List<int[]> list = new ArrayList<>();
        if (set == null || set.isEmpty()) {
            return list;
        }
        int start = -1;
        int prev = -1;
        for (int w : set) {
            if (start < 0) {
                start = w;
                prev = w;
                continue;
            }
            if (w == prev + 1) {
                prev = w;
                continue;
            }
            list.add(new int[]{start, prev});
            start = w;
            prev = w;
        }
        list.add(new int[]{start, prev});
        return list;
    }

    private static Result ok(Set<Integer> set) {
        List<Integer> weeks = new ArrayList<>(set);
        return new Result(true, format(set), null, weeks);
    }

    private static Result invalid(String error) {
        return new Result(false, "", error, new ArrayList<>());
    }

    /** 标准数字格式输出：连续 3 个及以上压缩为区间，其余逐个列出（供冲突提示等场景复用） */
    public static String format(Set<Integer> set) {
        StringBuilder sb = new StringBuilder();
        for (int[] r : toRanges(set)) {
            int len = r[1] - r[0] + 1;
            if (len >= 3) {
                if (sb.length() > 0) sb.append(',');
                sb.append(r[0]).append('-').append(r[1]);
            } else {
                for (int w = r[0]; w <= r[1]; w++) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(w);
                }
            }
        }
        return sb.toString();
    }

    /** 解析纯数字写法；越界返回 null */
    private static Set<Integer> expandPlain(String text) {
        Set<Integer> set = new TreeSet<>();
        String[] parts = text.split(",", -1);
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) {
                return null;
            }
            try {
                if (p.contains("-")) {
                    String[] se = p.split("-", -1);
                    if (se.length != 2) {
                        return null;
                    }
                    int a = Integer.parseInt(se[0].trim());
                    int b = Integer.parseInt(se[1].trim());
                    int from = Math.min(a, b);
                    int to = Math.max(a, b);
                    if (from < 1 || to > MAX_WEEK) {
                        return null;
                    }
                    for (int w = from; w <= to; w++) {
                        set.add(w);
                    }
                } else {
                    int w = Integer.parseInt(p);
                    if (w < 1 || w > MAX_WEEK) {
                        return null;
                    }
                    set.add(w);
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return set;
    }

    private static Set<Integer> rangeSet(int from, int to) {
        Set<Integer> set = new TreeSet<>();
        for (int w = Math.max(from, 1); w <= to; w++) {
            set.add(w);
        }
        return set;
    }

    private static Set<Integer> stepSet(int from, int to, int step) {
        Set<Integer> set = new TreeSet<>();
        for (int w = from; w <= to; w += step) {
            set.add(w);
        }
        return set;
    }

    private static Set<Integer> intersect(Set<Integer> left, Set<Integer> right) {
        if (left == null) {
            return new TreeSet<>(right);
        }
        Set<Integer> out = new TreeSet<>(left);
        out.retainAll(right);
        return out;
    }

    /** 全角字符转半角，兼容中文输入法下的逗号、短横线、数字 */
    private static String toHalfWidth(String s) {
        char[] chars = s.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '\u3000') {
                chars[i] = ' ';
            } else if (chars[i] >= '\uFF01' && chars[i] <= '\uFF5E') {
                chars[i] = (char) (chars[i] - 0xFEE0);
            }
        }
        return new String(chars);
    }
}
