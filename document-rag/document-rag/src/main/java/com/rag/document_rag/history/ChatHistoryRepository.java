package com.rag.document_rag.history;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;

@Repository
public class ChatHistoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChatHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void initializeSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS chat_history (
                    id BIGSERIAL PRIMARY KEY,
                    question TEXT NOT NULL,
                    answer TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    public ChatHistoryEntry save(String question, String answer) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO chat_history (question, answer) VALUES (?, ?) RETURNING id",
                Long.class,
                question,
                answer);
        return findById(id);
    }

    public ChatHistoryEntry updateAnswer(long id, String answer) {
        jdbcTemplate.update("UPDATE chat_history SET answer = ? WHERE id = ?", answer, id);
        return findById(id);
    }

    private ChatHistoryEntry findById(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT id, question, answer, created_at FROM chat_history WHERE id = ?",
                (rs, rowNum) -> new ChatHistoryEntry(
                        rs.getLong("id"),
                        rs.getString("question"),
                        rs.getString("answer"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class)),
                id);
    }

    public List<ChatHistoryEntry> findAll() {
        return jdbcTemplate.query(
                "SELECT id, question, answer, created_at FROM chat_history ORDER BY created_at DESC, id DESC",
                (rs, rowNum) -> new ChatHistoryEntry(
                        rs.getLong("id"),
                        rs.getString("question"),
                        rs.getString("answer"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class)));
    }
}
