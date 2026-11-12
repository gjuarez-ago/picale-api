-- Propuesta PostgreSQL D43. NO ejecutada por esta entrega.
-- Aplicar sólo con autorización nueva, respaldo y workers antiguos detenidos.
-- Nullable: filas existentes se adoptan bajo bloqueo al ser recuperadas.
BEGIN;
ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS upload_decision varchar(24);
ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS upload_lease_owner uuid;
ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS upload_lease_until timestamptz;
ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS upload_expires_at timestamptz;
ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS upload_protected_until timestamptz;
ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS upload_next_attempt_at timestamptz;
ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS upload_attempts integer;
ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS upload_last_error varchar(40);
CREATE INDEX IF NOT EXISTS ix_media_upload_retry ON media_assets(status, upload_next_attempt_at);
COMMIT;
