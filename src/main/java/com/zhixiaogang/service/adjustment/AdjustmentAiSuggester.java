package com.zhixiaogang.service.adjustment;

import com.zhixiaogang.entity.User;
import com.zhixiaogang.util.WeekRangeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调课 AI 智能推荐：在内存中构建全量占用索引（课表 + 已批准预约 + 已生效调课），
 * 遍历候选时段做三方占用判断，状态文字由后端确定性生成；随后把候选交给 AI 模型做排序与剔除
 * （失败/超时自动重试一次后降级为本地结果，降级原因透出给前端）。
 */
@Service
public class AdjustmentAiSuggester {

    private static final Logger log = LoggerFactory.getLogger(AdjustmentAiSuggester.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdjustmentAiRanker aiRanker;

    @Autowired
    private AdjustmentConflictChecker conflictChecker;

    /**
     * AI 智能推荐目标时段。
     * 后端先在内存中构建全量占用索引（课表 + 已批准预约 + 已生效调课），再遍历候选时段做三方占用判断，
     * 状态文字由后端确定性生成；随后把候选交给 AI 模型做排序与剔除（失败/超时自动重试一次后降级为本地结果）。
     */
    public Map<String, Object> aiSuggest(Map<String, Object> body, User user) {
        Map<String, Object> result = new HashMap<>();
        try {
            Long scheduleId = body == null ? null : AdjustmentSupport.longVal(body.get("scheduleId"));
            if (scheduleId == null) {
                result.put("success", false);
                result.put("message", "请选择要调的课程");
                return result;
            }
            Map<String, Object> orig = AdjustmentSupport.loadOrigSchedule(jdbcTemplate, scheduleId);
            if (orig == null) {
                result.put("success", false);
                result.put("message", "原课表条目不存在");
                return result;
            }
            if (!AdjustmentSupport.ownedBy(orig, user)) {
                result.put("success", false);
                result.put("message", "无权对该课程发起调课");
                return result;
            }

            boolean changeClassroom = AdjustmentSupport.flagVal(body.get("changeClassroom"));
            int oDay = ((Number) orig.get("week_day")).intValue();
            int oStart = ((Number) orig.get("start_period")).intValue();
            int oEnd = ((Number) orig.get("end_period")).intValue();
            String origWeekRange = orig.get("week_range") == null ? null : orig.get("week_range").toString();
            String reqWeekRange = body.get("weekRange") == null ? "" : String.valueOf(body.get("weekRange")).trim();
            String effWeekRange = reqWeekRange.isEmpty()
                    ? AdjustmentSupport.defaultWeekRange(origWeekRange) : reqWeekRange;
            // 调课场景以原课表生效周次为上界展开自然语言周次（如原课表 1-18 时 双周 → 2,4,...,18）
            WeekRangeParser.Result weekParsed = WeekRangeParser.parseWithin(effWeekRange, origWeekRange);
            if (!weekParsed.valid) {
                result.put("success", false);
                result.put("message", weekParsed.error);
                return result;
            }
            effWeekRange = weekParsed.normalized;
            if (!AdjustmentSupport.rangeWithin(origWeekRange, effWeekRange)) {
                result.put("success", false);
                result.put("message", "目标周次范围超出原课表周次范围（原："
                        + AdjustmentSupport.defaultWeekRange(origWeekRange) + "）");
                return result;
            }

            String semester = orig.get("semester") == null ? null : orig.get("semester").toString();
            Long origClassroomId = orig.get("classroom_id") == null ? null : ((Number) orig.get("classroom_id")).longValue();
            Long classId = orig.get("class_id") == null ? null : ((Number) orig.get("class_id")).longValue();
            String teacherId = orig.get("teacher_id") == null ? null : orig.get("teacher_id").toString();
            String teacherName = orig.get("teacher_name") == null ? null : orig.get("teacher_name").toString();
            String courseName = AdjustmentSupport.str(orig.get("course_name"));
            String className = "";
            if (classId != null) {
                List<Map<String, Object>> crows = jdbcTemplate.queryForList(
                        "SELECT class_name FROM class_info WHERE id = ?", classId);
                if (!crows.isEmpty()) {
                    className = AdjustmentSupport.str(crows.get(0).get("class_name"));
                }
            }

            List<RoomInfo> rooms = loadRooms();
            String origClassroomNo = "";
            String origRoomType = "";
            for (RoomInfo r : rooms) {
                if (r.id.equals(origClassroomId)) {
                    origClassroomNo = r.classroomNo;
                    origRoomType = r.roomType;
                    break;
                }
            }
            // 换教室时必须与原教室同类型；原教室未安排或类型未知（室外课、旧数据）时不做类型限制
            boolean roomTypeBound = changeClassroom && origClassroomId != null && !origRoomType.isEmpty();

            Occupancy occ = buildOccupancy(effWeekRange);

            int len = oEnd - oStart + 1;
            List<Map<String, Object>> raw = new ArrayList<>();
            for (int day = 1; day <= 5; day++) {
                for (int st = 1; st + len - 1 <= 8; st += len) {
                    int en = st + len - 1;
                    if (day == oDay && st == oStart) {
                        continue;
                    }
                    // 教师 / 班级任一占用 → 不进入推荐列表
                    if (teacherBusy(occ, teacherId, teacherName, day, st, en)) {
                        continue;
                    }
                    if (classId != null && busy(occ.clazz, String.valueOf(classId), day, st, en)) {
                        continue;
                    }

                    int freeRoomCount = 0;
                    RoomInfo firstFree = null;
                    for (RoomInfo r : rooms) {
                        if (busy(occ.room, String.valueOf(r.id), day, st, en)) {
                            continue;
                        }
                        // 换教室候选只统计与原教室同类型的空闲教室
                        if (roomTypeBound && !r.roomType.equals(origRoomType)) {
                            continue;
                        }
                        freeRoomCount++;
                        if (firstFree == null) firstFree = r;
                    }
                    boolean origRoomFree = origClassroomId == null
                            || !busy(occ.room, String.valueOf(origClassroomId), day, st, en);

                    Long roomId;
                    String roomNo;
                    String roomType;
                    String statusText = "全部空闲，可放心调课";
                    if (!changeClassroom) {
                        // 关闭换教室时教室固定为原教室；原教室被占用即构成教室冲突，该时段不进入推荐列表
                        if (!origRoomFree) {
                            continue;
                        }
                        roomId = origClassroomId;
                        roomNo = origClassroomNo;
                        roomType = origRoomType;
                    } else if (origClassroomId != null && origRoomFree) {
                        // 换教室模式下原教室仍空闲时优先沿用原教室
                        roomId = origClassroomId;
                        roomNo = origClassroomNo;
                        roomType = origRoomType;
                    } else if (firstFree != null) {
                        // 原教室未安排或被占用：取首个同类型空闲教室作为候选教室
                        roomId = firstFree.id;
                        roomNo = firstFree.classroomNo;
                        roomType = firstFree.roomType;
                    } else {
                        // 换教室模式下同类型教室均被占用 → 该时段不进入推荐列表
                        continue;
                    }

                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("weekDay", day);
                    c.put("startPeriod", st);
                    c.put("endPeriod", en);
                    c.put("classroomId", roomId);
                    c.put("classroomName", roomNo.isEmpty() ? "无教室" : roomNo);
                    c.put("roomType", roomType);
                    c.put("statusText", statusText);
                    c.put("source", "local");
                    c.put("sort", raw.size() + 1);
                    c.put("_origRoom", roomId != null && roomId.equals(origClassroomId));
                    c.put("_sameDay", day == oDay);
                    c.put("_freeRooms", freeRoomCount);
                    raw.add(c);
                }
            }

            // 本地排序：原教室优先 → 与原时段同一天优先 → 节次靠前优先
            raw.sort(Comparator
                    .comparing((Map<String, Object> c) -> Boolean.TRUE.equals(c.get("_origRoom")) ? 0 : 1)
                    .thenComparing(c -> Boolean.TRUE.equals(c.get("_sameDay")) ? 0 : 1)
                    .thenComparing(c -> (Integer) c.get("startPeriod")));
            if (raw.size() > 24) {
                raw = new ArrayList<>(raw.subList(0, 24));
            }

            boolean aiAvailable = aiRanker.isAvailable();
            boolean aiApplied = false;
            String aiReason = null;
            String aiText = null;
            boolean aiRepeat = false;
            long aiLatencyMs = -1L;
            long aiTimeoutSeconds = -1L;
            boolean aiRetried = false;
            if (!aiAvailable) {
                aiReason = "disabled";
                aiText = unavailableText(aiReason, false, -1L, false);
            } else if (!raw.isEmpty()) {
                AdjustmentAiRanker.RankResult rank = aiRanker.rank(raw, teacherName, className, courseName,
                        oDay, oStart, oEnd, changeClassroom, effWeekRange, origClassroomNo, origRoomType, user);
                aiApplied = rank.applied;
                aiReason = rank.reason;
                aiRepeat = rank.repeat;
                aiLatencyMs = rank.costMs;
                aiTimeoutSeconds = rank.timeoutSeconds;
                aiRetried = rank.retried;
                if (!aiApplied) {
                    aiText = unavailableText(aiReason, aiRepeat, aiTimeoutSeconds, aiRetried);
                }
            }

            List<Map<String, Object>> candidates = new ArrayList<>();
            int sort = 0;
            for (Map<String, Object> c : raw) {
                sort++;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("weekDay", c.get("weekDay"));
                m.put("startPeriod", c.get("startPeriod"));
                m.put("endPeriod", c.get("endPeriod"));
                m.put("classroomId", c.get("classroomId"));
                m.put("classroomName", c.get("classroomName"));
                m.put("roomType", c.get("roomType"));
                m.put("statusText", c.get("statusText"));
                m.put("source", aiApplied ? "ai" : "local");
                m.put("sort", sort);
                candidates.add(m);
            }

            result.put("success", true);
            result.put("candidates", candidates);
            result.put("origRoomType", origRoomType);
            result.put("aiAvailable", aiAvailable);
            result.put("aiUnavailableReason", aiReason);
            result.put("aiUnavailableText", aiText);
            result.put("aiRepeatFail", aiRepeat);
            result.put("aiLatencyMs", aiLatencyMs);
            result.put("aiTimeoutSeconds", aiTimeoutSeconds);
            result.put("aiRetried", aiRetried);
            result.put("message", aiApplied ? "已按 AI 推荐排序"
                    : (raw.isEmpty() ? "暂无可推荐的空闲时段" : aiText));
        } catch (Exception e) {
            log.warn("调课时段智能推荐失败", e);
            result.put("success", false);
            result.put("message", "推荐失败，请稍后重试");
            result.put("candidates", List.of());
        }
        return result;
    }

    /** 降级原因的中文说明 */
    private String unavailableText(String reason, boolean repeat, long timeoutSeconds, boolean retried) {
        if ("timeout".equals(reason)) {
            if (repeat) {
                return "模型刚响应超时，60 秒内暂不再等待模型，本次已用本地规则推荐";
            }
            String wait = timeoutSeconds > 0 ? "单次等待 " + timeoutSeconds + " 秒" : "等待超时";
            return "模型响应超时（" + wait + (retried ? "，已自动重试 1 次" : "") + "），本次已用本地规则推荐";
        }
        if ("disabled".equals(reason)) {
            return "当前未启用 AI 模型，已用本地规则推荐；如需 AI 智能推荐请在管理端配置模型";
        }
        return repeat ? "模型连续返回异常，已自动使用本地规则推荐" : "模型返回异常，已用本地规则推荐";
    }

    /** 教室下拉选项（换教室时使用） */
    public List<Map<String, Object>> classroomOptions() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (RoomInfo r : loadRooms()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id);
            m.put("classroomNo", r.classroomNo);
            m.put("building", r.building);
            m.put("capacity", r.capacity);
            m.put("roomType", r.roomType);
            list.add(m);
        }
        return list;
    }

    // ---------- AI 推荐占用索引 ----------

    private static class RoomInfo {
        Long id;
        String classroomNo = "";
        String building = "";
        Integer capacity;
        String roomType = "普通教室";
    }

    /** 内存占用索引：key = 资源ID|星期|节次 → 占用课程名 */
    private static class Occupancy {
        final Map<String, String> room = new HashMap<>();
        final Map<String, String> clazz = new HashMap<>();
        final Map<String, String> teacher = new HashMap<>();
    }

    private List<RoomInfo> loadRooms() {
        List<RoomInfo> rooms = new ArrayList<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, classroom_no, building, capacity, room_type FROM classroom WHERE status = 'available' " +
                "ORDER BY building, floor, classroom_no");
        for (Map<String, Object> r : rows) {
            RoomInfo info = new RoomInfo();
            info.id = ((Number) r.get("id")).longValue();
            info.classroomNo = AdjustmentSupport.str(r.get("classroom_no"));
            info.building = AdjustmentSupport.str(r.get("building"));
            info.capacity = AdjustmentSupport.intVal(r.get("capacity"));
            // 历史数据未填类型时按普通教室兜底
            String type = AdjustmentSupport.str(r.get("room_type"));
            info.roomType = type.isEmpty() ? "普通教室" : type;
            rooms.add(info);
        }
        return rooms;
    }

    private Occupancy buildOccupancy(String weekRange) {
        Occupancy occ = new Occupancy();
        LocalDate termMonday = termMonday();

        // 1) 全量课表占用
        List<Map<String, Object>> schedules = jdbcTemplate.queryForList(
                "SELECT s.week_day, s.start_period, s.end_period, s.week_range, s.classroom_id, s.class_id, " +
                "s.teacher_id, s.teacher_name, c.course_name " +
                "FROM schedule s LEFT JOIN course c ON s.course_id = c.id");
        for (Map<String, Object> r : schedules) {
            String rowRange = r.get("week_range") == null ? null : r.get("week_range").toString();
            if (!conflictChecker.weeksOverlap(weekRange, rowRange)) {
                continue;
            }
            fillOccupancy(occ, r.get("week_day"), r.get("start_period"), r.get("end_period"), rowRange,
                    r.get("classroom_id"), r.get("class_id"), r.get("teacher_id"), r.get("teacher_name"),
                    AdjustmentSupport.str(r.get("course_name")));
        }

        // 2) 已生效调课占用（教室取换教室后的生效教室；已同步到 schedule 的行由第 1 步按目标时段计入，跳过避免重复）
        List<Map<String, Object>> adjustments = jdbcTemplate.queryForList(
                "SELECT a.target_week_day, a.target_start_period, a.target_end_period, a.week_range, a.course_name, " +
                "a.class_id, a.teacher_id, a.teacher_name, s.week_day, s.start_period, s.end_period, " +
                conflictChecker.effectiveClassroomExpr("a", "s") + " AS classroom_id " +
                "FROM schedule_adjustment a LEFT JOIN schedule s ON a.schedule_id = s.id " +
                "WHERE a.status = 'approved'");
        for (Map<String, Object> r : adjustments) {
            // 已同步（schedule 行时段 == 目标时段）：第 1 步全量课表占用已按目标时段计入该课程，跳过
            if (AdjustmentSupport.intVal(r.get("week_day")) != null
                    && AdjustmentSupport.intVal(r.get("week_day")).equals(AdjustmentSupport.intVal(r.get("target_week_day")))
                    && AdjustmentSupport.intVal(r.get("start_period")).equals(AdjustmentSupport.intVal(r.get("target_start_period")))
                    && AdjustmentSupport.intVal(r.get("end_period")).equals(AdjustmentSupport.intVal(r.get("target_end_period")))) {
                continue;
            }
            String rowRange = r.get("week_range") == null ? null : r.get("week_range").toString();
            if (!conflictChecker.weeksOverlap(weekRange, rowRange)) {
                continue;
            }
            fillOccupancy(occ, r.get("target_week_day"), r.get("target_start_period"), r.get("target_end_period"),
                    rowRange, r.get("classroom_id"), r.get("class_id"), r.get("teacher_id"), r.get("teacher_name"),
                    AdjustmentSupport.str(r.get("course_name")));
        }

        // 3) 已批准教室预约占用（按预约日期换算教学周后判断是否与生效周次有交集）
        List<Map<String, Object>> reservations = jdbcTemplate.queryForList(
                "SELECT classroom_id, start_period, end_period, reservation_date, student_name, reason " +
                "FROM classroom_reservation WHERE status = 'approved'");
        for (Map<String, Object> r : reservations) {
            Object rd = r.get("reservation_date");
            LocalDate date = null;
            if (rd instanceof java.sql.Date) {
                date = ((java.sql.Date) rd).toLocalDate();
            } else if (rd instanceof LocalDate) {
                date = (LocalDate) rd;
            }
            Integer week = weekOf(date, termMonday);
            if (week != null && !AdjustmentSupport.weekInRange(weekRange, week)) {
                continue;
            }
            int day = date == null ? 0 : date.getDayOfWeek().getValue();
            Integer st = AdjustmentSupport.intVal(r.get("start_period"));
            Integer en = AdjustmentSupport.intVal(r.get("end_period"));
            Object roomId = r.get("classroom_id");
            if (day < 1 || roomId == null || st == null || en == null) {
                continue;
            }
            String reason = AdjustmentSupport.str(r.get("reason"));
            String label = reason.isEmpty() ? AdjustmentSupport.str(r.get("student_name")) : reason;
            for (int p = st; p <= en; p++) {
                occ.room.putIfAbsent(slotKey(roomId.toString(), day, p), label);
            }
        }
        return occ;
    }

    private void fillOccupancy(Occupancy occ, Object dayObj, Object startObj, Object endObj, String rowRange,
                               Object classroomId, Object classId, Object teacherId, Object teacherName,
                               String courseName) {
        Integer day = AdjustmentSupport.intVal(dayObj);
        Integer st = AdjustmentSupport.intVal(startObj);
        Integer en = AdjustmentSupport.intVal(endObj);
        if (day == null || st == null || en == null) {
            return;
        }
        for (int p = st; p <= en; p++) {
            if (classroomId != null) {
                occ.room.putIfAbsent(slotKey(classroomId.toString(), day, p), courseName);
            }
            if (classId != null) {
                occ.clazz.putIfAbsent(slotKey(classId.toString(), day, p), courseName);
            }
            String tk = teacherKey(teacherId, teacherName);
            if (tk != null) {
                occ.teacher.putIfAbsent(slotKey(tk, day, p), courseName);
            }
        }
    }

    private String teacherKey(Object teacherId, Object teacherName) {
        String tid = teacherId == null ? "" : teacherId.toString().trim();
        if (!tid.isEmpty()) {
            return "id:" + tid;
        }
        String tname = teacherName == null ? "" : teacherName.toString().trim();
        return tname.isEmpty() ? null : "name:" + tname;
    }

    private boolean teacherBusy(Occupancy occ, String teacherId, String teacherName, int day, int st, int en) {
        List<String> keys = new ArrayList<>();
        String tid = teacherId == null ? "" : teacherId.trim();
        String tname = teacherName == null ? "" : teacherName.trim();
        if (!tid.isEmpty()) keys.add("id:" + tid);
        if (!tname.isEmpty()) keys.add("name:" + tname);
        for (String k : keys) {
            if (busy(occ.teacher, k, day, st, en)) {
                return true;
            }
        }
        return false;
    }

    private boolean busy(Map<String, String> index, String resourceId, int day, int st, int en) {
        if (resourceId == null || resourceId.isEmpty()) {
            return false;
        }
        for (int p = st; p <= en; p++) {
            if (index.containsKey(slotKey(resourceId, day, p))) {
                return true;
            }
        }
        return false;
    }

    private String slotKey(String resourceId, int day, int period) {
        return resourceId + "|" + day + "|" + period;
    }

    /** 本学期第 1 周周一（semester_config.start_date 对齐到周一） */
    private LocalDate termMonday() {
        try {
            LocalDate startDate = jdbcTemplate.queryForObject(
                    "SELECT start_date FROM semester_config ORDER BY id DESC LIMIT 1", LocalDate.class);
            return startDate == null ? null : startDate.with(DayOfWeek.MONDAY);
        } catch (Exception e) {
            return null;
        }
    }

    /** 具体日期 → 第几教学周（termMonday 为第 1 周周一） */
    private Integer weekOf(LocalDate date, LocalDate termMonday) {
        if (date == null || termMonday == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(termMonday, date.with(DayOfWeek.MONDAY));
        return days < 0 ? null : (int) (days / 7) + 1;
    }
}
