-- Enable pgvector. Spring AI's PgVectorStore will auto-create its own `vector_store` table
-- on startup once this extension (and hstore/uuid-ossp used by its schema) are available.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
