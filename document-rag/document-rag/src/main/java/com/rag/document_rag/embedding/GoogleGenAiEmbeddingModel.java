package com.rag.document_rag.embedding;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.google.genai.Client;
import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;

@Component
public class GoogleGenAiEmbeddingModel extends AbstractEmbeddingModel {

    private final Client client;
    private final String modelName;
    private final int dimensions;
    private final boolean configured;

    public GoogleGenAiEmbeddingModel(
            @Value("${spring.ai.google.genai.api-key:${GEMINI_API_KEY:${SPRING_AI_GOOGLE_GENAI_API_KEY:}}}") String apiKey,
            @Value("${spring.ai.google.genai.embedding.model:gemini-embedding-001}") String modelName,
            @Value("${spring.ai.vectorstore.pgvector.dimensions:768}") int dimensions) {

        this.modelName = modelName;
        this.dimensions = dimensions;
        if (apiKey != null && !apiKey.isBlank() && !apiKey.equalsIgnoreCase("dummy") && !apiKey.contains("your_gemini_api_key")) {
            this.client = Client.builder().apiKey(apiKey).build();
            this.configured = true;
        } else {
            this.client = null;
            this.configured = false;
        }
    }

    public boolean isConfigured() {
        return this.configured && this.client != null;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        if (!isConfigured()) {
            throw new IllegalStateException("Google Gemini API key not configured or set to dummy placeholder.");
        }

        List<String> instructions = request.getInstructions();
        List<Embedding> embeddings = new ArrayList<>();

        EmbedContentConfig config = EmbedContentConfig.builder()
                .outputDimensionality(this.dimensions)
                .build();

        for (int i = 0; i < instructions.size(); i++) {
            String text = instructions.get(i);
            EmbedContentResponse response = client.models.embedContent(this.modelName, text, config);
            List<ContentEmbedding> contentEmbeddings = response.embeddings().orElse(List.of());

            if (!contentEmbeddings.isEmpty()) {
                List<Float> floatList = contentEmbeddings.get(0).values().orElse(List.of());
                float[] vector = new float[floatList.size()];
                for (int j = 0; j < floatList.size(); j++) {
                    vector[j] = floatList.get(j);
                }
                embeddings.add(new Embedding(vector, i));
            }
        }

        return new EmbeddingResponse(embeddings, new EmbeddingResponseMetadata());
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        if (!isConfigured()) {
            throw new IllegalStateException("Google Gemini API key not configured or set to dummy placeholder.");
        }

        EmbedContentConfig config = EmbedContentConfig.builder()
                .outputDimensionality(this.dimensions)
                .build();

        EmbedContentResponse response = client.models.embedContent(this.modelName, text, config);
        List<ContentEmbedding> contentEmbeddings = response.embeddings().orElse(List.of());

        if (!contentEmbeddings.isEmpty()) {
            List<Float> floatList = contentEmbeddings.get(0).values().orElse(List.of());
            float[] vector = new float[floatList.size()];
            for (int j = 0; j < floatList.size(); j++) {
                vector[j] = floatList.get(j);
            }
            return vector;
        }
        return new float[this.dimensions];
    }

    @Override
    public int dimensions() {
        return this.dimensions;
    }
}
