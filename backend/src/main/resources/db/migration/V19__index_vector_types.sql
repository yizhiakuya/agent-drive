-- Keep text and vision-description indexes distinct for the same file revision.
ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS document_type text NOT NULL DEFAULT 'text';

-- Vision descriptions created before this migration used the text default.
UPDATE documents
SET document_type = 'vision'
WHERE extractor_version = 'vision-description-v1';

-- Existing image OCR/text rows are no longer valid search sources. They are removed
-- so the next vision task can rebuild the file from the current image revision.
DELETE FROM documents d
USING files f
WHERE d.file_id = f.id
  AND d.document_type = 'text'
  AND lower(f.path) ~ '\.(png|jpe?g|gif|webp|bmp)$';

ALTER TABLE documents
    DROP CONSTRAINT IF EXISTS documents_file_id_source_revision_extractor_version_key;

ALTER TABLE documents
    ADD CONSTRAINT documents_document_type_check
        CHECK (document_type IN ('text', 'vision'));

ALTER TABLE documents
    ADD CONSTRAINT documents_file_revision_type_extractor_key
        UNIQUE (file_id, source_revision, document_type, extractor_version);

CREATE INDEX IF NOT EXISTS documents_file_revision_type_idx
    ON documents(file_id, source_revision, document_type);
