package com.practice.ragpdfsearch.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Phase B of RAG with conversation memory.
 * <p>
 * {@link ChatMemory} (auto-configured by Spring AI, in-memory, 20-message window)
 * remembers the user and assistant messages per {@code conversationId}. The
 * {@link MessageChatMemoryAdvisor} injects those prior messages into every call,
 * so follow-up questions like "how long did I work there?" can resolve against
 * the previous turn's answer.
 */
@RestController
@RequestMapping("/api/ask")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);
    private static final String DEFAULT_CONVERSATION_ID = "default";

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ChatMemory chatMemory;
    private final JdbcTemplate jdbcTemplate;

    public RagController(ChatModel chatModel, VectorStore vectorStore,
                         ChatMemory chatMemory, JdbcTemplate jdbcTemplate) {
        this.chatClient = ChatClient.create(chatModel);
        this.vectorStore = vectorStore;
        this.chatMemory = chatMemory;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Lists every known conversation with a one-line preview (its first user question)
     * and some metadata. Backs the sidebar in the chat UI.
     */
    @GetMapping("/conversations")
    public List<Map<String, Object>> listConversations() {
        String sql = """
                SELECT c.conversation_id,
                       c.message_count,
                       c.last_activity,
                       (SELECT content FROM SPRING_AI_CHAT_MEMORY m
                         WHERE m.conversation_id = c.conversation_id
                           AND m.type = 'USER'
                         ORDER BY m.sequence_id ASC
                         LIMIT 1) AS first_question
                  FROM (SELECT conversation_id,
                               COUNT(*) AS message_count,
                               MAX("timestamp") AS last_activity
                          FROM SPRING_AI_CHAT_MEMORY
                         GROUP BY conversation_id) c
                 ORDER BY c.last_activity DESC
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("conversationId", rs.getString("conversation_id"));
            row.put("messageCount", rs.getInt("message_count"));
            Timestamp ts = rs.getTimestamp("last_activity");
            row.put("lastActivity", ts != null ? ts.toInstant().toString() : null);
            row.put("firstQuestion", rs.getString("first_question"));
            return row;
        });
    }

    @GetMapping
    public Map<String, Object> ask(
            @RequestParam("q") String question,
            @RequestParam(name = "topK", defaultValue = "4") int topK,
            @RequestParam(name = "conversationId", defaultValue = DEFAULT_CONVERSATION_ID) String conversationId) {

        log.info("────────────── RAG PIPELINE START (conv={}) ──────────────", conversationId);
        log.info("[1] Received question: \"{}\"", question);

        // [1b] inspect what's in memory for this conversation BEFORE the call
        List<Message> priorMessages = chatMemory.get(conversationId);
        log.info("[1b] Memory has {} prior message(s) for conv={}:",
                priorMessages.size(), conversationId);
        for (int i = 0; i < priorMessages.size(); i++) {
            Message m = priorMessages.get(i);
            log.info("     #{} [{}] {}", i + 1, m.getMessageType(), snippet(m.getText(), 120));
        }

        // [2] embed & similarity-search
        log.info("[2] Embedding question and searching pgvector (topK={})", topK);
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(topK).build());

        // [3] log retrieved chunks
        log.info("[3] Retrieved {} chunk(s):", hits.size());
        for (int i = 0; i < hits.size(); i++) {
            Document d = hits.get(i);
            log.info("    #{} [id={}] [score={}] [filename={}]  {}",
                    i + 1, d.getId(), d.getScore(),
                    d.getMetadata().get("filename"), snippet(d.getText(), 140));
        }

        // [4] build system prompt
        String context = hits.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n"));
        String systemPrompt = """
                You are a helpful assistant. Answer the user's question using the context below
                AND any prior conversation. If the answer is not in the context or prior turns,
                say "I don't know based on the provided documents."

                Context:
                %s
                """.formatted(context);

        log.info("[4] Built system prompt ({} chars context, prior memory for conv={})",
                context.length(), conversationId);

        // [5] call the chat model, with memory advisor attached (uses conversationId)
        log.info("[5] Calling chat model...");
        long start = System.currentTimeMillis();
        String answer = chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
        long elapsed = System.currentTimeMillis() - start;

        log.info("[6] Answer in {} ms: \"{}\"", elapsed, answer);
        log.info("────────────── RAG PIPELINE END ──────────────");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("question", question);
        response.put("conversationId", conversationId);
        response.put("topK", topK);
        response.put("priorMessagesCount", priorMessages.size());  // proof memory was consulted
        response.put("retrievedChunks", hits.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("score", d.getScore());
            m.put("filename", d.getMetadata().get("filename"));
            m.put("preview", snippet(d.getText(), 200));
            return m;
        }).toList());
        response.put("latencyMs", elapsed);
        response.put("answer", answer);
        return response;
    }

    /**
     * Returns the live message history for a conversation — exactly what
     * {@link MessageChatMemoryAdvisor} will inject on the next {@code /api/ask} call.
     * Useful for proving memory is actually being used.
     */
    @GetMapping("/conversations/{conversationId}")
    public Map<String, Object> getConversation(@PathVariable String conversationId) {
        List<Message> messages = chatMemory.get(conversationId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("conversationId", conversationId);
        response.put("messageCount", messages.size());
        response.put("messages", messages.stream().map(m -> {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("role", m.getMessageType().getValue());
            mm.put("content", m.getText());
            return mm;
        }).toList());
        return response;
    }

    /** Clears all messages for one conversation. Idempotent — unknown IDs are a no-op. */
    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> clearConversation(@PathVariable String conversationId) {
        chatMemory.clear(conversationId);
        log.info("Cleared conversation memory for conv={}", conversationId);
        return ResponseEntity.noContent().build();
    }

    private static String snippet(String s, int max) {
        if (s == null) return "";
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
