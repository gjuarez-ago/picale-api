-- El asistente se habilita por espacio y solo funciona para organizaciones sin límites.
ALTER TABLE workspaces
    ADD COLUMN IF NOT EXISTS assistant_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Conserva el acceso de las cuentas sin límites que ya usaban el asistente antes del cambio.
UPDATE workspaces w
SET assistant_enabled = TRUE
FROM organizations o
WHERE w.organization_id = o.id
  AND o.sin_limites = TRUE
  AND w.assistant_enabled = FALSE;
