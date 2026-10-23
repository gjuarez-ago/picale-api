-- Read-only post-migration gate for the assistant release.
-- Run with: psql -v ON_ERROR_STOP=1 -f 20261022_assistant_release_verify.sql
DO $$
DECLARE
    missing text[] := ARRAY[]::text[];
BEGIN
    IF to_regclass('assistant_generation_requests') IS NULL THEN
        missing := array_append(missing, 'table assistant_generation_requests');
    END IF;
    IF to_regclass('contenido_jobs') IS NULL THEN
        missing := array_append(missing, 'table contenido_jobs');
    END IF;
    IF to_regclass('uk_assistant_generation_request') IS NULL THEN
        missing := array_append(missing, 'unique uk_assistant_generation_request');
    END IF;
    IF to_regclass('ix_contenido_jobs_dispatch') IS NULL THEN
        missing := array_append(missing, 'index ix_contenido_jobs_dispatch');
    END IF;
    IF to_regclass('ix_media_upload_batch') IS NULL THEN
        missing := array_append(missing, 'index ix_media_upload_batch');
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'contenido_jobs'::regclass AND contype = 'u'
          AND pg_get_constraintdef(oid) = 'UNIQUE (active_workspace)'
    ) THEN
        missing := array_append(missing, 'unique contenido_jobs(active_workspace)');
    END IF;

    IF EXISTS (
        SELECT required.column_name
        FROM (VALUES
            ('generation_job_id'), ('generation_version'), ('generation_index'),
            ('upload_batch_id'), ('upload_batch_index'), ('upload_renewed_at')
        ) AS required(column_name)
        LEFT JOIN information_schema.columns actual
          ON actual.table_schema = current_schema()
         AND actual.table_name = 'media_assets'
         AND actual.column_name = required.column_name
        WHERE actual.column_name IS NULL
    ) THEN
        missing := array_append(missing, 'assistant/upload columns on media_assets');
    END IF;

    IF cardinality(missing) > 0 THEN
        RAISE EXCEPTION 'Assistant release schema incomplete: %', array_to_string(missing, ', ');
    END IF;
END $$;

SELECT 'assistant release schema ready' AS verification;
