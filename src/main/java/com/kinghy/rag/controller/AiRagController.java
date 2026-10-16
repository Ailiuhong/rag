/*
 * Copyright 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.kinghy.rag.controller;

import com.alibaba.cloud.ai.advisor.RetrievalRerankAdvisor;
import com.alibaba.cloud.ai.model.RerankModel;
import com.kinghy.rag.annotation.Loggable;
import com.kinghy.rag.common.ApplicationConstant;
import com.kinghy.rag.common.ErrorCode;
import com.kinghy.rag.exception.BusinessException;
import com.kinghy.rag.service.SensitiveWordFilter;
import com.kinghy.rag.utils.SearchUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY;
import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_RETRIEVE_SIZE_KEY;

@Tag(name = "AiRagController", description = "Rag接口")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/ai")
public class AiRagController {

    private final ChatClient chatClient;
    private final SearchUtils searchUtils;
    private final SensitiveWordFilter sensitiveWordFilter;
    private final ChatMemory chatMemory = new InMemoryChatMemory(); // 生产换 Redis

    public AiRagController(VectorStore vectorStore, ChatModel chatModel,
                           RerankModel rerankModel, SearchUtils searchUtils,
                           SensitiveWordFilter sensitiveWordFilter,
                           @Value("classpath:/prompts/system-qa.st") Resource systemResource) throws IOException {

        this.searchUtils = searchUtils;
        this.sensitiveWordFilter = sensitiveWordFilter;

        // prompt、ChatClient 只在构造期初始化一次
        String promptTemplate = systemResource.getContentAsString(StandardCharsets.UTF_8);
        SearchRequest searchRequest = SearchRequest.builder().topK(5).build();

        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new RetrievalRerankAdvisor(
                        vectorStore, rerankModel, searchRequest, promptTemplate, 0.1))
                .build();
    }

    @Operation(summary = "rag", description = "Rag对话接口")
    @GetMapping(value = "/rag", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Loggable
    public Flux<String> generate(
            @RequestParam(value = "message", defaultValue = "你好") String message,
            @RequestParam(value = "conversationId", required = false) String conversationId,
            HttpSession session) {

        // 敏感词校验放流里，别同步抛异常
        String hitWord = sensitiveWordFilter.match(message);
        if (hitWord != null) {
            return Flux.just("[SENSITIVE] 包含敏感词:" + hitWord);
        }

        String chatId = StringUtils.hasText(conversationId) ? conversationId : session.getId();

        // 联网搜索只作兜底：异常/超时不影响主链路
        String webContext = "";
        try {
            List<Map<String, String>> maps = searchUtils.tavilySearch(message);
            StringBuilder sb = new StringBuilder();
            for (Map<String, String> map : maps) {
                sb.append("\n").append(map.get("title")).append("\n").append(map.get("content"));
            }
            webContext = sb.toString();
        } catch (Exception e) {
            log.warn("联网搜索失败，走纯向量兜底：{}", e.getMessage());
        }

        return chatClient.prompt()
                .system("如果向量库已包含用户提问的信息，回答时不使用网络来源。网络来源如下：" + webContext)
                .advisors(a -> a
                        .param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
                .user(message)
                .stream()
                .content();
    }
}