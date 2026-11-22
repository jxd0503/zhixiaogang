package com.zhixiaogang.service.impl;

import com.zhixiaogang.service.AIConfigService;
import com.zhixiaogang.service.AIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 通义千问（DashScope）对话实现，含超时取消与多模态处理。 */
@Service("qwenAIService")
public class QwenAIService implements AIService {

    @Autowired
    private AIConfigService aiConfigService;

    @Override
    public String chat(String message, String studentId) {
        return chat(message, studentId, null);
    }

    public String chat(String message, String studentId, String modelOverride) {
        return chatWithContext(message, studentId, modelOverride, null);
    }

    /** 文本调用请求超时（秒） */
    public static final long TEXT_REQUEST_TIMEOUT_SECONDS = 100L;

    /** 识图调用请求超时（秒），图片体积大时模型处理更慢 */
    public static final long IMAGE_REQUEST_TIMEOUT_SECONDS = 120L;

    public String chatWithContext(String message, String studentId, String modelOverride, String context) {
        java.util.Map<String, Object> r = chatWithContextDetailed(message, studentId, modelOverride, context);
        return Boolean.TRUE.equals(r.get("success")) ? (String) r.get("content") : "";
    }

    /**
     * 文本对话详细版：返回 {success, content, errorType, error}。
     * errorType 区分 disabled（未启用）、timeout（请求超时）、network（连接/网络异常）、
     * http（服务端非 2xx）、format（响应无法解析），供调用方给出准确提示。
     */
    public java.util.Map<String, Object> chatWithContextDetailed(String message, String studentId,
                                                                 String modelOverride, String context) {
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        if (!isEnabled()) {
            out.put("success", false);
            out.put("errorType", "disabled");
            out.put("error", "AI 功能未启用");
            return out;
        }

        // 始终使用管理端在「模型提供商管理」中指定的生效提供商与模型；
        // 前端传入的 modelOverride 仅保留在方法签名上以兼容旧调用链，不再参与选模。
        java.util.Map<String, String> runtime = resolveRuntime();
        String apiKey = runtime.get("apiKey");
        String apiUrl = runtime.get("apiUrl");
        String targetModel = runtime.get("model");

        System.out.println("AI请求使用提供商：" + runtime.get("name") + "，模型：" + targetModel);

        try {
            String systemPrompt = buildSystemPrompt(context);
            String jsonBody = String.format(
                "{\"model\":\"%s\",\"messages\":[{\"role\":\"system\",\"content\":\"%s\"},{\"role\":\"user\",\"content\":\"%s\"}],\"temperature\":0.7}",
                targetModel,
                escapeJson(systemPrompt),
                escapeJson(message)
            );

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(apiUrl))
                .timeout(java.time.Duration.ofSeconds(TEXT_REQUEST_TIMEOUT_SECONDS))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            java.net.http.HttpResponse<String> response = buildClient().send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                System.err.println("AI请求失败，HTTP " + status + "，响应摘要：" + truncate(response.body(), 300));
                out.put("success", false);
                out.put("errorType", "http");
                String detail = extractApiErrorMessage(response.body());
                out.put("error", status >= 500
                        ? "模型服务暂时不可用或响应异常，请稍后重试"
                        : (detail != null ? "模型请求被拒绝：" + detail : "模型请求被拒绝（HTTP " + status + "）"));
                return out;
            }
            String content = parseResponse(response.body());
            if (content == null || content.trim().isEmpty()) {
                out.put("success", false);
                out.put("errorType", "format");
                out.put("error", "模型响应为空或格式异常");
                return out;
            }
            out.put("success", true);
            out.put("content", content);
            return out;

        } catch (java.net.http.HttpConnectTimeoutException e) {
            out.put("success", false);
            out.put("errorType", "network");
            out.put("error", "连接模型服务超时，请检查网络或模型地址");
            return out;
        } catch (java.net.http.HttpTimeoutException e) {
            out.put("success", false);
            out.put("errorType", "timeout");
            out.put("error", "模型响应超时（已等待 " + TEXT_REQUEST_TIMEOUT_SECONDS + " 秒）");
            return out;
        } catch (Exception e) {
            System.err.println("AI请求异常：" + e.getMessage());
            out.put("success", false);
            out.put("errorType", "network");
            out.put("error", "模型服务暂时不可用或响应异常，请稍后重试");
            return out;
        }
    }

    /** 统一构建 HTTP 客户端：连接 10 秒兜底，防止模型端极端波动时请求无限挂起 */
    private java.net.http.HttpClient buildClient() {
        return java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();
    }

    /**
     * 多模态识图对话（OpenAI 兼容格式）：
     * user 消息 content 为数组，包含文本指令与图片（data:image/...;base64 内联）。
     * 模型名取当前生效提供商的生效模型（调用前需确认其为多模态模型）。
     * 返回模型回复文本；请求失败返回 null（由调用方给出友好提示）。
     */
    public String chatWithImage(String instruction, String base64Image, String mimeType) {
        java.util.Map<String, Object> r = chatWithImageDetailed(instruction, base64Image, mimeType);
        return Boolean.TRUE.equals(r.get("success")) ? (String) r.get("content") : null;
    }

    /**
     * 识图详细版：返回 {success, content, error}。
     * 模型返回的具体错误（4xx，如图片尺寸过小/格式不支持）透传 error；
     * 网络异常、超时、5xx 返回通用提示。
     */
    public java.util.Map<String, Object> chatWithImageDetailed(String instruction, String base64Image, String mimeType) {
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        if (!isEnabled()) {
            out.put("success", false);
            out.put("error", "AI 功能未启用");
            return out;
        }
        java.util.Map<String, String> runtime = resolveRuntime();
        String apiKey = runtime.get("apiKey");
        String apiUrl = runtime.get("apiUrl");
        String targetModel = runtime.get("model");

        System.out.println("AI识图请求使用提供商：" + runtime.get("name") + "，模型：" + targetModel);

        String jsonBody = "{\"model\":\"" + targetModel + "\",\"messages\":[{\"role\":\"user\",\"content\":"
                + "[{\"type\":\"text\",\"text\":\"" + escapeJson(instruction) + "\"},"
                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:" + mimeType
                + ";base64," + base64Image + "\"}}]}],\"temperature\":0.1}";

        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(apiUrl))
                .timeout(java.time.Duration.ofSeconds(IMAGE_REQUEST_TIMEOUT_SECONDS))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            java.net.http.HttpResponse<String> response = buildClient().send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                System.err.println("AI识图请求失败，HTTP " + status
                        + "，响应摘要：" + truncate(response.body(), 300));
                out.put("success", false);
                if (status >= 500) {
                    out.put("error", "模型服务暂时不可用或响应异常，请稍后重试");
                } else {
                    String detail = extractApiErrorMessage(response.body());
                    out.put("error", detail != null ? "识图失败：" + detail
                            : "识图请求被拒绝（HTTP " + status + "），请检查图片或模型配置");
                }
                return out;
            }
            String content = parseResponse(response.body());
            if (content == null) {
                out.put("success", false);
                out.put("error", "模型响应格式异常，无法解析识别结果");
                return out;
            }
            out.put("success", true);
            out.put("content", content);
            return out;
        } catch (java.net.http.HttpTimeoutException e) {
            System.err.println("AI识图请求超时：" + e.getMessage());
            out.put("success", false);
            out.put("errorType", "timeout");
            out.put("error", "模型响应超时（已等待 " + IMAGE_REQUEST_TIMEOUT_SECONDS + " 秒），图片较大时处理更慢，请重试");
            return out;
        } catch (Exception e) {
            System.err.println("AI识图请求异常：" + e.getMessage());
            out.put("success", false);
            out.put("errorType", "network");
            out.put("error", "模型服务暂时不可用或响应异常，请稍后重试");
            return out;
        }
    }

    /** 从 OpenAI 兼容错误响应中提取 error.message */
    private String extractApiErrorMessage(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            int i = body.indexOf("\"message\"");
            if (i < 0) return null;
            int colon = body.indexOf(':', i);
            if (colon < 0) return null;
            int start = body.indexOf('"', colon + 1);
            if (start < 0) return null;
            StringBuilder sb = new StringBuilder();
            for (int j = start + 1; j < body.length(); j++) {
                char c = body.charAt(j);
                if (c == '\\' && j + 1 < body.length()) {
                    sb.append(body.charAt(j + 1));
                    j++;
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            String msg = sb.toString().trim();
            return msg.isEmpty() ? null : msg;
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String buildSystemPrompt(String context) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是青岛港湾职业技术学院的智能校园助手，名字叫小智。");
        sb.append("你说话亲切友好。");
        sb.append("你可以帮助学生和教师查询课表、空教室等校园相关信息。");
        sb.append("称呼用户时严格遵循上下文【用户身份】中的说明；若上下文未说明，请用「你」称呼。");
        sb.append("请用简洁、自然、亲切的语言回答问题，避免使用生硬的机器语言。");
        sb.append("回复时尽量少用或不用表情符号，保持简洁专业。");

        if (context != null && !context.isEmpty()) {
            sb.append("\n\n以下是根据你的问题查询到的相关数据：\n");
            sb.append(context);
            sb.append("\n\n请根据以上数据回答用户的问题，确保回答准确无误。");
        }

        return sb.toString();
    }

    private String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }

    private String parseResponse(String responseBody) {
        try {
            // OpenAI兼容格式：{"choices":[{"message":{"content":"..."}}]}
            int choicesIndex = responseBody.indexOf("\"choices\"");
            if (choicesIndex != -1) {
                int messageIndex = responseBody.indexOf("\"message\"", choicesIndex);
                if (messageIndex != -1) {
                    int contentIndex2 = responseBody.indexOf("\"content\":\"", messageIndex);
                    if (contentIndex2 != -1) {
                        contentIndex2 += 11;
                        int endIndex = findContentEnd(responseBody, contentIndex2);
                        if (endIndex != -1) {
                            String content = responseBody.substring(contentIndex2, endIndex);
                            return unescapeJson(content);
                        }
                    }
                }
            }

            // 原始格式：{"output":{"text":"..."}}
            int outputIndex = responseBody.indexOf("\"output\"");
            if (outputIndex != -1) {
                int textIndex = responseBody.indexOf("\"text\":\"", outputIndex);
                if (textIndex != -1) {
                    textIndex += 8;
                    int endIndex = findContentEnd(responseBody, textIndex);
                    if (endIndex != -1) {
                        String content = responseBody.substring(textIndex, endIndex);
                        return unescapeJson(content);
                    }
                }
            }

            // 如果无法解析，返回原始响应（调试用）
            if (responseBody.length() < 500) {
                return "API响应解析失败，原始响应：" + responseBody;
            }
            return "";
        } catch (Exception e) {
            return "解析响应失败：" + e.getMessage();
        }
    }

    private int findContentEnd(String str, int start) {
        // 查找下一个未转义的引号
        int i = start;
        while (i < str.length()) {
            char c = str.charAt(i);
            if (c == '"') {
                // 检查是否被转义
                int backslashCount = 0;
                int j = i - 1;
                while (j >= start && str.charAt(j) == '\\') {
                    backslashCount++;
                    j--;
                }
                if (backslashCount % 2 == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    private String unescapeJson(String input) {
        if (input == null) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' && i + 1 < input.length()) {
                char next = input.charAt(i + 1);
                switch (next) {
                    case 'n': result.append('\n'); i++; break;
                    case 'r': result.append('\r'); i++; break;
                    case 't': result.append('\t'); i++; break;
                    case '"': result.append('"'); i++; break;
                    case '\\': result.append('\\'); i++; break;
                    default: result.append(c);
                }
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    @Override
    public String getName() {
        return "通义千问";
    }

    /**
     * 解析运行时三件套（apiUrl/apiKey/model）：
     * 优先取管理端指定的生效提供商（ai_provider 表）；
     * 不存在生效提供商时回退旧 ai_config 单行配置，兼容旧库。
     */
    private java.util.Map<String, String> resolveRuntime() {
        java.util.Map<String, String> runtime = new java.util.HashMap<>();
        java.util.Map<String, Object> active = aiConfigService.getActiveProviderRuntime();
        if (active != null) {
            runtime.put("name", String.valueOf(active.get("name")));
            runtime.put("apiUrl", String.valueOf(active.get("apiUrl")));
            runtime.put("apiKey", String.valueOf(active.get("apiKey")));
            runtime.put("model", String.valueOf(active.get("model")));
        } else {
            runtime.put("name", "通义千问(旧配置)");
            runtime.put("apiUrl", aiConfigService.getApiUrl());
            runtime.put("apiKey", aiConfigService.getApiKey());
            runtime.put("model", aiConfigService.getModel());
        }
        return runtime;
    }

    @Override
    public boolean isEnabled() {
        String apiKey = resolveRuntime().get("apiKey");
        return !aiConfigService.isAiDisabled() && apiKey != null && !apiKey.isEmpty();
    }

    public void disableAI() {
        aiConfigService.setAiDisabled(true);
    }

    public void enableAI() {
        aiConfigService.setAiDisabled(false);
    }

    public boolean isAiDisabled() {
        return aiConfigService.isAiDisabled();
    }
}
