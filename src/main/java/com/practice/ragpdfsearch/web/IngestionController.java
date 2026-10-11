package com.practice.ragpdfsearch.web;

import com.practice.ragpdfsearch.ingestion.IngestionService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/ingest")
public class IngestionController {

    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> ingest(@RequestParam("file") MultipartFile file) throws IOException {
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".pdf")) {
            return ResponseEntity.badRequest().body(Map.of("error", "File must be a .pdf"));
        }
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        }

        // Wrap the uploaded bytes as a Spring Resource that reports the original filename;
        // PagePdfDocumentReader uses getFilename() for metadata and error messages.
        var resource = new ByteArrayResource(file.getBytes()) {
            @Override public String getFilename() { return name; }
        };

        IngestionService.IngestResult result = ingestionService.ingest(resource, name);
        return ResponseEntity.ok(Map.of(
                "filename", name,
                "chunksStored", result.chunksStored(),
                "previousChunksReplaced", result.previousChunksReplaced()
        ));
    }
}
