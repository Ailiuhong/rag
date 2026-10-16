package com.kinghy.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

/**
 * 通义千问 OpenAI 兼容模式接入。
 *
 * 背景：spring-ai-alibaba 走的是 DashScope 原生接口
 * (/api/v1/services/aigc/text-generation/generation)，
 * 新模型（如 qwen3.8-27b）在该接口上会返回 "url error"，
 * SDK 拿到没有 output 的错误报文后直接 NPE。
 * 这里额外接入兼容模式 (/compatible-mode/v1/chat/completions)，
 * 并设为默认 ChatModel。
 */
@Configuration
@Slf4j
public class CompatibleChatModelConfig {

    /** DashScope 的 OpenAI 兼容模式地址 */
    private static final String COMPATIBLE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode";

    @Bean
    @Primary
    public OpenAiChatModel compatibleChatModel(
            @Value("${kinghy.dashscope.api-key:}") String apiKey,
            @Value("${kinghy.ai.compatible-model:qwen3.8-27b}") String defaultModel) {

        if (!StringUtils.hasText(apiKey)) {
            log.warn("未读取到 kinghy.dashscope.api-key，模型调用会返回 401。"
                    + "请确认项目根目录下存在 config/secrets.yml（该文件已被 .gitignore 排除）");
        }

        // 第一个参数是 baseUrl，第二个是 apiKey（内部会拼成 Bearer 认证头）
        OpenAiApi openAiApi = new OpenAiApi(COMPATIBLE_BASE_URL, apiKey);

        return new OpenAiChatModel(openAiApi, OpenAiChatOptions.builder()
                .withModel(defaultModel)
                .build());
    }
}
