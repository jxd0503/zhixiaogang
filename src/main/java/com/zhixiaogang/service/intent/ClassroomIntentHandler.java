package com.zhixiaogang.service.intent;

import com.zhixiaogang.entity.Classroom;
import com.zhixiaogang.mapper.ClassroomMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.zhixiaogang.service.classroom.ClassroomService;

import static com.zhixiaogang.service.intent.IntentSupport.*;

/********** 教室查询 / 教室状态 / 预约 / 我的预约意图 **********/
@Service
public class ClassroomIntentHandler {

    @Autowired
    private ClassroomService classroomService;

    @Autowired
    private ClassroomMapper classroomMapper;

    /** P2-9 AI模式空教室上下文复用离线查询的完整日期/时段解析 */
    String retrieveClassroomData(String message) {
        StringBuilder data = new StringBuilder();
        data.append("【空教室信息】\n");
        data.append(handleClassroomQuery(message)).append("\n");
        return data.toString();
    }

    private String handleClassroomQuery(String message) {
        // P1-3 具体教室号 + 有空/有课/能用 等问法 → 单教室当天状态
        if (extractClassroomNo(message) != null
                && (message.contains("有空") || message.contains("空闲") || message.contains("空不空")
                    || message.contains("有课") || message.contains("能用")
                    || message.contains("占用") || message.contains("有没有"))) {
            return handleClassroomSpecificStatus(message);
        }

        String building = extractBuilding(message);
        Integer weekDay = extractWeekDay(message);
        Integer period = extractPeriod(message);
        LocalDate date = extractDate(message);
        LocalDate today = LocalDate.now();

        // P4 相对日期：明天/后天/今天
        if (date == null) {
            if (message.contains("明天")) date = today.plusDays(1);
            else if (message.contains("后天")) date = today.plusDays(2);
            else if (message.contains("今天")) date = today;
        }
        // 星期几 → 换算成最近一个该星期的日期
        if (date == null && weekDay != null) {
            int delta = weekDay - today.getDayOfWeek().getValue();
            if (delta < 0) delta += 7;
            date = today.plusDays(delta);
        }
        if (date != null) {
            weekDay = date.getDayOfWeek().getValue();
        }

        // P4 上午/下午/晚上 → 节次范围（上午1-2段=1-4节，下午3-4段=5-8节，晚上5段=9-10节）
        Integer rangeStart = null, rangeEnd = null;
        String rangeLabel = null;
        if (period == null) {
            int[] segRange = extractPeriodRange(message);
            if (segRange != null) {
                rangeStart = segRange[0];
                rangeEnd = segRange[1];
                rangeLabel = rangeStart == rangeEnd ? getPeriodName(rangeStart)
                        : getPeriodName(rangeStart) + " ~ " + getPeriodName(rangeEnd);
            } else if (message.contains("上午") || message.contains("早上") || message.contains("早晨")) {
                rangeStart = 1; rangeEnd = 2; rangeLabel = "上午（1-4节）";
            } else if (message.contains("下午") || message.contains("中午")) {
                rangeStart = 3; rangeEnd = 4; rangeLabel = "下午（5-8节）";
            } else if (message.contains("晚上")) {
                rangeStart = 5; rangeEnd = 5; rangeLabel = "晚上（9-10节）";
            }
        }

        // P1-6 无日期但有 上午/下午/晚上 时段词 → 默认按今天查询该时段
        boolean defaultTodayForRange = false;
        if (date == null && rangeStart != null) {
            date = today;
            weekDay = today.getDayOfWeek().getValue();
            defaultTodayForRange = true;
        }

        // 明确时段（日期+节次）→ 按日查询
        if (period != null) {
            if (date == null) {
                date = today;
                weekDay = date.getDayOfWeek().getValue();
            }
            Map<String, Object> result = classroomService.getAvailableClassroomsByDatePeriod(date, period, building);
            return formatDatePeriodClassroomResponse(result, date, period, building);
        }

        // P2-10 日期 + 节次段/节次范围 → 按节次段分别列出，口径明确（避免并集误导）
        if (date != null && rangeStart != null) {
            StringBuilder sb = new StringBuilder();
            if (defaultTodayForRange) {
                sb.append("未指定日期，默认按今天查询；带上日期（如：明天晚上）结果更准确。\n");
            }
            sb.append("查询: ").append(date).append(" ").append(getDayName(weekDay)).append(" ").append(rangeLabel).append("\n");
            boolean anyAvailable = false;
            for (int p = rangeStart; p <= rangeEnd; p++) {
                Map<String, Object> r = classroomService.getAvailableClassroomsByDatePeriod(date, p, building);
                sb.append(getPeriodName(p)).append(" 可用空教室：");
                if ((Boolean) r.get("success")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> data = (List<Map<String, Object>>) r.get("data");
                    if (data.isEmpty()) {
                        sb.append("无\n");
                    } else {
                        anyAvailable = true;
                        String nos = data.stream().map(c -> String.valueOf(c.get("classroom_no")))
                                .collect(java.util.stream.Collectors.joining("、"));
                        sb.append(nos).append("（共").append(data.size()).append("间）\n");
                    }
                } else {
                    sb.append("查询失败\n");
                }
            }
            if (!anyAvailable) {
                sb.append("该时段各节次均无空教室，可换一个时段或楼栋试试。");
            } else {
                sb.append("说明：以上按节次段分别列出，同一教室仅在所列节次段空闲；需要整时段都空的教室请另行确认。");
            }
            return sb.toString();
        }

        // 仅有日期/星期，无节次
        if (date != null) {
            // P2-2 今天无节次 → 默认查询下一节次并提示
            if (date.equals(today)) {
                int np = classroomService.getNextPeriod();
                if (np == 0) {
                    return "今天已过所有上课时间段，明天再来查询吧！";
                }
                Map<String, Object> npResult = classroomService.getAvailableClassroomsForPeriod(np, building);
                if ((Boolean) npResult.get("success")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> npClassrooms = (List<Map<String, Object>>) npResult.get("data");
                    return "未指定节次，默认查询今天下一节次：\n"
                            + classroomService.formatClassroomResponseForNextPeriod(npClassrooms, np)
                            + "\n如需其他节次，请补充时间点（如：下午2点、第3节）。";
                }
                return (String) npResult.get("message");
            }
            return "请告诉我具体节次或时间点（如：上午、下午2点、第3节），我好帮您查询该时段的空教室。";
        }

        // 无明确时段 → 查询下一节次（现状兜底）
        int nextPeriod = classroomService.getNextPeriod();
        if (nextPeriod == 0) {
            return "当前已过所有上课时间段，明天再来查询吧！";
        }
        Map<String, Object> result = classroomService.getAvailableClassroomsForPeriod(nextPeriod, building);
        if ((Boolean) result.get("success")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> classrooms = (List<Map<String, Object>>) result.get("data");
            return classroomService.formatClassroomResponseForNextPeriod(classrooms, nextPeriod);
        } else {
            return (String) result.get("message");
        }
    }

    /**
     * P1-3 具体教室状态查询："B5-302现在有空吗/有课吗/明天能用吗"
     * 综合当天排课（schedule）与已批准预约（reservation）给出分节次结论。
     */
    @SuppressWarnings("unchecked")
    private String handleClassroomSpecificStatus(String message) {
        String classroomNo = extractClassroomNo(message);
        LocalDate today = LocalDate.now();
        LocalDate date = extractDate(message);
        if (date == null) {
            if (message.contains("明天")) date = today.plusDays(1);
            else if (message.contains("后天")) date = today.plusDays(2);
            else date = today;
        }

        Map<String, Object> status = classroomService.getClassroomDayStatus(classroomNo, date, null);
        if (!Boolean.TRUE.equals(status.get("success"))) {
            return (String) status.get("message");
        }
        Classroom room = (Classroom) status.get("classroom");
        List<Map<String, Object>> schedules = (List<Map<String, Object>>) status.get("schedules");
        List<com.zhixiaogang.entity.ClassroomReservation> reservations =
                (List<com.zhixiaogang.entity.ClassroomReservation>) status.get("reservations");

        // 按节次段（1-5）汇总占用原因
        Map<Integer, List<String>> occupied = new java.util.TreeMap<>();
        for (int p = 1; p <= 5; p++) {
            int actualStart = (p - 1) * 2 + 1;
            int actualEnd = p * 2;
            List<String> reasons = new ArrayList<>();
            for (Map<String, Object> s : schedules) {
                int sp = ((Number) s.get("start_period")).intValue();
                int ep = ((Number) s.get("end_period")).intValue();
                if (sp < actualEnd && ep > actualStart) {
                    String courseName = s.get("course_name") != null ? String.valueOf(s.get("course_name")) : "课程";
                    reasons.add("有课《" + courseName + "》");
                }
            }
            for (com.zhixiaogang.entity.ClassroomReservation r : reservations) {
                if (r.getStartPeriod() <= p && p <= r.getEndPeriod()) {
                    reasons.add("已被预约（" + (r.getReason() != null ? r.getReason() : "用途未填") + "）");
                }
            }
            if (!reasons.isEmpty()) occupied.put(p, reasons);
        }

        List<Integer> freeSegs = new ArrayList<>();
        for (int p = 1; p <= 5; p++) if (!occupied.containsKey(p)) freeSegs.add(p);

        boolean maintenance = !"available".equals(room.getStatus());
        StringBuilder sb = new StringBuilder();
        sb.append(classroomNo).append("（").append(date).append(" ").append(getDayName(date.getDayOfWeek().getValue())).append("）");
        if (maintenance) sb.append("当前处于维修状态，暂不可预约。");

        boolean askNow = date.equals(today)
                && (message.contains("现在") || message.contains("目前") || message.contains("这会儿") || message.contains("当前"));
        if (askNow) {
            int cur = classroomService.getCurrentPeriod();
            if (cur == 0) {
                sb.append("当前为非上课时段，");
                if (maintenance) {
                    sb.append("且教室维修中，暂不可用。");
                } else if (occupied.isEmpty()) {
                    sb.append("该教室今天没有排课也没有已批准的预约，目前空闲可使用。");
                } else {
                    Integer first = occupied.keySet().iterator().next();
                    sb.append("目前空闲；今天后续 ").append(getPeriodName(first))
                      .append(" ").append(String.join("、", occupied.get(first))).append("。");
                }
                return sb.toString();
            }
            if (occupied.containsKey(cur)) {
                sb.append("现在是").append(getPeriodName(cur)).append("，该教室")
                  .append(String.join("，", occupied.get(cur))).append("，当前不能使用。");
            } else if (maintenance) {
                sb.append("现在虽无排课，但教室维修中，暂不可用。");
            } else {
                sb.append("现在是").append(getPeriodName(cur)).append("，该教室当前空闲，可以使用。");
            }
            return sb.toString();
        }

        // 非"现在"问法：给出全天分节次情况
        if (occupied.isEmpty()) {
            sb.append(maintenance ? "" : "当天没有排课，也没有已批准的预约，全天空闲，可以预约。");
            if (maintenance) sb.append("维修结束后可预约。");
            return sb.toString();
        }
        sb.append("当天使用情况：\n");
        for (Map.Entry<Integer, List<String>> e : occupied.entrySet()) {
            sb.append("• ").append(getPeriodName(e.getKey())).append("：")
              .append(String.join("；", e.getValue())).append("\n");
        }
        if (!freeSegs.isEmpty() && !maintenance) {
            StringBuilder free = new StringBuilder();
            for (Integer p : freeSegs) free.append(getPeriodName(p)).append("、");
            free.setLength(free.length() - 1);
            sb.append("空闲可预约时段：").append(free).append("。");
        }
        return sb.toString();
    }

    /** P1-4 我的预约列表 */
    private String handleMyReservations(String studentId) {
        List<com.zhixiaogang.entity.ClassroomReservation> list =
                classroomService.getReservationsByStudent(studentId);
        if (list.isEmpty()) {
            return "您目前还没有预约记录。可以在「教室查询」面板选好教室后预约，或直接告诉我「想预约哪栋楼、哪天、哪个时段」。";
        }
        java.util.Set<Long> roomIds = new java.util.HashSet<>();
        for (com.zhixiaogang.entity.ClassroomReservation r : list) roomIds.add(r.getClassroomId());
        Map<Long, String> roomNoMap = new HashMap<>();
        if (!roomIds.isEmpty()) {
            for (Classroom c : classroomMapper.selectBatchIds(roomIds)) {
                roomNoMap.put(c.getId(), c.getClassroomNo());
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("您共有 ").append(list.size()).append(" 条预约记录：\n");
        int shown = 0;
        for (com.zhixiaogang.entity.ClassroomReservation r : list) {
            if (shown++ >= 10) { sb.append("…更多记录请在「教室查询」面板查看\n"); break; }
            sb.append("• ").append(r.getReservationDate())
              .append(" ").append(roomNoMap.getOrDefault(r.getClassroomId(), "教室#" + r.getClassroomId()))
              .append(" ").append(getPeriodName(r.getStartPeriod()));
            sb.append("，").append(reservationStatusText(r.getStatus()));
            if (r.getReason() != null && !r.getReason().isEmpty()) sb.append("（").append(r.getReason()).append("）");
            sb.append("\n");
        }
        return sb.toString();
    }

    private String reservationStatusText(String status) {
        if (status == null) return "未知状态";
        switch (status) {
            case "pending": return "待审核";
            case "approved": return "已通过";
            case "rejected": return "已拒绝";
            default: return status;
        }
    }

    @SuppressWarnings("unchecked")
    private String formatDatePeriodClassroomResponse(Map<String, Object> result, LocalDate date, int period, String building) {
        if (!(Boolean) result.get("success")) {
            return (String) result.get("message");
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("data");
        StringBuilder sb = new StringBuilder();
        String periodName = getPeriodName(period);
        sb.append("查询: ").append(date).append(" ").append(periodName).append("\n");
        if (rows.isEmpty()) {
            sb.append("该时段暂无可用的空教室。");
        } else {
            sb.append("空教室：\n");
            for (Map<String, Object> r : rows) {
                sb.append("• ").append(r.get("classroom_no"));
                sb.append(" (").append(r.get("building")).append("楼，").append(r.get("capacity")).append("人)");
                Object roomType = r.get("room_type");
                if (roomType != null && !roomType.toString().isEmpty()) sb.append(" ").append(roomType);
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private String handleClassroomReserve(String userName, String studentId, String message) {
        String building = extractBuilding(message);
        Integer startPeriod = extractPeriod(message);
        // P1-6 裸时段词（无"X点"/"第X节"）：晚上→9-10节(5段)，下午→3段，上午→1段
        if (startPeriod == null) {
            if (message.contains("晚上") || message.contains("晚自习") || message.contains("9-10")) startPeriod = 5;
            else if (message.contains("下午") || message.contains("中午")) startPeriod = 3;
            else if (message.contains("上午") || message.contains("早上")) startPeriod = 1;
        }
        // 节次段为闭区间 1-5；聊天预约为单个节次段，起止相同
        Integer endPeriod = startPeriod;
        LocalDate reservationDate = extractDate(message);

        // 处理相对日期
        if (reservationDate == null) {
            if (message.contains("明天")) reservationDate = LocalDate.now().plusDays(1);
            else if (message.contains("后天")) reservationDate = LocalDate.now().plusDays(2);
            else if (message.contains("今天")) reservationDate = LocalDate.now();
        }

        // P6 要素追问：楼号/教室 + 日期 + 节次三要素齐全才提交
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (building == null) missing.add("楼栋/教室号");
        if (reservationDate == null) missing.add("日期");
        if (startPeriod == null) missing.add("节次（如上午、下午2点、第3节）");
        if (!missing.isEmpty()) {
            return "预约教室需要以下信息，请您补充：" + String.join("、", missing)
                    + "。例如：帮我预约B5楼明天下午2点的教室。";
        }

        // 三要素齐全 → 查询该楼教室，取第一个可用教室提交
        Map<String, Object> classroomResult = classroomService.getClassroomsByBuilding(building);
        if ((Boolean) classroomResult.get("success")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> classrooms = (List<Map<String, Object>>) classroomResult.get("data");
            if (!classrooms.isEmpty()) {
                Long classroomId = ((Number) classrooms.get(0).get("id")).longValue();
                String classroomNo = (String) classrooms.get(0).get("classroom_no");
                Map<String, Object> result = classroomService.reserveClassroom(
                        classroomId, studentId, userName, "自主预约", reservationDate,
                        startPeriod, endPeriod);
                boolean success = (Boolean) result.get("success");
                if (success) {
                    return "已提交预约：" + classroomNo + " " + reservationDate + " " + getPeriodName(startPeriod)
                            + "，等待管理员审核。";
                }
                return (String) result.get("message");
            }
        }
        return building + "楼暂无可用教室";
    }
}
