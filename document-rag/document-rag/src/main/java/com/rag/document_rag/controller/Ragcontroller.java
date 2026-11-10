package com.rag.document_rag.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.rag.document_rag.history.ChatHistoryEntry;
import com.rag.document_rag.history.ChatHistoryRepository;

@RestController
public class Ragcontroller {

    private static final Logger log = LoggerFactory.getLogger(Ragcontroller.class);

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ChatHistoryRepository chatHistoryRepository;

    public Ragcontroller(ChatClient.Builder chatClientBuilder, VectorStore vectorStore,
            ChatHistoryRepository chatHistoryRepository) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.chatHistoryRepository = chatHistoryRepository;
    }

    @GetMapping("/history")
    public List<ChatHistoryEntry> history() {
        return chatHistoryRepository.findAll();
    }

    @GetMapping("/rag")
    public String rag(@RequestParam String question) {
        long requestStartedAt = System.nanoTime();
        ChatHistoryEntry historyEntry = chatHistoryRepository.save(question, "Generating answer...");
        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .topK(3)
                .build();

        long retrievalStartedAt = System.nanoTime();
        List<Document> results = vectorStore.similaritySearch(searchRequest);
        log.info("RAG retrieved {} chunks in {} ms", results.size(),
                (System.nanoTime() - retrievalStartedAt) / 1_000_000);

        if (results.isEmpty()) {
            String answer = "Information is not available in the provided documents.";
            chatHistoryRepository.updateAnswer(historyEntry.id(), answer);
            log.info("RAG request completed without a model call in {} ms",
                    (System.nanoTime() - requestStartedAt) / 1_000_000);
            return answer;
        }

        StringBuilder context = new StringBuilder();
        for (Document document : results) {
            context.append(document.getText()).append("\n\n");
        }

        String prompt = """
                Answer the question using only relevant facts in the document context. Be concise and do not add outside knowledge. If the context does not contain the answer, say: "The requested information is not present in the provided documents." If the context conflicts, briefly describe the disagreement.

                Document context:
                %s

                Question:
                %s
                """.formatted(context, question);

        String answer;
        try {
            answer = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
        } catch (RuntimeException e) {
            log.error("RAG model call failed for history entry {}", historyEntry.id(), e);
            answer = "Answer generation failed. Please check the backend logs and try again.";
        }
        chatHistoryRepository.updateAnswer(historyEntry.id(), answer);
        log.info("RAG answer completed in {} ms", (System.nanoTime() - requestStartedAt) / 1_000_000);
        return answer;
    }
}
