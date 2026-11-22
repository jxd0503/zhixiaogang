package com.zhixiaogang.service.schedule;

import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixiaogang.service.ai.AiCallExecutor;
import com.zhixiaogang.service.impl.QwenAIService;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.zhixiaogang.service.schedule.AiScheduleModels.*;
import static com.zhixiaogang.service.schedule.AiScheduleConstants.*;
import static com.zhixiaogang.service.schedule.AiScheduleUtils.*;
import static com.zhixiaogang.service.schedule.AiTimeExpressionParser.*;
import static com.zhixiaogang.service.schedule.AiConstraintSupport.*;

/**
 * AI 排课约束解析入口：自然语言约束文本 / 结构化条目解析为约束集合，并构建解析用 prompt。
 */
@Service
public class AiConstraintParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private QwenAIService qwenAIService;

    @Autowired
    private AiCallExecutor aiCallExecutor;

    ConstraintParseResult parseConstraints(String text, int weekEnd,
                                                  java.util.Set<String> teacherNames,
                                                  java.util.Set<String> workloadTeachers) {
        return parseConstraints(text, weekEnd, teacherNames, workloadTeachers,
                AiCallExecutor.TEXT_TIMEOUT_SECONDS, true);
    }

    /**
     * 解析入口：timeoutSeconds 为整批模型调用的等待上限，retry 为超时后是否自动重试。
     * 预览类场景传较短上限且不重试，避免失败时等待翻倍。
     */
    ConstraintParseResult parseConstraints(String text, int weekEnd,
                                                  java.util.Set<String> teacherNames,
                                                  java.util.Set<String> workloadTeachers,
                                                  long timeoutSeconds, boolean retry) {
        List<Constraint> list = new ArrayList<>();
        List<String> unrecognized = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return new ConstraintParseResult(list, unrecognized);
        }
        // 按分号、句号、换行、中英文逗号切分，逐段独立解析（任一条失败不得连带丢弃同批次其他约束）
        List<String> segments = new ArrayList<>();
        for (String seg : t.split("[\n\r;；。,，]+")) {
            String s = seg.trim();
            if (!s.isEmpty()) {
                segments.add(s);
            }
        }
        if (segments.isEmpty()) {
            return new ConstraintParseResult(list, unrecognized);
        }
        // 排课约束以 AI 解析为主，仅对「无主语的只 / 只能 / 仅」限定做本地确定性解析（聊天功能的本地解析不受影响）
        if (!qwenAIService.isEnabled()) {
            return new ConstraintParseResult(list, unrecognized, warnings,
                    "当前模型不可用，无法解析排课约束，请在模型管理中启用模型后重试");
        }
        // 第一轮：本地确定性解析（单双周、每日上限、无主语「只/只能」），其余段收集为待 AI 解析
        List<Integer> aiIndexes = new ArrayList<>();
        List<String> aiSegments = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            String seg = segments.get(i);
            if (!parseLocalRules(seg, weekEnd, teacherNames, workloadTeachers, list, warnings)) {
                aiIndexes.add(i);
                aiSegments.add(seg);
            }
        }
        // 第二轮：全部待解析段合并为一次模型调用，按段号回填结果；
        // 整批总等待由调用方给定上限，超时段走本地兜底或记为未识别
        Map<Integer, List<Map<String, Object>>> aiResults = aiParseBatch(aiSegments, aiIndexes, timeoutSeconds, retry);
        for (int j = 0; j < aiSegments.size(); j++) {
            applyAiItems(aiSegments.get(j), aiResults.getOrDefault(aiIndexes.get(j), new ArrayList<>()),
                    weekEnd, teacherNames, workloadTeachers, list, unrecognized, warnings);
        }
        return new ConstraintParseResult(list, unrecognized, warnings, null);
    }

    /** 本地确定性规则解析单段；返回 true 表示该段已处理，无需再交给模型 */
    private boolean parseLocalRules(String seg, int weekEnd, java.util.Set<String> teacherNames,
                                    java.util.Set<String> workloadTeachers,
                                    List<Constraint> list, List<String> warnings) {
        int warnBefore = warnings.size();
        // 单双周（奇偶周）约束：本地确定性解析为周次集合（单周 = 第 1,3,5… 周，双周 = 第 2,4,6… 周）
        // 支持与星期 / 时段组合（如「单周下午不排」「周五单周不排」）；
        // 「只在单周上课」反义解析为禁双周，「只在双周上课」解析为禁单周；单双周并列时合并为全周禁排
        Integer parity = parseWeekParity(seg);
        if (parity != null) {
            String type = "class";
            String name = TARGET_CLASS_NAME;
            String[] subject = detectSubjectEntity(seg, teacherNames);
            if (subject != null) {
                String[] resolved = resolveSubject(seg, subject[0], subject[1], teacherNames, workloadTeachers, warnings);
                if (resolved == null) {
                    return true;
                }
                type = resolved[0];
                name = resolved[1];
            }
            // 与奇偶周并列的星期 / 时段维度
            List<Integer> pdays = new ArrayList<>(parseAllowedDays(seg));
            List<Integer> pdayArg = pdays.isEmpty() ? null : pdays;
            Set<Integer> pperiods = parseAllowedPeriods(seg);
            Integer pps = null;
            Integer ppe = null;
            if (!pperiods.isEmpty()) {
                List<Integer> pl = new ArrayList<>(pperiods);
                Collections.sort(pl);
                pps = pl.get(0);
                ppe = pl.get(pl.size() - 1);
            }
            if (parity == 0) {
                // 单周 + 双周并列禁排：合并为全周禁排（两条约束叠加），由排课引擎给出无解提示
                list.add(new Constraint(type, name, pdayArg, pps, ppe, null, null, 1, null));
                list.add(new Constraint(type, name, pdayArg, pps, ppe, null, null, 2, null));
            } else {
                list.add(new Constraint(type, name, pdayArg, pps, ppe, null, null, parity, null));
            }
            return true;
        }
        // 每日课时上限：本地确定性解析为班级约束（排课时按天累计节数校验）
        Integer dailyCap = parseDailyPeriodCap(seg);
        if (dailyCap != null) {
            String name = TARGET_CLASS_NAME;
            String[] subject = detectSubjectEntity(seg, teacherNames);
            if (subject != null && "class".equals(subject[0]) && !isWildcard(subject[1])) {
                name = subject[1];
            }
            list.add(new Constraint("class", name, null, null, null, null, null, null, dailyCap));
            return true;
        }
        // 无主语 +「只 / 只能 / 仅」限定：本地确定性解析（模型对该类口语表述可能返回反义时段），
        // 主语固定为目标班级；解析不出时间维度时仍交回 AI
        if (detectSubjectEntity(seg, teacherNames) == null && hasOnlyKeyword(seg)) {
            List<Constraint> only = buildOnlyConstraints(seg, weekEnd, true);
            if (!only.isEmpty()) {
                list.addAll(only);
                return true;
            }
        }
        return warnBefore > warnings.size();
    }

    /**
     * 批量解析：所有待解析段按编号合并为一次模型调用，返回「段序号 → 模型解析条目」。
     * 调用失败或返回结构不符时返回空结果，各段由本地兜底处理，不连带丢弃其他段。
     */
    private Map<Integer, List<Map<String, Object>>> aiParseBatch(List<String> aiSegments, List<Integer> aiIndexes,
                                                                 long timeoutSeconds, boolean retry) {
        Map<Integer, List<Map<String, Object>>> out = new LinkedHashMap<>();
        if (aiSegments.isEmpty()) {
            return out;
        }
        String instruction = buildBatchConstraintInstruction(aiSegments);
        AiCallExecutor.CallResult call = retry
                ? aiCallExecutor.callText(instruction, "admin-schedule", null, timeoutSeconds)
                : aiCallExecutor.callTextOnce(instruction, "admin-schedule", null, timeoutSeconds);
        if (!call.success) {
            return out;
        }
        JsonNode root = parseAiJson(call.content);
        if (root == null) {
            return out;
        }
        JsonNode segs = root.get("segments");
        if (segs != null && segs.isArray()) {
            for (JsonNode s : segs) {
                int idx = s.path("index").asInt(-1);
                if (idx >= 1 && idx <= aiSegments.size()) {
                    out.put(aiIndexes.get(idx - 1), toMapList(s.get("items")));
                }
            }
            return out;
        }
        // 单段时兼容模型直接返回约束数组
        if (root.isArray() && aiSegments.size() == 1) {
            out.put(aiIndexes.get(0), toMapList(root));
        }
        return out;
    }

    /** 解析模型返回的 JSON（对象或数组，兼容 markdown 代码块与前后说明文字） */
    private JsonNode parseAiJson(String answer) {
        if (answer == null || answer.trim().isEmpty()) {
            return null;
        }
        String s = answer.trim();
        int objStart = s.indexOf('{');
        int arrStart = s.indexOf('[');
        if (objStart < 0 && arrStart < 0) {
            return null;
        }
        boolean array = objStart < 0 || (arrStart >= 0 && arrStart < objStart);
        int start = array ? arrStart : objStart;
        int end = s.lastIndexOf(array ? ']' : '}');
        if (end <= start) {
            return null;
        }
        try {
            return JSON.readTree(s.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    /** JSON 数组节点转为条目列表 */
    private List<Map<String, Object>> toMapList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new ArrayList<>();
        }
        return JSON.convertValue(node, new TypeReference<List<Map<String, Object>>>() {});
    }

    /** 单段模型解析结果的落库判定：条目清洗、主语校验、本地兜底与未识别记录 */
    private void applyAiItems(String seg, List<Map<String, Object>> parsed, int weekEnd,
                              java.util.Set<String> teacherNames, java.util.Set<String> workloadTeachers,
                              List<Constraint> list, List<String> unrecognized, List<String> warnings) {
        int warnBefore = warnings.size();
        int listBefore = list.size();
        boolean produced = false;
        String unknownRaw = "";
        for (Map<String, Object> m : parsed) {
            // AI 标注无法解析的描述（如与排课无关的内容）→ 先记录原文，段级兜底失败后原样回显
            String rawType = pick(m, "type", "类型").toLowerCase();
            if (rawType.contains("unknown") || rawType.contains("未识别") || m.containsKey("raw")) {
                String raw = pick(m, "raw", "原文", "描述", "description", "text");
                if (!raw.isEmpty() && unknownRaw.isEmpty()) {
                    unknownRaw = raw;
                }
                continue;
            }
            List<Integer> days = parseIntList(m.get("days"));
            Integer ps = toInt(m.get("period_start"));
            if (ps == null) ps = toInt(m.get("periodStart"));
            Integer pe = toInt(m.get("period_end"));
            if (pe == null) pe = toInt(m.get("periodEnd"));
            if (ps == null && pe == null && m.get("period") != null) {
                ps = toInt(m.get("period"));
                pe = ps;
            }
            // 模型把连写节次（如「78节」）原样回显为 78 时，按数字连写拆分为 7-8
            if (ps != null && pe != null && ps.equals(pe) && ps > 10) {
                List<Integer> split = splitJoinedPeriods(String.valueOf(ps));
                if (split != null) {
                    ps = Collections.min(split);
                    pe = Collections.max(split);
                }
            }
            if (ps != null && ps > 10) ps = null;
            if (pe != null && (pe < 1 || pe > 10)) pe = pe > 10 ? 10 : null;
            Integer ws = toInt(m.get("week_start"));
            if (ws == null) ws = toInt(m.get("weekStart"));
            Integer we = toInt(m.get("week_end"));
            if (we == null) we = toInt(m.get("weekEnd"));
            if (ws == null) {
                String wr = pick(m, "weeks", "week_range", "weekRange", "周次");
                if (!wr.isEmpty()) {
                    int[] range = parseWeekRangeText(wr);
                    if (range != null) {
                        ws = range[0];
                        we = range[1];
                    }
                }
            }
            if (ws != null && we == null) {
                we = ws;
            }
            boolean hasTime = !days.isEmpty() || ps != null || pe != null || ws != null;
            String type = normalizeConstraintType(rawType);
            String name = normalizeConstraintName(pick(m, "name", "名称", "target"));
            if (type.isEmpty() && name.isEmpty()) {
                // 空对象 / 只带时间字段：按规则 2 默认归目标班级；完全无内容则交由段级兜底
                if (!hasTime) {
                    continue;
                }
                String[] detected = detectSubjectEntity(seg, teacherNames);
                if (detected != null) {
                    type = detected[0];
                    name = detected[1];
                } else {
                    type = "class";
                    name = TARGET_CLASS_NAME;
                }
            } else {
                // 主语校验：模型给出的教师 / 教室 / 班级必须在原文中有据可依，否则按规则 2 归目标班级
                String[] subject = resolveSubject(seg, type, name, teacherNames, workloadTeachers, warnings);
                if (subject == null) {
                    // 「X 老师」查无此姓：已写入提示，跳过该条约束（不静默丢弃）
                    continue;
                }
                type = subject[0];
                name = subject[1];
            }
            list.add(new Constraint(type, name, days, ps, pe, ws, we));
            produced = true;
        }
        // AI 返回空数组 / 空对象 / 无任何可用产出 → 本地兜底解析纯时间约束（归目标班级），仍失败才记为未识别
        if (!produced) {
            List<Constraint> fallback = parseTimeOnlyTargetClass(seg, weekEnd, teacherNames);
            if (!fallback.isEmpty()) {
                list.addAll(fallback);
                produced = true;
            }
        }
        // 该段虽未产出可用约束，但已给出主语提示（查无此姓 / 同姓歧义），不再重复记为未识别
        if (!produced && warnings.size() > warnBefore) {
            produced = true;
        }
        if (!produced) {
            unrecognized.add(unknownRaw.isEmpty() ? seg : unknownRaw);
        }
        // 弱约束词（尽量 / 最好 / 优先 / 尽可能）：约束照常生效，但明确告知已按硬约束处理
        if (produced && list.size() > listBefore && hasWeakConstraintWord(seg)) {
            warnings.add("「" + seg + "」为软性建议表述，已按硬约束处理；如需调整请使用「不要排 / 只能排」等明确表述");
        }
    }

    private String buildConstraintInstruction(String text) {
        return buildConstraintRules()
                + "只输出 JSON 数组本身，不要输出任何解释文字，不要输出 markdown 代码块。"
                + "\n约束描述：\n" + text;
    }

    /** 批量解析指令：多段约束按编号合并为一次请求，模型按段号返回各自的约束数组 */
    private String buildBatchConstraintInstruction(List<String> segments) {
        StringBuilder sb = new StringBuilder(buildConstraintRules());
        sb.append("输出要求：只输出 JSON 对象 {\"segments\":[{\"index\":段号,\"items\":[该段的约束数组]}]}，")
                .append("index 与下方段落编号一一对应，每段都必须出现，items 内元素格式与上述约束格式相同；")
                .append("不要输出解释文字，不要输出 markdown 代码块。\n约束描述：\n");
        for (int i = 0; i < segments.size(); i++) {
            sb.append(i + 1).append(". ").append(segments.get(i)).append("\n");
        }
        return sb.toString();
    }

    /** 约束解析公共规则说明（不含输出格式与具体描述） */
    private String buildConstraintRules() {
        return "请把下面这段排课约束的自然语言描述解析为 JSON 数组，每个元素代表一条禁用约束，字段为："
                + "type（teacher 表示教师、class 表示班级、classroom 表示教室）、"
                + "name（约束目标名：教师姓名、班级名称或教室号）、"
                + "days（受约束的星期，整数数组，1=周一…7=周日，未指定星期输出空数组 [] 表示每天）、"
                + "period_start 与 period_end（受约束的节次，整数闭区间；未指定节次输出 null 表示全天）、"
                + "week_start 与 week_end（受约束的周次，整数闭区间；如「第3周」为 3-3、「1-8周」为 1-8、"
                + "「前8周」为 1-8、「后4周」（共16周时）为 13-16；未涉及周次时输出 null）。"
                + "一、主语归属规则（最关键，必须严格遵守）："
                + "1. 描述中出现明确的教师实体（如「王建国」「王建国老师」「李老师」「王教授」「张同志」）时输出 type=teacher、name=该教师姓名；"
                + "只要出现的是人名（即使没有「老师 / 教师」称谓，如「王建国周二不排」）也必须输出 type=teacher，"
                + "严禁把人名当作班级；"
                + "出现明确教室实体（如「B5-306」「第10微机室」「机房」）时输出 type=classroom、name=该教室号或名称；"
                + "出现明确班级实体（如「计算机1班」）时输出 type=class、name=该班级名称。"
                + "2. 用户未指明主语的约束一律归目标班级：描述中只有时间 / 节次 / 周次限制，"
                + "没有任何教师、教室、班级实体时，必须输出 type=class、name=「目标班级」，"
                + "表示约束作用于当前正在排课的目标班级；「我们班 / 本班 / 咱们班 / 这个班 / 本班级」等口语代词同样视为「目标班级」；"
                + "严禁臆造教师姓名或教室号，严禁输出 type=teacher 或 type=classroom。"
                + "例如「不要排78节的课」「下午不要安排课程」「周三上午不排课」「不要排晚自习」「只排1-4节」"
                + "全部输出 type=class、name=「目标班级」。"
                + "3. 一句话包含多条约束时按语义分条，数组中输出多个元素，每条分别判定主语归属；"
                + "例如「不要排78节的课，王建国老师周三下午不能上课」必须输出两条："
                + "{\"type\":\"class\",\"name\":\"目标班级\",\"days\":[],\"period_start\":7,\"period_end\":8} 与 "
                + "{\"type\":\"teacher\",\"name\":\"王建国\",\"days\":[3],\"period_start\":5,\"period_end\":8}。"
                + "4. 只有明确表达「所有班级 / 全部班级 / 每个班级 / 所有教师 / 全部教师 / 所有教室 / 全部教室」等全量语义时，"
                + "name 才输出通配符星号，不要输出「所有班级」这类字面文本。"
                + "二、口语化时段归一（无论有无主语都必须归一）："
                + "1.「78节」「7-8节」「第七八节」「七八节」→ period_start=7、period_end=8；"
                + "「910节」→ period_start=9、period_end=10；数字连写（如 78、910、12）必须拆分为两个节次，"
                + "严禁把「78节」解析为 78 这样的非法节次。"
                + "2.「上午」→ 1-4 节，「下午」→ 5-8 节，「晚间 / 晚上 / 晚自习」→ 9-10 节，「全天」→ 1-10 节。"
                + "3.「1-2节」「3-4节」「第5-6节」等区间直接识别为对应闭区间。"
                + "4. 星期表述支持「周一～周日」「星期一」「星期三」「周三」「礼拜三」等，统一转为 1-7 的整数。"
                + "5. 周次表述支持「第3周」「1-8周」「前8周」「后4周」「最后4周」（共16周时最后4周 = 13-16 周）等，转为 week_start / week_end。"
                + "三、语义转换规则（必须严格遵守）："
                + "1.「不能 / 不要 / 不可 / 禁止 / 别在 / 别安排 / 不要安排 / 请避开 / 避开 / 不排」等直接表示该范围不可排，按原样输出禁用约束；"
                + "例如「别在周三排课」输出为周三(days=[3])全天不可排，「请避开周五下午」输出为周五(days=[5])下午(5-8)不可排。"
                + "2.「只能 / 只 / 仅 / 限定」表示目标只能在限定范围内排课，必须转换为「限定范围之外全部不可排」的排除性约束："
                + "「只能下午上课」输出为上午(1-4)不可排、晚间(9-10)不可排两条；"
                + "「只能周一到周五上课」输出为周六、周日不可排；"
                + "「只上午使用」输出为下午(5-8)、晚间(9-10)不可排；"
                + "「只能排第3-4节」输出为第1-2节、第5-10节不可排。"
                + "严禁把「只能 / 只 / 仅」直接理解为「该范围不可排」。"
                + "3.若某条描述确实无法解析为可用约束（与排课无关、缺少任何时间范围），"
                + "必须输出 {\"type\":\"unknown\",\"raw\":\"该条描述原文\"} 进行标注；"
                + "包含周次信息但无法解析为 week_start / week_end 时同样按此规则输出；"
                + "严禁省略该条，严禁返回空对象。";
    }

    /**
     * 本地规则解析"只能 / 只 / 仅"限定语义：转换为"限定范围之外全部不可排"的排除性约束。
     * 无法识别目标或限定范围时返回空列表（交回 AI 处理）。
     */
    private List<Constraint> parseOnlyConstraints(String seg, int totalWeekEnd) {
        return buildOnlyConstraints(seg, totalWeekEnd, false);
    }

    /**
     * 本地规则解析"只能 / 只 / 仅"限定语义：转换为"限定范围之外全部不可排"的排除性约束。
     * forceTargetClass 为 true 时忽略目标识别结果，主语固定为目标班级（无明确主语的描述）。
     * 无法识别限定范围时返回空列表（交回 AI 处理）。
     */
    private List<Constraint> buildOnlyConstraints(String seg, int totalWeekEnd, boolean forceTargetClass) {
        List<Constraint> out = new ArrayList<>();
        String s = seg.replaceAll("\\s+", "");
        String[] triggers = {"只能", "只有", "仅", "限定", "只"};
        int idx = -1;
        for (String tr : triggers) {
            int i = s.indexOf(tr);
            if (i >= 0 && (idx == -1 || i < idx)) {
                idx = i;
            }
        }
        if (idx < 0) {
            return out;
        }
        String before = s.substring(0, idx);
        String after = s.substring(idx);
        for (String tr : new String[]{"只能", "只有", "仅", "限定", "只"}) {
            after = after.replace(tr, " ");
        }
        // 星期词只属于时间维度：先剔除再识别目标（防止"周二"被当作教师 / 班级名）
        Set<Integer> allowDays = new LinkedHashSet<>(parseAllowedDays(before));
        allowDays.addAll(parseAllowedDays(after));
        Set<Integer> allowPeriods = parseAllowedPeriods(after);
        String type;
        String name;
        if (forceTargetClass) {
            type = "class";
            name = TARGET_CLASS_NAME;
        } else {
            String[] target = resolveTarget(cleanTargetText(before));
            if (target == null) {
                return out;
            }
            type = target[0];
            name = target[1];
            if (name.isEmpty()) {
                return out;
            }
        }
        // 周次限定（"只能第 1-8 周排课"）：允许周次之外的周次全部禁用，本地确定性解析
        int[] allowWeeks = parseWeekRangeLocal(s, totalWeekEnd);
        if (allowWeeks != null) {
            for (int[] fw : complementWeeks(allowWeeks[0], allowWeeks[1], totalWeekEnd)) {
                out.add(new Constraint(type, name, null, null, null, fw[0], fw[1]));
            }
        }
        // 允许范围
        if (allowDays.isEmpty() && allowPeriods.isEmpty()) {
            return out;
        }
        boolean daysExplicit = !allowDays.isEmpty();
        boolean periodsExplicit = !allowPeriods.isEmpty();
        if (allowDays.isEmpty()) {
            allowDays = allDaysSet();
        }
        if (allowPeriods.isEmpty()) {
            allowPeriods = fullPeriodSet();
        }
        // 计算每日禁用节次，按相同禁用集分组，连续节次段各生成一条约束
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int d = 1; d <= 7; d++) {
            List<Integer> forbidden = new ArrayList<>();
            if (!allowDays.contains(d)) {
                // 同时限定了星期与节次（如"周二只能下午"）时，约束只作用于显式指定的星期
                if (daysExplicit && periodsExplicit) {
                    continue;
                }
                for (int p = 1; p <= 10; p++) {
                    forbidden.add(p);
                }
            } else {
                for (int p = 1; p <= 10; p++) {
                    if (!allowPeriods.contains(p)) {
                        forbidden.add(p);
                    }
                }
            }
            if (forbidden.isEmpty()) {
                continue;
            }
            StringBuilder key = new StringBuilder();
            for (int p : forbidden) {
                key.append(p).append(',');
            }
            groups.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(d);
        }
        for (Map.Entry<String, List<Integer>> e : groups.entrySet()) {
            List<Integer> fdays = e.getValue();
            List<Integer> fps = new ArrayList<>();
            for (String p : e.getKey().split(",")) {
                if (!p.isEmpty()) {
                    fps.add(Integer.parseInt(p));
                }
            }
            int i = 0;
            while (i < fps.size()) {
                int j = i;
                while (j + 1 < fps.size() && fps.get(j + 1) == fps.get(j) + 1) {
                    j++;
                }
                out.add(new Constraint(type, name, new ArrayList<>(fdays), fps.get(i), fps.get(j)));
                i = j + 1;
            }
        }
        return out;
    }

    /**
     * 本地兜底解析：描述中不含任何教师 / 教室 / 班级实体时（纯时间、节次、周次），
     * 主语固定为目标班级。无法识别时间维度时返回空列表。
     */
    private List<Constraint> parseTimeOnlyTargetClass(String seg, int weekEnd,
                                                      java.util.Collection<String> teacherNames) {
        List<Constraint> out = new ArrayList<>();
        if (seg == null || seg.trim().isEmpty()) {
            return out;
        }
        if (detectSubjectEntity(seg, teacherNames) != null) {
            return out;
        }
        if (hasOnlyKeyword(seg)) {
            List<Constraint> only = buildOnlyConstraints(seg, weekEnd, true);
            if (!only.isEmpty()) {
                return only;
            }
        }
        if (!hasForbidKeyword(seg)) {
            return out;
        }
        Set<Integer> days = parseAllowedDays(seg);
        Set<Integer> periods = parseAllowedPeriods(seg);
        int[] wk = parseWeekRangeLocal(seg, weekEnd);
        if (days.isEmpty() && periods.isEmpty() && wk == null) {
            return out;
        }
        List<Integer> dayList = days.isEmpty() ? null : new ArrayList<>(days);
        Integer ps = null;
        Integer pe = null;
        if (!periods.isEmpty()) {
            List<Integer> pl = new ArrayList<>(periods);
            Collections.sort(pl);
            ps = pl.get(0);
            pe = pl.get(pl.size() - 1);
        }
        if (wk != null && dayList == null && ps == null) {
            // 纯周次约束：按周次从排课范围中扣除
            out.add(new Constraint("class", TARGET_CLASS_NAME, null, null, null, wk[0], wk[1]));
            return out;
        }
        out.add(new Constraint("class", TARGET_CLASS_NAME, dayList, ps, pe,
                wk == null ? null : wk[0], wk == null ? null : wk[1]));
        return out;
    }
}
