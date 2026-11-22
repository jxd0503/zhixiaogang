package com.zhixiaogang.service.impl;

import com.zhixiaogang.service.AIService;
import org.springframework.stereotype.Service;

/** 无外部模型时的规则引擎兜底回答实现。 */
@Service("ruleEngineAIService")
public class RuleEngineAIService implements AIService {

    @Override
    public String chat(String message, String studentId) {
        return "";
    }

    @Override
    public String getName() {
        return "规则引擎";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
