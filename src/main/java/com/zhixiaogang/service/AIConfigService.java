package com.zhixiaogang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixiaogang.entity.AIConfig;
import com.zhixiaogang.entity.AIConfigProvider;
import com.zhixiaogang.mapper.AIConfigMapper;
import com.zhixiaogang.mapper.AIConfigProviderMapper;
import com.zhixiaogang.util.ApiKeyCipher;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** AI 配置与多提供商管理：负责 API Key 加解密、默认值兜底与生效提供商解析。 */
@Service
public class AIConfigService {

    @Autowired
    private AIConfigMapper aiConfigMapper;

    @Autowired
    private AIConfigProviderMapper aiConfigProviderMapper;

    @Autowired
    private ApiKeyCipher apiKeyCipher;

    private volatile AIConfig cache;

    private static final String DEFAULT_API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String DEFAULT_MODEL = "qwen-plus";
    private static final String DEFAULT_PROVIDER = "rule-engine";

    @PostConstruct
    public void init() {
        refresh();
    }

    public void refresh() {
        try {
            AIConfig config = aiConfigMapper.selectById(1L);
            if (config == null) {
                config = createDefaults();
                aiConfigMapper.insert(config);
            }
            // 缓存中统一保存明文，写库时再加密
            config.setApiKey(apiKeyCipher.decrypt(config.getApiKey()));
            this.cache = config;
        } catch (Exception e) {
            System.err.println("AIConfigService: 配置表暂未就绪，使用默认值: " + e.getMessage());
            this.cache = createDefaults();
        }
    }

    /** 是否已启用 API Key 静态加密（供管理端提示明文存储） */
    public boolean isKeyEncryptionEnabled() {
        return apiKeyCipher.isEnabled();
    }

    /** 掩码后的 API Key，用于接口回显 */
    public String getMaskedApiKey() {
        return apiKeyCipher.mask(getApiKey());
    }

    public AIConfig getConfig() {
        if (cache == null) {
            refresh();
        }
        return cache;
    }

    public String getApiKey() {
        AIConfig c = getConfig();
        return c != null ? c.getApiKey() : "";
    }

    public String getApiUrl() {
        AIConfig c = getConfig();
        return c != null && c.getApiUrl() != null && !c.getApiUrl().isEmpty() ? c.getApiUrl() : DEFAULT_API_URL;
    }

    public String getModel() {
        AIConfig c = getConfig();
        return c != null && c.getModel() != null && !c.getModel().isEmpty() ? c.getModel() : DEFAULT_MODEL;
    }

    public String getDefaultProvider() {
        AIConfig c = getConfig();
        return c != null && c.getDefaultProvider() != null && !c.getDefaultProvider().isEmpty() ? c.getDefaultProvider() : DEFAULT_PROVIDER;
    }

    public boolean isAiDisabled() {
        AIConfig c = getConfig();
        return c != null && c.getAiDisabled() != null && c.getAiDisabled() == 1;
    }

    public void saveConfig(String apiKey, String apiUrl, String model, String defaultProvider) {
        AIConfig config = getConfig();
        if (config == null) {
            config = createDefaults();
        }
        if (apiKey != null && !apiKey.isEmpty()) {
            config.setApiKey(apiKey);
        }
        if (apiUrl != null && !apiUrl.isEmpty()) {
            config.setApiUrl(apiUrl);
        }
        if (model != null && !model.isEmpty()) {
            config.setModel(model);
        }
        if (defaultProvider != null && !defaultProvider.isEmpty()) {
            config.setDefaultProvider(defaultProvider);
        }
        config.setId(1L);
        // 写库前统一加密（已加密的值不会重复加密）
        config.setApiKey(apiKeyCipher.encrypt(config.getApiKey()));
        try {
            aiConfigMapper.updateById(config);
        } catch (Exception e) {
            aiConfigMapper.insert(config);
        }
        refresh();
    }

    public void setAiDisabled(boolean disabled) {
        // 只更新开关字段，避免把缓存中的明文 Key 整对象回写
        AIConfig patch = new AIConfig();
        patch.setId(1L);
        patch.setAiDisabled(disabled ? 1 : 0);
        try {
            if (aiConfigMapper.updateById(patch) <= 0) {
                AIConfig config = createDefaults();
                config.setAiDisabled(disabled ? 1 : 0);
                aiConfigMapper.insert(config);
            }
        } catch (Exception e) {
            AIConfig config = createDefaults();
            config.setAiDisabled(disabled ? 1 : 0);
            aiConfigMapper.insert(config);
        }
        refresh();
    }

    private AIConfig createDefaults() {
        AIConfig config = new AIConfig();
        config.setId(1L);
        config.setApiKey("");
        config.setApiUrl(DEFAULT_API_URL);
        config.setModel(DEFAULT_MODEL);
        config.setDefaultProvider(DEFAULT_PROVIDER);
        config.setAiDisabled(0);
        return config;
    }

    // ===================== 多模型提供商管理 =====================

    /** 提供商列表（apiKey 脱敏，models 拆分为数组） */
    public List<Map<String, Object>> listProviders() {
        List<AIConfigProvider> providers = aiConfigProviderMapper.selectList(
                new LambdaQueryWrapper<AIConfigProvider>()
                        .orderByAsc(AIConfigProvider::getSortOrder)
                        .orderByAsc(AIConfigProvider::getId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (AIConfigProvider p : providers) {
            result.add(toProviderView(p));
        }
        return result;
    }

    /** 新增 / 更新提供商。id 为空或不存在时新增；apiKey 为空或含 "*" 时保留原值 */
    public boolean saveProvider(Long id, String name, String apiUrl, String apiKey, String models,
                                String multimodalModels, Integer enabled) {
        if (name == null || name.trim().isEmpty()
                || apiUrl == null || apiUrl.trim().isEmpty()
                || models == null || normalizeModels(models).isEmpty()) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        AIConfigProvider provider;
        if (id == null) {
            provider = new AIConfigProvider();
            provider.setCreateTime(now);
            provider.setIsDefault(0);
            Long providerCount = aiConfigProviderMapper.selectCount(null);
            provider.setSortOrder(providerCount != null ? providerCount.intValue() : 0);
            provider.setApiKey(apiKeyCipher.encrypt(apiKey != null ? apiKey.trim() : ""));
        } else {
            provider = aiConfigProviderMapper.selectById(id);
            if (provider == null) {
                return false;
            }
            // 留空或传入脱敏值（含 *）时保留原 Key；否则加密后写入
            if (apiKey != null && !apiKey.trim().isEmpty() && !apiKey.contains("*")) {
                provider.setApiKey(apiKeyCipher.encrypt(apiKey.trim()));
            }
        }

        List<String> modelList = normalizeModels(models);
        provider.setName(name.trim());
        provider.setApiUrl(apiUrl.trim());
        provider.setModels(String.join(",", modelList));
        // 多模态模型：逗号分隔去空格去重；未传（新增传 null）时保留默认空
        if (multimodalModels != null) {
            provider.setMultimodalModels(String.join(",", normalizeModels(multimodalModels)));
        }
        // 原生效模型不在新模型列表内时，自动取第一个模型
        if (provider.getCurrentModel() == null || !modelList.contains(provider.getCurrentModel())) {
            provider.setCurrentModel(modelList.get(0));
        }
        if (enabled != null) {
            provider.setEnabled(enabled == 1 ? 1 : 0);
        } else if (provider.getEnabled() == null) {
            provider.setEnabled(1);
        }
        provider.setUpdateTime(now);

        if (id == null) {
            // 第一条提供商自动设为生效
            if (provider.getSortOrder() == 0) {
                provider.setEnabled(1);
                provider.setIsDefault(1);
            }
            aiConfigProviderMapper.insert(provider);
        } else {
            aiConfigProviderMapper.updateById(provider);
        }
        return true;
    }

    /** 删除提供商；生效中或仅剩一条时不允许删除 */
    public boolean deleteProvider(Long id) {
        if (id == null) {
            return false;
        }
        AIConfigProvider provider = aiConfigProviderMapper.selectById(id);
        if (provider == null) {
            return false;
        }
        if (provider.getIsDefault() != null && provider.getIsDefault() == 1) {
            return false;
        }
        Long total = aiConfigProviderMapper.selectCount(null);
        if (total != null && total <= 1) {
            return false;
        }
        aiConfigProviderMapper.deleteById(id);
        return true;
    }

    /** 设为全局唯一生效提供商（目标必须为启用状态） */
    public boolean setDefaultProvider(Long id) {
        if (id == null) {
            return false;
        }
        AIConfigProvider provider = aiConfigProviderMapper.selectById(id);
        if (provider == null || provider.getEnabled() == null || provider.getEnabled() != 1) {
            return false;
        }
        AIConfigProvider clear = new AIConfigProvider();
        clear.setIsDefault(0);
        clear.setUpdateTime(LocalDateTime.now());
        aiConfigProviderMapper.update(clear,
                new LambdaQueryWrapper<AIConfigProvider>().eq(AIConfigProvider::getIsDefault, 1));

        provider.setIsDefault(1);
        provider.setUpdateTime(LocalDateTime.now());
        aiConfigProviderMapper.updateById(provider);
        return true;
    }

    /** 启用 / 停用切换；生效中提供商不允许停用 */
    public boolean toggleProvider(Long id) {
        if (id == null) {
            return false;
        }
        AIConfigProvider provider = aiConfigProviderMapper.selectById(id);
        if (provider == null) {
            return false;
        }
        boolean currentlyEnabled = provider.getEnabled() != null && provider.getEnabled() == 1;
        if (currentlyEnabled && provider.getIsDefault() != null && provider.getIsDefault() == 1) {
            return false;
        }
        provider.setEnabled(currentlyEnabled ? 0 : 1);
        provider.setUpdateTime(LocalDateTime.now());
        aiConfigProviderMapper.updateById(provider);
        return true;
    }

    /** 设置某提供商的当前生效模型（必须在其 models 列表内） */
    public boolean setCurrentModel(Long id, String model) {
        if (id == null || model == null || model.trim().isEmpty()) {
            return false;
        }
        AIConfigProvider provider = aiConfigProviderMapper.selectById(id);
        if (provider == null) {
            return false;
        }
        List<String> modelList = normalizeModels(provider.getModels());
        if (!modelList.contains(model.trim())) {
            return false;
        }
        provider.setCurrentModel(model.trim());
        provider.setUpdateTime(LocalDateTime.now());
        aiConfigProviderMapper.updateById(provider);
        return true;
    }

    /**
     * 取当前生效提供商运行时配置 {name, apiUrl, apiKey, model}。
     * model = current_model（为空或非法时取 models 第一个）；无生效提供商返回 null（调用方走兜底/未启用提示）。
     */
    public Map<String, Object> getActiveProviderRuntime() {
        try {
            AIConfigProvider provider = aiConfigProviderMapper.selectOne(
                    new LambdaQueryWrapper<AIConfigProvider>()
                            .eq(AIConfigProvider::getIsDefault, 1)
                            .eq(AIConfigProvider::getEnabled, 1)
                            .last("LIMIT 1"));
            if (provider == null) {
                return null;
            }
            List<String> modelList = normalizeModels(provider.getModels());
            String model = provider.getCurrentModel();
            if (model == null || model.isEmpty() || !modelList.contains(model)) {
                model = modelList.isEmpty() ? "" : modelList.get(0);
            }
            Map<String, Object> runtime = new LinkedHashMap<>();
            runtime.put("name", provider.getName());
            runtime.put("apiUrl", provider.getApiUrl());
            runtime.put("apiKey", apiKeyCipher.decrypt(provider.getApiKey() != null ? provider.getApiKey() : ""));
            runtime.put("model", model);
            return runtime;
        } catch (Exception e) {
            System.err.println("AIConfigService: 读取生效提供商失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 多模态识图能力判定（用于管理端 AI 识图导入课表）：
     * 取当前生效提供商（is_default=1 且启用）的 current_model（空则取 models 第一个），
     * 该模型名在 multimodal_models 列表内 → 可识图。
     * 返回 {supported, model, providerName, multimodalModels}；无生效提供商返回 null。
     */
    public Map<String, Object> getMultimodalStatus() {
        try {
            AIConfigProvider provider = aiConfigProviderMapper.selectOne(
                    new LambdaQueryWrapper<AIConfigProvider>()
                            .eq(AIConfigProvider::getIsDefault, 1)
                            .eq(AIConfigProvider::getEnabled, 1)
                            .last("LIMIT 1"));
            if (provider == null) {
                return null;
            }
            List<String> modelList = normalizeModels(provider.getModels());
            String model = provider.getCurrentModel();
            if (model == null || model.isEmpty() || !modelList.contains(model)) {
                model = modelList.isEmpty() ? "" : modelList.get(0);
            }
            List<String> multimodalList = normalizeModels(provider.getMultimodalModels());
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("supported", model != null && !model.isEmpty() && multimodalList.contains(model));
            status.put("model", model);
            status.put("providerName", provider.getName());
            status.put("multimodalModels", multimodalList);
            return status;
        } catch (Exception e) {
            System.err.println("AIConfigService: 读取多模态能力失败: " + e.getMessage());
            return null;
        }
    }

    /** 脱敏视图：apiKey 解密后仅保留前 4 位和后 4 位 */
    private Map<String, Object> toProviderView(AIConfigProvider p) {
        String plainKey = apiKeyCipher.decrypt(p.getApiKey());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", p.getId());
        view.put("name", p.getName());
        view.put("apiUrl", p.getApiUrl());
        view.put("apiKey", apiKeyCipher.mask(plainKey));
        view.put("hasKey", plainKey != null && !plainKey.isEmpty());
        view.put("models", normalizeModels(p.getModels()));
        List<String> modelList = normalizeModels(p.getModels());
        String current = p.getCurrentModel();
        if (current == null || current.isEmpty() || !modelList.contains(current)) {
            current = modelList.isEmpty() ? "" : modelList.get(0);
        }
        view.put("currentModel", current);
        view.put("multimodalModels", normalizeModels(p.getMultimodalModels()));
        view.put("enabled", p.getEnabled() != null && p.getEnabled() == 1);
        view.put("isDefault", p.getIsDefault() != null && p.getIsDefault() == 1);
        view.put("sortOrder", p.getSortOrder());
        return view;
    }

    /** 逗号（含中文逗号）分隔，去空格、去重、保序 */
    private List<String> normalizeModels(String models) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (models != null) {
            for (String m : models.split("[,，]")) {
                String trimmed = m.trim();
                if (!trimmed.isEmpty()) {
                    set.add(trimmed);
                }
            }
        }
        return new ArrayList<>(set);
    }
}
