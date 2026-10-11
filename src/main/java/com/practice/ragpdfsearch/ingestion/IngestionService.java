package com.practice.ragpdfsearch.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Phase A of RAG: load → chunk → embed → store.
 * <p>
 * {@link VectorStore#add(List)} does the embedding + persistence in one call, using the
 * {@code EmbeddingModel} bean configured by Spring AI (Ollama + nomic-embed-text here).
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final VectorStore vectorStore;

    /**
     * Smaller chunks (~400 tokens) give finer-grained retrieval. Trade-off: each chunk
     * carries less surrounding context, so the LLM relies more on retrieving the RIGHT
     * chunk vs. getting a lot of context in one shot. Good for short, fact-oriented PDFs.
     */
    private final TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(400)
            .withMinChunkSizeChars(200)
            .withMinChunkLengthToEmbed(5)
            .withMaxNumChunks(10000)
            .withKeepSeparator(true)
            .build();

    public IngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Reads a PDF, splits each page into ~800-token chunks with 350-char overlap,
     * tags each chunk with the source filename, embeds them, and stores them in pgvector.
     *
     * @return number of chunks stored
     */
    public int ingest(Resource pdf, String filename) {
        log.info("Ingesting PDF: {}", filename);

        var reader = new PagePdfDocumentReader(pdf);
        List<Document> pages = reader.get();
        log.info("Read {} page(s) from {}", pages.size(), filename);

        pages.forEach(d -> d.getMetadata().put("filename", filename));

        List<Document> chunks = splitter.apply(pages);
        log.info("Split {} page(s) into {} chunk(s)", pages.size(), chunks.size());

        vectorStore.add(chunks);
        log.info("Stored {} chunk(s) in vector store", chunks.size());

        return chunks.size();
    }
}
