package com.rag.document_rag.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Resilient Dual-Provider Embedding Model:
 * 1. Primary Preference: Google Gemini embedding (gemini-embedding-001)
 * 2. Automatic Shift/Fallback: If Gemini token limit or quota is exhausted, shifts to Ollama (nomic-embed-text)
 */
@Primary
@Component
public class ConditionalEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(ConditionalEmbeddingModel.class);

    private final ObjectProvider<GoogleGenAiEmbeddingModel> geminiEmbeddingProvider;
    private final ObjectProvider<OllamaEmbeddingModel> ollamaEmbeddingProvider;
    private final String ollamaBaseUrl;
    private final String ollamaEmbeddingModelName;
    private volatile OllamaEmbeddingModel fallbackOllamaEmbeddingModel;

    public ConditionalEmbeddingModel(
            ObjectProvider<GoogleGenAiEmbeddingModel> geminiEmbeddingProvider,
            ObjectProvider<OllamaEmbeddingModel> ollamaEmbeddingProvider,
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
            @Value("${spring.ai.ollama.embedding.options.model:nomic-embed-text}") String ollamaEmbeddingModelName) {
        this.geminiEmbeddingProvider = geminiEmbeddingProvider;
        this.ollamaEmbeddingProvider = ollamaEmbeddingProvider;
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.ollamaEmbeddingModelName = ollamaEmbeddingModelName;
    }

    private EmbeddingModel getOllamaModel() {
        OllamaEmbeddingModel model = ollamaEmbeddingProvider.getIfAvailable();
        if (model == null) {
            if (fallbackOllamaEmbeddingModel == null) {
                synchronized (this) {
                    if (fallbackOllamaEmbeddingModel == null) {
                        OllamaApi ollamaApi = OllamaApi.builder()
                                .baseUrl(ollamaBaseUrl)
                                .build();
                        OllamaEmbeddingOptions options = OllamaEmbeddingOptions.builder()
                                .model(ollamaEmbeddingModelName)
                                .build();
                        fallbackOllamaEmbeddingModel = OllamaEmbeddingModel.builder()
                                .ollamaApi(ollamaApi)
                                .options(options)
                                .build();
                    }
                }
            }
            return fallbackOllamaEmbeddingModel;
        }
        return model;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        GoogleGenAiEmbeddingModel geminiModel = geminiEmbeddingProvider.getIfAvailable();
        if (geminiModel != null && geminiModel.isConfigured()) {
            try {
                return geminiModel.call(request);
            } catch (Exception ex) {
                log.warn("Gemini embedding failed or token limit exceeded ({}). Shifting to Ollama ({})...",
                        ex.getMessage(), ollamaEmbeddingModelName);
            }
        }
        return getOllamaModel().call(request);
    }

    @Override
    public float[] embed(String text) {
        GoogleGenAiEmbeddingModel geminiModel = geminiEmbeddingProvider.getIfAvailable();
        if (geminiModel != null && geminiModel.isConfigured()) {
            try {
                return geminiModel.embed(text);
            } catch (Exception ex) {
                log.warn("Gemini embedding failed or token limit exceeded ({}). Shifting to Ollama ({})...",
                        ex.getMessage(), ollamaEmbeddingModelName);
            }
        }
        return getOllamaModel().embed(text);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public int dimensions() {
        return 768;
    }
}
