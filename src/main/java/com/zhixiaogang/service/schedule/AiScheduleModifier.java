package com.zhixiaogang.service.schedule;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.zhixiaogang.service.TeacherCourseService;
import com.zhixiaogang.service.ai.AiCallExecutor;
import com.zhixiaogang.service.schedule.AiScheduleModels.*;
import static com.zhixiaogang.service.schedule.AiScheduleConstants.*;
import static com.zhixiaogang.service.schedule.AiScheduleUtils.*;
import static com.zhixiaogang.service.schedule.AiConstraintSupport.*;
import static com.zhixiaogang.service.schedule.AiConstraintEntrySupport.*;
import static com.zhixiaogang.service.schedule.AiScheduleTexts.*;
import static com.zhixiaogang.service.schedule.AiScheduleRepairer.*;

/**
 * AI 排课调整：按自然语言指令重排预览条目，并做教师 / 班级 / 教室冲突校验。
 */
@Service
public class AiScheduleModifier {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TeacherCourseService teacherCourseService;

    @Autowired
    private AiCallExecutor aiCallExecutor;

    @Autowired
    private AiConstraintParser aiConstraintParser;

    /**
     * 自然语言调整预览：把当前预览条目（带临时 idx）+ 用户调整指令交给模型，模型输出调整后的条目；
     * 服务端以 idx 为锚把模型输出合并回原条目，仅采纳可动字段（星期 / 节次 / 教室 / 周次），
     * 未提及条目保持原样；随后复用教师 / 班级 / 教室冲突与教室存在性校验。全过程不写库。
     */
    public Map<String, Object> modify(Map<String, Object> request) {
        try {
            List<Map<String, Object>> rawItems = new ArrayList<>();
            Object itemsObj = request.get("items");
            if (itemsObj instanceof List) {
                for (Object o : (List<?>) itemsObj) {
                    if (o instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) o;
                        rawItems.add(m);
                    }
                }
            }
            if (rawItems.isEmpty()) {
                return fail("没有可调整的预览条目，请先生成排课预览");
            }
            String instruction = str(request.get("instruction"));
            if (instruction.isEmpty()) {
                return fail("请输入调整指令");
            }
            // 调整依赖模型解析：模型不可用或调用失败时明确提示，不静默退回本地规则
            // （纯约束指令只做约束合并，不需要改动课表条目，模型可用性在约束解析处校验）
            String semester = str(request.get("semester"));
            int weekStart = intOrDefault(request.get("weekStart"), 1);
            int weekEnd = intOrDefault(request.get("weekEnd"), 16);
            int minPeriod = intOrDefault(request.get("minPeriod"), 1);
            int maxPeriod = intOrDefault(request.get("maxPeriod"), 8);
            if (minPeriod < 1) minPeriod = 1;
            if (maxPeriod < minPeriod) maxPeriod = minPeriod;
            if (maxPeriod > 10) maxPeriod = 10;

            // 已知教师名单：约束主语识别（裸人名 / 「X 老师」简称还原为全名）
            Set<String> workloadTeachers = new HashSet<>();
            Set<String> teacherNames = new HashSet<>();
            for (Map<String, Object> row : jdbcTemplate.queryForList(
                    "SELECT DISTINCT teacher_name FROM schedule WHERE teacher_name IS NOT NULL AND teacher_name <> ''")) {
                String tn = str(row.get("teacher_name"));
                if (!tn.isEmpty()) {
                    workloadTeachers.add(tn);
                    teacherNames.add(tn);
                }
            }
            for (Map<String, Object> row : jdbcTemplate.queryForList(
                    "SELECT name FROM user WHERE role = 'teacher' AND name IS NOT NULL AND name <> ''")) {
                String tn = str(row.get("name"));
                if (!tn.isEmpty()) {
                    teacherNames.add(tn);
                }
            }
            for (Map<String, Object> raw : rawItems) {
                String tn = pick(raw, "teacherName", "teacher_name", "授课教师", "教师");
                if (!tn.isEmpty()) {
                    workloadTeachers.add(tn);
                    teacherNames.add(tn);
                }
            }

            // 约束合并：以当前已识别约束（结构化条目）为基线，叠加本次指令新识别的约束
            List<ConstraintEntry> entries = new ArrayList<>();
            List<String> cUnrecognized = new ArrayList<>();
            List<String> cWarnings = new ArrayList<>();
            Object structuredRaw = request.get("structuredConstraints");
            boolean hasStructured = false;
            if (structuredRaw instanceof List) {
                for (Object so : (List<?>) structuredRaw) {
                    if (so instanceof Map) {
                        hasStructured = true;
                        break;
                    }
                }
            }
            if (hasStructured) {
                entries.addAll(parseStructuredEntries((List<?>) structuredRaw));
            } else {
                String baseText = str(request.get("constraints"));
                if (!baseText.isEmpty()) {
                    ConstraintParseResult base = aiConstraintParser.parseConstraints(baseText, weekEnd, teacherNames, workloadTeachers);
                    if (base.fatalError != null) {
                        return fail(base.fatalError);
                    }
                    for (Constraint c : base.constraints) {
                        mergeConstraintEntry(entries, c);
                    }
                    cUnrecognized.addAll(base.unrecognized);
                    cWarnings.addAll(base.warnings);
                }
            }
            boolean constraintIntent = hasConstraintIntent(instruction);
            Constraint positive = constraintIntent ? parseMustSchedule(instruction, teacherNames) : null;
            if (positive != null) {
                mergeConstraintEntry(entries, positive);
            } else if (constraintIntent) {
                ConstraintParseResult added = aiConstraintParser.parseConstraints(instruction, weekEnd, teacherNames, workloadTeachers);
                if (added.fatalError != null) {
                    return fail(added.fatalError);
                }
                for (Constraint c : added.constraints) {
                    mergeConstraintEntry(entries, c);
                }
                cUnrecognized.addAll(added.unrecognized);
                cWarnings.addAll(added.warnings);
            }
            for (int i = 0; i < entries.size(); i++) {
                entries.get(i).id = i + 1;
            }
            List<Map<String, Object>> constraintList = new ArrayList<>();
            for (ConstraintEntry e : entries) {
                constraintList.add(constraintItem(e));
            }
            // 纯约束指令（不含「调到 / 删除 / 新增」等改条目语义）：只更新约束，不改动课表条目
            boolean constraintOnly = constraintIntent && !hasItemChangeIntent(instruction);

            // 规范化当前条目（保留原值）
            List<Map<String, Object>> originals = new ArrayList<>();
            for (Map<String, Object> raw : rawItems) {
                originals.add(normalizePreviewItem(raw, semester));
            }

            List<Map<String, Object>> merged = new ArrayList<>();
            if (constraintOnly) {
                // 纯约束指令：只更新约束集合，课表条目原样保留，不调用模型改写条目
                merged.addAll(originals);
            } else {
                // 注入临时 idx 供模型逐条对应
                List<Map<String, Object>> forAi = new ArrayList<>();
                for (int i = 0; i < originals.size(); i++) {
                    Map<String, Object> it = originals.get(i);
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("idx", i);
                    a.put("className", it.get("className"));
                    a.put("courseName", it.get("courseName"));
                    a.put("teacherName", it.get("teacherName"));
                    a.put("classroomNo", it.get("classroomNo"));
                    a.put("weekDay", it.get("weekDay"));
                    a.put("startPeriod", it.get("startPeriod"));
                    a.put("endPeriod", it.get("endPeriod"));
                    a.put("weekRange", it.get("weekRange"));
                    forAi.add(a);
                }

                String prompt = buildModifyInstruction(forAi, instruction, semester, weekStart, weekEnd, minPeriod, maxPeriod);
                AiCallExecutor.CallResult call = aiCallExecutor.callText(prompt, "admin-schedule", null);
                if (!call.success) {
                    if ("timeout".equals(call.errorType)) {
                        return fail("调整失败：模型响应超时（已等待 " + AiCallExecutor.TEXT_TIMEOUT_SECONDS + " 秒"
                                + (call.retried ? "，已自动重试 1 次" : "") + "），请稍后重试");
                    }
                    if ("disabled".equals(call.errorType)) {
                        return fail("当前模型不可用，无法解析调整指令，请先启用模型");
                    }
                    return fail("调整失败：模型调用异常，请稍后重试");
                }
                String aiResp = call.content;
                List<Map<String, Object>> parsed = teacherCourseService.parseScheduleJsonText(aiResp);
                if (parsed.isEmpty()) {
                    return fail("无法理解调整指令：" + instruction);
                }

                // 以 idx 为锚合并：仅采纳可动字段；显式 deleted=true（或含删除语义且条目缺失）视为删除
                Map<Integer, Map<String, Object>> patch = new LinkedHashMap<>();
                for (Map<String, Object> m : parsed) {
                    Integer idx = toInt(m.get("idx"));
                    if (idx != null && idx >= 0 && idx < originals.size() && !patch.containsKey(idx)) {
                        patch.put(idx, m);
                    }
                }
                if (patch.isEmpty()) {
                    return fail("无法理解调整指令：" + instruction);
                }
                boolean deleteHint = hasDeleteKeyword(instruction);
                for (int i = 0; i < originals.size(); i++) {
                    Map<String, Object> base = originals.get(i);
                    Map<String, Object> m = patch.get(i);
                    if (m == null) {
                        // 未被模型提及：默认保持原样；若指令含删除语义且模型漏输出该条目，则按删除处理
                        if (deleteHint && patch.size() < originals.size()) {
                            continue;
                        }
                        merged.add(base);
                        continue;
                    }
                    if (boolTrue(m.get("deleted"))) {
                        continue;
                    }
                    Map<String, Object> out = new LinkedHashMap<>(base);
                    Integer day = toInt(m.get("weekDay"));
                    if (day == null) day = toInt(m.get("week_day"));
                    if (day != null && day >= 1 && day <= 7) out.put("weekDay", day);
                    Integer sp = toInt(m.get("startPeriod"));
                    if (sp == null) sp = toInt(m.get("start_period"));
                    if (sp != null && sp >= 1 && sp <= 10) out.put("startPeriod", sp);
                    Integer ep = toInt(m.get("endPeriod"));
                    if (ep == null) ep = toInt(m.get("end_period"));
                    if (ep != null && ep >= 1 && ep <= 10) out.put("endPeriod", ep);
                    if (hasAnyKey(m, "classroomNo", "classroom_no", "教室号", "教室")) {
                        String room = pick(m, "classroomNo", "classroom_no", "教室号", "教室");
                        out.put("classroomNo", room);
                        out.put("classroomDisplay", room.isEmpty() ? NO_CLASSROOM_DISPLAY : room);
                    }
                    if (hasAnyKey(m, "weekRange", "week_range", "周次", "周次范围")) {
                        out.put("weekRange", normalizeWeekRange(pick(m, "weekRange", "week_range", "周次", "周次范围")));
                    }
                    merged.add(out);
                }
            }
            // 节次纠偏：保证 1 <= start <= end <= 10
            for (Map<String, Object> it : merged) {
                int sp = intOrDefault(it.get("startPeriod"), 1);
                int ep = intOrDefault(it.get("endPeriod"), sp);
                if (sp < 1) sp = 1;
                if (sp > 10) sp = 10;
                if (ep < sp) ep = sp;
                if (ep > 10) ep = 10;
                it.put("startPeriod", sp);
                it.put("endPeriod", ep);
                it.put("conflict", false);
                it.put("conflictReason", "");
            }
            if (merged.isEmpty()) {
                return fail("调整后无可入库条目，如需清空请直接取消预览");
            }

            // 冲突校验：与现有课表（DB）+ 预览条目之间两两冲突 + 教室存在性
            Map<String, Long> roomIdByNo = new LinkedHashMap<>();
            for (Map<String, Object> row : jdbcTemplate.queryForList("SELECT id, classroom_no FROM classroom")) {
                Long rid = toLong(row.get("id"));
                String no = str(row.get("classroom_no"));
                if (rid != null && !no.isEmpty()) {
                    roomIdByNo.put(no, rid);
                }
            }
            Map<String, List<Slot>> existTeacher = new HashMap<>();
            Map<Long, List<Slot>> existClass = new HashMap<>();
            Map<Long, List<Slot>> existRoom = new HashMap<>();
            for (Map<String, Object> row : jdbcTemplate.queryForList(
                    "SELECT teacher_name, class_id, classroom_id, week_day, start_period, end_period FROM schedule")) {
                Integer wd = toInt(row.get("week_day"));
                Integer sp = toInt(row.get("start_period"));
                Integer ep = toInt(row.get("end_period"));
                if (wd == null || sp == null || ep == null) {
                    continue;
                }
                String tn = str(row.get("teacher_name"));
                if (!tn.isEmpty()) {
                    existTeacher.computeIfAbsent(tn, k -> new ArrayList<>()).add(new Slot(wd, sp, ep));
                }
                Long cid = toLong(row.get("class_id"));
                if (cid != null) {
                    existClass.computeIfAbsent(cid, k -> new ArrayList<>()).add(new Slot(wd, sp, ep));
                }
                Long rid = toLong(row.get("classroom_id"));
                if (rid != null) {
                    existRoom.computeIfAbsent(rid, k -> new ArrayList<>()).add(new Slot(wd, sp, ep));
                }
            }

            // 约束变更后重排：合并后的约束若命中现有条目，自动挪到满足全部约束的时段；
            // 无法安置的条目移除并给出明确原因（不得静默保留与约束矛盾的旧课表）
            boolean allowWeekend = boolOrDefault(request.get("allowWeekend"), false);
            if (request.get("allowWeekend") == null) {
                for (Map<String, Object> it : merged) {
                    if (intOrDefault(it.get("weekDay"), 1) >= 6) {
                        allowWeekend = true;
                        break;
                    }
                }
            }
            Map<String, Long> classIdByName = new LinkedHashMap<>();
            for (Map<String, Object> row : jdbcTemplate.queryForList("SELECT id, class_name FROM class_info")) {
                Long cid = toLong(row.get("id"));
                String cn = str(row.get("class_name"));
                if (cid != null && !cn.isEmpty()) {
                    classIdByName.put(cn, cid);
                }
            }
            for (Map<String, Object> it : merged) {
                if (toLong(it.get("classId")) != null) {
                    continue;
                }
                String cn = str(it.get("className"));
                if (cn.isEmpty()) {
                    continue;
                }
                Long cid = classIdByName.get(cn);
                if (cid == null) {
                    cid = matchId(cn, classIdByName);
                }
                if (cid != null) {
                    it.put("classId", cid);
                }
            }
            RepairResult repair = repairPreviewItems(merged, entries, allowWeekend, minPeriod, maxPeriod,
                    existTeacher, existClass, existRoom, roomIdByNo, classIdByName, teacherNames);
            merged = repair.items;

            int conflictCount = 0;
            Set<Long> classIds = new LinkedHashSet<>();
            for (int i = 0; i < merged.size(); i++) {
                Map<String, Object> it = merged.get(i);
                int day = intOrDefault(it.get("weekDay"), 1);
                int sp = intOrDefault(it.get("startPeriod"), 1);
                int ep = intOrDefault(it.get("endPeriod"), sp);
                String teacher = str(it.get("teacherName"));
                Long classId = toLong(it.get("classId"));
                String roomNo = str(it.get("classroomNo"));
                if (classId != null) {
                    classIds.add(classId);
                }
                String reason = null;
                Long rid = null;
                if (!roomNo.isEmpty()) {
                    rid = roomIdByNo.get(roomNo);
                    if (rid == null) {
                        reason = "教室「" + roomNo + "」不存在";
                    }
                }
                if (reason == null && !teacher.isEmpty()
                        && (isBusy(existTeacher.get(teacher), day, sp, ep)
                            || busyOtherTeacher(merged, i, teacher, day, sp, ep))) {
                    reason = "教师「" + teacher + "」该时段已被占用";
                }
                if (reason == null && classId != null
                        && (isBusy(existClass.get(classId), day, sp, ep)
                            || busyOtherClass(merged, i, classId, day, sp, ep))) {
                    reason = "班级该时段已被占用";
                }
                if (reason == null && rid != null
                        && (isBusy(existRoom.get(rid), day, sp, ep)
                            || busyOtherRoom(merged, i, roomNo, day, sp, ep))) {
                    reason = "教室「" + roomNo + "」该时段已被占用";
                }
                it.put("conflict", reason != null);
                it.put("conflictReason", reason == null ? "" : reason);
                if (reason != null) {
                    conflictCount++;
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("items", merged);
            result.put("totalCount", merged.size());
            result.put("conflictCount", conflictCount);
            result.put("classCount", classIds.size());
            result.put("constraintCount", constraintList.size());
            result.put("constraintList", constraintList);
            result.put("constraintUnrecognized", cUnrecognized);
            result.put("constraintUnmatched", cWarnings);
            result.put("failures", repair.failures);
            result.put("failureCount", repair.failures.size());
            result.put("movedCount", repair.moved);
            StringBuilder msg = new StringBuilder("调整完成：课表 " + merged.size() + " 条、约束 " + constraintList.size() + " 条");
            // 无论是否发生移动都明确给出重排条数，便于用户判断本次调整的实际影响
            if (repair.moved > 0) {
                msg.append("，已按新约束重排 ").append(repair.moved).append(" 条课表");
            } else {
                msg.append("，已重排 0 条课表");
            }
            if (!repair.failures.isEmpty()) {
                msg.append("，").append(repair.failures.size()).append(" 门课程因新约束无法排入（详见原因）");
            }
            if (conflictCount > 0) {
                msg.append("，其中 ").append(conflictCount).append(" 条课表存在冲突（请继续调整或重新生成）");
            } else if (!merged.isEmpty()) {
                msg.append("，无冲突");
            }
            result.put("message", msg.toString());
            return result;
        } catch (Exception e) {
            return fail("调整失败：" + rootMessage(e));
        }
    }

    /** 构造自然语言调整的模型指令：要求输出全部条目并保留 idx，仅可动字段可改，删除用 deleted 标记 */
    private String buildModifyInstruction(List<Map<String, Object>> items, String instruction,
                                          String semester, int weekStart, int weekEnd, int minPeriod, int maxPeriod) {
        String json;
        try {
            json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(items);
        } catch (Exception e) {
            json = String.valueOf(items);
        }
        return "请根据用户的自然语言调整指令，修改下面的排课预览条目，输出调整后的完整 JSON 数组。\n"
                + "上下文：可排节次 " + minPeriod + "-" + maxPeriod + " 节；学期 " + (semester.isEmpty() ? "未指定" : semester)
                + "；周次范围 " + weekStart + "-" + weekEnd + "。\n"
                + "当前排课预览条目（每个条目带唯一 idx 序号）：\n" + json + "\n"
                + "用户调整指令：\n" + instruction + "\n"
                + "输出规则（必须严格遵守）：\n"
                + "1. 必须输出所有条目，逐条保留其 idx、className、courseName、teacherName 字段，顺序不变；"
                + "className、courseName、teacherName 一律原样保留，不得改动。\n"
                + "2. 仅允许修改以下字段：weekDay（1=周一…7=周日，整数）、startPeriod、endPeriod（整数节次，闭区间）、"
                + "classroomNo（教室号，未知可留空）、weekRange（周次，如 \"1-18\"）；未被指令要求修改的条目，以上字段也保持原值不变。\n"
                + "3. 语义示例：「调到周三下午」= weekDay=3、startPeriod=5、endPeriod=8；「换到 B3-201」= classroomNo=\"B3-201\"；"
                + "「第 1-8 周」= weekRange=\"1-8\"；「A 与 B 对调」= 交换两条目的 weekDay/startPeriod/endPeriod（涉及教室时也交换 classroomNo）。\n"
                + "4. 若指令要求删除某条目，必须保留该条目 idx 并额外输出 \"deleted\": true，不得直接省略该条目。\n"
                + "5. 严禁改动未被提及条目的字段值，严禁增删字段结构。\n"
                + "6. 只输出 JSON 数组本身，不要输出任何解释文字，不要输出 markdown 代码块。";
    }

    /** 判定预览条目之间是否两两冲突：同教师 */
    private boolean busyOtherTeacher(List<Map<String, Object>> items, int selfIdx, String teacher, int day, int sp, int ep) {
        for (int j = 0; j < items.size(); j++) {
            if (j == selfIdx) {
                continue;
            }
            Map<String, Object> o = items.get(j);
            if (teacher.equals(str(o.get("teacherName"))) && overlaps(o, day, sp, ep)) {
                return true;
            }
        }
        return false;
    }

    /** 判定预览条目之间是否两两冲突：同班级 */
    private boolean busyOtherClass(List<Map<String, Object>> items, int selfIdx, Long classId, int day, int sp, int ep) {
        for (int j = 0; j < items.size(); j++) {
            if (j == selfIdx) {
                continue;
            }
            Map<String, Object> o = items.get(j);
            Long cid = toLong(o.get("classId"));
            if (cid != null && cid.equals(classId) && overlaps(o, day, sp, ep)) {
                return true;
            }
        }
        return false;
    }

    /** 判定预览条目之间是否两两冲突：同教室 */
    private boolean busyOtherRoom(List<Map<String, Object>> items, int selfIdx, String roomNo, int day, int sp, int ep) {
        for (int j = 0; j < items.size(); j++) {
            if (j == selfIdx) {
                continue;
            }
            Map<String, Object> o = items.get(j);
            if (roomNo.equals(str(o.get("classroomNo"))) && overlaps(o, day, sp, ep)) {
                return true;
            }
        }
        return false;
    }
}
