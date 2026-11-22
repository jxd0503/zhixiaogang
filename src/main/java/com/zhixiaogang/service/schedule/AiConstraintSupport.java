package com.zhixiaogang.service.schedule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.zhixiaogang.service.schedule.AiScheduleModels.*;
import static com.zhixiaogang.service.schedule.AiScheduleConstants.*;
import static com.zhixiaogang.service.schedule.AiScheduleUtils.*;
import static com.zhixiaogang.service.schedule.AiTimeExpressionParser.*;

/**
 * AI 排课约束主语识别：目标班级 / 教师 / 课程 / 教室归一、教师名单消歧、约束生效与冲突应用。
 */
final class AiConstraintSupport {

    /** 清洗目标文本：剔除周次词、星期词、禁用词、节次时段词与填充词，仅保留可能的目标名 */
    static String cleanTargetText(String seg) {
        String t = seg == null ? "" : seg;
        t = stripWeekWords(t);
        t = stripDayWords(t);
        for (String w : FORBID_WORDS) {
            t = t.replace(w, "");
        }
        for (String w : PERIOD_WORDS) {
            t = t.replace(w, "");
        }
        for (String w : FILLER_WORDS) {
            t = t.replace(w, "");
        }
        return t.trim();
    }

    /** 由清洗后的文本判定约束目标类型与名称；无法确定时返回 null */
    static String[] resolveTarget(String cleaned) {
        if (cleaned == null) {
            return null;
        }
        Matcher rm = Pattern.compile("(?i)([A-Za-z]{1,3}\\d{1,2}-\\d{2,4})").matcher(cleaned);
        if (rm.find()) {
            return new String[]{"classroom", rm.group(1).toUpperCase()};
        }
        if (cleaned.contains("教室") || cleaned.contains("机房")
                || cleaned.contains("实训室") || cleaned.contains("实验室")) {
            return null; // 提到教室但无具体教室号，无法确定目标
        }
        if (cleaned.contains("班")) {
            int bi = cleaned.lastIndexOf("班");
            String name = normalizeConstraintName(cleaned.substring(0, bi + 1));
            return name.isEmpty() ? null : new String[]{"class", name};
        }
        if (cleaned.contains("老师") || cleaned.contains("教师")
                || cleaned.contains("同志") || cleaned.contains("教授")) {
            String name = normalizeConstraintName(cleaned.replaceAll("老师|教师|同志|教授", "").trim());
            return name.isEmpty() ? null : new String[]{"teacher", name};
        }
        if (!cleaned.isEmpty()) {
            return new String[]{"teacher", normalizeConstraintName(cleaned)};
        }
        return new String[]{"class", "*"};
    }

    /** 是否为目标班级标记（无明确主语的约束默认归属当前排课班级） */
    static boolean isTargetClass(String name) {
        return TARGET_CLASS_NAME.equals(normalizeConstraintName(name));
    }

    /**
     * 约束主语归属校验：教师姓名优先（与教师名单精确 / 包含匹配，「X 老师」按姓氏检索），
     * 其次是模型给出的教师 / 教室 / 班级主语（必须能在原文中找到依据），
     * 最后按「无明确主语」处理，默认归目标班级。返回 null 表示查无此姓，调用方应跳过该条约束。
     */
    static String[] resolveSubject(String seg, String type, String name,
                                    java.util.Set<String> teacherNames,
                                    java.util.Set<String> workloadTeachers, List<String> warnings) {
        String n = name == null ? "" : name.trim();
        // 0. 口语代词（我们班 / 本班 / 这个班 / 我们 …）一律归一为目标班级，按无明确主语处理
        if (isTargetClass(n)) {
            return new String[]{"class", TARGET_CLASS_NAME};
        }
        // 1.「X 老师 / X 教师 / X 教授 / X 同志」简称：按姓氏检索教师名单，唯一命中用全名，多重命中标注歧义，查无此姓给出提示
        String aliasBase = teacherAliasBase(n);
        if (aliasBase != null && !aliasBase.isEmpty()) {
            return resolveTeacherSubject(aliasBase, n, teacherNames, workloadTeachers, warnings);
        }
        // 2. 名称本身与教师名单精确 / 包含匹配 → 教师约束（无论模型判为教师还是班级）
        if (!n.isEmpty() && !isTargetClass(n) && !isWildcard(n)) {
            List<String> hits = matchTeachers(n, teacherNames);
            if (hits.size() == 1) {
                return new String[]{"teacher", hits.get(0)};
            }
            if (hits.size() > 1 && ("teacher".equals(type) || hasTeacherWord(seg))) {
                return resolveTeacherSubject(n, n, teacherNames, workloadTeachers, warnings);
            }
        }
        // 2b. 模型把「X 老师 / X 教授」简写为单姓 / 简称（如「张老师」→「张」）且名单无命中：
        // 仍按姓氏检索并给出「未找到姓X的教师」等明确提示，不得沿用臆造的教师名
        if ("teacher".equals(type) && !n.isEmpty() && !isWildcard(n) && hasTeacherWord(seg)) {
            String label = teacherAliasBase(n) != null ? n : n + "老师";
            return resolveTeacherSubject(n, label, teacherNames, workloadTeachers, warnings);
        }
        // 3. 原文出现已知教师姓名（裸人名、无老师称谓）→ 教师约束
        String inText = findTeacherNameInText(seg, teacherNames);
        if (inText != null) {
            return new String[]{"teacher", inText};
        }
        // 4. 原有主语校验：模型给出的名称必须在原文中有据可依
        if (!n.isEmpty() && !isTargetClass(n) && isExplicitSubjectInText(seg, type, n)) {
            return new String[]{type.isEmpty() ? inferTypeBySubject(n, seg) : type, n};
        }
        String[] detected = detectSubjectEntity(seg, teacherNames);
        if (detected != null) {
            if ("teacher".equals(detected[0]) && !isWildcard(detected[1])) {
                String label = hasTeacherWord(seg) ? detected[1] + "老师" : detected[1];
                return resolveTeacherSubject(detected[1], label, teacherNames, workloadTeachers, warnings);
            }
            return detected;
        }
        return new String[]{"class", TARGET_CLASS_NAME};
    }

    /**
     * 按教师名单解析教师主语：唯一命中采用全名；同姓多人返回「X 老师（不明确，请指定全名）」并提示；
     * 查无此姓给出「未找到姓X的教师」提示并返回 null。
     */
    static String[] resolveTeacherSubject(String base, String label,
                                           java.util.Set<String> teacherNames,
                                           java.util.Set<String> workloadTeachers, List<String> warnings) {
        List<String> hits = matchTeachers(base, teacherNames);
        if (hits.size() > 1 && workloadTeachers != null && !workloadTeachers.isEmpty()) {
            // 同姓多名时先按本次排课涉及的教师消歧，仍不唯一才提示用户指定全名
            List<String> inWorkload = new ArrayList<>();
            for (String h : hits) {
                if (workloadTeachers.contains(h)) {
                    inWorkload.add(h);
                }
            }
            if (!inWorkload.isEmpty()) {
                hits = inWorkload;
            }
        }
        if (hits.size() == 1) {
            return new String[]{"teacher", hits.get(0)};
        }
        if (hits.isEmpty()) {
            String who = base.length() <= 1 ? "姓" + base + "的教师" : "教师「" + base + "」";
            warnings.add("未找到" + who + "，约束「" + label + "」未生效");
            return null;
        }
        warnings.add("教师「" + label + "」匹配到多名同姓教师（" + String.join("、", hits)
                + "），请在约束中指定教师全名");
        return new String[]{"teacher", label + TEACHER_AMBIGUOUS_SUFFIX};
    }

    /**
     * 名称与教师名单匹配：精确 → 前缀（姓氏 / 简称）→ 双向包含，返回全部命中（去重、保持名单顺序）。
     */
    static List<String> matchTeachers(String base, java.util.Collection<String> teacherNames) {
        List<String> out = new ArrayList<>();
        if (base == null || base.isEmpty() || teacherNames == null || teacherNames.isEmpty()) {
            return out;
        }
        String b = normName(base);
        if (b.isEmpty()) {
            return out;
        }
        for (String t : teacherNames) {
            if (t != null && !t.isEmpty() && normName(t).equals(b) && !out.contains(t)) {
                out.add(t);
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        for (String t : teacherNames) {
            if (t != null && !t.isEmpty() && normName(t).startsWith(b) && !out.contains(t)) {
                out.add(t);
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        for (String t : teacherNames) {
            if (t != null && !t.isEmpty()) {
                String tn = normName(t);
                if ((tn.contains(b) || b.contains(tn)) && !out.contains(t)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /** 原文中出现的已知教师姓名（最长匹配优先）；无命中返回 null */
    static String findTeacherNameInText(String seg, java.util.Collection<String> teacherNames) {
        String s = seg == null ? "" : seg.replaceAll("\\s+", "");
        if (s.isEmpty() || teacherNames == null) {
            return null;
        }
        String best = null;
        for (String t : teacherNames) {
            if (t == null) {
                continue;
            }
            String tn = normName(t);
            if (tn.length() < 2 || !s.contains(tn)) {
                continue;
            }
            if (best == null || tn.length() > best.length()) {
                best = tn;
            }
        }
        return best;
    }

    /** 「X 老师 / X 教师 / X 教授 / X 同志」形式的教师简称对应的姓名部分（X）；不是该形式时返回 null */
    static String teacherAliasBase(String name) {
        String n = name == null ? "" : name.replaceAll("\\s+", "");
        for (String title : TEACHER_TITLES) {
            if (n.length() > title.length() && n.endsWith(title)) {
                return n.substring(0, n.length() - title.length());
            }
        }
        return null;
    }

    /** 原文是否带教师称谓（老师 / 教师 / 教授 / 同志） */
    static boolean hasTeacherWord(String seg) {
        if (seg == null) {
            return false;
        }
        return seg.contains("老师") || seg.contains("教师") || seg.contains("教授") || seg.contains("同志");
    }

    /** 模型给出的主语是否在原文中有据可依（纯时间词、臆造的教师名 / 教室号均判为否） */
    static boolean isExplicitSubjectInText(String seg, String type, String name) {
        String s = seg == null ? "" : seg.replaceAll("\\s+", "");
        if (s.isEmpty()) {
            return false;
        }
        String n = name == null ? "" : name.replaceAll("\\s+", "");
        if (n.isEmpty()) {
            return false;
        }
        if (isWildcard(n)) {
            // 全量语义必须有明确的全量表述，否则「所有班级」会被误当成无主语约束的全量目标
            return s.contains("所有") || s.contains("全部") || s.contains("一切")
                    || s.contains("任意") || s.contains("任何") || s.contains("每个")
                    || s.contains("每位") || s.contains("每间");
        }
        if (isTimeOnlyName(n)) {
            return false;
        }
        if (s.contains(n)) {
            return true;
        }
        if ("teacher".equals(type)) {
            return s.contains("老师") || s.contains("教师") || s.contains("教授") || s.contains("同志");
        }
        if ("classroom".equals(type)) {
            return s.contains("教室") || s.contains("机房") || s.contains("实训室") || s.contains("实验室");
        }
        if ("class".equals(type)) {
            return s.contains("班");
        }
        return false;
    }

    /** 名称是否只由时间 / 节次 / 周次词组成（如「1-4节」「78节」「周三上午」），不能充当主语 */
    static boolean isTimeOnlyName(String name) {
        String s = name == null ? "" : name.replaceAll("\\s+", "");
        if (s.isEmpty()) {
            return true;
        }
        s = stripWeekWords(s);
        s = stripDayWords(s);
        for (String w : PERIOD_WORDS) {
            s = s.replace(w, "");
        }
        s = s.replaceAll("[0-9０-９]+", "");
        s = s.replaceAll("[一二三四五六七八九十]+", "");
        s = s.replaceAll("[第节课的、,，\\-~—－到至:：\\s]", "");
        return s.isEmpty();
    }

    /**
     * 从原文识别明确主语实体：教室号 / 数字教室 / 教师姓名（含裸人名）/ 班级名称。
     * 原文不含任何教师、教室、班级实体时返回 null（调用方按「无主语」归目标班级）。
     */
    static String[] detectSubjectEntity(String seg, java.util.Collection<String> teacherNames) {
        String s = seg == null ? "" : seg.replaceAll("\\s+", "");
        if (s.isEmpty()) {
            return null;
        }
        Matcher roomNo = Pattern.compile("(?i)([A-Za-z]{1,3}\\d{1,2}-\\d{2,4})").matcher(s);
        if (roomNo.find()) {
            return new String[]{"classroom", roomNo.group(1).toUpperCase()};
        }
        Matcher roomNum = Pattern.compile("(\\d{1,3})\\s*(?:微机室|机房|实训室|实验室|多媒体教室|教室)").matcher(s);
        if (roomNum.find()) {
            return new String[]{"classroom", roomNum.group(1)};
        }
        // 裸人名：原文直接出现已知教师姓名（无「老师 / 教师」称谓）→ 教师约束
        String teacherInText = findTeacherNameInText(s, teacherNames);
        if (teacherInText != null) {
            return new String[]{"teacher", teacherInText};
        }
        if (s.contains("班")) {
            String before = cleanTargetText(s);
            int bi = before.lastIndexOf("班");
            if (bi >= 0) {
                String name = normalizeConstraintName(before.substring(0, bi + 1));
                if (!name.isEmpty() && !isTargetClass(name)) {
                    return new String[]{"class", name};
                }
            }
        }
        if (s.contains("老师") || s.contains("教师") || s.contains("教授") || s.contains("同志")) {
            String name = normalizeConstraintName(
                    cleanTargetText(s).replaceAll("老师|教师|教授|同志", ""));
            return new String[]{"teacher", name.isEmpty() ? "*" : name};
        }
        if (s.contains("教室") || s.contains("机房") || s.contains("实训室") || s.contains("实验室")) {
            String name = normalizeConstraintName(cleanTargetText(s));
            return new String[]{"classroom", name.isEmpty() ? "*" : name};
        }
        return null;
    }

    /** 按原文中的实体标记推断约束类型（模型只给了名称、未给类型时使用） */
    static String inferTypeBySubject(String name, String seg) {
        String s = seg == null ? "" : seg;
        if (s.contains("老师") || s.contains("教师") || s.contains("教授") || s.contains("同志")) {
            return "teacher";
        }
        if (s.contains("教室") || s.contains("机房") || s.contains("实训室") || s.contains("实验室")) {
            return "classroom";
        }
        if ((name != null && name.contains("班")) || s.contains("班")) {
            return "class";
        }
        return "teacher";
    }

    /** 全量语义名称归一化为通配符 "*"（AI 若输出字面文本时兜底）；目标班级别名归一化为目标班级标记 */
    static String normalizeConstraintName(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        if (s.equals("*")) {
            return "*";
        }
        String plain = s.endsWith("的") ? s.substring(0, s.length() - 1) : s;
        for (String alias : TARGET_CLASS_ALIASES) {
            if (plain.equals(alias)) {
                return TARGET_CLASS_NAME;
            }
        }
        if (s.contains("所有") || s.contains("全部") || s.contains("一切")
                || s.contains("任意") || s.contains("任何") || s.startsWith("每")) {
            return "*";
        }
        return s;
    }

    static boolean isWildcard(String name) {
        return "*".equals(normalizeConstraintName(name));
    }

    static String normalizeConstraintType(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase();
        if (s.contains("teacher") || s.contains("教师") || s.contains("老师")) {
            return "teacher";
        }
        if (s.contains("room") || s.contains("教室") || s.contains("机房") || s.contains("实训室") || s.contains("多媒体")) {
            return "classroom";
        }
        if (s.contains("class") || s.contains("班级") || s.contains("班")) {
            return "class";
        }
        return "";
    }

    /**
     * 把约束占用合并进工作副本（不覆盖现有硬冲突检查）。
     * name 为通配符 "*" 时对该类目标全量生效。返回未能匹配到任何目标的约束列表。
     */
    static List<Constraint> applyConstraints(List<Constraint> constraints,
                                              Map<String, List<Slot>> workTeacher,
                                              Map<Long, List<Slot>> workClass,
                                              Map<Long, List<Slot>> workRoom,
                                              Map<String, Long> classIdByName,
                                              Map<String, Long> roomIdByNo,
                                              java.util.Set<String> teacherNames) {
        List<Constraint> unmatched = new ArrayList<>();
        for (Constraint c : constraints) {
            if (c == null || c.name == null || c.name.isEmpty()) {
                continue;
            }
            List<Integer> days = (c.days == null || c.days.isEmpty()) ? allDays() : c.days;
            int ps = c.periodStart == null ? 1 : c.periodStart;
            int pe = c.periodEnd == null ? 12 : c.periodEnd;
            if (pe < ps) {
                int tmp = ps;
                ps = pe;
                pe = tmp;
            }
            boolean wildcard = isWildcard(c.name);
            List<Object> targets = new ArrayList<>();
            if ("teacher".equals(c.type)) {
                if (wildcard) {
                    targets.addAll(teacherNames);
                } else {
                    String key = nameMatch(c.name, teacherNames);
                    if (key != null) {
                        targets.add(key);
                    }
                }
                for (Object t : targets) {
                    for (int d : days) {
                        workTeacher.computeIfAbsent((String) t, x -> new ArrayList<>()).add(new Slot(d, ps, pe));
                    }
                }
            } else if ("class".equals(c.type)) {
                // 目标班级约束作用于本次排课的全部班级（模糊主语默认归属）
                if (wildcard || isTargetClass(c.name)) {
                    targets.addAll(classIdByName.values());
                } else {
                    Long id = matchId(c.name, classIdByName);
                    if (id != null) {
                        targets.add(id);
                    }
                }
                for (Object t : targets) {
                    for (int d : days) {
                        workClass.computeIfAbsent((Long) t, x -> new ArrayList<>()).add(new Slot(d, ps, pe));
                    }
                }
            } else if ("classroom".equals(c.type)) {
                if (wildcard) {
                    targets.addAll(roomIdByNo.values());
                } else {
                    Long id = matchId(c.name, roomIdByNo);
                    if (id != null) {
                        targets.add(id);
                    }
                }
                for (Object t : targets) {
                    for (int d : days) {
                        workRoom.computeIfAbsent((Long) t, x -> new ArrayList<>()).add(new Slot(d, ps, pe));
                    }
                }
            } else {
                continue;
            }
            if (targets.isEmpty() && (c.name == null || !c.name.contains(TEACHER_AMBIGUOUS_SUFFIX))) {
                // 同姓歧义已在解析阶段给出明确提示，不再重复记为未命中
                unmatched.add(c);
            }
        }
        return unmatched;
    }

    /**
     * 解析无目标的口语化禁用为全量班级约束（"别在周三排课"→所有班级周三全天不可排）；
     * 识别不到星期或节次范围时返回 null（交回 AI 处理）。
     */
    static Constraint parseTargetlessBan(String seg) {
        String s = seg == null ? "" : seg.replaceAll("\\s+", "");
        Set<Integer> days = parseAllowedDays(s);
        Set<Integer> periods = parseAllowedPeriods(s);
        if (days.isEmpty() && periods.isEmpty()) {
            return null;
        }
        List<Integer> dayList = days.isEmpty() ? null : new ArrayList<>(days);
        Integer ps = null;
        Integer pe = null;
        if (!periods.isEmpty()) {
            List<Integer> plist = new ArrayList<>(periods);
            Collections.sort(plist);
            ps = plist.get(0);
            pe = plist.get(plist.size() - 1);
        }
        return new Constraint("class", "*", dayList, ps, pe, null, null);
    }

    /**
     * 「周六也要上课 / 必须安排」类正向表述：本地解析为「需排课」约束（如「周六需排课」）。
     * 仅用于预览页调整，排课引擎不消费该约束；非此类表述返回 null。
     */
    static Constraint parseMustSchedule(String seg, java.util.Collection<String> teacherNames) {
        if (seg == null || seg.isEmpty() || hasForbidKeyword(seg)) {
            return null;
        }
        boolean positive = seg.contains("也要") || seg.contains("还得") || seg.contains("必须")
                || seg.contains("需要") || seg.contains("要上") || seg.contains("要排");
        if (!positive) {
            return null;
        }
        Set<Integer> days = parseAllowedDays(seg);
        if (days.isEmpty()) {
            return null;
        }
        List<Integer> dayList = new ArrayList<>(days);
        Collections.sort(dayList);
        Set<Integer> periods = parseAllowedPeriods(seg);
        Integer ps = null;
        Integer pe = null;
        if (!periods.isEmpty()) {
            List<Integer> pl = new ArrayList<>(periods);
            Collections.sort(pl);
            ps = pl.get(0);
            pe = pl.get(pl.size() - 1);
        }
        String type = "class";
        String name = TARGET_CLASS_NAME;
        String[] subject = detectSubjectEntity(seg, teacherNames);
        if (subject != null) {
            type = subject[0];
            name = subject[1];
        }
        return new Constraint(type, name, dayList, ps, pe, null, null, null, null, true);
    }

    private AiConstraintSupport() {
    }
}
