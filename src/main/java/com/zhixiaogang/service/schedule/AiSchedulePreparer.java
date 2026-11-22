package com.zhixiaogang.service.schedule;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.zhixiaogang.service.SemesterConfigService;
import com.zhixiaogang.service.schedule.AiScheduleModels.*;
import static com.zhixiaogang.service.schedule.AiScheduleUtils.*;
import static com.zhixiaogang.service.schedule.AiTimeExpressionParser.*;
import static com.zhixiaogang.service.schedule.AiConstraintSupport.*;
import static com.zhixiaogang.service.schedule.AiConstraintEntrySupport.*;
import static com.zhixiaogang.service.schedule.AiScheduleTexts.*;

/**
 * AI 排课前置准备：班级与教师范围解析、现有课表占用加载、约束集合解析与归一。
 */
@Service
public class AiSchedulePreparer {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SemesterConfigService semesterConfigService;

    @Autowired
    private AiConstraintParser aiConstraintParser;

    private List<Map<String, Object>> resolveClasses(Map<String, Object> request) {
        List<Map<String, Object>> classes = new ArrayList<>();
        Object idsObj = request.get("classIds");
        List<Object> ids = new ArrayList<>();
        if (idsObj instanceof List) {
            ids.addAll((List<?>) idsObj);
        } else if (idsObj != null) {
            ids.add(idsObj);
        }
        for (Object idObj : ids) {
            Long id = toLong(idObj);
            if (id == null) {
                continue;
            }
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, class_name, department FROM class_info WHERE id = ?", id);
            if (!rows.isEmpty()) {
                classes.add(rows.get(0));
            }
        }
        return classes;
    }

    /**
     * 前置校验：目标班级是否已有课程表。
     * 命中时返回阻断结果（success=false、blocked=true、含班级名），调用方应立即终止本次排课；
     * 未选班级时返回 null，交由 prepareBasics 给出「请选择至少一个班级」提示。
     */
    Map<String, Object> blockExistingSchedule(Map<String, Object> request) {
        List<Map<String, Object>> classes = resolveClasses(request);
        if (classes.isEmpty()) {
            return null;
        }
        List<String> hitNames = new ArrayList<>();
        for (Map<String, Object> cls : classes) {
            Long id = toLong(cls.get("id"));
            if (id == null) {
                continue;
            }
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM schedule WHERE class_id = ?", Integer.class, id);
            if (count != null && count > 0) {
                hitNames.add(str(cls.get("class_name")));
            }
        }
        if (hitNames.isEmpty()) {
            return null;
        }
        Map<String, Object> result = fail("该班级当前已存在课程表，请先清理已有课程数据后再执行排课操作"
                + (hitNames.size() > 0 ? "（已有课表：" + String.join("、", hitNames) + "）" : ""));
        result.put("blocked", true);
        result.put("classes", hitNames);
        return result;
    }

    /**
     * 阶段 A：基础准备 —— 解析班级 / 课程、周次与节次范围，加载现有课表占用与教室池。
     * 任何一项不满足时返回错误结果（success=false），全部准备完成返回 null。
     */
    Map<String, Object> prepareBasics(AiScheduleContext ctx) {
        Map<String, Object> request = ctx.request;
        List<Map<String, Object>> classes = resolveClasses(request);
        if (classes.isEmpty()) {
            return fail("请选择至少一个班级");
        }
        List<Map<String, Object>> courses = resolveCourses(request);
        if (courses.isEmpty()) {
            return fail("课程清单为空，请先导入或添加课程");
        }
        for (Map<String, Object> c : courses) {
            String teacher = str(c.get("teacherName"));
            if (teacher.isEmpty()) {
                List<String> classNames = new ArrayList<>();
                for (Map<String, Object> cls : classes) {
                    classNames.add("「" + str(cls.get("class_name")) + "」");
                }
                return fail("排课失败：班级" + String.join("、", classNames)
                        + "的课程「" + c.get("courseName") + "」未指定授课教师（教师：未指定），"
                        + "请先在课程清单中选择教师后再生成");
            }
        }

        int weekStart = intOrDefault(request.get("weekStart"), 1);
        // weekEnd 未提供时默认取当前学期配置的持续周数；未配置学期时保持 16
        int defaultWeekEnd = 16;
        try {
            com.zhixiaogang.entity.SemesterConfig cfg = semesterConfigService.getCurrentSemester();
            if (cfg != null && cfg.getWeeks() != null && cfg.getWeeks() > 0) {
                defaultWeekEnd = cfg.getWeeks();
            }
        } catch (Exception ignore) {
            // 学期配置读取失败时保持默认 16
        }
        int weekEnd = intOrDefault(request.get("weekEnd"), defaultWeekEnd);
        if (weekStart > weekEnd) {
            int t = weekStart;
            weekStart = weekEnd;
            weekEnd = t;
        }
        String globalWeekRange = weekStart + "-" + weekEnd;

        int minPeriod = intOrDefault(request.get("minPeriod"), 1);
        int maxPeriod = intOrDefault(request.get("maxPeriod"), 8);
        if (minPeriod < 1) minPeriod = 1;
        if (maxPeriod < minPeriod) maxPeriod = minPeriod;
        if (maxPeriod > 10) maxPeriod = 10;
        boolean allowWeekend = boolOrDefault(request.get("allowWeekend"), false);

        List<Integer> days = new ArrayList<>();
        for (int d = 1; d <= (allowWeekend ? 7 : 5); d++) {
            days.add(d);
        }
        List<int[]> segments = new ArrayList<>();
        for (int s = minPeriod; s <= maxPeriod; s += 2) {
            segments.add(new int[]{s, Math.min(s + 1, maxPeriod)});
        }
        if (segments.isEmpty()) {
            return fail("节次范围配置不正确，请设置有效的节次上下限");
        }

        // 预加载现有课表占用（教师 / 班级 / 教室，含周次范围），并复制为工作副本
        List<Map<String, Object>> existingRows = jdbcTemplate.queryForList(
                "SELECT teacher_name, teacher_id, class_id, classroom_id, week_day, start_period, end_period, week_range "
                        + "FROM schedule");
        Map<String, List<Slot>> existTeacher = new HashMap<>();
        Map<Long, List<Slot>> existClass = new HashMap<>();
        Map<Long, List<Slot>> existRoom = new HashMap<>();
        for (Map<String, Object> row : existingRows) {
            Integer wd = toInt(row.get("week_day"));
            Integer sp = toInt(row.get("start_period"));
            Integer ep = toInt(row.get("end_period"));
            if (wd == null || sp == null || ep == null) {
                continue;
            }
            java.util.BitSet weeks = weeksOf(row.get("week_range") == null ? null : row.get("week_range").toString());
            String tn = row.get("teacher_name") == null ? "" : row.get("teacher_name").toString().trim();
            if (!tn.isEmpty()) {
                existTeacher.computeIfAbsent(tn, k -> new ArrayList<>()).add(new Slot(wd, sp, ep, weeks));
            }
            // 工号同时作为占用键：课程清单可能填写工号而非姓名
            String tid = row.get("teacher_id") == null ? "" : row.get("teacher_id").toString().trim();
            if (!tid.isEmpty() && !tid.equals(tn)) {
                existTeacher.computeIfAbsent(tid, k -> new ArrayList<>()).add(new Slot(wd, sp, ep, weeks));
            }
            Long cid = toLong(row.get("class_id"));
            if (cid != null) {
                existClass.computeIfAbsent(cid, k -> new ArrayList<>()).add(new Slot(wd, sp, ep, weeks));
            }
            Long rid = toLong(row.get("classroom_id"));
            if (rid != null) {
                existRoom.computeIfAbsent(rid, k -> new ArrayList<>()).add(new Slot(wd, sp, ep, weeks));
            }
        }
        Map<String, List<Slot>> workTeacher = deepCopySlots(existTeacher);
        Map<Long, List<Slot>> workClass = deepCopySlots(existClass);
        Map<Long, List<Slot>> workRoom = deepCopySlots(existRoom);

        List<Map<String, Object>> rooms = jdbcTemplate.queryForList(
                "SELECT id, classroom_no, building, room_type, capacity, description FROM classroom "
                        + "WHERE status IS NULL OR status <> 'maintenance'");

        // 本次排课涉及的教师（现有课表教师 ∪ 本次课程教师）：同姓简称优先在本范围内消歧
        java.util.Set<String> workloadTeachers = new HashSet<>(workTeacher.keySet());
        for (Map<String, Object> c : courses) {
            String tn = str(c.get("teacherName"));
            if (!tn.isEmpty()) {
                workloadTeachers.add(tn);
            }
        }
        // 已知教师名单：教师账号姓名（user 表 role=teacher）∪ 本次排课教师，
        // 用于约束主语识别（裸人名 / 「X 老师」简称均按该名单还原为教师全名）
        java.util.Set<String> teacherNames = new HashSet<>(workloadTeachers);
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT name FROM user WHERE role = 'teacher' AND name IS NOT NULL AND name <> ''")) {
            String tn = str(row.get("name"));
            if (!tn.isEmpty()) {
                teacherNames.add(tn);
            }
        }
        ctx.classes = classes;
        ctx.courses = courses;
        ctx.weekStart = weekStart;
        ctx.defaultWeekEnd = defaultWeekEnd;
        ctx.weekEnd = weekEnd;
        ctx.globalWeekRange = globalWeekRange;
        ctx.minPeriod = minPeriod;
        ctx.maxPeriod = maxPeriod;
        ctx.allowWeekend = allowWeekend;
        ctx.days = days;
        ctx.segments = segments;
        ctx.existingRows = existingRows;
        ctx.existTeacher = existTeacher;
        ctx.existClass = existClass;
        ctx.existRoom = existRoom;
        ctx.workTeacher = workTeacher;
        ctx.workClass = workClass;
        ctx.workRoom = workRoom;
        ctx.rooms = rooms;
        ctx.workloadTeachers = workloadTeachers;
        ctx.teacherNames = teacherNames;
        return null;
    }

    /**
     * 阶段 B：约束准备 —— 解析结构化约束 / 自然语言约束，归一出周次与时段限制、每日课时上限。
     * 约束解析出现致命错误时返回错误结果（success=false），否则返回 null。
     */
    Map<String, Object> prepareConstraints(AiScheduleContext ctx) {
        Map<String, Object> request = ctx.request;
        List<Map<String, Object>> classes = ctx.classes;
        List<Map<String, Object>> courses = ctx.courses;
        int weekStart = ctx.weekStart;
        int weekEnd = ctx.weekEnd;
        String globalWeekRange = ctx.globalWeekRange;
        List<Integer> days = ctx.days;
        Map<String, List<Slot>> workTeacher = ctx.workTeacher;
        Map<Long, List<Slot>> workClass = ctx.workClass;
        Map<Long, List<Slot>> workRoom = ctx.workRoom;
        List<Map<String, Object>> rooms = ctx.rooms;
        java.util.Set<String> workloadTeachers = ctx.workloadTeachers;
        java.util.Set<String> teacherNames = ctx.teacherNames;
        // 约束来源：优先取预览页回传的结构化条目（同主体时间片段已合并、删除后仅回传剩余条目），
        // 否则解析自然语言约束文本
        List<ConstraintEntry> constraintEntries = new ArrayList<>();
        List<String> constraintUnrecognized = new ArrayList<>();
        List<String> constraintWarnings = new ArrayList<>();
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
            constraintEntries.addAll(parseStructuredEntries((List<?>) structuredRaw));
        } else {
            ConstraintParseResult parsedConstraints = aiConstraintParser.parseConstraints(str(request.get("constraints")), weekEnd,
                    teacherNames, workloadTeachers);
            if (parsedConstraints.fatalError != null) {
                return fail(parsedConstraints.fatalError);
            }
            for (Constraint c : parsedConstraints.constraints) {
                mergeConstraintEntry(constraintEntries, c);
            }
            constraintUnrecognized.addAll(parsedConstraints.unrecognized);
            constraintWarnings.addAll(parsedConstraints.warnings);
        }
        for (int ci = 0; ci < constraintEntries.size(); ci++) {
            constraintEntries.get(ci).id = ci + 1;
        }
        // excludedConstraintIds 指定的条目在占用合并与冲突检查之前整体剔除（兼容旧版前端）
        java.util.Set<Integer> excludedIds = new java.util.HashSet<>();
        Object excludedRaw = request.get("excludedConstraintIds");
        if (excludedRaw instanceof List) {
            for (Object eo : (List<?>) excludedRaw) {
                Integer d = toInt(eo);
                if (d != null) {
                    excludedIds.add(d);
                }
            }
        }
        List<ConstraintEntry> activeEntries = new ArrayList<>();
        for (ConstraintEntry entry : constraintEntries) {
            if (excludedIds.contains(entry.id)) {
                continue;
            }
            activeEntries.add(entry);
        }
        // 条目重新连续编号，展平片段交给排课引擎逐条生效
        List<Constraint> constraints = new ArrayList<>();
        List<Map<String, Object>> constraintList = new ArrayList<>();
        for (int ci = 0; ci < activeEntries.size(); ci++) {
            ConstraintEntry entry = activeEntries.get(ci);
            entry.id = ci + 1;
            for (Constraint part : entry.parts) {
                // 需排课类正向约束只做记录展示，排课引擎按禁用约束解释，不能混入
                if (!part.mustSchedule) {
                    constraints.add(part);
                }
            }
            constraintList.add(constraintItem(entry));
        }
        Map<String, Long> classIdByName = new LinkedHashMap<>();
        for (Map<String, Object> cls : classes) {
            Long id = toLong(cls.get("id"));
            String cn = str(cls.get("class_name"));
            if (id != null && !cn.isEmpty()) {
                classIdByName.put(cn, id);
            }
        }
        Map<String, Long> roomIdByNo = new LinkedHashMap<>();
        Map<Long, Map<String, Object>> roomById = new LinkedHashMap<>();
        for (Map<String, Object> room : rooms) {
            Long id = toLong(room.get("id"));
            String no = str(room.get("classroom_no"));
            if (id != null && !no.isEmpty()) {
                roomIdByNo.put(no, id);
            }
            if (id != null) {
                roomById.put(id, room);
            }
        }
        // 班级人数（容量匹配）与常用教学楼（就近优先）：用于「教室类型 + AI 自动推荐」的教室挑选
        Map<Long, Integer> classSizeById = new HashMap<>();
        for (Map<String, Object> cls : classes) {
            Long cid = toLong(cls.get("id"));
            if (cid == null) {
                continue;
            }
            Integer cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM class_member WHERE class_id = ? AND status IN ('approved','withdraw_pending')",
                    Integer.class, cid);
            classSizeById.put(cid, cnt == null ? 0 : cnt);
        }
        Map<Long, String> classBuilding = new HashMap<>();
        Map<Long, Map<String, Integer>> buildingCounts = new HashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT s.class_id AS class_id, c.building AS building, COUNT(*) AS cnt "
                        + "FROM schedule s JOIN classroom c ON s.classroom_id = c.id "
                        + "WHERE c.building IS NOT NULL AND c.building <> '' "
                        + "GROUP BY s.class_id, c.building")) {
            Long cid = toLong(row.get("class_id"));
            String building = str(row.get("building"));
            if (cid == null || building.isEmpty()) {
                continue;
            }
            buildingCounts.computeIfAbsent(cid, k -> new LinkedHashMap<>())
                    .merge(building, intOrDefault(row.get("cnt"), 0), Integer::sum);
        }
        for (Map.Entry<Long, Map<String, Integer>> e : buildingCounts.entrySet()) {
            String bestBuilding = "";
            int bestCount = -1;
            for (Map.Entry<String, Integer> be : e.getValue().entrySet()) {
                if (be.getValue() > bestCount) {
                    bestCount = be.getValue();
                    bestBuilding = be.getKey();
                }
            }
            classBuilding.put(e.getKey(), bestBuilding);
        }
        // 周次类约束：对匹配目标按周次区间从排课范围中扣除；无法匹配时作为未命中提示
        List<Constraint> weekConstraints = new ArrayList<>();
        List<Constraint> slotConstraints = new ArrayList<>();
        for (Constraint c : constraints) {
            // 每日课时上限单独生效（按天按节计数），不参与周次扣除与节次占用
            if (c.maxPerDay != null) {
                continue;
            }
            // 纯周次约束按周次扣除；带星期/节次维度的复合约束（如「单周下午不排」）走时段占用，
            // 预览条目周次为整段，无法按周拆分时段，故受限时段/星期在所有周次统一禁用（偏严格但不违规）
            boolean noSlot = (c.days == null || c.days.isEmpty())
                    && c.periodStart == null && c.periodEnd == null;
            if (noSlot && (c.weekStart != null || c.weekParity != null)) {
                weekConstraints.add(c);
            } else {
                slotConstraints.add(c);
            }
        }
        // 主语提示（同姓教师歧义 / 查无此姓等）先行汇总，保证约束即使未生效也不会静默丢弃
        List<String> unmatchedNotes = new ArrayList<>(constraintWarnings);
        List<Constraint> unmatchedConstraints = applyConstraints(slotConstraints, workTeacher, workClass, workRoom,
                classIdByName, roomIdByNo, teacherNames);
        java.util.Set<String> unmatchedSet = new java.util.HashSet<>();
        for (Constraint c : unmatchedConstraints) {
            unmatchedSet.add(describe(c));
        }
        for (String desc : unmatchedSet) {
            unmatchedNotes.add("约束「" + desc + "」未匹配到目标，未生效");
        }
        // 课程级周次范围计算：基础 = 全局周次范围；命中周次约束的目标按周次区间扣除
        Map<String, String> courseWeekAdjust = new LinkedHashMap<>();
        // 单周与双周并列禁排的「班级|课程」，用于给出明确无解提示
        java.util.Set<String> parityBlocked = new java.util.HashSet<>();
        for (Map<String, Object> cls : classes) {
            Long classId = toLong(cls.get("id"));
            String className = str(cls.get("class_name"));
            for (Map<String, Object> course : courses) {
                String teacher = str(course.get("teacherName"));
                String classroomNo = str(course.get("classroomNo"));
                Set<Integer> allowed = buildAllowedWeeks(globalWeekRange);
                List<Constraint> hits = new ArrayList<>();
                for (Constraint wc : weekConstraints) {
                    boolean hit = false;
                    boolean wild = isWildcard(wc.name) || isTargetClass(wc.name);
                    if ("class".equals(wc.type)) {
                        hit = wild ? (classId != null) : (classId != null
                                && classId.equals(matchId(wc.name, classIdByName)));
                    } else if ("teacher".equals(wc.type)) {
                        hit = wild ? (!teacher.isEmpty()) : (teacher.equals(nameMatch(wc.name, teacherNames)));
                    } else {
                        hit = wild ? (!classroomNo.isEmpty())
                                : (normName(wc.name).equalsIgnoreCase(normName(classroomNo)));
                    }
                    if (hit) {
                        hits.add(wc);
                    }
                }
                if (!hits.isEmpty()) {
                    boolean banSingle = false;
                    boolean banDouble = false;
                    for (Constraint wc : hits) {
                        if (wc.weekParity != null) {
                            if (wc.weekParity == 1) {
                                banSingle = true;
                            } else if (wc.weekParity == 2) {
                                banDouble = true;
                            }
                        }
                    }
                    if (banSingle && banDouble) {
                        parityBlocked.add(className + "|" + str(course.get("courseName")));
                    }
                    for (Constraint wc : hits) {
                        if (wc.weekParity != null) {
                            // 单双周：单周禁用第 1,3,5… 周，双周禁用第 2,4,6… 周
                            // 注意：weekParity=2 对应偶数周，w % 2 的结果为 0，故取 parity % 2 比较
                            int parityMod = wc.weekParity % 2;
                            for (int w = 1; w <= weekEnd; w++) {
                                if (w % 2 == parityMod) {
                                    allowed.remove(w);
                                }
                            }
                            continue;
                        }
                        int ws = Math.max(1, wc.weekStart);
                        int we = Math.min(20, wc.weekEnd == null ? wc.weekStart : wc.weekEnd);
                        if (we < ws) {
                            int tmp = ws;
                            ws = we;
                            we = tmp;
                        }
                        for (int w = ws; w <= we; w++) {
                            allowed.remove(w);
                        }
                    }
                    courseWeekAdjust.put(className + "|" + str(course.get("courseName")),
                            joinWeeks(allowed));
                }
            }
        }
        List<String> constraintSummary = new ArrayList<>();
        for (ConstraintEntry entry : activeEntries) {
            constraintSummary.add(describeEntry(entry));
        }

        // 每日课时上限：按班级收集上限（多条取最小值），排课时逐天累计节数校验
        Map<Long, Integer> classDayPeriodCap = new HashMap<>();
        for (Constraint c : constraints) {
            if (c.maxPerDay == null || c.maxPerDay <= 0 || !"class".equals(c.type)) {
                continue;
            }
            if (isWildcard(c.name) || isTargetClass(c.name)) {
                for (Long id : classIdByName.values()) {
                    classDayPeriodCap.merge(id, c.maxPerDay, Math::min);
                }
            } else {
                Long id = matchId(c.name, classIdByName);
                if (id != null) {
                    classDayPeriodCap.merge(id, c.maxPerDay, Math::min);
                } else if (!unmatchedNotes.contains("约束「" + describe(c) + "」未匹配到目标，未生效")) {
                    unmatchedNotes.add("约束「" + describe(c) + "」未匹配到目标，未生效");
                }
            }
        }
        ctx.classes = classes;
        ctx.courses = courses;
        ctx.weekStart = weekStart;
        ctx.weekEnd = weekEnd;
        ctx.globalWeekRange = globalWeekRange;
        ctx.days = days;
        ctx.workTeacher = workTeacher;
        ctx.workClass = workClass;
        ctx.workRoom = workRoom;
        ctx.rooms = rooms;
        ctx.workloadTeachers = workloadTeachers;
        ctx.teacherNames = teacherNames;
        ctx.constraintEntries = constraintEntries;
        ctx.constraintUnrecognized = constraintUnrecognized;
        ctx.constraintWarnings = constraintWarnings;
        ctx.structuredRaw = structuredRaw;
        ctx.hasStructured = hasStructured;
        ctx.excludedIds = excludedIds;
        ctx.excludedRaw = excludedRaw;
        ctx.activeEntries = activeEntries;
        ctx.constraints = constraints;
        ctx.constraintList = constraintList;
        ctx.classIdByName = classIdByName;
        ctx.roomIdByNo = roomIdByNo;
        ctx.roomById = roomById;
        ctx.classSizeById = classSizeById;
        ctx.classBuilding = classBuilding;
        ctx.buildingCounts = buildingCounts;
        ctx.weekConstraints = weekConstraints;
        ctx.slotConstraints = slotConstraints;
        ctx.unmatchedNotes = unmatchedNotes;
        ctx.unmatchedConstraints = unmatchedConstraints;
        ctx.unmatchedSet = unmatchedSet;
        ctx.courseWeekAdjust = courseWeekAdjust;
        ctx.parityBlocked = parityBlocked;
        ctx.constraintSummary = constraintSummary;
        ctx.classDayPeriodCap = classDayPeriodCap;
        return null;
    }
}
