package com.zhixiaogang.service.schedule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.zhixiaogang.service.schedule.AiScheduleConstants.*;
import static com.zhixiaogang.service.schedule.AiScheduleUtils.*;
import com.zhixiaogang.util.WeekRangeParser;

/**
 * AI 排课时间表达式解析：星期 / 节次 / 周次范围、单双周、每日课时上限等自然语言时间片段归一。
 */
final class AiTimeExpressionParser {

    /** 是否含"只能 / 只 / 仅 / 限定"类限定语义 */
    static boolean hasOnlyKeyword(String seg) {
        if (seg == null) {
            return false;
        }
        return seg.contains("只能") || seg.contains("只有") || seg.contains("仅")
                || seg.contains("限定") || seg.contains("只");
    }

    /** 是否以禁用词开头（无约束目标的口语化表述，如"别在周三排课"） */
    static boolean startsWithBanWord(String seg) {
        if (seg == null) {
            return false;
        }
        String s = seg.trim();
        return s.startsWith("别在") || s.startsWith("别安排") || s.startsWith("请避开")
                || s.startsWith("避开") || s.startsWith("不要") || s.startsWith("不能");
    }

    /** 是否含"不能 / 不可 / 禁止"类禁用语义（含此类时交给 AI 处理混合语义） */
    static boolean hasForbidKeyword(String seg) {
        if (seg == null) {
            return false;
        }
        return seg.contains("不能") || seg.contains("不可") || seg.contains("禁止")
                || seg.contains("不要") || seg.contains("不得") || seg.contains("不予")
                || seg.contains("别排") || seg.contains("不排") || seg.contains("别在")
                || seg.contains("别安排") || seg.contains("请避开") || seg.contains("避开");
    }

    /** 是否提到周次（第 3 周 / 1-8 周 / 周次 / 前 8 周 等；周二这类星期不算） */
    static boolean mentionsWeek(String seg) {
        if (seg == null) {
            return false;
        }
        return seg.matches(".*\\d+\\s*周.*") || seg.contains("周次")
                || seg.matches(".*[前后]\\s*\\d+\\s*周.*")
                || seg.matches(".*第[一二三四五六七八九十百]+\\s*周.*");
    }

    /** 是否含时间维度（星期 / 节次时段 / 周次） */
    static boolean hasTimeDimension(String seg) {
        if (seg == null || seg.isEmpty()) {
            return false;
        }
        if (DAY_WORD.matcher(seg).find() || seg.contains("周末") || seg.contains("工作日")
                || seg.contains("周天")) {
            return true;
        }
        for (String w : PERIOD_WORDS) {
            if (seg.contains(w)) {
                return true;
            }
        }
        return mentionsWeek(seg);
    }

    /** 去掉星期词与"周末 / 工作日"等整体词（星期只属于时间维度，不能作为目标名） */
    static String stripDayWords(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String t = s.replaceAll("(?:周|星期|礼拜)[一二三四五六日天]\\s*[到至~\\-—]?\\s*"
                + "(?:(?:周|星期|礼拜)[一二三四五六日天])?", "");
        t = t.replace("周末", "").replace("工作日", "");
        return t;
    }

    /** 去掉周次词（第 N-M 周 / 第 N 周 / 前 N 周 / 后 N 周 / 周次） */
    static String stripWeekWords(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String t = s.replaceAll("(?:第|前|后)?\\s*\\d{1,2}\\s*(?:[-到至~—]\\s*\\d{1,2}\\s*)?周", "");
        return t.replace("周次", "");
    }

    /** 解析周次区间：第 N-M 周 / 第 N 周 / 前 N 周 / 后 N 周 / 最后 N 周；无法解析返回 null */
    static int[] parseWeekRangeLocal(String s, int totalWeekEnd) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        String num = "[0-9一二三四五六七八九十两]{1,3}";
        Matcher pre = Pattern.compile("[前]\\s*(" + num + ")\\s*周").matcher(s);
        if (pre.find()) {
            int n = parseWeekNumber(pre.group(1));
            if (n > 0) {
                return new int[]{1, n};
            }
        }
        Matcher post = Pattern.compile("(?:最后|后)\\s*(" + num + ")\\s*周").matcher(s);
        if (post.find()) {
            int n = parseWeekNumber(post.group(1));
            if (n > 0 && totalWeekEnd > 0) {
                return new int[]{Math.max(1, totalWeekEnd - n + 1), totalWeekEnd};
            }
        }
        Matcher range = Pattern.compile("第?\\s*(\\d{1,2})\\s*[-到至~—]\\s*(\\d{1,2})\\s*周").matcher(s);
        if (range.find()) {
            int a = parseIntSafe(range.group(1));
            int b = parseIntSafe(range.group(2));
            if (a > 0 && b > 0) {
                return new int[]{Math.min(a, b), Math.max(a, b)};
            }
        }
        Matcher one = Pattern.compile("第?\\s*(\\d{1,2})\\s*周").matcher(s);
        if (one.find()) {
            int w = parseIntSafe(one.group(1));
            if (w > 0) {
                return new int[]{w, w};
            }
        }
        return null;
    }

    /** 周次数量解析：支持阿拉伯数字与中文数字（两 / 十二 / 二十）；无法解析返回 -1 */
    static int parseWeekNumber(String raw) {
        String s = raw == null ? "" : raw.trim().replace("两", "二");
        if (s.isEmpty()) {
            return -1;
        }
        if (s.matches("\\d+")) {
            return parseIntSafe(s);
        }
        int tenIdx = s.indexOf("十");
        if (tenIdx >= 0) {
            int tens = tenIdx == 0 ? 1 : cnDigit(s.charAt(0));
            int ones = tenIdx == s.length() - 1 ? 0 : cnDigit(s.charAt(tenIdx + 1));
            if (tens < 0 || ones < 0) {
                return -1;
            }
            return tens * 10 + ones;
        }
        return s.length() == 1 ? cnDigit(s.charAt(0)) : -1;
    }

    /**
     * 解析禁用奇偶周：1=禁单周（奇数周），2=禁双周（偶数周），0=单双周同时禁排（并列），null=未涉及。
     * 别名：单号周 / 奇数周 / 奇周；双号周 / 偶数周 / 偶周。
     * 「只在单周上课」反义解析为禁双周（2），「只在双周上课」解析为禁单周（1）。
     */
    static Integer parseWeekParity(String seg) {
        String s = seg == null ? "" : seg.replaceAll("\\s+", "");
        if (s.isEmpty()) {
            return null;
        }
        boolean hasSingle = s.contains("单周") || s.contains("单号周")
                || s.contains("奇数周") || s.contains("奇周");
        boolean hasDouble = s.contains("双周") || s.contains("双号周")
                || s.contains("偶数周") || s.contains("偶周");
        // 「单双周」「单、双周」「奇偶周」等并列写法视为单双周同时禁排
        boolean bothWords = s.contains("单双周") || s.contains("单、双周") || s.contains("单和双周")
                || s.contains("奇偶周") || s.contains("奇数周和偶数周");
        if (bothWords || (hasSingle && hasDouble)) {
            return 0;
        }
        if (hasSingle || hasDouble) {
            boolean onlyPhrase = s.contains("只") || s.contains("仅");
            if (onlyPhrase) {
                // 「只在单周上课」= 双周禁排；「只在双周上课」= 单周禁排
                return hasSingle ? 2 : 1;
            }
            return hasSingle ? 1 : 2;
        }
        return null;
    }

    /** 解析每日课时数上限：「每天/每日/一天 最多/至多/不超过/只排 N 节课」；未涉及返回 null */
    static Integer parseDailyPeriodCap(String seg) {
        String s = seg == null ? "" : seg.replaceAll("\\s+", "");
        if (s.isEmpty()) {
            return null;
        }
        Matcher m = Pattern.compile("(?:每天|每日|天天|一天|一天内)\\s*(?:最多|至多|不超过|不能超过|不得超过|只能|仅|只)"
                + "\\s*(?:排|安排|上|上满|安排上)?\\s*([0-9一二三四五六七八九十两]{1,3})\\s*节课?").matcher(s);
        if (!m.find()) {
            return null;
        }
        int n = parseWeekNumber(m.group(1));
        return n > 0 ? n : null;
    }

    /** 是否含弱约束词（尽量 / 最好 / 优先 / 尽可能等） */
    static boolean hasWeakConstraintWord(String seg) {
        if (seg == null) {
            return false;
        }
        for (String w : WEAK_CONSTRAINT_WORDS) {
            if (seg.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** 允许周次区间在 1..totalWeekEnd 内的补集（禁用周次），按连续段返回 */
    static List<int[]> complementWeeks(int from, int to, int totalWeekEnd) {
        List<int[]> out = new ArrayList<>();
        int a = Math.max(1, from);
        int b = Math.min(totalWeekEnd <= 0 ? 20 : totalWeekEnd, to);
        if (a > 1) {
            out.add(new int[]{1, a - 1});
        }
        if (b < totalWeekEnd) {
            out.add(new int[]{b + 1, totalWeekEnd});
        }
        return out;
    }

    static Set<Integer> allDaysSet() {
        Set<Integer> days = new LinkedHashSet<>();
        for (int d = 1; d <= 7; d++) {
            days.add(d);
        }
        return days;
    }

    static Set<Integer> fullPeriodSet() {
        Set<Integer> ps = new LinkedHashSet<>();
        for (int p = 1; p <= 10; p++) {
            ps.add(p);
        }
        return ps;
    }

    /** 中文数字单字取值（一→1 … 九→9，十→10） */
    static int cnDigit(char ch) {
        String t = String.valueOf(ch);
        if (t.equals("十")) {
            return 10;
        }
        int i = "一二三四五六七八九".indexOf(ch);
        return i < 0 ? -1 : i + 1;
    }

    /** 解析连续的星期区间"周三到周五 / 周一至周三 / 周一~周五"，补全中间缺失的星期 */
    static void addDayRanges(String s, Set<Integer> days) {
        if (s == null || s.isEmpty()) {
            return;
        }
        String[][] dayMap = {
                {"周一", "星期一", "礼拜一"}, {"周二", "星期二", "礼拜二"},
                {"周三", "星期三", "礼拜三"}, {"周四", "星期四", "礼拜四"},
                {"周五", "星期五", "礼拜五"}, {"周六", "星期六", "礼拜六"},
                {"周日", "星期日", "星期天", "礼拜天", "周天"}};
        Matcher m = Pattern.compile("(?:周|星期|礼拜)([一二三四五六日天])\\s*[到至~\\-—]\\s*"
                + "(?:周|星期|礼拜)?([一二三四五六日天])").matcher(s);
        while (m.find()) {
            int a = dayIndexOf(m.group(1));
            int b = dayIndexOf(m.group(2));
            if (a > 0 && b > 0) {
                for (int d = Math.min(a, b); d <= Math.max(a, b); d++) {
                    days.add(d);
                }
            }
        }
    }

    /** 星期汉字转序号（一→1 … 日/天→7）；无法识别返回 0 */
    static int dayIndexOf(String cn) {
        if (cn == null || cn.isEmpty()) {
            return 0;
        }
        String t = cn.substring(0, 1);
        if ("日天".equals(t)) {
            return 7;
        }
        int i = "一二三四五六".indexOf(t);
        return i < 0 ? 0 : i + 1;
    }

    /** 解析"只能…"中的允许星期；无法识别返回空集 */
    static Set<Integer> parseAllowedDays(String s) {
        Set<Integer> days = new LinkedHashSet<>();
        if (s == null || s.isEmpty()) {
            return days;
        }
        if (s.contains("工作日")) {
            for (int d = 1; d <= 5; d++) {
                days.add(d);
            }
            return days;
        }
        if (s.contains("周末")) {
            days.add(6);
            days.add(7);
            return days;
        }
        // 星期区间（周三到周五 等）优先补全
        addDayRanges(s, days);
        if (!days.isEmpty()) {
            return days;
        }
        if (s.contains("周一到周五") || s.contains("周一至周五") || s.contains("星期一至星期五")) {
            for (int d = 1; d <= 5; d++) {
                days.add(d);
            }
            return days;
        }
        if (s.contains("周六周日") || s.contains("星期六星期天") || s.contains("周六、周日")) {
            days.add(6);
            days.add(7);
            return days;
        }
        String[][] dayMap = {
                {"周一", "星期一", "礼拜一"}, {"周二", "星期二", "礼拜二"},
                {"周三", "星期三", "礼拜三"}, {"周四", "星期四", "礼拜四"},
                {"周五", "星期五", "礼拜五"}, {"周六", "星期六", "礼拜六"},
                {"周日", "星期日", "星期天", "礼拜天", "周天"}};
        for (int d = 1; d <= 7; d++) {
            for (String k : dayMap[d - 1]) {
                if (s.contains(k)) {
                    days.add(d);
                    break;
                }
            }
        }
        return days;
    }

    /** 解析"只能…"中的允许节次（支持"第 1-2 节和第 5-6 节"等多段合并）；无法识别返回空集 */
    static Set<Integer> parseAllowedPeriods(String s) {
        Set<Integer> ps = new LinkedHashSet<>();
        if (s == null || s.isEmpty()) {
            return ps;
        }
        if (s.contains("上午") || s.contains("早上") || s.contains("早晨")) {
            for (int p = 1; p <= 4; p++) {
                ps.add(p);
            }
        }
        if (s.contains("下午")) {
            for (int p = 5; p <= 8; p++) {
                ps.add(p);
            }
        }
        if (s.contains("晚间") || s.contains("晚上") || s.contains("晚自习")
                || s.contains("夜晚") || s.contains("晚补")) {
            ps.add(9);
            ps.add(10);
        }
        if (s.contains("全天")) {
            for (int p = 1; p <= 10; p++) {
                ps.add(p);
            }
        }
        // 第 X-Y 节 / X-Y 节：收集全部区间（必须以"节"结尾，避免把 1-8 周误当节次）
        Matcher range = Pattern.compile("第?\\s*(\\d{1,2})\\s*[-到至]\\s*(\\d{1,2})\\s*节").matcher(s);
        while (range.find()) {
            int a = parseIntSafe(range.group(1));
            int b = parseIntSafe(range.group(2));
            for (int p = Math.min(a, b); p <= Math.max(a, b); p++) {
                ps.add(p);
            }
        }
        // 第 X、Y 节 / X、Y 节（支持顿号分隔且无"第"前缀，如"5、6节"）
        Matcher multi = Pattern.compile("(?:第)?\\s*(\\d{1,2})\\s*[、,，]\\s*(\\d{1,2})\\s*节").matcher(s);
        while (multi.find()) {
            ps.add(parseIntSafe(multi.group(1)));
            ps.add(parseIntSafe(multi.group(2)));
        }
        // 数字连写（"78节"→7、8；"910节"→9、10）：拆分为两个节次段
        Matcher joined = Pattern.compile("(\\d{2,3})\\s*节").matcher(s);
        while (joined.find()) {
            List<Integer> split = splitJoinedPeriods(joined.group(1));
            if (split != null) {
                ps.addAll(split);
            }
        }
        // 第 X 节 / X 节（区间命中的端点重复加入无碍，Set 去重；仅接受 1-10 的合法节次）
        Matcher single = Pattern.compile("第?\\s*(\\d{1,2})\\s*节").matcher(s);
        while (single.find()) {
            int v = parseIntSafe(single.group(1));
            if (v >= 1 && v <= 10) {
                ps.add(v);
            }
        }
        // 中文数字：第一二节 / 第三节 / 第十节 → 映射为对应节次
        Matcher cn = Pattern.compile("第?\\s*([一二三四五六七八九十]{1,4})\\s*节").matcher(s);
        while (cn.find()) {
            String g = cn.group(1);
            for (int i = 0; i < g.length(); i++) {
                int v = cnDigit(g.charAt(i));
                if (v >= 1 && v <= 10) {
                    ps.add(v);
                }
            }
        }
        if (ps.isEmpty()) {
            return ps;
        }
        // 单节表述映射到其所在的两节段（"只能第3节"→允许 3-4 节），保证按节段正常排课
        List<Integer> expanded = new ArrayList<>(ps);
        for (int p : expanded) {
            int start = ((p - 1) / 2) * 2 + 1;
            ps.add(start);
            ps.add(start + 1);
        }
        return ps;
    }

    /**
     * 数字连写节次拆分："78"→[7,8]、"910"→[9,10]、"12"→[1,2]；
     * 单个数本身合法（如 "10"）或无法拆分时返回 null。
     */
    static List<Integer> splitJoinedPeriods(String digits) {
        if (digits == null || digits.length() < 2) {
            return null;
        }
        List<Integer> best = new ArrayList<>();
        collectJoinedPeriods(digits, 0, new ArrayList<>(), best);
        return best.size() >= 2 ? best : null;
    }

    /** 递归枚举连写数字的合法拆分，保留 token 数最少的方案 */
    static void collectJoinedPeriods(String digits, int start, List<Integer> acc, List<Integer> best) {
        if (start == digits.length()) {
            if (!acc.isEmpty() && (best.isEmpty() || acc.size() < best.size())) {
                best.clear();
                best.addAll(acc);
            }
            return;
        }
        for (int len = 1; len <= 2 && start + len <= digits.length(); len++) {
            String part = digits.substring(start, start + len);
            if (part.length() > 1 && part.startsWith("0")) {
                continue;
            }
            int v = parseIntSafe(part);
            if (v < 1 || v > 10) {
                continue;
            }
            acc.add(v);
            collectJoinedPeriods(digits, start + len, acc, best);
            acc.remove(acc.size() - 1);
        }
    }

    /** 解析"第 3 周 / 1-8 周 / 3"等周次文本为闭区间；无法解析返回 null */
    static int[] parseWeekRangeText(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = Pattern.compile("(\\d{1,2})\\s*[-到至]\\s*(\\d{1,2})").matcher(raw);
        if (m.find()) {
            int a = parseIntSafe(m.group(1));
            int b = parseIntSafe(m.group(2));
            if (a > 0 && b > 0) {
                return new int[]{Math.min(a, b), Math.max(a, b)};
            }
        }
        Matcher s1 = Pattern.compile("(\\d{1,2})").matcher(raw);
        if (s1.find()) {
            int w = parseIntSafe(s1.group(1));
            if (w > 0) {
                return new int[]{w, w};
            }
        }
        return null;
    }

    /** "1-16" / "单周" → 周次集合；解析失败返回空集 */
    static Set<Integer> buildAllowedWeeks(String weekRange) {
        if (weekRange == null || weekRange.trim().isEmpty()) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(WeekRangeParser.weeks(weekRange));
    }

    /** {1,2,4,5} → "1-2,4-5" 连续区间压缩 */
    static String joinWeeks(Set<Integer> weeks) {
        if (weeks == null || weeks.isEmpty()) {
            return "";
        }
        List<Integer> sorted = new ArrayList<>(weeks);
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < sorted.size()) {
            int j = i;
            while (j + 1 < sorted.size() && sorted.get(j + 1) == sorted.get(j) + 1) {
                j++;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            if (j == i) {
                sb.append(sorted.get(i));
            } else {
                sb.append(sorted.get(i)).append('-').append(sorted.get(j));
            }
            i = j + 1;
        }
        return sb.toString();
    }

    static List<Integer> allWeekDays() {
        List<Integer> ds = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            ds.add(d);
        }
        return ds;
    }

    static List<Integer> allDays() {
        List<Integer> days = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            days.add(d);
        }
        return days;
    }

    private AiTimeExpressionParser() {
    }
}
