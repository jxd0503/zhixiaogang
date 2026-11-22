package com.zhixiaogang.service.schedule;

import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.zhixiaogang.service.schedule.AiScheduleModels.*;
import static com.zhixiaogang.service.schedule.AiScheduleConstants.*;
import static com.zhixiaogang.service.schedule.AiScheduleUtils.*;
import static com.zhixiaogang.service.schedule.AiTimeExpressionParser.*;
import static com.zhixiaogang.service.schedule.AiScheduleTexts.*;

/**
 * AI 排课引擎：候选教室 / 时段分配、逐课程回溯与冲突校验，产出最终预览结果。
 */
@Service
public class AiSchedulingEngine {

    /** 体育类课程：默认不占用教室 */
    private boolean isSportsCourse(String courseName) {
        String n = courseName == null ? "" : courseName;
        if (n.isEmpty()) {
            return false;
        }
        String[] keys = {"体育", "田径", "篮球", "足球", "排球", "羽毛球", "乒乓球", "网球",
                "健美操", "体操", "武术", "游泳", "军训", "体测", "形体"};
        for (String k : keys) {
            if (n.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** 用户未指定教室类型时，按课程名推断类型（保持与教室管理中的类型枚举一致） */
    private String guessRoomType(String courseName) {
        String n = courseName == null ? "" : courseName;
        if (isSportsCourse(n)) {
            return "室外场地";
        }
        String[] microKeys = {"计算机", "程序", "编程", "软件", "网络", "数据库", "信息", "网页",
                "Java", "Python", "C语言", "算法", "人工智能", "大数据", "实训", "上机", "操作"};
        for (String k : microKeys) {
            if (n.toLowerCase().contains(k.toLowerCase())) {
                return "微机室";
            }
        }
        if (n.contains("实验")) {
            return "实验室";
        }
        String[] voiceKeys = {"语音", "口语", "听力", "听说"};
        for (String k : voiceKeys) {
            if (n.contains(k)) {
                return "语音室";
            }
        }
        String[] mediaKeys = {"美术", "音乐", "舞蹈", "设计", "影视", "多媒体"};
        for (String k : mediaKeys) {
            if (n.contains(k)) {
                return "多媒体教室";
            }
        }
        return "普通教室";
    }

    /** 教室类型匹配：「多媒体教室」与「多媒体」等写法差异按包含关系兼容 */
    private boolean roomTypeEquals(String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        if (x.isEmpty() || y.isEmpty()) {
            return false;
        }
        return x.equals(y) || x.contains(y) || y.contains(x);
    }

    private int roomCapacity(Map<String, Object> room) {
        return room == null ? 0 : intOrDefault(room.get("capacity"), 0);
    }

    /** 指定类型的全部教室（不看容量与占用） */
    private List<Map<String, Object>> roomsOfType(List<Map<String, Object>> rooms, String type) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> room : rooms) {
            if (roomTypeEquals(str(room.get("room_type")), type)) {
                out.add(room);
            }
        }
        return out;
    }

    /**
     * 「教室类型 + AI 自动推荐」候选池：严格按类型筛选 + 容量满足班级人数，
     * 排序为 同教学楼优先 → 容量最贴合优先 → 教室号，保证就近且不浪费大教室。
     */
    private List<Map<String, Object>> roomPool(List<Map<String, Object>> rooms, String type,
                                               int classSize, String preferBuilding) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> room : roomsOfType(rooms, type)) {
            int cap = roomCapacity(room);
            // 容量未知（<=0）视为不限容量，避免因数据缺失排除全部教室
            if (cap > 0 && classSize > 0 && cap < classSize) {
                continue;
            }
            out.add(room);
        }
        String prefer = preferBuilding == null ? "" : preferBuilding.trim();
        out.sort(Comparator
                .comparingInt((Map<String, Object> r) -> {
                    String b = str(r.get("building"));
                    return !prefer.isEmpty() && b.equals(prefer) ? 0 : 1;
                })
                .thenComparingInt(r -> roomCapacity(r) <= 0 ? Integer.MAX_VALUE : roomCapacity(r))
                .thenComparing(r -> str(r.get("classroom_no"))));
        return out;
    }

    /** 从候选池挑该时段空闲的教室：优先复用本课程上次使用的教室，找不到再按池顺序取（按周次精确判占用） */
    private Map<String, Object> pickFreeRoom(List<Map<String, Object>> pool, Map<Long, List<Slot>> workRoom,
                                             int day, int start, int end, Long preferId, java.util.BitSet weeks) {
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        if (preferId != null) {
            for (Map<String, Object> room : pool) {
                Long rid = toLong(room.get("id"));
                if (preferId.equals(rid) && !isBusy(workRoom.get(rid), day, start, end, weeks)) {
                    return room;
                }
            }
        }
        for (Map<String, Object> room : pool) {
            Long rid = toLong(room.get("id"));
            if (rid != null && !isBusy(workRoom.get(rid), day, start, end, weeks)) {
                return room;
            }
        }
        return null;
    }

    /**
     * 阶段 C：逐课程分配 —— 按班级 × 课程求可行时段与教室，某门课程排不下时整体回滚并记录原因。
     */
    void allocate(AiScheduleContext ctx) {
        Map<String, Object> request = ctx.request;
        List<Map<String, Object>> classes = ctx.classes;
        List<Map<String, Object>> courses = ctx.courses;
        String globalWeekRange = ctx.globalWeekRange;
        List<Integer> days = ctx.days;
        List<int[]> segments = ctx.segments;
        Map<String, List<Slot>> workTeacher = ctx.workTeacher;
        Map<Long, List<Slot>> workClass = ctx.workClass;
        Map<Long, List<Slot>> workRoom = ctx.workRoom;
        List<Map<String, Object>> rooms = ctx.rooms;
        Map<String, Long> roomIdByNo = ctx.roomIdByNo;
        Map<Long, Map<String, Object>> roomById = ctx.roomById;
        Map<Long, Integer> classSizeById = ctx.classSizeById;
        Map<Long, String> classBuilding = ctx.classBuilding;
        List<Constraint> weekConstraints = ctx.weekConstraints;
        List<Constraint> slotConstraints = ctx.slotConstraints;
        Map<String, String> courseWeekAdjust = ctx.courseWeekAdjust;
        java.util.Set<String> parityBlocked = ctx.parityBlocked;
        Map<Long, Integer> classDayPeriodCap = ctx.classDayPeriodCap;
        String semester = str(request.get("semester"));
        List<Map<String, Object>> items = new ArrayList<>();
        Map<Long, Map<Integer, Integer>> classDayLoad = new HashMap<>();
        // 每个班级每天已排入的节数（按节计数，用于每日上限校验）
        Map<Long, Map<Integer, Integer>> classDayPeriods = new HashMap<>();
        java.util.Set<String> courseDayKeys = new java.util.HashSet<>();
        // 「班级|课程」→ 上次分配的教室：同一课程多个时段尽量用同一间教室
        Map<String, Long> courseRoomPrefer = new HashMap<>();

        // 逐门课程排课：某门课程排不下时只回滚并跳过该课程，其余课程继续正常排入
        List<String> scheduleFailures = new ArrayList<>();

        for (Map<String, Object> cls : classes) {
            Long classId = toLong(cls.get("id"));
            String className = str(cls.get("class_name"));
            for (Map<String, Object> course : courses) {
                // 本课程开始前的占用快照：排不下时整体回滚，避免半门课程残留在结果里
                Map<Long, List<Slot>> workClassSnapshot = deepCopySlots(workClass);
                Map<String, List<Slot>> workTeacherSnapshot = deepCopySlots(workTeacher);
                Map<Long, List<Slot>> workRoomSnapshot = deepCopySlots(workRoom);
                Map<Long, Map<Integer, Integer>> dayLoadSnapshot = copyCountMap(classDayLoad);
                Map<Long, Map<Integer, Integer>> dayPeriodSnapshot = copyCountMap(classDayPeriods);
                java.util.Set<String> dayKeysSnapshot = new java.util.HashSet<>(courseDayKeys);
                Map<String, Long> roomPreferSnapshot = new HashMap<>(courseRoomPrefer);
                // 本课程已产出的条目起点：失败时一并回滚
                int courseItemStart = items.size();
                // 本课程内的失败原因（非空表示该课程排入失败）
                String courseFailure = null;
                String courseName = str(course.get("courseName"));
                int weekHours = intOrDefault(course.get("weekHours"), 2);
                if (weekHours < 1) weekHours = 1;
                String teacher = str(course.get("teacherName"));
                String classroomNo = str(course.get("classroomNo"));
                String weekRange = str(course.get("weekRange"));
                if (weekRange.isEmpty()) {
                    weekRange = globalWeekRange;
                }
                // 课程自身声明的周次（失败提示里展示原始范围，便于对照约束调整）
                String declaredWeekRange = weekRange;
                // 周次类约束扣减：与课程自身周次求交集得到实际生效周次
                String adjustKey = className + "|" + courseName;
                String adjusted = courseWeekAdjust.get(adjustKey);
                if (adjusted != null) {
                    Set<Integer> eff = buildAllowedWeeks(weekRange);
                    eff.retainAll(buildAllowedWeeks(adjusted));
                    weekRange = joinWeeks(eff);
                    if (weekRange.isEmpty()) {
                        String reason = parityBlocked.contains(adjustKey)
                                ? "单周、双周均禁排"
                                : "周次约束（" + weekConstraintNames(weekConstraints) + "）扣减后无剩余周次";
                        scheduleFailures.add(failureMessage(className, courseName, teacher,
                                declaredWeekRange, days, segments, "",
                                "课程周次「" + declaredWeekRange + "」经约束扣减后已无剩余可排周次（" + reason + "）",
                                "请调整周次约束或修改该课程周次后重试"));
                        continue;
                    }
                }
                // 教室模式：auto（类型 + AI 自动推荐）/ fixed（指定教室）/ none（无需教室）
                java.util.BitSet courseWeeks = weeksOf(weekRange);
                String roomMode = str(course.get("roomMode")).toLowerCase();
                String classroomType = str(course.get("classroomType"));
                boolean allowAutoChange = boolOrDefault(course.get("allowAutoChange"), false);
                String noRoomMark = classroomNo.trim();
                if (noRoomMark.equals("无教室") || noRoomMark.equals("室外") || noRoomMark.equals("操场")
                        || noRoomMark.equals("体育场") || noRoomMark.equals("体育馆") || noRoomMark.equals("无")) {
                    roomMode = "none";
                    classroomNo = "";
                }
                if (roomMode.isEmpty()) {
                    roomMode = classroomNo.isEmpty() ? "auto" : "fixed";
                }
                if ("fixed".equals(roomMode) && classroomNo.isEmpty()) {
                    roomMode = "auto";
                }
                if ("auto".equals(roomMode) && classroomType.isEmpty()) {
                    classroomType = guessRoomType(courseName);
                }
                boolean noRoom = "none".equals(roomMode);
                // 指定教室：填了具体教室号时固定排该教室
                Long fixedRoomId = null;
                boolean hasFixedRoom = "fixed".equals(roomMode) && !classroomNo.isEmpty();
                if (hasFixedRoom) {
                    fixedRoomId = roomIdByNo.get(classroomNo);
                    if (fixedRoomId == null) {
                        scheduleFailures.add(failureMessage(className, courseName, teacher,
                                weekRange, days, segments, "",
                                "指定的教室「" + classroomNo + "」在教室管理中不存在，无法为其分配教室",
                                "请检查教室号，或改用「按类型自动推荐教室 / 无需教室」后重试"));
                        continue;
                    }
                }
                // 自动推荐候选池：按类型严格筛选 + 容量匹配，同教学楼优先、容量最贴合优先
                int classSize = classSizeById.getOrDefault(classId, 0);
                String preferBuilding = classBuilding.getOrDefault(classId, "");
                List<Map<String, Object>> autoPool = null;
                List<Map<String, Object>> altPool = null;
                String roomCause = "可用教室不足";
                if ("auto".equals(roomMode)) {
                    autoPool = roomPool(rooms, classroomType, classSize, preferBuilding);
                    if (autoPool.isEmpty()) {
                        boolean typeExists = !roomsOfType(rooms, classroomType).isEmpty();
                        String reason = typeExists
                                ? "「" + classroomType + "」类型教室容量均不足（班级 " + classSize + " 人）"
                                : "教室管理中没有「" + classroomType + "」类型的教室";
                        String advice = typeExists
                                ? "请调整教室类型，或更换可容纳 " + classSize + " 人的教室后重试"
                                : "请先在「教室管理」中添加该类教室，或调整教室类型后重试";
                        scheduleFailures.add(failureMessage(className, courseName, teacher,
                                weekRange, days, segments, "", reason, advice));
                        continue;
                    }
                    roomCause = "教室类型「" + classroomType + "」在可选时段内均被占用";
                }
                // 指定教室勾选「允许自动更换」时，同类型教室作为替补池
                if (hasFixedRoom && allowAutoChange) {
                    Map<String, Object> fixedRoom = roomById.get(fixedRoomId);
                    String fixedType = fixedRoom == null ? "" : str(fixedRoom.get("room_type"));
                    List<Map<String, Object>> pool = roomPool(rooms, fixedType, classSize, preferBuilding);
                    // 替补池不包含指定教室本身
                    altPool = new ArrayList<>();
                    for (Map<String, Object> room : pool) {
                        Long rid = toLong(room.get("id"));
                        if (rid != null && !rid.equals(fixedRoomId)) {
                            altPool.add(room);
                        }
                    }
                }
                String courseRoomKey = classId + "|" + courseName;
                int segCount = (int) Math.ceil(weekHours / 2.0);

                for (int k = 0; k < segCount; k++) {
                    int len = (k == segCount - 1 && weekHours % 2 == 1) ? 1 : 2;
                    int[] best = null;          // {day, start, end}
                    Map<String, Object> bestRoom = null;
                    double bestScore = Double.MAX_VALUE;
                    // 是否因教室不可用而放弃过候选时段（用于失败时给出真实原因）
                    boolean roomBlocked = false;

                    for (int day : days) {
                        for (int si = 0; si < segments.size(); si++) {
                            int[] seg = segments.get(si);
                            int s = seg[0];
                            int e = (len == 1) ? seg[0] : seg[1];
                            if (isBusy(workClass.get(classId), day, s, e, courseWeeks)) {
                                continue;
                            }
                            if (!teacher.isEmpty() && isBusy(workTeacher.get(teacher), day, s, e, courseWeeks)) {
                                continue;
                            }
                            // 教室分配：固定教室优先原教室；勾选允许更换或类型推荐时从候选池取空闲教室
                            Map<String, Object> slotRoom = null;
                            if (!noRoom) {
                                Long preferRoomId = courseRoomPrefer.get(courseRoomKey);
                                if (hasFixedRoom) {
                                    if (!isBusy(workRoom.get(fixedRoomId), day, s, e, courseWeeks)) {
                                        slotRoom = roomById.get(fixedRoomId);
                                    } else if (allowAutoChange) {
                                        slotRoom = pickFreeRoom(altPool, workRoom, day, s, e, preferRoomId, courseWeeks);
                                    }
                                } else {
                                    slotRoom = pickFreeRoom(autoPool, workRoom, day, s, e, preferRoomId, courseWeeks);
                                }
                                if (slotRoom == null) {
                                    roomBlocked = true;
                                    continue;
                                }
                            }
                            // 每日课时上限：该班级当天已排节数 + 本次节数不得超过上限
                            Integer dayCap = classDayPeriodCap.get(classId);
                            if (dayCap != null) {
                                int periodLoad = classDayPeriods
                                        .computeIfAbsent(classId, x -> new HashMap<>())
                                        .getOrDefault(day, 0);
                                if (periodLoad + (e - s + 1) > dayCap) {
                                    continue;
                                }
                            }

                            int load = classDayLoad
                                    .computeIfAbsent(classId, x -> new HashMap<>())
                                    .getOrDefault(day, 0);
                            boolean sameDay = courseDayKeys.contains(classId + "|" + courseName + "|" + day);
                            double score = load * 10.0 + (sameDay ? 200 : 0) + day * 0.5 + si * 0.1;
                            if (score < bestScore) {
                                bestScore = score;
                                best = new int[]{day, s, e};
                                bestRoom = slotRoom;
                            }
                        }
                    }

                    if (best == null) {
                        Integer dayCap = classDayPeriodCap.get(classId);
                        if (dayCap != null && !roomBlocked) {
                            String capReason = weekHours > days.size() * dayCap
                                    ? "每天最多 " + dayCap + " 节，无法容纳周学时 " + weekHours + "（需 " + segCount + " 个时段）"
                                    : "每天最多 " + dayCap + " 节的限制下已无剩余可用时段";
                            courseFailure = failureMessage(className, courseName, teacher,
                                    weekRange, days, segments,
                                    "第 " + (k + 1) + " 个时段（" + len + " 节，周学时 " + weekHours + "）排不进去",
                                    capReason,
                                    "请调高每日课时上限，或减少该课程周学时（当前 " + weekHours + "）后重试");
                            break;
                        }
                        if (hasFixedRoom) {
                            String hint = allowAutoChange
                                    ? "同类型的其他教室在这些时段也已排满"
                                    : "如需自动更换为同类型其他教室，请勾选「占用时允许自动换教室」";
                            courseFailure = failureMessage(className, courseName, teacher,
                                    weekRange, days, segments,
                                    "第 " + (k + 1) + " 个时段（" + len + " 节，周学时 " + weekHours + "）排不进去",
                                    "指定教室「" + classroomNo + "」在可排时段内均已被占用",
                                    "请更换教室、" + hint + "，或调整时间范围后重试");
                            break;
                        }
                        if (noRoom) {
                            courseFailure = buildFailure(className, courseName, weekHours, k + 1, len,
                                    teacher, weekRange, days, segments, classId,
                                    workClass, workTeacher, slotConstraints);
                            break;
                        }
                        // 类型推荐：教室全部占用时给出明确原因，便于用户调整类型或时段后重试
                        String cause = roomBlocked
                                ? roomCause
                                : "这些时段内班级和教师都已被其他课程占用";
                        courseFailure = failureMessage(className, courseName, teacher,
                                weekRange, days, segments,
                                "第 " + (k + 1) + " 个时段（" + len + " 节，周学时 " + weekHours + "）排不进去",
                                cause,
                                "请调整教室类型 / 时间范围，或减少该课程周学时（当前 " + weekHours + "）后重试");
                        break;
                    }

                    int day = best[0];
                    int start = best[1];
                    int end = best[2];
                    if (!teacher.isEmpty()) {
                        workTeacher.computeIfAbsent(teacher, x -> new ArrayList<>())
                                .add(new Slot(day, start, end, courseWeeks));
                    }
                    workClass.computeIfAbsent(classId, x -> new ArrayList<>())
                            .add(new Slot(day, start, end, courseWeeks));
                    classDayLoad.computeIfAbsent(classId, x -> new HashMap<>()).merge(day, 1, Integer::sum);
                    classDayPeriods.computeIfAbsent(classId, x -> new HashMap<>())
                            .merge(day, end - start + 1, Integer::sum);
                    courseDayKeys.add(classId + "|" + courseName + "|" + day);

                    // 实际落到的教室（类型推荐 / 指定教室自动更换后都落到具体教室）
                    Long assignedRoomId = bestRoom == null ? null : toLong(bestRoom.get("id"));
                    String assignedNo = bestRoom == null ? "" : str(bestRoom.get("classroom_no"));
                    String classroomDisplay;
                    String note = "";
                    if (noRoom) {
                        classroomDisplay = NO_CLASSROOM_DISPLAY;
                    } else if (hasFixedRoom) {
                        if (assignedRoomId != null && assignedRoomId.equals(fixedRoomId)) {
                            classroomDisplay = assignedNo;
                        } else {
                            classroomDisplay = "指定：" + classroomNo + "（该时段已占用）→ 实际：" + assignedNo;
                            note = "指定教室「" + classroomNo + "」该时段已被占用，已自动更换为同类型教室「" + assignedNo + "」";
                        }
                    } else {
                        classroomDisplay = "AI自动推荐（类型：" + classroomType + "）→ 实际：" + assignedNo;
                    }
                    if (assignedRoomId != null) {
                        workRoom.computeIfAbsent(assignedRoomId, x -> new ArrayList<>())
                                .add(new Slot(day, start, end, courseWeeks));
                        courseRoomPrefer.put(courseRoomKey, assignedRoomId);
                    }

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("classId", classId);
                    item.put("className", className);
                    item.put("courseName", courseName);
                    item.put("classroomNo", assignedNo);
                    item.put("classroomDisplay", classroomDisplay);
                    item.put("roomMode", roomMode);
                    item.put("classroomType", classroomType);
                    item.put("teacherName", teacher);
                    item.put("weekDay", day);
                    item.put("startPeriod", start);
                    item.put("endPeriod", end);
                    item.put("weekRange", weekRange);
                    item.put("semester", semester);
                    item.put("note", note);
                    item.put("conflict", false);
                    item.put("conflictReason", "");
                    items.add(item);
                }
                // 本课程排不下：回滚该课程已排入的时段与条目，其余课程继续排
                if (courseFailure != null) {
                    scheduleFailures.add(courseFailure);
                    items.subList(courseItemStart, items.size()).clear();
                    workClass = workClassSnapshot;
                    workTeacher = workTeacherSnapshot;
                    workRoom = workRoomSnapshot;
                    classDayLoad = dayLoadSnapshot;
                    classDayPeriods = dayPeriodSnapshot;
                    courseDayKeys = dayKeysSnapshot;
                    courseRoomPrefer = roomPreferSnapshot;
                }
            }
        }
        ctx.classes = classes;
        ctx.courses = courses;
        ctx.globalWeekRange = globalWeekRange;
        ctx.days = days;
        ctx.segments = segments;
        ctx.workTeacher = workTeacher;
        ctx.workClass = workClass;
        ctx.workRoom = workRoom;
        ctx.rooms = rooms;
        ctx.roomIdByNo = roomIdByNo;
        ctx.roomById = roomById;
        ctx.classSizeById = classSizeById;
        ctx.classBuilding = classBuilding;
        ctx.weekConstraints = weekConstraints;
        ctx.slotConstraints = slotConstraints;
        ctx.courseWeekAdjust = courseWeekAdjust;
        ctx.parityBlocked = parityBlocked;
        ctx.classDayPeriodCap = classDayPeriodCap;
        ctx.semester = semester;
        ctx.items = items;
        ctx.classDayLoad = classDayLoad;
        ctx.classDayPeriods = classDayPeriods;
        ctx.courseDayKeys = courseDayKeys;
        ctx.courseRoomPrefer = courseRoomPrefer;
        ctx.scheduleFailures = scheduleFailures;
    }

    /**
     * 阶段 D：冲突校验与结果组装 —— 委托 AiScheduleFinalizer 做四维复核并输出预览结果。
     */
    Map<String, Object> finish(AiScheduleContext ctx) {
        return AiScheduleFinalizer.finish(ctx);
    }
}
