package com.rag.document_rag.history;

import java.time.OffsetDateTime;

public record ChatHistoryEntry(long id, String question, String answer, OffsetDateTime createdAt) {
}
