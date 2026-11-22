package com.zhixiaogang.controller;

import com.zhixiaogang.service.AIConfigService;
import com.zhixiaogang.service.OperationLogService;
import com.zhixiaogang.service.impl.QwenAIService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/** 管理端 AI 配置：总开关、多模态能力、模型提供商增删改查与默认/启用/模型切换。 */
@Controller
@RequestMapping("/admin")
public class AdminAiController {

    private static final Logger log = LoggerFactory.getLogger(AdminAiController.class);

    @Autowired
    private QwenAIService qwenAIService;

    @Autowired
    private AIConfigService aiConfigService;

    @Autowired
    private OperationLogService operationLogService;

    /** 当前生效模型的多模态能力判定：{supported, model, providerName}，前端据此举置灰识图按钮 */
    @GetMapping("/ai/multimodal-status")
    @ResponseBody
    public Map<String, Object> multimodalStatus() {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> status = aiConfigService.getMultimodalStatus();
        result.put("success", true);
        if (status == null) {
            result.put("supported", false);
            result.put("model", "");
            result.put("providerName", "");
        } else {
            result.putAll(status);
        }
        return result;
    }

    @PostMapping("/ai/disable")
    @ResponseBody
    public Map<String, Object> disableAI() {
        Map<String, Object> result = new HashMap<>();
        qwenAIService.disableAI();
        result.put("success", true);
        result.put("message", "智能AI已断开");
        AdminWebSupport.logAction(operationLogService, "update", "ai_provider", null, "停用智能AI", result);
        return result;
    }
    
    @PostMapping("/ai/enable")
    @ResponseBody
    public Map<String, Object> enableAI() {
        Map<String, Object> result = new HashMap<>();
        qwenAIService.enableAI();
        result.put("success", true);
        result.put("message", "智能AI已启用");
        AdminWebSupport.logAction(operationLogService, "update", "ai_provider", null, "启用智能AI", result);
        return result;
    }
    
    @GetMapping("/ai/status")
    @ResponseBody
    public Map<String, Object> getAIStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("disabled", qwenAIService.isAiDisabled());
        result.put("enabled", qwenAIService.isEnabled());
        return result;
    }

    @GetMapping("/ai/config")
    @ResponseBody
    public Map<String, Object> getAIConfig() {
        Map<String, Object> result = new HashMap<>();
        com.zhixiaogang.entity.AIConfig config = aiConfigService.getConfig();
        result.put("success", true);
        result.put("apiKey", aiConfigService.getMaskedApiKey());
        result.put("apiUrl", config != null ? config.getApiUrl() : "");
        result.put("model", config != null ? config.getModel() : "");
        result.put("defaultProvider", config != null ? config.getDefaultProvider() : "");
        result.put("aiDisabled", aiConfigService.isAiDisabled());
        result.put("keyEncryptionEnabled", aiConfigService.isKeyEncryptionEnabled());
        return result;
    }

    @PostMapping("/ai/config")
    @ResponseBody
    public Map<String, Object> saveAIConfig(@RequestBody Map<String, String> request) {
        Map<String, Object> result = new HashMap<>();

        try {
            String apiKey = request.get("apiKey");
            String apiUrl = request.get("apiUrl");
            String model = request.get("model");
            String defaultProvider = request.get("defaultProvider");

            // 若 apiKey 为空或为脱敏占位符，则保留原值
            if (apiKey == null || apiKey.isEmpty() || apiKey.contains("***")) {
                apiKey = aiConfigService.getApiKey();
            }

            aiConfigService.saveConfig(apiKey, apiUrl, model, defaultProvider);
            result.put("success", true);
            result.put("message", "AI配置保存成功");
        } catch (Exception e) {
            log.error("保存 AI 配置失败", e);
            result.put("success", false);
            result.put("message", "保存失败，请稍后重试");
        }

        AdminWebSupport.logAction(operationLogService, "update", "ai_provider", null,
                "保存AI配置（模型 " + request.get("model") + "）", result);
        return result;
    }

    // ===================== 多模型提供商管理 =====================

    @GetMapping("/ai/providers")
    @ResponseBody
    public Map<String, Object> listProviders() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("providers", aiConfigService.listProviders());
        result.put("keyEncryptionEnabled", aiConfigService.isKeyEncryptionEnabled());
        return result;
    }

    @PostMapping("/ai/providers")
    @ResponseBody
    public Map<String, Object> saveProvider(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new HashMap<>();
        try {
            Long id = parseLong(request.get("id"));
            String name = (String) request.get("name");
            String apiUrl = (String) request.get("apiUrl");
            String apiKey = (String) request.get("apiKey");
            String models = request.get("models") != null ? String.valueOf(request.get("models")) : null;
            String multimodalModels = request.get("multimodalModels") != null
                    ? String.valueOf(request.get("multimodalModels")) : null;
            Integer enabled = parseEnabled(request.get("enabled"));

            boolean ok = aiConfigService.saveProvider(id, name, apiUrl, apiKey, models, multimodalModels, enabled);
            if (ok) {
                result.put("success", true);
                result.put("message", id == null ? "提供商添加成功" : "提供商更新成功");
            } else {
                result.put("success", false);
                result.put("message", "保存失败：名称、API地址、模型列表不能为空");
            }
            AdminWebSupport.logAction(operationLogService, id == null ? "create" : "update", "ai_provider", id,
                    (id == null ? "新增模型提供商：" : "修改模型提供商：") + name, result);
        } catch (Exception e) {
            log.error("保存模型提供商失败", e);
            result.put("success", false);
            result.put("message", "保存失败，请稍后重试");
            AdminWebSupport.logAction(operationLogService, "update", "ai_provider", null, "保存模型提供商", result);
        }
        return result;
    }

    @PostMapping("/ai/providers/{id}/default")
    @ResponseBody
    public Map<String, Object> setDefaultProvider(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        boolean ok = aiConfigService.setDefaultProvider(id);
        result.put("success", ok);
        result.put("message", ok ? "已设为当前生效提供商" : "设置失败：请先启用该提供商");
        AdminWebSupport.logAction(operationLogService, "update", "ai_provider", id, "切换当前生效提供商 #" + id, result);
        return result;
    }

    @PostMapping("/ai/providers/{id}/toggle")
    @ResponseBody
    public Map<String, Object> toggleProvider(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        boolean ok = aiConfigService.toggleProvider(id);
        result.put("success", ok);
        result.put("message", ok ? "提供商状态已更新" : "操作失败：当前生效的提供商不可停用");
        AdminWebSupport.logAction(operationLogService, "update", "ai_provider", id, "切换模型提供商启用状态 #" + id, result);
        return result;
    }

    @PostMapping("/ai/providers/{id}/model")
    @ResponseBody
    public Map<String, Object> setProviderModel(@PathVariable Long id, @RequestBody Map<String, String> request) {
        Map<String, Object> result = new HashMap<>();
        boolean ok = aiConfigService.setCurrentModel(id, request.get("model"));
        result.put("success", ok);
        result.put("message", ok ? "生效模型已更新" : "设置失败：该模型不在提供商的可用模型列表内");
        AdminWebSupport.logAction(operationLogService, "update", "ai_provider", id,
                "切换生效模型：" + request.get("model"), result);
        return result;
    }

    @DeleteMapping("/ai/providers/{id}")
    @ResponseBody
    public Map<String, Object> deleteProvider(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        boolean ok = aiConfigService.deleteProvider(id);
        result.put("success", ok);
        result.put("message", ok ? "提供商已删除" : "删除失败：生效中或仅剩一条的提供商不允许删除");
        AdminWebSupport.logAction(operationLogService, "delete", "ai_provider", id, "删除模型提供商 #" + id, result);
        return result;
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseEnabled(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value ? 1 : 0;
        }
        String text = String.valueOf(value).trim();
        if ("1".equals(text) || "true".equalsIgnoreCase(text)) {
            return 1;
        }
        if ("0".equals(text) || "false".equalsIgnoreCase(text)) {
            return 0;
        }
        return null;
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "";
        }
        if (apiKey.length() <= 7) {
            return "***";
        }
        return apiKey.substring(0, 3) + "***" + apiKey.substring(apiKey.length() - 4);
    }
}
