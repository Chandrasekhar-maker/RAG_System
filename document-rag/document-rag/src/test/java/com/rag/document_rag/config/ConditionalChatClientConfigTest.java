package com.rag.document_rag.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        return createClient(geminiKey, true);
    }

    private ChatClient createClient(String geminiKey, boolean refinerEnabled) {
        ChatClient.Builder builder = config.chatClientBuilder(
                geminiProvider,
                ollamaProvider,
                geminiKey,
                "http://localhost:11434",
                "qwen2.5:3b-instruct",
                refinerEnabled
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

    @Test
    void testStage2RefinementWithClumsyMarkdownAndLatexTransformsOutput() {
        ChatClient client = createClient("test-gemini-key", true);

        String clumsyRawOutput = "### 1. What the Project is About\n"
                + "The project concerns a location-allocation problem with facilities.\n"
                + "$$\\min_{x,y,w} \\sum_{i=1}^n \\sum_{j=1}^m w_{ij} \\sqrt{(x_i - \\alpha_j)^2 + (y_i - \\beta_j)^2}$$\n"
                + "---\n"
                + "The model identity is Google Gemini.";

        String refinedOutput = "The project addresses a location-allocation problem where the primary objective is to find optimal site locations and minimize total product delivery costs across customer points.\n\n"
                + "The model identity is Google Gemini.";

        ChatResponse rawResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(clumsyRawOutput))));
        ChatResponse polishedResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(refinedOutput))));

        when(geminiModel.call(any(Prompt.class)))
                .thenReturn(rawResponse)
                .thenReturn(polishedResponse);

        String answer = client.prompt().user("Explain problem").call().content();

        assertThat(answer).isEqualTo(refinedOutput);
        // Stage 1 (Raw generation) + Stage 2 (Refinement) = 2 calls to Gemini
        verify(geminiModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void testStage2RefinementFailureGracefullyFallsBackToCleanedText() {
        ChatClient client = createClient("test-gemini-key", true);

        String clumsyRawOutput = "### 1. Problem Overview\n"
                + "We solve the transportation equation:\n"
                + "$$\\min \\sum w_{ij} d_{ij}$$\n"
                + "---\n"
                + "The model identity is Google Gemini.";

        ChatResponse rawResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(clumsyRawOutput))));

        // First call succeeds (Stage 1), second call throws exception (Stage 2 fails)
        when(geminiModel.call(any(Prompt.class)))
                .thenReturn(rawResponse)
                .thenThrow(new RuntimeException("Gemini refiner temporary error"));
        when(ollamaModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("Ollama refiner unavailable"));

        String answer = client.prompt().user("Explain problem").call().content();

        // Must NOT crash; should return regex-cleaned version without $$ or ###
        assertThat(answer).doesNotContain("$$");
        assertThat(answer).doesNotContain("###");
        assertThat(answer).contains("Problem Overview:");
        assertThat(answer).contains("The model identity is Google Gemini.");
    }

    @Test
    void testRefinerDisabledPassesThroughRawResponse() {
        ChatClient client = createClient("test-gemini-key", false);

        String clumsyRawOutput = "### 1. Problem Overview\n$$\\min x$$\n---\nDone.";
        ChatResponse rawResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(clumsyRawOutput))));

        when(geminiModel.call(any(Prompt.class))).thenReturn(rawResponse);

        String answer = client.prompt().user("Explain problem").call().content();

        // Raw output returned verbatim without Stage 2 call
        assertThat(answer).isEqualTo(clumsyRawOutput);
        verify(geminiModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void testMissingInfoResponseSkipsRefinement() {
        ChatClient client = createClient("test-gemini-key", true);

        String missingInfo = "The requested information is not present in the provided documents.";
        ChatResponse rawResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(missingInfo))));

        when(geminiModel.call(any(Prompt.class))).thenReturn(rawResponse);

        String answer = client.prompt().user("Where is John?").call().content();

        assertThat(answer).isEqualTo(missingInfo);
        verify(geminiModel, times(1)).call(any(Prompt.class));
    }
}
