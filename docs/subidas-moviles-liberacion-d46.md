# Liberación de API: subidas persistentes D46

Estado: **desplegada y verificada en producción el 12 de noviembre de 2026**. Autorización del usuario: «Puedes liberar la api por favor». Sólo se publicó la API; no se publicó ni reconstruyó la app.

## Versión y alcance

- Commit de entrega: `0bbace69e3173e3767168ba662eb0bf5951a4a8e`, basado en `cb483b7df8179a585432d1db24c65defe9b74fcb` (ver padre exacto en candidate.json).
- Referencia local: `refs/codex/releases/subidas-d46`. Se utilizó un índice Git temporal; rama e índice de trabajo conservados, sin push.
- Cambios: coordinación durable y recuperación de multipart, admisión por negocio, identidad/propiedad de sesiones, protección de finalización/cancelación y recuperación de apertura con respuesta perdida. Incluye esquema aditivo D43+D44.
- Se excluyeron los cambios locales de redacción IA, catálogo musical, renderizador y documentos comerciales. El catálogo conserva los 48 audios del padre. Dockerfile.release reutiliza los audios de la imagen anterior y comprueba sus SHA256.
- `multipartMobileReady=false`; no se habilitó un motor multipart en Flutter. La configuración productiva existente de multipart, `SIN_LIMITES`, se conservó.

## Validación de la versión exacta

- Paquete de 710 archivos cotejados contra el árbol Git (respetando normalización de fin de línea), sin rutas peligrosas, credenciales reales, .git, target ni uploads.
- Suite completa del candidato aislado: **1411 pruebas aprobadas**, 0 fallos, 0 errores y 3 opcionales omitidas; 187 suites, Maven exit 0.
- Se conservan las evidencias D45: 152 pruebas PostgreSQL18.3 y 4 casos HTTP/R2 reales, con prefijo de prueba completamente limpio. Esta liberación no ejecutó de nuevo esas subidas ni utilizó una sesión productiva de Juan.
- Imagen compilada y verificada antes del corte: `sha256:a98e1827e1fbae1e4951236e791b8dea7a933b0fb8d4227c9b54e6a516fb2830`. BUILD_SHA exacto; ffmpeg y ffprobe presentes.

## Respaldo y migración

1. Preflight: servidor api-videos-prod correcto, versión cb483b7 saludable, base configurada coincidente, PostgreSQL18.4, cero multipart y cero subidas simples pendientes.
2. Se detuvo el único contenedor anterior y se repitió la comprobación de cero multipart pendientes. No hubo coexistencia de workers de versiones diferentes.
3. Respaldo de la base realmente configurada: `/home/contacto_rodtech_cloud/respaldos/picale-antes-d46-20261112-222559.dump`, 1896028 bytes, SHA256 `9bafa752caf7188bcadca241c883ebaa7214652d145bbb244f83e588befe1f3a`.
4. Se validó el índice del archivo y su decodificación completa. Se restauró todo el respaldo con pg_restore --exit-on-error en un contenedor PostgreSQL aislado, sin red ni puertos publicados, y se cotejaron conteos y bytes de media_assets.
5. Se ensayaron las migraciones D43+D44 **dos veces sobre esa copia de producción**, comprobando idempotencia. Luego se aplicaron dentro de una transacción a producción, con límites de espera y ejecución, y se comprobaron 13 columnas, upload_scopes, índice de reintentos y unicidad por tenant/clientUploadId. Los conteos y bytes existentes se conservaron durante la migración.
6. El contenedor y volumen temporales de restauración se eliminaron. El respaldo y la imagen previa se conservaron. No se borraron archivos de usuarios ni se modificó el bucket durante la liberación.

## Comprobaciones finales

- Salud Docker: healthy; contenedor `86b96d281780353c868ea36794298a6b236150ac207bb59da3f354d994c7033a`.
- SHA comprobado dentro de la imagen, API local y dirección pública desde el equipo cliente: `https://picale.rodtech.cloud/api/v1/ops/version`.
- Base de datos coincidente, 13 columnas y tabla/índice/unicidad verificados de forma independiente después del arranque.
- .env.vps y docker-compose.vps.yml conservaron exactamente sus hashes. No se tocaron nginx, proxy Cloud SQL, otras APIs ni servicios locales de PostgreSQL.
- Cero errores de ejecución observados hasta 22:28:54 UTC, más de dos minutos después del arranque (22:26:08 UTC). Esto verifica observación operativa; no sustituye un caso autenticado de subida productiva. El código fuente previo quedó respaldado y la fuente entregada instalada en la VM.
- El primer intento del verificador público con Python desde la VM recibió HTTP403; su causa no se estableció. La comprobación pública se realizó por separado desde el cliente y devolvió la versión correcta. Las comprobaciones locales, de esquema, respaldo y salud se completaron por SSH.

## Recuperación

Imagen anterior preservada: `metricol-api:previo-d46-cb483b7`. También se conserva `previous-source.tar.gz` en la carpeta remota de ejecución. No fue necesario rollback.

Antes de volver a un binario antiguo, detener/drenar el actual y comprobar que no existen sesiones multipart pendientes o decisiones nuevas en curso. Con sesiones nuevas, no alternar workers antiguos ni restaurar la base a ciegas. El esquema aditivo se conserva al revertir una imagen; un respaldo previo no incorpora escrituras posteriores.

## Pendientes fuera de esta liberación

- Prueba de extremo a extremo con sesión productiva del usuario y dispositivo móvil. No se acredita con la comprobación pública de versión ni con las pruebas autenticadas locales de D45.
- Integración multipart móvil y pruebas físicas Android; iOS14 requiere Mac y pruebas reales pendientes. La app/APK existente no cambia.

## Evidencia

Directorio local ignorado `target/subidas-d46/`: candidate.json, package.json, payload-verified-fast.json, tests-approved.json, candidate-tests.log, status.json, independent-final.json y public-client.json. Scripts de despliegue y verificación en el mismo directorio; sin secretos reales.

Directorio remoto privado: `/home/contacto_rodtech_cloud/.cache/picale-upload-deploy/picale-subidas-d46-0bbace69e317`. Conserva build.log, backup.json, migration-rehearsal.log, backup-restore.log, previous-source.tar.gz y status.json.
