package com.rag.document_rag.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.ObjectProvider;

import com.google.genai.errors.ApiException;

class ConditionalChatClientConfigTest {

    private GoogleGenAiChatModel geminiModel;
    private OllamaChatModel ollamaModel;
    private ObjectProvider<GoogleGenAiChatModel> geminiProvider;
    private ObjectProvider<OllamaChatModel> ollamaProvider;
    private ConditionalChatClientConfig config;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        geminiModel = mock(GoogleGenAiChatModel.class);
        ollamaModel = mock(OllamaChatModel.class);
        geminiProvider = mock(ObjectProvider.class);
        ollamaProvider = mock(ObjectProvider.class);

        when(geminiProvider.getIfAvailable()).thenReturn(geminiModel);
        when(ollamaProvider.getIfAvailable()).thenReturn(ollamaModel);

        GoogleGenAiChatOptions geminiOptions = GoogleGenAiChatOptions.builder()
                .model("gemini-3.8-flash")
                .temperature(0.3)
                .build();
        when(geminiModel.getOptions()).thenReturn(geminiOptions);

        OllamaChatOptions ollamaOptions = OllamaChatOptions.builder()
                .model("qwen2.5:3b-instruct")
                .build();
        when(ollamaModel.getOptions()).thenReturn(ollamaOptions);

        config = new ConditionalChatClientConfig();
    }

    private ChatClient createClient(String geminiKey) {
        ChatClient.Builder builder = config.chatClientBuilder(
                geminiProvider,
                ollamaProvider,
                geminiKey,
                "http://localhost:11434",
                "qwen2.5:3b-instruct"
        );
        return builder.build();
    }

    @Test
    void testPrimaryGeminiSuccessDoesNotCallOllama() {
        ChatClient client = createClient("test-gemini-key");

        ChatResponse geminiResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Gemini answer"))));
        when(geminiModel.call(any(Prompt.class))).thenReturn(geminiResponse);

        String answer = client.prompt().user("Hello").call().content();

        assertThat(answer).isEqualTo("Gemini answer");

        // Verify Gemini received GoogleGenAiChatOptions
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(geminiModel).call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getOptions()).isInstanceOf(GoogleGenAiChatOptions.class);

        // Verify Ollama was never called
        verify(ollamaModel, never()).call(any(Prompt.class));
    }

    @Test
    void testGeminiQuotaExhaustionFallsBackToOllamaWithValidModelName() {
        ChatClient client = createClient("test-gemini-key");

        ApiException quotaEx = new ApiException(429, "RESOURCE_EXHAUSTED", "Quota exceeded for quota metric");
        when(geminiModel.call(any(Prompt.class))).thenThrow(quotaEx);

        ChatResponse ollamaResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Qwen fallback answer"))));
        when(ollamaModel.call(any(Prompt.class))).thenReturn(ollamaResponse);

        String answer = client.prompt().user("Hello").call().content();

        assertThat(answer).isEqualTo("Qwen fallback answer");

        // Verify Ollama was called with OllamaChatOptions containing non-null model name
        ArgumentCaptor<Prompt> ollamaPromptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(ollamaModel).call(ollamaPromptCaptor.capture());
        assertThat(ollamaPromptCaptor.getValue().getOptions()).isInstanceOf(OllamaChatOptions.class);
        OllamaChatOptions capturedOptions = (OllamaChatOptions) ollamaPromptCaptor.getValue().getOptions();
        assertThat(capturedOptions.getModel()).isEqualTo("qwen2.5:3b-instruct");
    }

    @Test
    void testGeminiTransientExceptionFallsBackToOllama() {
        ChatClient client = createClient("test-gemini-key");

        when(geminiModel.call(any(Prompt.class))).thenThrow(new TransientAiException("Service unavailable 503"));

        ChatResponse ollamaResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Qwen fallback answer"))));
        when(ollamaModel.call(any(Prompt.class))).thenReturn(ollamaResponse);

        String answer = client.prompt().user("Hello").call().content();

        assertThat(answer).isEqualTo("Qwen fallback answer");
        verify(ollamaModel).call(any(Prompt.class));
    }

    @Test
    void testGeminiProgrammingErrorDoesNotFallbackToOllama() {
        ChatClient client = createClient("test-gemini-key");

        when(geminiModel.call(any(Prompt.class))).thenThrow(new NullPointerException("Null pointer bug"));

        assertThatThrownBy(() -> client.prompt().user("Hello").call().content())
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Null pointer bug");

        // Ollama must NOT be called for programming errors
        verify(ollamaModel, never()).call(any(Prompt.class));
    }

    @Test
    void testBothModelsFailProducesMeaningfulCombinedError() {
        ChatClient client = createClient("test-gemini-key");

        ApiException quotaEx = new ApiException(429, "RESOURCE_EXHAUSTED", "Quota exhausted 429");
        when(geminiModel.call(any(Prompt.class))).thenThrow(quotaEx);
        when(ollamaModel.call(any(Prompt.class))).thenThrow(new RuntimeException("Connection refused to Ollama"));

        assertThatThrownBy(() -> client.prompt().user("Hello").call().content())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Primary model (Google Gemini) error")
                .hasMessageContaining("Quota exhausted 429")
                .hasMessageContaining("Fallback model (Qwen/Ollama - qwen2.5:3b-instruct) error")
                .hasMessageContaining("Connection refused to Ollama");
    }
}
