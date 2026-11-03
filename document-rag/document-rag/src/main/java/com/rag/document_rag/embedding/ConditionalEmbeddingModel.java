package com.rag.document_rag.embedding;

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
 * Delegates embedding operations to either the Google Gemini embedding model or the Ollama embedding model
 * based on the {@code ai.provider} configuration property. This bean is marked {@code @Primary} so that
 * {@code VectorStore} injects it automatically, regardless of which concrete provider is active.
 */
@Primary
@Component
public class ConditionalEmbeddingModel implements EmbeddingModel {

    private final ObjectProvider<GoogleGenAiEmbeddingModel> geminiEmbeddingProvider;
    private final ObjectProvider<OllamaEmbeddingModel> ollamaEmbeddingProvider;
    private final String provider;
    private final String ollamaBaseUrl;
    private final String ollamaEmbeddingModelName;
    private volatile OllamaEmbeddingModel fallbackOllamaEmbeddingModel;

    public ConditionalEmbeddingModel(
            ObjectProvider<GoogleGenAiEmbeddingModel> geminiEmbeddingProvider,
            ObjectProvider<OllamaEmbeddingModel> ollamaEmbeddingProvider,
            @Value("${ai.provider:gemini}") String provider,
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
            @Value("${spring.ai.ollama.embedding.options.model:nomic-embed-text}") String ollamaEmbeddingModelName) {
        this.geminiEmbeddingProvider = geminiEmbeddingProvider;
        this.ollamaEmbeddingProvider = ollamaEmbeddingProvider;
        this.provider = provider;
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.ollamaEmbeddingModelName = ollamaEmbeddingModelName;
    }

    private EmbeddingModel delegate() {
        if ("ollama".equalsIgnoreCase(provider)) {
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
        // default to Gemini
        return geminiEmbeddingProvider.getIfAvailable();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return delegate().call(request);
    }

    @Override
    public float[] embed(String text) {
        return delegate().embed(text);
    }

    @Override
    public float[] embed(Document document) {
        return delegate().embed(document);
    }

    @Override
    public int dimensions() {
        return delegate().dimensions();
    }
}
