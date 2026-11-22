package com.zhixiaogang.service.intent;

import com.zhixiaogang.entity.ChatLog;
import com.zhixiaogang.entity.User;
import com.zhixiaogang.mapper.ChatLogMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.zhixiaogang.service.UserService;
import com.zhixiaogang.service.ai.AiCallExecutor;

import static com.zhixiaogang.service.intent.IntentSupport.*;

/********** 聊天统一入口：意图识别、AI/规则模式分流、上下文历史、日志落库，具体意图委托给各处理器 **********/
@Service
public class IntentService {

    // P2-7 AI上下文记忆：按用户缓存最近3轮工具查询结果摘要
    private static final int MAX_CONTEXT_HISTORY = 3;
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.LinkedList<String>> aiContextHistory =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    private UserService userService;

    @Autowired
    private ChatLogMapper chatLogMapper;

    @Autowired
    private com.zhixiaogang.service.impl.QwenAIService qwenAIService;

    @Autowired
    private AiCallExecutor aiCallExecutor;

    @Autowired
    private ScheduleQueryIntentHandler scheduleQueryIntentHandler;

    @Autowired
    private ClassroomIntentHandler classroomIntentHandler;

    @Autowired
    private KnowledgeIntentHandler knowledgeIntentHandler;

    @Autowired
    private TeacherIntentHandler teacherIntentHandler;

    public enum IntentType {
        SCHEDULE_QUERY,
        CLASSROOM_QUERY,
        CLASSROOM_RESERVE,
        MY_RESERVATION,
        KNOWLEDGE_QUERY,
        GREETING,
        UNKNOWN
    }

    public Map<String, Object> processMessage(String studentId, String message) {
        return processMessage(studentId, message, null, null);
    }

    /** 聊天消息一律走 AI 解析；aiMode 参数仅为兼容旧调用保留，取值被忽略 */
    public Map<String, Object> processMessage(String studentId, String message, Boolean aiMode, String model) {
        Map<String, Object> result = new HashMap<>();
        IntentType intent = identifyIntent(message);

        User user = userService.getUserByStudentId(studentId);
        String userName = user != null ? user.getName() : "同学";

        String response = handleAIModeWithData(studentId, userName, message, intent, model);

        saveChatLog(studentId, message, response, intent.name(), "AI_MODE");

        result.put("success", true);
        result.put("response", response);
        result.put("intent", intent.name());
        return result;
    }
    
    private String handleAIModeWithData(String studentId, String userName, String message, IntentType intent, String model) {
        String context = retrieveContextData(studentId, userName, message, intent);
        context = prependHistory(studentId, context);
        String response = runAIChat(context, studentId, message, model);
        storeContext(studentId, context);
        return response;
    }

    /** 教师端智能AI：上下文按教师视角构建（身份+授课安排），不查学生课表 */
    private String handleTeacherAIMode(String teacherId, String userName, String message, IntentType intent, String model) {
        String context = retrieveTeacherContextData(teacherId, userName, message, intent);
        context = prependHistory(teacherId, context);
        String response = runAIChat(context, teacherId, message, model);
        storeContext(teacherId, context);
        return response;
    }

    /** P2-7 把最近几轮工具查询上下文拼到当前上下文前面 */
    private String prependHistory(String userId, String currentContext) {
        java.util.LinkedList<String> history = aiContextHistory.get(userId);
        if (history == null || history.isEmpty()) return currentContext;
        StringBuilder sb = new StringBuilder();
        sb.append("【历史查询参考】以下是此前对话中查询到的数据，你可以引用：\n");
        int idx = history.size();
        for (String ctx : history) {
            // 只取摘要部分（截断过长的上下文）
            String snippet = ctx.length() > 600 ? ctx.substring(0, 600) + "..." : ctx;
            sb.append("[第").append(idx--).append("轮] ").append(snippet).append("\n\n");
        }
        sb.append(currentContext);
        return sb.toString();
    }

    /** P2-7 存储本轮上下文到用户历史队列 */
    private void storeContext(String userId, String context) {
        aiContextHistory.compute(userId, (k, v) -> {
            if (v == null) v = new java.util.LinkedList<>();
            v.addFirst(context);
            while (v.size() > MAX_CONTEXT_HISTORY) v.removeLast();
            return v;
        });
    }

    private String runAIChat(String context, String userId, String message, String model) {
        if (qwenAIService.isEnabled()) {
            AiCallExecutor.CallResult call = aiCallExecutor.callText(message, userId, context);
            if (call.success) {
                return call.content;
            }
            if ("timeout".equals(call.errorType)) {
                return "模型响应超时（已等待 " + AiCallExecutor.TEXT_TIMEOUT_SECONDS + " 秒"
                        + (call.retried ? "，已自动重试 1 次" : "") + "），请稍后重试或换用更快模型";
            }
            return "智能 AI 暂时无法回答，请稍后重试";
        }

        return "智能 AI 服务未配置，请联系管理员配置模型";
    }
    
    private String retrieveContextData(String studentId, String userName, String message, IntentType intent) {
        StringBuilder context = new StringBuilder();
        context.append("【用户身份】").append(userName).append("同学（学生），请用「")
               .append(userName).append("同学」或「你」称呼用户，不要称呼「老师」。\n");

        switch (intent) {
            case SCHEDULE_QUERY:
                context.append(scheduleQueryIntentHandler.retrieveScheduleData(studentId, message));
                break;
            case CLASSROOM_QUERY:
                context.append(classroomIntentHandler.retrieveClassroomData(message));
                break;
            case CLASSROOM_RESERVE:
                context.append(classroomIntentHandler.retrieveClassroomData(message));
                break;
            case KNOWLEDGE_QUERY:
                context.append(knowledgeIntentHandler.retrieveKnowledgeData(message));
                break;
            default:
                break;
        }
        
        return context.toString();
    }

    /** 教师端AI上下文：身份（老师）+ 教师授课安排/空教室等，按角色取数 */
    private String retrieveTeacherContextData(String teacherId, String userName, String message, IntentType intent) {
        StringBuilder context = new StringBuilder();
        context.append("【用户身份】").append(userName).append("老师（教师），请始终用「")
               .append(userName).append("老师」称呼用户，不要称呼「同学」。\n");

        // P1-5 命中授课/班级语义但未识别为 SCHEDULE_QUERY 时，也注入教师授课与班级数据
        boolean teacherSemanic = message.contains("我的课") || message.contains("授课")
                || message.contains("班级") || message.contains("学生") || message.contains("有课");

        switch (intent) {
            case SCHEDULE_QUERY:
                context.append(scheduleQueryIntentHandler.retrieveTeacherScheduleData(teacherId, userName, message));
                break;
            case CLASSROOM_QUERY:
            case CLASSROOM_RESERVE:
                context.append(classroomIntentHandler.retrieveClassroomData(message));
                if (teacherSemanic) {
                    context.append("\n").append(scheduleQueryIntentHandler.retrieveTeacherScheduleData(teacherId, userName, message));
                }
                break;
            case KNOWLEDGE_QUERY:
                if (teacherSemanic) {
                    context.append(scheduleQueryIntentHandler.retrieveTeacherScheduleData(teacherId, userName, message));
                    if (message.contains("班级") || message.contains("学生")) {
                        context.append("\n【教师班级与学生名单】\n")
                               .append(teacherIntentHandler.handleTeacherStudents(userName, teacherId));
                    }
                } else {
                    context.append(knowledgeIntentHandler.retrieveKnowledgeData(message));
                }
                break;
            default:
                if (teacherSemanic) {
                    context.append(scheduleQueryIntentHandler.retrieveTeacherScheduleData(teacherId, userName, message));
                }
                break;
        }
        return context.toString();
    }

    private IntentType identifyIntent(String message) {
        String lowerMessage = message.toLowerCase();

        // P10 问候/闲聊/自我介绍：最高优先级
        if (isGreeting(lowerMessage)) {
            return IntentType.GREETING;
        }

        boolean hasReserveKeyword = lowerMessage.contains("预约")
                && (lowerMessage.contains("教室") || extractBuilding(message) != null);
        boolean hasClassroomKeyword = lowerMessage.contains("空教室") || lowerMessage.contains("教室");
        boolean hasConsultWord = false;
        for (String w : CONSULT_WORDS) {
            if (lowerMessage.contains(w)) { hasConsultWord = true; break; }
        }

        // P9 咨询意图优先于执行意图："预约教室要提前多久""怎么查空教室" → 知识库问答
        if ((hasReserveKeyword || hasClassroomKeyword) && hasConsultWord) {
            return IntentType.KNOWLEDGE_QUERY;
        }

        // P1-4 查询已有预约（"我的预约/我预约的教室/我预约了哪些教室/预约记录"）→ 非新建
        if (lowerMessage.contains("我的预约") || lowerMessage.contains("我预约的")
                || lowerMessage.contains("我预约了") || lowerMessage.contains("预约记录")
                || lowerMessage.contains("预约了哪些")) {
            return IntentType.MY_RESERVATION;
        }

        // 明确的预约执行意图
        if (hasReserveKeyword || (lowerMessage.contains("教室") && lowerMessage.contains("预约"))) {
            return IntentType.CLASSROOM_RESERVE;
        }

        // P2-3 混合意图：同时含课表与空教室语义 → 优先课表
        boolean hasScheduleKeyword = lowerMessage.contains("课表") || lowerMessage.contains("什么课")
                || lowerMessage.contains("哪些课") || lowerMessage.contains("几节课");
        if (hasScheduleKeyword && hasClassroomKeyword) {
            return IntentType.SCHEDULE_QUERY;
        }

        // P1-3 具体教室号 + 有空/有课/能用/空闲/占用 → 教室状态查询
        if (extractClassroomNo(message) != null
                && (lowerMessage.contains("有空") || lowerMessage.contains("空闲") || lowerMessage.contains("空不空")
                    || lowerMessage.contains("有课") || lowerMessage.contains("能用")
                    || lowerMessage.contains("占用") || lowerMessage.contains("有没有"))) {
            return IntentType.CLASSROOM_QUERY;
        }

        // 明确的教室查询意图
        if (hasClassroomKeyword || lowerMessage.contains("自习")) {
            return IntentType.CLASSROOM_QUERY;
        }

        // P1 课表意图：扩展 "上什么课/上哪些课/上啥课"、"周X+课"、"几节课"、"下一节课"
        // 相对日期（明天/今天/后天）+ 课的问法，如 "明天有课吗""今天有什么课""后天上课吗"
        if (lowerMessage.contains("课表") || lowerMessage.contains("课程") || lowerMessage.contains("上课")
            || lowerMessage.contains("有什么课") || lowerMessage.contains("哪些课") || lowerMessage.contains("星期")
            || lowerMessage.contains("上什么课") || lowerMessage.contains("上哪些课") || lowerMessage.contains("上啥课")
            || lowerMessage.contains("几节课") || lowerMessage.contains("多少节课") || lowerMessage.contains("几节")
            || lowerMessage.contains("下一节") || lowerMessage.contains("下节课") || lowerMessage.contains("下一堂")
            || (extractWeekDay(message) != null && lowerMessage.contains("课"))
            || ((lowerMessage.contains("明天") || lowerMessage.contains("今天") || lowerMessage.contains("后天"))
                && (lowerMessage.contains("有课") || lowerMessage.contains("上课") || lowerMessage.contains("什么课") || lowerMessage.contains("课")))) {
            return IntentType.SCHEDULE_QUERY;
        }

        return IntentType.KNOWLEDGE_QUERY;
    }

    /** P10 问候 / 自我介绍识别 */
    private boolean isGreeting(String lowerMessage) {
        String trimmed = lowerMessage.trim();
        if (trimmed.contains("你是谁") || trimmed.contains("你叫什么") || trimmed.contains("自我介绍")
                || trimmed.contains("介绍一下你")) {
            return true;
        }
        String[] greetings = {"你好", "您好", "hello", "hi", "嗨", "在吗", "早上好", "下午好", "晚上好"};
        for (String g : greetings) {
            if (trimmed.startsWith(g)) {
                return true;
            }
        }
        // P2-11 礼貌收尾语：仅对短消息生效，避免拦截 "好的，帮我预约…" 这类真实指令
        if (trimmed.length() <= 8) {
            String[] closers = {"谢谢", "感谢", "多谢", "辛苦了", "再见", "拜拜", "拜", "好的",
                    "行", "嗯", "明白", "明白了", "懂了", "知道了", "了解", "没事", "不客气"};
            for (String c : closers) {
                if (trimmed.equals(c) || trimmed.startsWith(c)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void saveChatLog(String studentId, String userMessage, String botResponse, String intentType, String mode) {
        if (studentId == null || studentId.isEmpty()) {
            return;
        }
        try {
            ChatLog chatLog = new ChatLog();
            chatLog.setStudentId(studentId);
            chatLog.setUserMessage(userMessage);
            chatLog.setBotResponse(botResponse);
            chatLog.setIntentType(intentType);
            chatLog.setMode(mode);
            chatLog.setCreateTime(LocalDateTime.now());
            chatLogMapper.insert(chatLog);
        } catch (Exception e) {
            // 忽略保存失败的情况，不影响主流程
        }
    }

    public List<ChatLog> getChatLogs(String studentId) {
        return chatLogMapper.selectList(null).stream()
                .filter(log -> studentId.equals(log.getStudentId()))
                .toList();
    }

    // ==================== P13 教师端问答 ====================

    public Map<String, Object> processTeacherMessage(String teacherId, String message) {
        return processTeacherMessage(teacherId, message, null, null);
    }

    /** 教师端聊天同样一律走 AI 解析；aiMode 参数仅兼容旧调用，取值被忽略 */
    public Map<String, Object> processTeacherMessage(String teacherId, String message, Boolean aiMode, String model) {
        Map<String, Object> result = new HashMap<>();
        User user = userService.getUserByStudentId(teacherId);
        String userName = user != null ? user.getName() : "老师";
        IntentType intent = identifyIntent(message);

        String response = handleTeacherAIMode(teacherId, userName, message, intent, model);

        saveChatLog(teacherId, message, response, intent.name(), "AI_MODE");
        result.put("success", true);
        result.put("response", response);
        result.put("intent", intent.name());
        return result;
    }
}
