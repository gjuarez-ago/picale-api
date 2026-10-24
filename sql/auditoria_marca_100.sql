-- Auditoria de impacto antes de activar la marca obligatoria al 100%.
-- Solo lectura. Replica exactamente las ocho partes de BrandService.completitud.

WITH estado AS (
    SELECT id,
           archived_at,
           ARRAY_REMOVE(ARRAY[
               CASE WHEN NULLIF(BTRIM(giro), '') IS NULL THEN 'giro' END,
               CASE WHEN NULLIF(BTRIM(ciudad), '') IS NULL THEN 'ciudad' END,
               CASE WHEN NULLIF(BTRIM(descripcion), '') IS NULL THEN 'descripcion' END,
               CASE WHEN objetivo IS NULL THEN 'objetivo' END,
               CASE WHEN NULLIF(BTRIM(brand_profile::jsonb ->> 'queVende'), '') IS NULL THEN 'queVende' END,
               CASE WHEN NULLIF(BTRIM(brand_profile::jsonb ->> 'publico'), '') IS NULL THEN 'publico' END,
               CASE
                   WHEN COALESCE(jsonb_array_length(COALESCE(brand_profile::jsonb -> 'tono', '[]'::jsonb)), 0) = 0
                       THEN 'tono'
               END,
               CASE
                   WHEN NULLIF(BTRIM(brand_profile::jsonb ->> 'whatsapp'), '') IS NULL
                    AND NULLIF(BTRIM(brand_profile::jsonb ->> 'web'), '') IS NULL
                    AND NULLIF(BTRIM(brand_profile::jsonb ->> 'direccion'), '') IS NULL
                       THEN 'contacto'
               END
           ], NULL) AS faltan
    FROM workspaces
), resumen AS (
    SELECT *, ROUND((8 - CARDINALITY(faltan)) * 100.0 / 8)::int AS porcentaje
    FROM estado
)
SELECT CASE WHEN archived_at IS NULL THEN 'activos' ELSE 'archivados' END AS estado,
       porcentaje,
       COUNT(*) AS workspaces
FROM resumen
GROUP BY 1, 2
ORDER BY 1, 2;

WITH estado AS (
    SELECT id,
           ARRAY_REMOVE(ARRAY[
               CASE WHEN NULLIF(BTRIM(giro), '') IS NULL THEN 'giro' END,
               CASE WHEN NULLIF(BTRIM(ciudad), '') IS NULL THEN 'ciudad' END,
               CASE WHEN NULLIF(BTRIM(descripcion), '') IS NULL THEN 'descripcion' END,
               CASE WHEN objetivo IS NULL THEN 'objetivo' END,
               CASE WHEN NULLIF(BTRIM(brand_profile::jsonb ->> 'queVende'), '') IS NULL THEN 'queVende' END,
               CASE WHEN NULLIF(BTRIM(brand_profile::jsonb ->> 'publico'), '') IS NULL THEN 'publico' END,
               CASE
                   WHEN COALESCE(jsonb_array_length(COALESCE(brand_profile::jsonb -> 'tono', '[]'::jsonb)), 0) = 0
                       THEN 'tono'
               END,
               CASE
                   WHEN NULLIF(BTRIM(brand_profile::jsonb ->> 'whatsapp'), '') IS NULL
                    AND NULLIF(BTRIM(brand_profile::jsonb ->> 'web'), '') IS NULL
                    AND NULLIF(BTRIM(brand_profile::jsonb ->> 'direccion'), '') IS NULL
                       THEN 'contacto'
               END
           ], NULL) AS faltan
    FROM workspaces
    WHERE archived_at IS NULL
)
SELECT falta, COUNT(*) AS workspaces_activos
FROM estado
CROSS JOIN LATERAL UNNEST(faltan) AS falta
GROUP BY falta
ORDER BY workspaces_activos DESC, falta;

WITH estado AS (
    SELECT archived_at,
           ARRAY_REMOVE(ARRAY[
               CASE WHEN NULLIF(BTRIM(giro), '') IS NULL THEN 'giro' END,
               CASE WHEN NULLIF(BTRIM(ciudad), '') IS NULL THEN 'ciudad' END,
               CASE WHEN NULLIF(BTRIM(descripcion), '') IS NULL THEN 'descripcion' END,
               CASE WHEN objetivo IS NULL THEN 'objetivo' END,
               CASE WHEN NULLIF(BTRIM(brand_profile::jsonb ->> 'queVende'), '') IS NULL THEN 'queVende' END,
               CASE WHEN NULLIF(BTRIM(brand_profile::jsonb ->> 'publico'), '') IS NULL THEN 'publico' END,
               CASE
                   WHEN COALESCE(jsonb_array_length(COALESCE(brand_profile::jsonb -> 'tono', '[]'::jsonb)), 0) = 0
                       THEN 'tono'
               END,
               CASE
                   WHEN NULLIF(BTRIM(brand_profile::jsonb ->> 'whatsapp'), '') IS NULL
                    AND NULLIF(BTRIM(brand_profile::jsonb ->> 'web'), '') IS NULL
                    AND NULLIF(BTRIM(brand_profile::jsonb ->> 'direccion'), '') IS NULL
                       THEN 'contacto'
               END
           ], NULL) AS faltan
    FROM workspaces
)
SELECT COUNT(*) FILTER (WHERE archived_at IS NULL) AS activos,
       COUNT(*) FILTER (WHERE archived_at IS NULL AND CARDINALITY(faltan) = 0) AS activos_listos,
       COUNT(*) FILTER (WHERE archived_at IS NULL AND CARDINALITY(faltan) > 0) AS activos_bloqueados
FROM estado;
