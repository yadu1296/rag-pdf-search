package com.practice.ragpdfsearch.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Phase A of RAG: load → chunk → embed → store.
 * <p>
 * Re-uploading the same filename replaces the existing chunks instead of duplicating them.
 * Dedupe scope is the filename value stored in each chunk's metadata.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    private final TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(400)
            .withMinChunkSizeChars(200)
            .withMinChunkLengthToEmbed(5)
            .withMaxNumChunks(10000)
            .withKeepSeparator(true)
            .build();

    public IngestionService(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    public IngestResult ingest(Resource pdf, String filename) {
        log.info("Ingesting PDF: {}", filename);

        // Drop any prior chunks for this filename so re-uploads don't accumulate duplicates.
        // Parameterised query — safe even if a filename contains apostrophes.
        int replaced = jdbcTemplate.update(
                "DELETE FROM vector_store WHERE metadata->>'filename' = ?",
                filename);
        if (replaced > 0) {
            log.info("Replacing {} existing chunk(s) for {}", replaced, filename);
        }

        var reader = new PagePdfDocumentReader(pdf);
        List<Document> pages = reader.get();
        log.info("Read {} page(s) from {}", pages.size(), filename);

        pages.forEach(d -> d.getMetadata().put("filename", filename));

        List<Document> chunks = splitter.apply(pages);
        log.info("Split {} page(s) into {} chunk(s)", pages.size(), chunks.size());

        vectorStore.add(chunks);
        log.info("Stored {} chunk(s) in vector store", chunks.size());

        return new IngestResult(chunks.size(), replaced);
    }

    /**
     * @param chunksStored     number of chunks persisted on this call
     * @param previousChunksReplaced number of existing chunks removed first; 0 means first upload
     */
    public record IngestResult(int chunksStored, int previousChunksReplaced) {}
}
