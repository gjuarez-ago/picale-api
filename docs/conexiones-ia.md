# Conexiones de IA (ver y desconectar asistentes)

## Qué resuelve

Cuando una persona conecta Claude, ChatGPT u otro asistente a Pícale por el
servidor MCP, el asistente trabaja con un JWT de la API que dura 15 días. Antes
no había forma de cortarlo desde Pícale: solo quitando el conector en el propio
asistente. Ahora cada conexión queda registrada y la persona la ve y la
desconecta desde su perfil; desde ese instante las llamadas del asistente
reciben 401 aunque el JWT siga siendo válido.

## Cómo funciona

1. El MCP termina el login de la persona y llama `POST /api/v1/conexiones-ia`
   con `X-Picale-Origen: mcp`, `X-Picale-Cliente: <nombre>` y el cuerpo
   `{cliente, clienteId, expiraEpoch}`. Recibe el id de la conexión y lo mete
   en sus propios tokens sellados.
2. En cada llamada a la API, el MCP manda `X-Picale-Conexion: <id>`.
3. `service/conexiones/ConexionIaFilter` (corre después de la seguridad y
   antes de la bitácora) exige ese id en toda petición con `X-Picale-Origen`:
   - sin id → 401 `CONEXION_REQUERIDA`;
   - id de otra persona, revocado, vencido o mal formado → 401 `CONEXION_REVOCADA`;
   - vivo → anota la última actividad (como mucho una vez por minuto, o
     cuando cambia la acción) y deja pasar.
   El MCP convierte ese 401 en `invalid_token` y el asistente vuelve a pedir
   autorización: la persona decide si vuelve a entrar.
4. El perfil lista `GET /api/v1/conexiones-ia` y desconecta con
   `DELETE /api/v1/conexiones-ia/{id}` (o todas con `DELETE /api/v1/conexiones-ia`).
5. Al renovar tokens, el MCP consulta `GET /api/v1/conexiones-ia/{id}` y, si
   `activa` es falso, niega la renovación.

## Tabla

`conexiones_ia`: id, user_id, user_email, cliente, cliente_id, creada_en,
expira_en, ultima_actividad_en, ultima_accion, revocada_en. La crea Hibernate
con `ddl-auto=update`. La eliminación definitiva de una persona borra sus
conexiones (`EliminacionDefinitiva.borrarPersona`).

## Al desplegar

Las sesiones que los asistentes ya tenían no traen id de conexión: su primera
llamada después del despliegue recibe 401 y el asistente pide volver a entrar.
Es una sola vez y es a propósito: a partir de ahí todo queda registrado.
Hay que desplegar primero la API y después el MCP.
