package com.rag.document_rag.controller;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Ragcontroller {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public Ragcontroller(
            ChatClient.Builder chatClientBuilder,
            VectorStore vectorStore) {

        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
    }

    @GetMapping("/rag")
    public String rag(@RequestParam String question) {

     
        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .topK(3)
                .build();

        List<Document> results =
                vectorStore.similaritySearch(searchRequest);

        if (results.isEmpty()) {
            return "Information is not available in the provided documents.";
        }


        
        StringBuilder context = new StringBuilder();

        for (Document document : results) {

                context.append("SOURCE INFORMATION:\n");
                context.append(document.getText());
                context.append("\n\n");

    context.append("METADATA:\n");
    context.append(document.getMetadata());
    context.append("\n\n");
}

        
        String prompt = """

                Your job is to answer the user's question using ONLY the information
                provided in the document context below.

                Follow these rules strictly:

                1. First understand the user's question and identify exactly what
                   information the user is asking for.

                2. Answer ONLY that question. Do not change the question or answer
                   a different question.

                3. Use only information present in the provided document context.
                   Do not use your own general knowledge, assumptions, or outside
                   information.

                4. Give a complete answer, not just a single sentence or isolated fact.
                   Include additional information from the provided context when it
                   directly helps explain, support, clarify, or give context to the
                   answer.

                5. If the document contains multiple pieces of information that are
                   directly related to the question, combine them into one clear answer.

                6. Do not include unrelated information from the document just because
                   it was retrieved.

                7. If the requested information is not present or cannot be answered
                  from the provided context, say exactly:
                  "The requested information is not present in the provided documents."

                8. Never invent, guess, or complete missing information using outside
                   knowledge.

                9. Keep the answer focused and useful. The answer can contain multiple
                  sentences or short bullet points when the document provides enough
                  relevant information.
                10. If the document contains conflicting information, present all relevant
                     perspectives and clearly indicate that there are differing viewpoints.
                11.At the end of your answer, state the exact name of the LLM model that is generating this response.
                   Do not infer or guess your model identity. If you cannot reliably determine your exact model name,
                   state that the model identity is unavailable.




        Document context:
        %s

        User question:
        %s
                """.formatted(context, question);

       
        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }
}