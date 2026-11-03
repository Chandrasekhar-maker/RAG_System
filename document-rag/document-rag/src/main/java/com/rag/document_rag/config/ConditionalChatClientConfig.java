package com.rag.document_rag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
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
 * Intelligent Dual-Model ChatClient Configuration:
 * 1. Primary Preference: Google Gemini (gemini-3.8-flash)
 * 2. Automatic Shift: If Gemini token limit or quota is completed / exceeded, shifts to Qwen (Ollama)
 */
@Configuration
public class ConditionalChatClientConfig {

    private static final Logger log = LoggerFactory.getLogger(ConditionalChatClientConfig.class);

    @Bean
    @Primary
    public ChatClient.Builder chatClientBuilder(
            ObjectProvider<GoogleGenAiChatModel> geminiChatModelProvider,
            ObjectProvider<OllamaChatModel> ollamaChatModelProvider,
            @Value("${spring.ai.google.genai.api-key:${GEMINI_API_KEY:}}") String geminiApiKey,
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
            @Value("${spring.ai.ollama.chat.options.model:qwen2.5:3b-instruct}") String ollamaModelName) {

        ChatModel resilientChatModel = new ChatModel() {
            private volatile OllamaChatModel fallbackOllamaChatModel;

            private boolean isGeminiConfigured() {
                return geminiApiKey != null
                        && !geminiApiKey.isBlank()
                        && !geminiApiKey.equalsIgnoreCase("dummy")
                        && !geminiApiKey.contains("your_gemini_api_key");
            }

            private OllamaChatModel getOllamaModel() {
                OllamaChatModel model = ollamaChatModelProvider.getIfAvailable();
                if (model == null) {
                    if (fallbackOllamaChatModel == null) {
                        synchronized (this) {
                            if (fallbackOllamaChatModel == null) {
                                OllamaApi ollamaApi = OllamaApi.builder()
                                        .baseUrl(ollamaBaseUrl)
                                        .build();
                                OllamaChatOptions options = OllamaChatOptions.builder()
                                        .model(ollamaModelName)
                                        .build();
                                fallbackOllamaChatModel = OllamaChatModel.builder()
                                        .ollamaApi(ollamaApi)
                                        .options(options)
                                        .build();
                            }
                        }
                    }
                    return fallbackOllamaChatModel;
                }
                return model;
            }

            @Override
            public ChatResponse call(Prompt prompt) {
                // First preference: Google Gemini
                if (isGeminiConfigured()) {
                    GoogleGenAiChatModel geminiModel = geminiChatModelProvider.getIfAvailable();
                    if (geminiModel != null) {
                        try {
                            log.info("Generating response with Primary model: Google Gemini...");
                            return geminiModel.call(prompt);
                        } catch (Exception ex) {
                            log.warn("Gemini call failed or token/quota limit completed ({}). Shifting to Qwen (Ollama)...",
                                    ex.getMessage());
                        }
                    } else {
                        log.warn("GeminiChatModel bean not available. Shifting to Qwen (Ollama)...");
                    }
                } else {
                    log.info("Gemini API key not configured or set to dummy. Shifting directly to Qwen (Ollama)...");
                }

                // Fallback / Shift: Qwen via Ollama
                log.info("Generating response with Fallback model: Qwen (Ollama - {})...", ollamaModelName);
                return getOllamaModel().call(prompt);
            }
        };

        return ChatClient.builder(resilientChatModel);
    }
}
