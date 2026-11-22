package com.zhixiaogang.service;

import com.zhixiaogang.service.impl.QwenAIService;
import com.zhixiaogang.service.impl.RuleEngineAIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/** 按配置选择并路由 AI 服务实现（通义千问 / 规则引擎）。 */
@Service
public class AIServiceManager {

    private final RuleEngineAIService ruleEngineAIService;
    private final QwenAIService qwenAIService;

    @Autowired
    private AIConfigService aiConfigService;

    private Map<String, AIService> serviceMap = new HashMap<>();

    public AIServiceManager(RuleEngineAIService ruleEngineAIService, QwenAIService qwenAIService) {
        this.ruleEngineAIService = ruleEngineAIService;
        this.qwenAIService = qwenAIService;
    }

    @PostConstruct
    public void init() {
        serviceMap.put("rule-engine", ruleEngineAIService);
        serviceMap.put("qwen", qwenAIService);
    }

    public AIService getAIService() {
        String defaultProvider = aiConfigService.getDefaultProvider();
        AIService service = serviceMap.get(defaultProvider);
        if (service != null && service.isEnabled()) {
            return service;
        }
        return ruleEngineAIService;
    }

    public AIService getAIService(String provider) {
        AIService service = serviceMap.get(provider);
        if (service != null && service.isEnabled()) {
            return service;
        }
        return ruleEngineAIService;
    }

    public AIService getActiveService() {
        if (qwenAIService.isEnabled()) {
            return qwenAIService;
        }
        return ruleEngineAIService;
    }
}
