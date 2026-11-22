package com.zhixiaogang.service;

import com.zhixiaogang.mapper.KnowledgeBaseMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 知识库检索与关键词匹配，为 AI 回答提供上下文。 */
@Service
public class KnowledgeBaseService {

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    public Map<String, Object> searchKnowledge(String keyword) {
        Map<String, Object> result = new HashMap<>();
        List<String> words = new java.util.ArrayList<>();
        if (keyword != null) {
            for (String w : keyword.split("\\s+")) {
                if (!w.isEmpty()) words.add(w);
            }
        }
        return searchByWords(words, result);
    }

    /** P3/P12 按显式词列表检索：标题/标签强命中优先，最多返回3条 */
    public Map<String, Object> searchByWords(List<String> words) {
        return searchByWords(words, new HashMap<>());
    }

    private Map<String, Object> searchByWords(List<String> words, Map<String, Object> result) {
        List<String> validWords = words == null ? List.of() : words.stream().filter(w -> w != null && !w.isEmpty()).toList();
        if (validWords.isEmpty()) {
            result.put("success", false);
            result.put("message", "未找到相关知识");
            return result;
        }
        List<Map<String, Object>> rows = knowledgeBaseMapper.searchKnowledge(validWords);
        if (rows.isEmpty()) {
            result.put("success", false);
            result.put("message", "未找到相关知识");
            return result;
        }

        List<Map<String, Object>> strong = new java.util.ArrayList<>();
        List<Map<String, Object>> weak = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object scoreObj = row.get("score");
            int score = scoreObj instanceof Number ? ((Number) scoreObj).intValue() : 1;
            if (score >= 2) strong.add(row);
            else weak.add(row);
        }
        if (strong.isEmpty()) {
            result.put("success", true);
            result.put("data", weak.stream().limit(3).toList());
            return result;
        }
        // 仅保留与最高分条目分差不超过 2 的强命中，过滤标题偶含通用词的条目
        int maxScore = strong.stream()
                .mapToInt(r -> {
                    Object s = r.get("score");
                    return s instanceof Number ? ((Number) s).intValue() : 2;
                }).max().orElse(2);
        int cutoff = Math.max(2, maxScore - 2);
        List<Map<String, Object>> picked = strong.stream()
                .filter(r -> {
                    Object s = r.get("score");
                    return s instanceof Number && ((Number) s).intValue() >= cutoff;
                })
                .limit(3)
                .toList();
        result.put("success", true);
        result.put("data", picked);
        return result;
    }

    public Map<String, Object> getAllKnowledge() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> knowledge = knowledgeBaseMapper.selectList(null).stream()
                .map(kb -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", kb.getId());
                    map.put("title", kb.getTitle());
                    map.put("content", kb.getContent());
                    map.put("category", kb.getCategory());
                    return map;
                })
                .toList();
        
        result.put("success", true);
        result.put("data", knowledge);
        return result;
    }

    public String formatKnowledgeResponse(List<Map<String, Object>> knowledgeList) {
        if (knowledgeList.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> knowledge : knowledgeList) {
            String title = (String) knowledge.get("title");
            String content = (String) knowledge.get("content");
            
            sb.append("【").append(title).append("】\n");
            sb.append(content).append("\n\n");
        }
        
        return sb.toString();
    }
}
