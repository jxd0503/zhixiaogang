package com.zhixiaogang.service;

/** AI 服务抽象：统一的对话接口，供不同模型实现。 */
public interface AIService {

    String chat(String message, String studentId);

    String getName();

    boolean isEnabled();
}
