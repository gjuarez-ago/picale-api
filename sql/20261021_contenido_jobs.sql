-- Aplicar antes de desplegar API. No elimina datos ni inicia generaciones.
CREATE TABLE IF NOT EXISTS contenido_jobs (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    active_workspace UUID UNIQUE,
    status VARCHAR(24) NOT NULL,
    response_json TEXT NOT NULL,
    request_json TEXT NOT NULL,
    prepared BYTEA,
    credit_reference VARCHAR(255) NOT NULL,
    external_started BOOLEAN NOT NULL DEFAULT FALSE,
    owner UUID,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_contenido_jobs_dispatch ON contenido_jobs(status, lease_until);
ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS generation_job_id UUID;
ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS generation_version VARCHAR(255);
ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS generation_index INTEGER;
CREATE INDEX IF NOT EXISTS ix_media_generation_job ON media_assets(generation_job_id,tenant_id);
