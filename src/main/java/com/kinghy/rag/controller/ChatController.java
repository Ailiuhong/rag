package com.kinghy.rag.controller;

import com.kinghy.rag.annotation.Loggable;
import com.kinghy.rag.common.ErrorCode;
import com.kinghy.rag.config.ChatClientRouter;
import com.kinghy.rag.exception.BusinessException;
import com.kinghy.rag.service.SensitiveWordFilter;
import com.kinghy.rag.utils.SearchUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY;
import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_RETRIEVE_SIZE_KEY;

/**
 * @Title: ChatController
 * @Author KingHY
 * @Package com.kinghy.rag.controller
 * @description: 对话接口，模型名由 URL 路径决定
 */
@Tag(name = "ChatController", description = "对话接口")
@Slf4j
@RestController
public class ChatController {

    private final ChatClientRouter router;

    @Autowired
    private SearchUtils searchUtils;

    @Autowired
    private SensitiveWordFilter sensitiveWordFilter;

    public ChatController(ChatClientRouter router) {
        this.router = router;
    }

    /**
     * 流式对话接口，模型名写在路径里
     * 例如： /api/qwen3.8-27b/chat?message=你好
     */
    @Operation(summary = "chat", description = "流式对话接口，路径中的 model 决定调用哪个模型")
    @GetMapping(value = "/api/{model}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Loggable
    public Flux<String> streamRagChat(
            @Parameter(description = "模型名，例如 qwen3.8-27b", example = "qwen3.8-27b")
            @PathVariable("model") String model,
            @Parameter(description = "用户提问")
            @RequestParam(value = "message", defaultValue = "你好") String message,
            @Parameter(description = "系统提示词")
            @RequestParam(value = "prompt", defaultValue = "你是一名AI助手，致力于帮助人们解决问题.") String prompt,
            @Parameter(description = "会话ID，传相同的值即共享上下文；不传则按浏览器会话隔离")
            @RequestParam(value = "conversationId", required = false) String conversationId,
            HttpSession session) {

        // 前缀树匹配 + 本地缓存，避免每个请求都全表查询敏感词
        String hitWord = sensitiveWordFilter.match(message);
        if (hitWord != null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "包含敏感词:" + hitWord);
        }

        List<Map<String, String>> maps = searchUtils.tavilySearch(message);
        if (!maps.isEmpty()) {
            for (Map<String, String> map : maps) {
                message = message + "网络来源:" + "\n" + map.get("title") + "\n" + map.get("content");
            }
        }

        // 未指定会话ID时使用当前浏览器会话，避免所有用户共用同一份对话记忆
        String chatId = StringUtils.hasText(conversationId) ? conversationId : session.getId();

        return router.client(model).prompt()
                .options(router.options(model)) // 模型名来自路径参数
                .system(prompt)
                .advisors(a -> a
                        .param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 100))
                .user(message)
                .stream()
                .content();
    }
}
