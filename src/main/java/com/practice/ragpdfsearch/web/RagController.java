package com.practice.ragpdfsearch.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Phase B of RAG, written out step by step so the pipeline is visible in the logs.
 * <p>
 * The equivalent "framework" version is to pass a {@code RetrievalAugmentationAdvisor}
 * to the {@code ChatClient}, which hides steps 2-4 behind one call. We do it manually
 * here for learning / debugging.
 */
@RestController
@RequestMapping("/api/ask")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public RagController(ChatModel chatModel, VectorStore vectorStore) {
        this.chatClient = ChatClient.create(chatModel);
        this.vectorStore = vectorStore;
    }

    @GetMapping
    public Map<String, Object> ask(@RequestParam("q") String question,
                                   @RequestParam(name = "topK", defaultValue = "4") int topK) {

        log.info("────────────── RAG PIPELINE START ──────────────");
        log.info("[1] Received question: \"{}\"", question);

        // ── Step 2: embed the question and run similarity search against pgvector.
        //    VectorStore.similaritySearch() does the embedding internally using the
        //    configured EmbeddingModel bean (Ollama + nomic-embed-text).
        log.info("[2] Embedding question and searching pgvector (topK={})", topK);
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(topK).build()
        );

        // ── Step 3: inspect what was retrieved. Scores are cosine similarity (0..1, higher = closer).
        log.info("[3] Retrieved {} chunk(s):", hits.size());
        for (int i = 0; i < hits.size(); i++) {
            Document d = hits.get(i);
            log.info("    #{} [id={}] [score={}] [filename={}]  {}",
                    i + 1,
                    d.getId(),
                    d.getScore(),
                    d.getMetadata().get("filename"),
                    snippet(d.getText(), 140));
        }

        // ── Step 4: build the final prompt. The retrieved chunks become the system context,
        //    the user's question stays as the user message. Classic RAG prompt pattern.
        String context = hits.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n"));

        String systemPrompt = """
                You are a helpful assistant. Answer the user's question using ONLY the context below.
                If the answer is not in the context, say "I don't know based on the provided documents."

                Context:
                %s
                """.formatted(context);

        log.info("[4] Built system prompt ({} chars of context across {} chunk(s))",
                context.length(), hits.size());
        log.debug("Full system prompt:\n{}", systemPrompt);

        // ── Step 5: send to the chat model (Ollama llama3.1:8b).
        log.info("[5] Calling chat model...");
        long start = System.currentTimeMillis();
        String answer = chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .call()
                .content();
        long elapsed = System.currentTimeMillis() - start;

        log.info("[6] Answer in {} ms: \"{}\"", elapsed, answer);
        log.info("────────────── RAG PIPELINE END ──────────────");

        // Preserve order in the JSON response for readability.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("question", question);
        response.put("topK", topK);
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

    private static String snippet(String s, int max) {
        if (s == null) return "";
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
