-- Aplicar por el procedimiento de migraciones del entorno antes de desplegar.
-- No borrar reservas al reiniciar: son la barrera contra repeticiones de pago.
CREATE TABLE IF NOT EXISTS assistant_generation_requests (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    workspace_id UUID NOT NULL,
    request_id UUID NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    job_id VARCHAR(80),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_assistant_generation_request UNIQUE (workspace_id, request_id)
);
