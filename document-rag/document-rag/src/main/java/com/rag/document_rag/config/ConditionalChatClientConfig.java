package com.rag.document_rag.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configures a {@link ChatClient.Builder} that delegates to the appropriate chat model
 * (Gemini via {@link GoogleGenAiChatModel} or local Ollama) based on the {@code ai.provider}
 * property. This keeps the RAG controller independent of the underlying provider.
 */
@Configuration
public class ConditionalChatClientConfig {

    @Bean
    @Primary
    public ChatClient.Builder chatClientBuilder(@Value("${ai.provider:gemini}") String provider,
                                                ObjectProvider<GoogleGenAiChatModel> geminiChatModelProvider,
                                                ObjectProvider<OllamaChatModel> ollamaChatModelProvider,
                                                @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
                                                @Value("${spring.ai.ollama.chat.options.model:qwen2.5:3b-instruct}") String ollamaModelName) {
        ChatModel selectedModel;
        if ("ollama".equalsIgnoreCase(provider)) {
            OllamaChatModel ollamaChatModel = ollamaChatModelProvider.getIfAvailable();
            if (ollamaChatModel == null) {
                // If auto-configured bean is not present, construct OllamaChatModel directly using OllamaApi
                OllamaApi ollamaApi = OllamaApi.builder()
                        .baseUrl(ollamaBaseUrl)
                        .build();
                OllamaChatOptions options = OllamaChatOptions.builder()
                        .model(ollamaModelName)
                        .build();
                ollamaChatModel = OllamaChatModel.builder()
                        .ollamaApi(ollamaApi)
                        .options(options)
                        .build();
            }
            selectedModel = ollamaChatModel;
        } else {
            GoogleGenAiChatModel geminiChatModel = geminiChatModelProvider.getIfAvailable();
            if (geminiChatModel == null) {
                throw new IllegalStateException("Gemini chat model bean not available while provider set to gemini");
            }
            selectedModel = geminiChatModel;
        }
        return ChatClient.builder(selectedModel);
    }
}
