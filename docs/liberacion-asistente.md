# Liberación coordinada del asistente

Este runbook es la puerta de salida para API, MCP, web y móvil. Ningún paso que
modifique producción se ejecuta sin autorización explícita. La liberación no se
considera terminada sólo porque los contenedores estén sanos: debe quedar
evidencia del recorrido autenticado y de que una confirmación no duplica una
generación ni un cargo.

## 1. Congelar el candidato

Antes de mezclar o etiquetar:

- Separar archivos personales o ajenos al candidato (por ejemplo `.claude/`,
  reportes locales y documentos comerciales).
- Confirmar todos los archivos funcionales y de pruebas. Los scripts de
  despliegue usan `git archive HEAD`; un cambio sin commit **no viaja**.
- Registrar rama, commit y estado limpio de cada repositorio:

| Componente | Rama de salida | Commit | Estado limpio |
| --- | --- | --- | --- |
| API | `feature/asistente-operativo` | etiqueta `assistant-rc-20261023.1` | candidato congelado; sólo quedan documentos comerciales no incluidos |
| MCP | `feature/asistente-operativo` | `fa86a43` | candidato congelado; configuración personal `.claude` no incluida |
| Web | `feature/asistente-operativo` | `f93f1a6` | limpio |
| Móvil | `agente` | `4dccec3` | limpio |

- Mantener `CD_ENABLED` apagado en API y MCP mientras se prepara el conjunto.
- Crear la misma etiqueta de candidato en los cuatro repositorios, por ejemplo
  `assistant-rc-20261022.1`, sólo después de que todos los gates estén verdes.

## 2. Gates reproducibles

Ejecutar desde una copia limpia de cada commit, no desde el árbol de trabajo:

```text
API:    mvn -B --no-transfer-progress test
MCP:    mvn -B --no-transfer-progress test
Web:    npm ci
        npm test -- --watch=false --browsers=ChromeHeadless --progress=false
        npm run build:pages
        npm run test:e2e
Móvil:  flutter pub get
        flutter analyze
        flutter test
        flutter build appbundle --release
```

Conservar los reportes, SHA y hora. Un gate omitido o ejecutado contra cambios
sin confirmar deja el candidato en `NO-GO`.

## 3. Configuración productiva

### Estado verificado el 23 de octubre de 2026

El código funcional fue validado en los árboles de trabajo actuales, pero el
candidato continúa en **NO-GO** hasta congelar commits limpios y completar la
secuencia productiva con autorización explícita.

| Gate local | Resultado | Evidencia |
| --- | --- | --- |
| API | PASS | 1,164 pruebas; 0 fallas; 0 errores; 3 omitidas |
| MCP | PASS | 176 pruebas; 0 fallas; 0 errores; 0 omitidas |
| Web unitarias | PASS | 387 de 387 |
| Web build Pages | PASS | build productivo; advertencia no bloqueante de 4.36 kB en CSS del asistente |
| Web E2E | PASS | 3 de 3, en ejecución serial determinista |
| Móvil analyze | PASS | sin problemas |
| Móvil tests | PASS | 244 pruebas |
| Móvil AAB release | PASS | 62,407,788 bytes; SHA-256 `6A6B836DF74026E7671EC2939F85E9A6FB6EEEF5C5EFF2E8B4CA8E4677BBF4A9` |
| Sintaxis de despliegue | PASS | scripts Bash y YAML modificados validados |
| Producción | PENDIENTE | sin cambios aplicados |

La inspección de sólo lectura de producción encontró ambos contenedores
sanos, pero las dos llaves compartidas tienen longitud cero y la URL MCP del
asistente no está configurada. Las tres migraciones de esta liberación tampoco
se han aplicado. No se debe iniciar el despliegue hasta tener commits
reproducibles, respaldo confirmado y autorización expresa para modificar
producción y ejecutar el smoke con costo controlado.

En la VM existen dos archivos no versionados:

- `/home/contacto_rodtech_cloud/metricol.api/.env.vps`
- `/home/contacto_rodtech_cloud/picale-mcp/.env.vps`

La API debe tener `ASSISTANT_MCP_URL=https://mcp.picale.click/mcp`. Ambos
archivos deben contener el mismo `ASSISTANT_DELEGATION_KEY`, generado al azar y
de al menos 32 caracteres. No imprimir la llave ni copiarla a un ticket.

Validación segura (sólo muestra longitudes y coincidencia):

```bash
API_ENV=/home/contacto_rodtech_cloud/metricol.api/.env.vps
MCP_ENV=/home/contacto_rodtech_cloud/picale-mcp/.env.vps
api_key=$(sudo grep -E '^ASSISTANT_DELEGATION_KEY=' "$API_ENV" | tail -1 | cut -d= -f2-)
mcp_key=$(sudo grep -E '^ASSISTANT_DELEGATION_KEY=' "$MCP_ENV" | tail -1 | cut -d= -f2-)
api_url=$(sudo grep -E '^ASSISTANT_MCP_URL=' "$API_ENV" | tail -1 | cut -d= -f2-)
printf 'API key: %s chars · MCP key: %s chars\n' "${#api_key}" "${#mcp_key}"
test "${#api_key}" -ge 32
test "$api_key" = "$mcp_key"
test "$api_url" = 'https://mcp.picale.click/mcp'
```

Los dos `docker-compose.vps.yml` también rechazan valores ausentes antes de
recrear contenedores.

## 4. Respaldo y migraciones

El árbol actual requiere tres migraciones aditivas. Las dos primeras pertenecen
directamente al asistente; la tercera acompaña los cambios de carga incluidos en
el mismo candidato:

1. `sql/20261021_assistant_generation_requests.sql`
2. `sql/20261021_contenido_jobs.sql`
3. `sql/20261022_media_upload_batches.sql`

Primero solicitar y esperar un respaldo exitoso de Cloud SQL:

```bash
gcloud sql backups create --instance=cmrg --project=cmrg-505321 \
  --description="antes-asistente-<SHA-API>"
gcloud sql backups list --instance=cmrg --project=cmrg-505321 --limit=3
```

Después de subir el candidato a la carpeta de la API, aplicar las tres en una
sola transacción mediante el proxy ya existente. `ON_ERROR_STOP` impide seguir
después del primer error y `-1` revierte el conjunto completo:

```bash
cd /home/contacto_rodtech_cloud/metricol.api
sudo docker run --rm --network metricolapi_default \
  --env-file .env.vps -v "$PWD/sql:/migrations:ro" postgres:18-alpine \
  sh -c 'export PGPASSWORD="$SPRING_DATASOURCE_PASSWORD"; exec psql \
    -h cloud-sql-proxy -U "$SPRING_DATASOURCE_USERNAME" -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 -1 \
    -f /migrations/20261021_assistant_generation_requests.sql \
    -f /migrations/20261021_contenido_jobs.sql \
    -f /migrations/20261022_media_upload_batches.sql \
    -f /migrations/20261022_assistant_release_verify.sql'
```

El último archivo es un gate de sólo lectura: aborta si falta una tabla, índice,
restricción o columna requerida. No eliminar el esquema al volver atrás: es
aditivo y una versión anterior lo ignora. Restaurar el respaldo sólo ante
corrupción comprobada y con una autorización separada.

## 5. Orden de despliegue

1. Detener nuevas generaciones y esperar a que terminen los trabajos en memoria
   de la versión anterior.
2. Confirmar respaldo, migraciones y configuración compartida.
3. Desplegar MCP y verificar su SHA y metadata OAuth.
4. Desplegar API y verificar su SHA, base de datos esperada y salud.
5. Publicar web primero en una rama de vista previa; ejecutar E2E contra el
   conjunto nuevo y luego promover a producción.
6. Construir y distribuir móvil con la compatibilidad para `UNAVAILABLE`.
7. Ejecutar los smoke tests siguientes antes de reabrir generaciones.

Si MCP o API no publican el SHA esperado, detener la secuencia. Los scripts de
despliegue conservan o recuperan la imagen anterior; la base queda con el
esquema aditivo.

## 6. Smoke tests y evidencia

Usar un workspace productivo de prueba y registrar IDs, horas y SHAs, sin datos
personales ni secretos:

- Abrir un hilo, enviar un mensaje de sólo lectura y recibir eventos completos.
- Solicitar una propuesta; comprobar que todavía no existe generación ni cargo.
- Confirmar una vez con un `request_id` nuevo; guardar `run_id`, `request_id` y
  `job_id`.
- Repetir exactamente la confirmación y comprobar que devuelve el mismo trabajo,
  sin segunda llamada pagada ni segundo movimiento de créditos.
- Verificar una fila de `assistant_generation_requests`, un trabajo en
  `contenido_jobs` y estado final `READY`.
- Durante un segundo trabajo controlado, reiniciar únicamente el componente
  autorizado y comprobar recuperación sin duplicación.
- Confirmar que logs y métricas permiten seguir `run_id`/`job_id`, y que no
  contienen la llave de delegación, tokens ni URLs firmadas completas.

| Evidencia | Resultado | Referencia |
| --- | --- | --- |
| SHA API/MCP/web/móvil | listo en candidato local | `assistant-rc-20261023.1`; MCP `fa86a43`; web `f93f1a6`; móvil `4dccec3` |
| Respaldo Cloud SQL exitoso | pendiente | pendiente |
| Migraciones verificadas | pendiente | pendiente |
| Conversación y propuesta | pendiente | pendiente |
| Una confirmación = una generación | pendiente | pendiente |
| Reintento sin cargo duplicado | pendiente | pendiente |
| Recuperación tras reinicio | pendiente | pendiente |
| Logs sin secretos | pendiente | pendiente |

La liberación cambia a `GO` únicamente cuando todas las filas tienen evidencia
actual y no queda ningún gate pendiente.
