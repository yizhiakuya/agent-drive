CREATE TABLE IF NOT EXISTS index_documents (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    file_id UUID NOT NULL,
    source_revision BIGINT NOT NULL,
    document_type VARCHAR(32) NOT NULL,
    extractor_version VARCHAR(128) NOT NULL,
    content TEXT NOT NULL,
    chunk_version VARCHAR(128) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (owner_id, file_id, source_revision, document_type)
);

CREATE TABLE IF NOT EXISTS index_chunks (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    document_id UUID NOT NULL REFERENCES index_documents(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    source_revision BIGINT NOT NULL,
    chunk_version VARCHAR(128) NOT NULL,
    content TEXT NOT NULL,
    embedding_fingerprint VARCHAR(256),
    embedding TEXT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (owner_id, document_id, chunk_index, chunk_version)
);

CREATE INDEX IF NOT EXISTS index_documents_owner_idx ON index_documents(owner_id, updated_at);
CREATE INDEX IF NOT EXISTS index_chunks_owner_idx ON index_chunks(owner_id, document_id, chunk_index);
