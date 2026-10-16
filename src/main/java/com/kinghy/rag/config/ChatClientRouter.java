package com.kinghy.rag.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 按模型名选择调用哪条链路：
 * 默认走 DashScope 的 OpenAI 兼容模式（新模型如 qwen3.8-27b 只能走这里）；
 * 在配置 kinghy.ai.native-models 里列出的模型才走 DashScope 原生接口。
 *
 * 默认配置下不会调用原生接口，因此不会触发 qwen-plus 等收费模型的默认调用。
 */
@Component
public class ChatClientRouter {

    private final ChatClient compatibleClient;
    private final ChatClient nativeClient;
    private final Set<String> nativeModels;

    public ChatClientRouter(
            @Qualifier("compatibleChatModel") ChatModel compatibleChatModel,
            @Qualifier("dashscopeChatModel") ChatModel nativeChatModel,
            VectorStore vectorStore,
            ChatMemory chatMemory,
            @Value("${kinghy.ai.native-models:}") String nativeModels) {

        this.compatibleClient = buildClient(compatibleChatModel, vectorStore, chatMemory);
        this.nativeClient = buildClient(nativeChatModel, vectorStore, chatMemory);
        this.nativeModels = Arrays.stream(nativeModels.split(","))
                .map(String::trim)
                .filter(model -> !model.isEmpty())
                .collect(Collectors.toCollection(HashSet::new));
    }

    private ChatClient buildClient(ChatModel model, VectorStore vectorStore, ChatMemory chatMemory) {
        return ChatClient.builder(model)
                .defaultSystem("你是一个乐于助人、回答准确的AI助手")
                .defaultAdvisors(
                        new MessageChatMemoryAdvisor(chatMemory), // 多轮对话记忆
                        new QuestionAnswerAdvisor(vectorStore, SearchRequest.builder().build())) // 向量库检索
                .build();
    }

    public boolean isNative(String model) {
        return nativeModels.contains(model);
    }

    public ChatClient client(String model) {
        return isNative(model) ? nativeClient : compatibleClient;
    }

    /**
     * 两条链路的 options 类型不同，必须按模型来源返回对应类型
     */
    public ChatOptions options(String model) {
        return isNative(model)
                ? DashScopeChatOptions.builder().withModel(model).build()
                : OpenAiChatOptions.builder().withModel(model).build();
    }
}