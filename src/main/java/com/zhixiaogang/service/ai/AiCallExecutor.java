package com.zhixiaogang.service.ai;

import com.zhixiaogang.service.impl.QwenAIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * AI 调用执行器：所有模型调用统一在守护线程池中执行并施加单次等待上限，
 * 超时后自动重试一次，仍失败则返回原因供调用方降级或提示，避免请求线程无限阻塞。
 */
@Service
public class AiCallExecutor {

    /** 文本调用单次等待上限（秒），小于 QwenAIService 的 100 秒请求兜底，保证先由执行器判定超时 */
    public static final long TEXT_TIMEOUT_SECONDS = 90L;

    /** 识图调用单次等待上限（秒），略大于 QwenAIService 的 120 秒请求兜底，让内部超时先返回具体提示 */
    public static final long IMAGE_TIMEOUT_SECONDS = 125L;

    /** AI 并发上限：避免大量请求同时占用线程并打满 Tomcat 线程池 */
    private static final int POOL_SIZE = 8;

    /** 等待队列上限：队列满时快速失败，防止请求无限堆积 */
    private static final int QUEUE_CAPACITY = 32;

    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            POOL_SIZE, POOL_SIZE, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r, "ai-call-worker");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    @Autowired
    private QwenAIService qwenAIService;

    /** 文本调用（默认 90 秒上限） */
    public CallResult callText(String message, String studentId, String context) {
        return callText(message, studentId, context, TEXT_TIMEOUT_SECONDS);
    }

    /** 文本调用：超时后自动重试一次 */
    public CallResult callText(String message, String studentId, String context, long timeoutSeconds) {
        long begin = System.currentTimeMillis();
        CallResult result = doText(message, studentId, context, timeoutSeconds);
        if (!result.success && "timeout".equals(result.errorType)) {
            result = doText(message, studentId, context, timeoutSeconds);
            result.retried = true;
        }
        result.costMs = System.currentTimeMillis() - begin;
        return result;
    }

    /** 文本单次调用：不重试，用于预览类场景，避免失败时等待翻倍 */
    public CallResult callTextOnce(String message, String studentId, String context, long timeoutSeconds) {
        long begin = System.currentTimeMillis();
        CallResult result = doText(message, studentId, context, timeoutSeconds);
        result.costMs = System.currentTimeMillis() - begin;
        return result;
    }

    /** 识图调用：超时后自动重试一次 */
    public CallResult callImage(String instruction, String base64Image, String mimeType) {
        long begin = System.currentTimeMillis();
        CallResult result = doImage(instruction, base64Image, mimeType);
        if (!result.success && "timeout".equals(result.errorType)) {
            result = doImage(instruction, base64Image, mimeType);
            result.retried = true;
        }
        result.costMs = System.currentTimeMillis() - begin;
        return result;
    }

    private CallResult doText(String message, String studentId, String context, long timeoutSeconds) {
        CallResult result = new CallResult();
        Future<Map<String, Object>> future;
        try {
            future = EXECUTOR.submit(() -> qwenAIService.chatWithContextDetailed(message, studentId, null, context));
        } catch (RejectedExecutionException e) {
            result.errorType = "busy";
            result.errorText = "AI 服务繁忙，请稍后重试";
            return result;
        }
        try {
            Map<String, Object> raw = future.get(timeoutSeconds, TimeUnit.SECONDS);
            apply(result, raw);
        } catch (TimeoutException e) {
            // 取消并中断底层请求，尽快释放工作线程，避免超时任务继续占满线程池
            future.cancel(true);
            result.errorType = "timeout";
            result.errorText = "模型响应超时（已等待 " + timeoutSeconds + " 秒）";
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            result.errorType = "timeout";
            result.errorText = "模型调用被中断";
        } catch (ExecutionException e) {
            result.errorType = "unknown";
            result.errorText = "模型调用异常，请稍后重试";
        }
        return result;
    }

    private CallResult doImage(String instruction, String base64Image, String mimeType) {
        CallResult result = new CallResult();
        Future<Map<String, Object>> future;
        try {
            future = EXECUTOR.submit(() -> qwenAIService.chatWithImageDetailed(instruction, base64Image, mimeType));
        } catch (RejectedExecutionException e) {
            result.errorType = "busy";
            result.errorText = "AI 服务繁忙，请稍后重试";
            return result;
        }
        try {
            Map<String, Object> raw = future.get(IMAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            apply(result, raw);
        } catch (TimeoutException e) {
            future.cancel(true);
            result.errorType = "timeout";
            result.errorText = "模型响应超时（已等待 " + QwenAIService.IMAGE_REQUEST_TIMEOUT_SECONDS
                    + " 秒），图片较大时处理更慢，请重试";
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            result.errorType = "timeout";
            result.errorText = "模型调用被中断";
        } catch (ExecutionException e) {
            result.errorType = "unknown";
            result.errorText = "模型调用异常，请稍后重试";
        }
        return result;
    }

    private void apply(CallResult result, Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            result.errorType = "unknown";
            result.errorText = "模型调用无返回";
            return;
        }
        result.success = Boolean.TRUE.equals(raw.get("success"));
        result.content = (String) raw.get("content");
        result.errorType = (String) raw.get("errorType");
        result.errorText = (String) raw.get("error");
        if (!result.success && result.errorType == null) {
            result.errorType = "unknown";
        }
    }

    /** 单次调用结果（含自动重试后的最终结果） */
    public static class CallResult {
        public boolean success;
        public String content;
        /** disabled / timeout / network / http / format / unknown */
        public String errorType;
        public String errorText;
        /** 总耗时（毫秒，含自动重试） */
        public long costMs = -1L;
        /** 是否因超时自动重试过 */
        public boolean retried;
    }
}
