-- Apply before deploying the API; no existing assets are changed/deleted.
ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS upload_batch_id uuid;
ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS upload_batch_index integer;
ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS upload_renewed_at timestamp;
CREATE INDEX IF NOT EXISTS ix_media_upload_batch
    ON media_assets (tenant_id, upload_batch_id, status);
