package com.zhixiaogang.service.adjustment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixiaogang.entity.User;
import com.zhixiaogang.service.ai.AiCallExecutor;
import com.zhixiaogang.service.impl.QwenAIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 调课 AI 推荐排序：构建提示词、调用模型、解析排序结果。
 * 候选先由本地规则排序，只把前若干条交给模型做剔除与微调，压低模型生成时间；
 * 超时后自动重试一次，仍失败才降级本地规则，并记录最近一次失败状态供 60 秒窗口直接降级。
 */
@Service
public class AdjustmentAiRanker {

    /** 交给模型的候选条数上限：本地规则已排序，模型只需处理靠前的少量候选 */
    private static final int AI_INPUT_LIMIT = 12;

    /** 候选较少时的等待上限 */
    public static final long TIMEOUT_MIN_SECONDS = 70L;

    /** 候选较多时的等待上限（覆盖实测 67 秒波峰） */
    public static final long TIMEOUT_MAX_SECONDS = 90L;

    /** 失败后的短期标记窗口：窗口内再次请求直接降级本地规则（仅约束用户手动重复点击） */
    private static final long REPEAT_FAIL_WINDOW_MS = 60_000L;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 最近一次模型调用状态（静态，跨请求共享） */
    private static volatile long lastFailAt = 0L;
    private static volatile long lastCostMs = -1L;
    private static volatile String lastFailReason = null;

    @Autowired
    private QwenAIService qwenAIService;

    @Autowired
    private AiCallExecutor aiCallExecutor;

    /** 模型是否可用（已配置并启用） */
    public boolean isAvailable() {
        return qwenAIService != null && qwenAIService.isEnabled();
    }

    /** 最近一次成功调用的耗时（毫秒），未成功过为 -1 */
    public long lastCostMs() {
        return lastCostMs;
    }

    /**
     * 让模型对候选时段作剔除与微调，成功时原地改写 raw 的顺序。
     * 降级时返回原因：disabled（未启用）、timeout（等待超时）、invalid（返回无法解析）、
     * empty（未返回有效排序）；repeat 表示处于上次失败后的 60 秒窗口内，未发起真实调用。
     */
    public RankResult rank(List<Map<String, Object>> raw, String teacherName, String className,
                           String courseName, int oDay, int oStart, int oEnd, boolean changeClassroom,
                           String weekRange, String origClassroomNo, String origRoomType, User user) {
        RankResult result = new RankResult();
        if (!isAvailable()) {
            result.reason = "disabled";
            return result;
        }
        long now = System.currentTimeMillis();
        if (lastFailReason != null && lastFailAt > 0 && now - lastFailAt < REPEAT_FAIL_WINDOW_MS) {
            result.reason = lastFailReason;
            result.repeat = true;
            return result;
        }
        if (raw.isEmpty()) {
            return degrade(result, "empty", -1L);
        }

        // 本地规则已排好序，只交前 12 条给模型，模型只做剔除与靠前名次的微调
        int headSize = Math.min(raw.size(), AI_INPUT_LIMIT);
        List<Map<String, Object>> head = new ArrayList<>(raw.subList(0, headSize));
        long timeout = timeoutSeconds(headSize);
        result.timeoutSeconds = timeout;

        String prompt = buildPrompt(head, teacherName, className, courseName, oDay, oStart, oEnd,
                changeClassroom, weekRange, origClassroomNo, origRoomType);
        String studentId = user == null ? null : user.getStudentId();
        String context = "你是高校教务排课助手，擅长为调课挑选最合适的目标时段，输出必须是严格 JSON。";

        long begin = System.currentTimeMillis();
        Attempt attempt = callModel(prompt, studentId, context, timeout);
        result.retried = attempt.retried;
        long cost = System.currentTimeMillis() - begin;
        if (!attempt.ok) {
            return degrade(result, attempt.reason, cost);
        }

        List<Map<String, Object>> ordered = reorderHead(raw, headSize, attempt.exclude, attempt.order);
        if (ordered.isEmpty()) {
            return degrade(result, "empty", cost);
        }
        // 前 12 条按模型结果重排，其余保持本地规则顺序
        for (int i = headSize; i < raw.size(); i++) {
            ordered.add(raw.get(i));
        }

        raw.clear();
        raw.addAll(ordered);
        lastFailAt = 0L;
        lastFailReason = null;
        lastCostMs = cost;
        result.applied = true;
        result.costMs = cost;
        return result;
    }

    /** 按候选条数决定等待上限：候选多时模型生成更慢，自动放宽 */
    private long timeoutSeconds(int candidateCount) {
        return candidateCount <= 6 ? TIMEOUT_MIN_SECONDS : TIMEOUT_MAX_SECONDS;
    }

    /** 单次模型调用：超时、异常、空响应、无法解析均返回失败原因（超时由执行器自动重试一次） */
    private Attempt callModel(String prompt, String studentId, String context, long timeoutSeconds) {
        Attempt attempt = new Attempt();
        AiCallExecutor.CallResult call = aiCallExecutor.callText(prompt, studentId, context, timeoutSeconds);
        attempt.retried = call.retried;
        if (!call.success) {
            if ("timeout".equals(call.errorType)) {
                attempt.reason = "timeout";
            } else if ("format".equals(call.errorType)) {
                attempt.reason = "empty";
            } else if ("disabled".equals(call.errorType)) {
                attempt.reason = "disabled";
            } else {
                attempt.reason = "invalid";
            }
            return attempt;
        }
        JsonNode root = parseAiJson(call.content);
        if (root == null) {
            attempt.reason = "invalid";
            return attempt;
        }

        JsonNode ex = root.get("exclude");
        if (ex != null && ex.isArray()) {
            for (JsonNode n : ex) {
                int idx = n.asInt(-1);
                if (idx >= 1) {
                    attempt.exclude.add(idx);
                }
            }
        }
        JsonNode order = root.get("order");
        if (order != null && order.isArray()) {
            for (JsonNode n : order) {
                int idx = n.asInt(-1);
                if (idx >= 1) {
                    attempt.order.add(idx);
                }
            }
        }
        // 既无剔除也无次序：视为认可本地规则顺序，不算失败
        attempt.ok = true;
        return attempt;
    }

    /** 前段候选按模型剔除与次序重排（编号越界或重复的忽略） */
    private List<Map<String, Object>> reorderHead(List<Map<String, Object>> raw, int headSize,
                                                  Set<Integer> exclude, List<Integer> order) {
        List<Map<String, Object>> list = new ArrayList<>();
        Set<Integer> used = new LinkedHashSet<>();
        for (int idx : order) {
            if (idx <= headSize && !exclude.contains(idx) && used.add(idx)) {
                list.add(raw.get(idx - 1));
            }
        }
        for (int idx = 1; idx <= headSize; idx++) {
            if (!exclude.contains(idx) && !used.contains(idx)) {
                list.add(raw.get(idx - 1));
            }
        }
        return list;
    }

    /** 记录降级状态并填充返回结果 */
    private RankResult degrade(RankResult result, String reason, long costMs) {
        result.reason = reason;
        result.costMs = costMs;
        lastFailAt = System.currentTimeMillis();
        lastFailReason = reason;
        lastCostMs = costMs;
        return result;
    }

    /** 构建排序提示词 */
    private String buildPrompt(List<Map<String, Object>> raw, String teacherName, String className,
                               String courseName, int oDay, int oStart, int oEnd, boolean changeClassroom,
                               String weekRange, String origClassroomNo, String origRoomType) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：为教师筛选调课目标时段。只允许对下列候选做排序与剔除，禁止改写候选的状态文字。\n");
        sb.append("教师：").append(teacherName == null ? "" : teacherName)
                .append("；班级：").append(className.isEmpty() ? "无" : className)
                .append("；课程：《").append(courseName).append("》\n");
        sb.append("原时段：").append(AdjustmentSupport.weekdayText(oDay)).append(" ")
                .append(AdjustmentSupport.periodText(oStart, oEnd))
                .append("；原教室：").append(origClassroomNo.isEmpty() ? "无" : origClassroomNo)
                .append("；原教室类型：").append(origRoomType == null || origRoomType.isEmpty() ? "未知" : origRoomType)
                .append("；是否换教室：").append(changeClassroom ? "是" : "否")
                .append("；生效周次：").append(weekRange).append("\n");
        if (changeClassroom && origRoomType != null && !origRoomType.isEmpty()) {
            sb.append("说明：候选教室均已按原教室类型（").append(origRoomType)
                    .append("）过滤，不得以教室类型不匹配为由剔除候选。\n");
        }
        sb.append("说明：候选已按本地规则预排序（原教室优先、同日优先、节次靠前），")
                .append("你只需剔除明显不合理的候选，必要时微调前几名次序。\n");
        sb.append("候选（编号 | 星期 节次 | 教室 | 状态 | 该时段空闲教室数）：\n");
        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> c = raw.get(i);
            sb.append(i + 1).append(". ")
                    .append(AdjustmentSupport.weekdayText((Integer) c.get("weekDay"))).append(" ")
                    .append(AdjustmentSupport.periodText(c.get("startPeriod"), c.get("endPeriod"))).append(" | ")
                    .append(c.get("classroomName")).append(" | ")
                    .append(c.get("statusText")).append(" | ")
                    .append(c.get("_freeRooms")).append("\n");
        }
        sb.append("排序偏好：全部空闲优先；节次连续优先；原教室优先；换教室时可选教室多的优先；同一天节次靠前优先。\n");
        sb.append("剔除规则：仅剔除明显不合理的候选，拿不准的保留；若无需要剔除的候选，exclude 输出空数组。\n");
        sb.append("输出要求：只输出 JSON，不要解释、不要代码块，格式为 {\"exclude\":[要剔除的编号]}；")
                .append("如需调整推荐次序可附加 \"order\":[从高到低的编号]（可只列前 5 名）。");
        return sb.toString();
    }

    /** 解析 AI 返回的 JSON（兼容 ```json 代码块与前后说明文字） */
    private JsonNode parseAiJson(String answer) {
        try {
            String s = answer.trim();
            int start = s.indexOf('{');
            int end = s.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            return JSON.readTree(s.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    /** 单次模型调用结果 */
    private static class Attempt {
        boolean ok;
        boolean retried;
        String reason;
        final Set<Integer> exclude = new LinkedHashSet<>();
        final List<Integer> order = new ArrayList<>();
    }

    /** 模型排序结果 */
    public static class RankResult {
        /** 是否已按模型结果排序 */
        public boolean applied;
        /** 降级原因（applied 为 true 时为 null） */
        public String reason;
        /** 是否处于上次失败后的短期标记窗口 */
        public boolean repeat;
        /** 本次调用耗时（毫秒，含自动重试，未调用为 -1） */
        public long costMs = -1L;
        /** 本次单次调用等待上限（秒） */
        public long timeoutSeconds = -1L;
        /** 是否因超时自动重试过 */
        public boolean retried;
    }
}
