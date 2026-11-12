-- Propuesta PostgreSQL D44; no ejecutada. Requiere antes el esquema D43.
-- Retirar/drenar binarios anteriores antes de habilitar aperturas nuevas.
BEGIN;
CREATE TABLE IF NOT EXISTS upload_scopes (id uuid PRIMARY KEY);
ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS upload_owner_id uuid;
ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS upload_identity_hash varchar(64);
ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS upload_declared_bytes bigint;
ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS upload_protocol_version integer;
ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS upload_opening_state varchar(16);
COMMIT;
