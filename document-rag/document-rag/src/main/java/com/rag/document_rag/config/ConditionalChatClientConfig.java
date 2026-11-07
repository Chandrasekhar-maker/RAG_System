package com.rag.document_rag.config;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;

import reactor.core.publisher.Flux;

/**
 * Intelligent Dual-Model ChatClient Configuration:
 * 1. Primary Preference: Google Gemini (gemini-3.8-flash)
 * 2. Automatic Shift: If Gemini token limit or quota is completed / exceeded / unavailable, shifts to Qwen (Ollama)
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
            @Value("${spring.ai.ollama.chat.options.model:${spring.ai.ollama.chat.model:${OLLAMA_CHAT_MODEL:qwen2.5:3b-instruct}}}") String ollamaModelName) {

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
            public ChatOptions getOptions() {
                GoogleGenAiChatModel geminiModel = geminiChatModelProvider.getIfAvailable();
                if (isGeminiConfigured() && geminiModel != null && geminiModel.getOptions() != null) {
                    return geminiModel.getOptions();
                }
                OllamaChatModel ollamaModel = getOllamaModel();
                if (ollamaModel != null && ollamaModel.getOptions() != null) {
                    return ollamaModel.getOptions();
                }
                return OllamaChatOptions.builder().model(ollamaModelName).build();
            }

            @Override
            @SuppressWarnings("deprecation")
            public ChatOptions getDefaultOptions() {
                return getOptions();
            }

            private Prompt preparePromptForGemini(Prompt prompt, GoogleGenAiChatModel geminiModel) {
                if (prompt.getOptions() instanceof GoogleGenAiChatOptions) {
                    return prompt;
                }

                GoogleGenAiChatOptions defaultOptions = geminiModel.getOptions();
                GoogleGenAiChatOptions.Builder builder = (defaultOptions != null)
                        ? defaultOptions.mutate()
                        : GoogleGenAiChatOptions.builder();

                ChatOptions existing = prompt.getOptions();
                if (existing != null) {
                    if (existing.getTemperature() != null) {
                        builder.temperature(existing.getTemperature());
                    }
                    if (existing.getTopP() != null) {
                        builder.topP(existing.getTopP());
                    }
                    if (existing.getTopK() != null) {
                        builder.topK(existing.getTopK());
                    }
                    if (StringUtils.hasText(existing.getModel())
                            && !existing.getModel().toLowerCase().contains("qwen")
                            && !existing.getModel().toLowerCase().contains("ollama")) {
                        builder.model(existing.getModel());
                    }
                }

                return prompt.mutate().chatOptions(builder.build()).build();
            }

            private Prompt preparePromptForOllama(Prompt prompt, OllamaChatModel ollamaModel) {
                OllamaChatOptions defaultOptions = (ollamaModel != null && ollamaModel.getOptions() instanceof OllamaChatOptions oco)
                        ? oco
                        : null;
                OllamaChatOptions.Builder builder = (defaultOptions != null)
                        ? defaultOptions.mutate()
                        : OllamaChatOptions.builder();

                // Explicitly set the Ollama model so verifyPromptChatOptions never receives null or empty model
                builder.model(ollamaModelName);

                ChatOptions existing = prompt.getOptions();
                if (existing != null) {
                    if (existing.getTemperature() != null) {
                        builder.temperature(existing.getTemperature());
                    }
                    if (existing.getTopP() != null) {
                        builder.topP(existing.getTopP());
                    }
                    if (existing.getTopK() != null) {
                        builder.topK(existing.getTopK());
                    }
                }

                return prompt.mutate().chatOptions(builder.build()).build();
            }

            private boolean isProgrammingError(Throwable ex) {
                Throwable current = ex;
                while (current != null) {
                    if (current instanceof ClassCastException
                            || current instanceof NullPointerException
                            || current instanceof IndexOutOfBoundsException
                            || current instanceof ArrayStoreException
                            || current instanceof UnsupportedOperationException) {
                        return true;
                    }
                    current = current.getCause();
                }
                return false;
            }

            private boolean isFallbackEligible(Throwable ex) {
                if (isProgrammingError(ex)) {
                    return false;
                }

                Throwable current = ex;
                while (current != null) {
                    if (current instanceof ApiException apiEx) {
                        int code = apiEx.code();
                        return code == 429 || code == 408 || code == 400 || code == 403 || code >= 500;
                    }
                    if (current instanceof GenAiIOException) {
                        return true;
                    }
                    if (current instanceof TransientAiException
                            || current instanceof NonTransientAiException) {
                        return true;
                    }
                    if (current instanceof IOException) {
                        return true;
                    }

                    String msg = current.getMessage();
                    if (msg != null) {
                        String lower = msg.toLowerCase();
                        if (lower.contains("quota")
                                || lower.contains("rate limit")
                                || lower.contains("ratelimit")
                                || lower.contains("token")
                                || lower.contains("resource_exhausted")
                                || lower.contains("resource has been exhausted")
                                || lower.contains("too many requests")
                                || lower.contains("429")
                                || lower.contains("503")
                                || lower.contains("500")
                                || lower.contains("502")
                                || lower.contains("504")
                                || lower.contains("unavailable")
                                || lower.contains("overloaded")
                                || lower.contains("timeout")
                                || lower.contains("timed out")
                                || lower.contains("connection refused")
                                || lower.contains("connection reset")
                                || lower.contains("service unavailable")) {
                            return true;
                        }
                    }
                    current = current.getCause();
                }
                return false;
            }

            @Override
            public ChatResponse call(Prompt prompt) {
                Throwable geminiFailure = null;

                // First preference: Google Gemini
                if (isGeminiConfigured()) {
                    GoogleGenAiChatModel geminiModel = geminiChatModelProvider.getIfAvailable();
                    if (geminiModel != null) {
                        try {
                            log.info("Generating response with Primary model: Google Gemini...");
                            Prompt geminiPrompt = preparePromptForGemini(prompt, geminiModel);
                            return geminiModel.call(geminiPrompt);
                        } catch (Exception ex) {
                            if (!isFallbackEligible(ex)) {
                                log.error("Gemini invocation failed due to an unrelated programming or prompt error: {}",
                                        ex.getMessage(), ex);
                                if (ex instanceof RuntimeException re) {
                                    throw re;
                                }
                                throw new RuntimeException("Error during Gemini invocation", ex);
                            }
                            geminiFailure = ex;
                            log.warn("Gemini call failed due to provider/quota/network issue ({}). Shifting to Fallback model: Qwen (Ollama - {})...",
                                    ex.getMessage(), ollamaModelName);
                        }
                    } else {
                        log.warn("GoogleGenAiChatModel bean not available. Shifting to Fallback model: Qwen (Ollama - {})...", ollamaModelName);
                        geminiFailure = new IllegalStateException("GoogleGenAiChatModel bean not available in application context");
                    }
                } else {
                    log.info("Gemini API key not configured or set to dummy. Shifting directly to Fallback model: Qwen (Ollama - {})...", ollamaModelName);
                }

                // Fallback / Shift: Qwen via Ollama
                try {
                    log.info("Generating response with Fallback model: Qwen (Ollama - {})...", ollamaModelName);
                    OllamaChatModel ollamaModel = getOllamaModel();
                    Prompt ollamaPrompt = preparePromptForOllama(prompt, ollamaModel);
                    return ollamaModel.call(ollamaPrompt);
                } catch (Exception ollamaEx) {
                    log.error("Fallback model (Qwen/Ollama - {}) also failed: {}", ollamaModelName, ollamaEx.getMessage(), ollamaEx);
                    if (geminiFailure != null) {
                        IllegalStateException failure = new IllegalStateException(
                                "Both AI models failed. Primary model (Google Gemini) error: [" + geminiFailure.getMessage()
                                + "]. Fallback model (Qwen/Ollama - " + ollamaModelName + ") error: [" + ollamaEx.getMessage() + "].",
                                ollamaEx);
                        failure.addSuppressed(geminiFailure);
                        throw failure;
                    }
                    if (ollamaEx instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new RuntimeException("Ollama invocation failed: " + ollamaEx.getMessage(), ollamaEx);
                }
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                Throwable geminiFailure = null;

                if (isGeminiConfigured()) {
                    GoogleGenAiChatModel geminiModel = geminiChatModelProvider.getIfAvailable();
                    if (geminiModel != null) {
                        try {
                            log.info("Streaming response with Primary model: Google Gemini...");
                            Prompt geminiPrompt = preparePromptForGemini(prompt, geminiModel);
                            return geminiModel.stream(geminiPrompt);
                        } catch (Exception ex) {
                            if (!isFallbackEligible(ex)) {
                                log.error("Gemini streaming failed due to an unrelated programming error: {}", ex.getMessage(), ex);
                                throw ex;
                            }
                            geminiFailure = ex;
                            log.warn("Gemini streaming failed ({}). Shifting to Qwen (Ollama - {})...",
                                    ex.getMessage(), ollamaModelName);
                        }
                    } else {
                        log.warn("GoogleGenAiChatModel bean not available. Shifting to Qwen (Ollama - {})...", ollamaModelName);
                        geminiFailure = new IllegalStateException("GoogleGenAiChatModel bean not available");
                    }
                } else {
                    log.info("Gemini API key not configured or set to dummy. Shifting directly to Qwen (Ollama - {})...", ollamaModelName);
                }

                try {
                    log.info("Streaming response with Fallback model: Qwen (Ollama - {})...", ollamaModelName);
                    OllamaChatModel ollamaModel = getOllamaModel();
                    Prompt ollamaPrompt = preparePromptForOllama(prompt, ollamaModel);
                    return ollamaModel.stream(ollamaPrompt);
                } catch (Exception ollamaEx) {
                    log.error("Fallback model (Qwen/Ollama) streaming failed: {}", ollamaEx.getMessage(), ollamaEx);
                    if (geminiFailure != null) {
                        IllegalStateException failure = new IllegalStateException(
                                "Both AI models failed. Primary model (Google Gemini) error: [" + geminiFailure.getMessage()
                                + "]. Fallback model (Qwen/Ollama - " + ollamaModelName + ") error: [" + ollamaEx.getMessage() + "].",
                                ollamaEx);
                        failure.addSuppressed(geminiFailure);
                        throw failure;
                    }
                    throw ollamaEx;
                }
            }
        };

        return ChatClient.builder(resilientChatModel);
    }
}
