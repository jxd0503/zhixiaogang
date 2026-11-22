package com.zhixiaogang.service.intent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import com.zhixiaogang.service.KnowledgeBaseService;

import static com.zhixiaogang.service.intent.IntentSupport.*;

/********** 知识库查询 / 问候 / 兜底未知意图 **********/
@Service
public class KnowledgeIntentHandler {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    String retrieveKnowledgeData(String message) {
        StringBuilder data = new StringBuilder();
        data.append("【知识库信息】\n");
        
        String keyword = extractKeyword(message);
        if (keyword != null && !keyword.isEmpty()) {
            Map<String, Object> result = knowledgeBaseService.searchKnowledge(keyword);
            if ((Boolean) result.get("success")) {
                @SuppressWarnings("unchecked")
                java.util.List<java.util.Map<String, Object>> knowledge = (java.util.List<java.util.Map<String, Object>>) result.get("data");
                if (!knowledge.isEmpty()) {
                    for (java.util.Map<String, Object> item : knowledge) {
                        data.append("  " + item.get("title") + "：\n");
                        data.append("    " + item.get("content") + "\n");
                    }
                }
            }
        }
        
        return data.toString();
    }

    /** P10 问候 / 自我介绍回复 */
    private String handleGreeting(String message) {
        String lower = message.toLowerCase().trim();
        if (lower.contains("你是谁") || lower.contains("你叫什么") || lower.contains("介绍")) {
            return "我是小智，你的校园智能助手。我可以帮你查课表、查空教室、预约教室，还能回答校园生活中的常见问题。";
        }
        if (lower.contains("谢谢") || lower.contains("感谢") || lower.contains("多谢") || lower.contains("辛苦")) {
            return "不客气，随时找我！";
        }
        if (lower.contains("再见") || lower.contains("拜拜") || lower.equals("拜")) {
            return "再见，祝你学习顺利，有需要随时找我！";
        }
        if (lower.equals("好的") || lower.equals("行") || lower.equals("嗯")
                || lower.contains("明白") || lower.contains("懂了") || lower.contains("知道了")
                || lower.contains("了解") || lower.contains("没事") || lower.contains("不客气")) {
            return "好的，还有其他需要随时告诉我。";
        }
        return "您好，我是小智，可以帮您查课表、查空教室、预约教室，请直接告诉我需求。";
    }

    private String handleKnowledgeQuery(String message) {
        String keyword = extractKeyword(message);

        if (keyword != null && !keyword.isEmpty()) {
            Map<String, Object> result = knowledgeBaseService.searchKnowledge(keyword);
            if ((Boolean) result.get("success")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> knowledge = (List<Map<String, Object>>) result.get("data");
                String knowledgeResponse = knowledgeBaseService.formatKnowledgeResponse(knowledge);
                if (!knowledgeResponse.isEmpty()) {
                    return knowledgeResponse;
                }
            }
        }

        return "抱歉，我暂时无法回答这个问题。你可以尝试以下关键词：\n" +
               "• 查课表：查询课程安排\n" +
               "• 查教室：查看可用教室\n" +
               "• 预约教室：预约空闲教室";
    }

    /** 按指定词列表检索知识库并格式化，无命中返回空串 */
    @SuppressWarnings("unchecked")
    private String formatKnowledgeByWords(java.util.List<String> words) {
        Map<String, Object> result = knowledgeBaseService.searchByWords(words);
        if ((Boolean) result.get("success")) {
            List<Map<String, Object>> knowledge = (List<Map<String, Object>>) result.get("data");
            return knowledgeBaseService.formatKnowledgeResponse(knowledge);
        }
        return "";
    }

    private String handleUnknown(String message) {
        return "抱歉，我暂时无法理解您的问题。你可以尝试以下操作：\n" +
               "• 查课表：查询课程安排\n" +
               "• 查教室：查看可用教室\n" +
               "• 预约教室：预约空闲教室";
    }
}
