package com.zhixiaogang.service.schedule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.zhixiaogang.service.schedule.AiScheduleModels.*;
import static com.zhixiaogang.service.schedule.AiScheduleConstants.*;
import static com.zhixiaogang.service.schedule.AiScheduleUtils.*;
import static com.zhixiaogang.service.schedule.AiScheduleTexts.*;

/**
 * AI 排课结果收尾：按时间区间（含周次）/ 授课对象 / 教学场地 / 授课人员四维与现有课表全量复核，
 * 剔除冲突课次并组装预览结果。
 */
final class AiScheduleFinalizer {

    private AiScheduleFinalizer() {
    }

    static Map<String, Object> finish(AiScheduleContext ctx) {
        List<Map<String, Object>> classes = ctx.classes;
        Map<String, List<Slot>> existTeacher = ctx.existTeacher;
        Map<Long, List<Slot>> existClass = ctx.existClass;
        Map<Long, List<Slot>> existRoom = ctx.existRoom;
        List<String> constraintUnrecognized = ctx.constraintUnrecognized;
        List<Map<String, Object>> constraintList = ctx.constraintList;
        List<String> unmatchedNotes = ctx.unmatchedNotes;
        List<String> constraintSummary = ctx.constraintSummary;
        List<Map<String, Object>> items = ctx.items;
        List<String> scheduleFailures = ctx.scheduleFailures;
        // 四维全量复核（授课对象 / 教学场地 / 授课人员 / 时间区间含周次）：与现有课表逐条交叉校验，
        // 命中冲突的课次从结果中剔除并计入失败说明，确保返回的课表全部零冲突
        int conflictCount = 0;
        List<Map<String, Object>> validItems = new ArrayList<>();
        for (Map<String, Object> item : items) {
            int day = ((Number) item.get("weekDay")).intValue();
            int sp = ((Number) item.get("startPeriod")).intValue();
            int ep = ((Number) item.get("endPeriod")).intValue();
            java.util.BitSet itemWeeks = weeksOf(str(item.get("weekRange")));
            String reason = null;
            Long classId = toLong(item.get("classId"));
            String teacher = str(item.get("teacherName"));
            if (teacher != null && !teacher.isEmpty() && isBusy(existTeacher.get(teacher), day, sp, ep, itemWeeks)) {
                reason = "教师「" + teacher + "」该时段已有排课";
            } else if (isBusy(existClass.get(classId), day, sp, ep, itemWeeks)) {
                reason = "班级该时段已有排课";
            } else if (!str(item.get("classroomNo")).isEmpty()) {
                Long rid = ctx.roomIdByNo == null ? null : ctx.roomIdByNo.get(str(item.get("classroomNo")));
                if (rid != null && isBusy(existRoom.get(rid), day, sp, ep, itemWeeks)) {
                    reason = "教室「" + item.get("classroomNo") + "」该时段已有排课";
                }
            }
            if (reason != null) {
                item.put("conflict", true);
                item.put("conflictReason", reason);
                conflictCount++;
                String slotText = str(item.get("className")) + "《" + str(item.get("courseName")) + "》"
                        + (day >= 1 && day <= 7 ? WEEKDAY_CN[day] : "") + " 第" + sp + "-" + ep
                        + "节（周次 " + str(item.get("weekRange")) + "）";
                scheduleFailures.add(failureMessage(str(item.get("className")), str(item.get("courseName")), teacher,
                        str(item.get("weekRange")), ctx.days, ctx.segments, "",
                        "生成结果与现有课表冲突，已自动剔除：" + reason,
                        "请清理或调整该班级 / 教师 / 教室的已有课程后重新生成（冲突课次：" + slotText + "）"));
                continue;
            }
            validItems.add(item);
        }
        items = validItems;
        ctx.items = items;

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("items", items);
        result.put("totalCount", items.size());
        result.put("conflictCount", conflictCount);
        result.put("classCount", classes.size());
        result.put("failures", scheduleFailures);
        result.put("failureCount", scheduleFailures.size());
        result.put("constraintCount", constraintList.size());
        result.put("constraintSummary", constraintSummary);
        result.put("constraintList", constraintList);
        result.put("constraintUnmatched", unmatchedNotes);
        result.put("constraintUnrecognized", constraintUnrecognized);
        StringBuilder cnote = new StringBuilder();
        if (!constraintList.isEmpty()) {
            cnote.append("已识别排课约束：").append(constraintList.size()).append(" 条");
        }
        if (!unmatchedNotes.isEmpty()) {
            if (cnote.length() > 0) {
                cnote.append("；");
            }
            cnote.append(String.join("；", unmatchedNotes));
        }
        for (String raw : constraintUnrecognized) {
            if (cnote.length() > 0) {
                cnote.append("；");
            }
            cnote.append("未能识别的约束：").append(raw);
        }
        result.put("constraintNote", cnote.toString());
        StringBuilder msg = new StringBuilder("排课生成完成：成功排入 " + items.size() + " 条");
        if (!scheduleFailures.isEmpty()) {
            msg.append("，").append(scheduleFailures.size()).append(" 门课程未能排入（下方已列出原因，其余课程不受影响）");
        }
        if (conflictCount > 0) {
            msg.append("，已自动剔除 ").append(conflictCount).append(" 条与现有课表冲突的课次");
        } else {
            msg.append("，无冲突");
        }
        result.put("message", msg.toString());
        return result;
    }
}
