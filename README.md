# rag-pdf-search

End-to-end Retrieval-Augmented Generation (RAG) over PDFs — ask questions and get answers grounded in the document content.

## Stack

- **Spring Boot 4.1.1** + **Spring AI 2.0.1**
- **Java 21**
- **Postgres 16 + pgvector** (vector store)
- **Ollama** (fully local, no API keys)
  - `llama3.1:8b` — chat / answering
  - `nomic-embed-text` — embeddings (768-dim)
- **Flyway** — schema migrations
- **Apache PDFBox** (via `spring-ai-pdf-document-reader`)

A detailed walkthrough with architecture notes and gotchas is in `C:\Users\G.Yadagiri\workspace\documentations\rag-pdf-search-walkthrough.html`.

## Prerequisites

- JDK 21
- Maven 3.9+
- Docker Desktop
- [Ollama](https://ollama.com/download) installed and running on `localhost:11434`

Pull the models (one-time, ~5 GB total):

```powershell
ollama pull llama3.1:8b
ollama pull nomic-embed-text
```

## Run

### 1. Start Postgres

```powershell
docker compose up -d
```

Postgres listens on `localhost:5433`, DB `ragdb`, user `raguser` / `ragpass`.

### 2. Start the app

```powershell
mvn spring-boot:run
```

First boot runs the Flyway migration (`V1__init.sql`) and lets Spring AI create the `vector_store` table with an HNSW index.

### 3. Smoke test

```powershell
curl http://localhost:8080/health
# {"status":"UP","service":"rag-pdf-search"}
```

## API

### `POST /api/ingest` — upload a PDF

Multipart body, field name `file`. Max size 25 MB.

```powershell
curl.exe --location "http://localhost:8080/api/ingest" `
         --form "file=@`"C:/path/to/your.pdf`""
```

Response:

```json
{ "filename": "your.pdf", "chunksStored": 7 }
```

> On PowerShell, use **`curl.exe`** (not `curl`, which is an alias for `Invoke-WebRequest` and doesn't understand `--form`). Alternative: Postman with Body → form-data → key `file`, type **File**.

### `GET /api/ask` — ask a question

Query params: `q` (required), `topK` (default `4`).

```powershell
curl "http://localhost:8080/api/ask?q=Where+did+I+start+my+career&topK=4"
```

Response (shortened):

```json
{
  "question": "Where did I start my career",
  "topK": 4,
  "retrievedChunks": [
    { "id": "...", "score": 0.78, "filename": "resume.pdf", "preview": "..." }
  ],
  "latencyMs": 1234,
  "answer": "Wipro Technologies."
}
```

Watch the Spring Boot console — each call logs every RAG pipeline step:

```
────────────── RAG PIPELINE START ──────────────
[1] Received question: "..."
[2] Embedding question and searching pgvector (topK=4)
[3] Retrieved 4 chunk(s):
    #1 [id=...] [score=0.78] [filename=...]  "..."
[4] Built system prompt (N chars of context across 4 chunk(s))
[5] Calling chat model...
[6] Answer in 1234 ms: "..."
────────────── RAG PIPELINE END ──────────────
```

## Peek at the vector store

```sql
SELECT count(*) FROM vector_store;

SELECT id, LEFT(content, 120) AS preview, metadata
FROM vector_store
LIMIT 5;
```

To wipe and re-ingest cleanly:

```sql
TRUNCATE TABLE vector_store;
```

## Project layout

```
rag-pdf-search/
├── docker-compose.yml                     # pgvector/pgvector:pg16 on port 5433
├── pom.xml
└── src/main/
    ├── java/com/practice/ragpdfsearch/
    │   ├── RagPdfSearchApplication.java   # @SpringBootApplication
    │   ├── ingestion/
    │   │   └── IngestionService.java      # PDF → chunks → embed → store
    │   └── web/
    │       ├── HealthController.java      # GET  /health
    │       ├── IngestionController.java   # POST /api/ingest  (multipart)
    │       └── RagController.java         # GET  /api/ask
    └── resources/
        ├── application.yml
        └── db/migration/V1__init.sql      # enables vector, hstore, uuid-ossp
```

## Tuning knobs

| Where | Setting | Default | What it does |
|---|---|---|---|
| `IngestionService` | `TokenTextSplitter` chunk size | `400` tokens | Larger = more context per chunk, less retrieval granularity |
| `RagController` | `topK` query param | `4` | How many chunks to retrieve per question |
| `application.yml` | `spring.ai.ollama.chat.options.temperature` | `0.2` | Lower = more focused answers; higher = more creative |
| `application.yml` | `spring.ai.vectorstore.pgvector.dimensions` | `768` | **Must match the embedding model.** nomic-embed-text = 768, OpenAI text-embedding-3-small = 1536 |

## What we tried and removed (and why)

The repo today is the clean "arrived" state. The journey had a few branches we backed out of — kept here so future-you doesn't repeat them.

### Removed: Anthropic Claude as the chat model

**What was there:**
- `spring-ai-starter-model-anthropic` in `pom.xml`
- A `spring.ai.anthropic` block in `application.yml` (api-key, custom-headers for `anthropic-workspace-id`, chat options)
- The `spring.ai.model.chat/embedding` selectors (needed to disambiguate between Anthropic and Ollama)

**Why dropped:**
1. Needed paid API credits (Anthropic Console, separate from Claude Pro subscription).
2. Organization-scoped API keys additionally require an `anthropic-workspace-id` header on every call, which added config surface.
3. We already have Ollama running locally with `llama3.1:8b` — same job, zero cost, zero API keys.

**How to put it back if needed:**
1. Add `spring-ai-starter-model-anthropic` to `pom.xml`.
2. Add the Anthropic config block back to `application.yml` with `api-key: ${ANTHROPIC_API_KEY}` (never hardcode).
3. Add `spring.ai.model.chat: anthropic` under `spring.ai`.
4. For org-scoped keys, also set `spring.ai.anthropic.custom-headers.anthropic-workspace-id: ${ANTHROPIC_WORKSPACE_ID}`.

### Removed: Spring AI Transformers (ONNX in-JVM embeddings)

**What was there:** `spring-ai-starter-model-transformers`, which bundles ONNX Runtime 1.21.1 and the `all-MiniLM-L6-v2` embedding model (384 dims), running inside the JVM — no external process needed.

**Why dropped:** On this Windows laptop the native `onnxruntime.dll` failed to initialize at startup (`UnsatisfiedLinkError: initialization routine failed`), even with Visual C++ Redistributable installed. Almost always caused by corporate endpoint protection / Windows Defender blocking DLL loads from Java's Temp extraction folder. Fighting antivirus is not a hill worth dying on.

**Alternative chosen:** Ollama's `nomic-embed-text` (768 dims, native app, no JVM DLL dance).

### Downgraded away from: Spring Boot 3.5.14 + Spring AI 1.0.0

**What was tried first:** Boot 3.5.14 (to match the sibling `eight-digit-bin-project`) with Spring AI 1.0.0.

**Why moved to Boot 4.1.1 + Spring AI 2.0.1:**
1. Spring Boot 3.x reached end-of-life in June 2026 — no OSS security fixes.
2. Spring AI 2.0 is the current supported line, built for Boot 4.x / Framework 7.x.
3. Fresh project — no legacy to migrate, might as well start on the current stack.

### Removed: a stray `spring-boot` direct dependency

**What was there:** IntelliJ silently added a `<dependency>spring-boot:3.4.13-SNAPSHOT</dependency>` entry at the bottom of our `<dependencies>` block during an earlier failed boot — probably from a "fix missing class" quick-fix.

**Why dropped:** Our corporate Artifactory (`bwinparty.corp`) proxies Spring snapshots, and this stray entry forced Maven to pull `spring-boot:3.4.13-SNAPSHOT` while every other `spring-boot-*` artifact was at 3.5.14/4.1.1. The classpath mix caused `ClientHttpConnectorBuilder` and `ApplicationServletEnvironment` NoClassDefFoundErrors.

**Lesson:** when IDE quick-fixes offer to "add missing dependency," check the proposed version. Keep `spring-boot-dependencies` pinned in `<dependencyManagement>` (as this pom does) so transitive overrides can't win.

### Removed: the hardcoded Anthropic key default in `application.yml`

**What was there (briefly):**

```yaml
api-key: ${ANTHROPIC_API_KEY:sk-ant-usr-1c0c6qNj...}
```

**Why dropped:** Hardcoded API keys end up in git history and get leaked by secret scanners. Env-var-only (`${ANTHROPIC_API_KEY}`) is the only safe pattern. If this file ever gets committed with a real key as default, the key must be rotated.

---

## Swapping providers later

| Switch to | How |
|---|---|
| **OpenAI chat/embeddings** | Add `spring-ai-starter-model-openai`; set `OPENAI_API_KEY`; change `pgvector.dimensions` to `1536` for OpenAI embeddings |
| **Anthropic Claude** chat | Add `spring-ai-starter-model-anthropic`; set `ANTHROPIC_API_KEY` (and `ANTHROPIC_WORKSPACE_ID` for org-scoped keys); set `spring.ai.model.chat: anthropic` |
| **Different Ollama model** | `ollama pull <model>` then change `spring.ai.ollama.chat.options.model` |

Any time you swap the embedding model, you **must**:
1. Update `spring.ai.vectorstore.pgvector.dimensions` to match the new model's output size.
2. `TRUNCATE TABLE vector_store` and re-ingest everything (old vectors become unusable).

## Troubleshooting (quick ref)

- **`HttpMediaTypeNotSupportedException` on upload** → PowerShell `curl` alias. Use `curl.exe`.
- **Ollama slow on first call** → model warm-up. Subsequent calls are faster. Dramatically faster on GPU.
- **"ClassNotFoundException: org.springframework.boot.SpringApplication" from IntelliJ** → stale Maven cache. Reload Maven project in IntelliJ.
- **`vector_store` table missing** → check `spring.ai.vectorstore.pgvector.initialize-schema: true` is set.
- **Postgres auth failed in DBeaver** → user `raguser`, password `ragpass`, DB `ragdb`, port `5433` (not 5432).

See the HTML walkthrough for the full build story including the dependency-conflict adventure, the ONNX-DLL dead end, and the Anthropic-workspace-header rabbit hole.
