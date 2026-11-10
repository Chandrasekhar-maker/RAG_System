package com.rag.document_rag.config;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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
 * Intelligent Dual-Model ChatClient Configuration with Two-Stage Human-Readability Refinement:
 * 1. Primary Preference: Google Gemini Flash-Lite
 * 2. Automatic Shift: If Gemini token limit or quota is completed / exceeded / unavailable, shifts to Qwen (Ollama)
 * 3. Stage-2 Refiner (Additive): Automatically refines technical Markdown/LaTeX and clumsy symbols into natural, readable prose.
 */
@Configuration
public class ConditionalChatClientConfig {

    private static final Logger log = LoggerFactory.getLogger(ConditionalChatClientConfig.class);

    private enum ModelSource {
        GEMINI,
        OLLAMA
    }

    private static class RawExecutionResult {
        private final ChatResponse response;
        private final ModelSource source;

        public RawExecutionResult(ChatResponse response, ModelSource source) {
            this.response = response;
            this.source = source;
        }

        public ChatResponse getResponse() {
            return response;
        }

        public ModelSource getSource() {
            return source;
        }
    }

    public ChatClient.Builder chatClientBuilder(
            ObjectProvider<GoogleGenAiChatModel> geminiChatModelProvider,
            ObjectProvider<OllamaChatModel> ollamaChatModelProvider,
            String geminiApiKey,
            String ollamaBaseUrl,
            String ollamaModelName) {
        return chatClientBuilder(geminiChatModelProvider, ollamaChatModelProvider, geminiApiKey, ollamaBaseUrl, ollamaModelName, true);
    }

    @Bean
    @Primary
    public ChatClient.Builder chatClientBuilder(
            ObjectProvider<GoogleGenAiChatModel> geminiChatModelProvider,
            ObjectProvider<OllamaChatModel> ollamaChatModelProvider,
            @Value("${spring.ai.google.genai.api-key:${GEMINI_API_KEY:}}") String geminiApiKey,
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
            @Value("${spring.ai.ollama.chat.options.model:${spring.ai.ollama.chat.model:${OLLAMA_CHAT_MODEL:qwen2.5:3b-instruct}}}") String ollamaModelName,
            @Value("${app.rag.refiner.enabled:false}") boolean refinerEnabled) {

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

            private String extractContent(ChatResponse response) {
                if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                    return null;
                }
                return response.getResult().getOutput().getText();
            }

            private ChatResponse createChatResponse(String newText, ChatResponse originalResponse) {
                Generation generation = new Generation(new AssistantMessage(newText));
                if (originalResponse != null && originalResponse.getMetadata() != null) {
                    return new ChatResponse(List.of(generation), originalResponse.getMetadata());
                }
                return new ChatResponse(List.of(generation));
            }

            private boolean shouldRefine(String text) {
                if (!StringUtils.hasText(text)) {
                    return false;
                }
                String trimmed = text.trim();
                if (trimmed.length() < 50) {
                    return false;
                }
                if (trimmed.contains("The requested information is not present in the provided documents")
                        && trimmed.length() < 180) {
                    return false;
                }
                if (trimmed.contains("Information is not available in the provided documents")) {
                    return false;
                }
                // Check for clumsy mathematical notation or markdown structures
                if (trimmed.contains("$$")
                        || trimmed.contains("###")
                        || trimmed.contains("---")
                        || trimmed.contains("\\min")
                        || trimmed.contains("\\sum")
                        || trimmed.contains("\\sqrt")
                        || trimmed.contains("\\le")
                        || trimmed.contains("\\ge")
                        || trimmed.contains("\\in")) {
                    return true;
                }
                if (trimmed.contains("* **") && trimmed.contains("\n\n")) {
                    return true;
                }
                return trimmed.length() > 300;
            }

            private String buildRefinerPrompt(String rawText) {
                return """
                        You are an expert technical editor. Your task is to rewrite the AI-generated answer below into smooth, clear, and professional human-readable English paragraphs that anyone can easily read and understand.

                        CRITICAL INSTRUCTIONS:
                        1. Explain Mathematics in Plain English:
                           - Convert all mathematical formulas, raw LaTeX code (such as $$, \\min, \\sum, \\sqrt, \\le, \\ge, subscripts like a_i), and mathematical notations into intuitive English explanations.
                           - For example, instead of writing raw formulas for objective functions or constraints, explain clearly: "The goal is to minimize total transportation and delivery costs based on distance and volume. Each site operates under capacity limits, customer demand must be satisfied, and forbidden zones prevent facilities from being placed in restricted geographic areas."
                        2. Eliminate Clumsy Markdown Syntax:
                           - Remove header hashes (###, ##), horizontal dividers (---, ___), and fragmented bullet lists.
                           - Organize the response into coherent, well-structured, narrative paragraphs with natural topic transitions.
                        3. Strict Factual Accuracy:
                           - Keep completely faithful to all facts, terminology, numbers, constraints, and concepts from the original text.
                           - Do NOT invent, assume, or add outside facts.
                        4. Missing Information:
                           - If any section indicates that requested information is not present, state that clearly and naturally.
                        5. Model Identity:
                           - If the original text concludes with a statement about the model identity (e.g., 'The model identity is...'), preserve that exact model identity statement at the very end on its own line.

                        Original text to polish:
                        """ + "\n" + (rawText != null ? rawText : "");
            }

            private String cleanResidualFormatting(String text) {
                if (!StringUtils.hasText(text)) {
                    return text;
                }
                String cleaned = text;
                // Remove LaTeX display math delimiters $$...$$
                cleaned = cleaned.replaceAll("\\$\\$([\\s\\S]*?)\\$\\$", "$1");
                // Remove inline math delimiters $...$
                cleaned = cleaned.replaceAll("\\$([^$\\n]+)\\$", "$1");
                // Replace markdown headers (### Header -> Header:)
                cleaned = cleaned.replaceAll("(?m)^#{1,6}\\s*(.+)$", "$1:");
                // Remove horizontal rules
                cleaned = cleaned.replaceAll("(?m)^\\s*[-*_]{3,}\\s*$", "\n");
                // Clean excessive newlines
                cleaned = cleaned.replaceAll("\\n{3,}", "\n\n");
                return cleaned.trim();
            }

            private ChatResponse refineWithGemini(ChatResponse rawResponse, GoogleGenAiChatModel geminiModel) {
                String rawText = extractContent(rawResponse);
                try {
                    if (geminiModel != null) {
                        String refinerPromptText = buildRefinerPrompt(rawText);
                        Prompt prompt = preparePromptForGemini(new Prompt(refinerPromptText), geminiModel);
                        ChatResponse refined = geminiModel.call(prompt);
                        String refinedText = extractContent(refined);
                        if (StringUtils.hasText(refinedText)) {
                            return createChatResponse(refinedText, rawResponse);
                        }
                    }
                } catch (Exception ex) {
                    log.warn("Gemini stage-2 refinement failed: {}", ex.getMessage(), ex);
                    try {
                        OllamaChatModel ollamaModel = getOllamaModel();
                        if (ollamaModel != null) {
                            String refinerPromptText = buildRefinerPrompt(rawText);
                            Prompt prompt = preparePromptForOllama(new Prompt(refinerPromptText), ollamaModel);
                            ChatResponse refined = ollamaModel.call(prompt);
                            String refinedText = extractContent(refined);
                            if (StringUtils.hasText(refinedText)) {
                                return createChatResponse(refinedText, rawResponse);
                            }
                        }
                    } catch (Exception ollamaEx) {
                        log.warn("Ollama stage-2 refinement also failed ({}); falling back to text cleaning.", ollamaEx.getMessage());
                    }
                    return createChatResponse(cleanResidualFormatting(rawText), rawResponse);
                }
                return rawResponse;
            }

            private ChatResponse refineWithOllama(ChatResponse rawResponse, OllamaChatModel ollamaModel) {
                String rawText = extractContent(rawResponse);
                try {
                    if (ollamaModel != null) {
                        String refinerPromptText = buildRefinerPrompt(rawText);
                        Prompt prompt = preparePromptForOllama(new Prompt(refinerPromptText), ollamaModel);
                        ChatResponse refined = ollamaModel.call(prompt);
                        String refinedText = extractContent(refined);
                        if (StringUtils.hasText(refinedText)) {
                            return createChatResponse(refinedText, rawResponse);
                        }
                    }
                } catch (Exception ex) {
                    log.warn("Ollama stage-2 refinement failed ({}), falling back to text cleaning.", ex.getMessage());
                    return createChatResponse(cleanResidualFormatting(rawText), rawResponse);
                }
                return rawResponse;
            }

            private RawExecutionResult executeRawCall(Prompt prompt) {
                Throwable geminiFailure = null;

                // First preference: Google Gemini
                if (isGeminiConfigured()) {
                    GoogleGenAiChatModel geminiModel = geminiChatModelProvider.getIfAvailable();
                    if (geminiModel != null) {
                        long geminiStartedAt = System.nanoTime();
                        try {
                            log.info("Generating response with Primary model: Google Gemini...");
                            Prompt geminiPrompt = preparePromptForGemini(prompt, geminiModel);
                            ChatResponse response = geminiModel.call(geminiPrompt);
                            log.info("Google Gemini completed in {} ms", (System.nanoTime() - geminiStartedAt) / 1_000_000);
                            return new RawExecutionResult(response, ModelSource.GEMINI);
                        } catch (Exception ex) {
                            log.warn("Google Gemini failed after {} ms", (System.nanoTime() - geminiStartedAt) / 1_000_000);
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
                long ollamaStartedAt = System.nanoTime();
                try {
                    log.info("Generating response with Fallback model: Qwen (Ollama - {})...", ollamaModelName);
                    OllamaChatModel ollamaModel = getOllamaModel();
                    Prompt ollamaPrompt = preparePromptForOllama(prompt, ollamaModel);
                    ChatResponse response = ollamaModel.call(ollamaPrompt);
                    log.info("Ollama chat completed in {} ms", (System.nanoTime() - ollamaStartedAt) / 1_000_000);
                    return new RawExecutionResult(response, ModelSource.OLLAMA);
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
            public ChatResponse call(Prompt prompt) {
                RawExecutionResult rawResult = executeRawCall(prompt);
                if (!refinerEnabled) {
                    return rawResult.getResponse();
                }

                String rawText = extractContent(rawResult.getResponse());
                if (!shouldRefine(rawText)) {
                    return rawResult.getResponse();
                }

                log.info("Refining response with Stage 2 (Human Readability Polisher) using {}...", rawResult.getSource());
                if (rawResult.getSource() == ModelSource.GEMINI) {
                    GoogleGenAiChatModel geminiModel = geminiChatModelProvider.getIfAvailable();
                    return refineWithGemini(rawResult.getResponse(), geminiModel);
                } else {
                    OllamaChatModel ollamaModel = getOllamaModel();
                    return refineWithOllama(rawResult.getResponse(), ollamaModel);
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
