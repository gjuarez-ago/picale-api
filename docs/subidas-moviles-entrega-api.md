# Entregas de API para subidas móviles

**Entrega vigente: D46, API 0bbace69e3173e3767168ba662eb0bf5951a4a8e liberada el 12 nov 2026.** Respaldo restaurado, esquema aplicado y salud/SHA público verificados. Ver [registro de liberación D46](subidas-moviles-liberacion-d46.md).

## Historial de entrega D33

Estado: **API cb483b7 desplegada y verificada operativamente el 11 nov (D33)**. SHA público, imagen, salud, base/esquema y preservación de configuración/backup comprobados; `deployment-summary.json` guarda evidencia actual. No se publicó app. Pruebas autenticadas/físicas e iOS pendientes. Ver primer checkpoint de avance; las secciones siguientes describen la propuesta original.

## Candidato concreto

- Commit: `cb483b7df8179a585432d1db24c65defe9b74fcb`.
- Destino identificado en los scripts existentes: VM `api-videos-prod`, proyecto `cmrg-505321`, zona `us-central1-a`, contenedor `metricol-api`, servicio de Compose `metricol-api`.
- Endpoint público de versión: `https://picale.rodtech.cloud/api/v1/ops/version`.
- Observación de sólo lectura del 11 nov: producción reportó `e266b2bac10c7dc11e28137e7c0f0f9247690ce8`, diferente al candidato. Es una observación temporal, no una garantía de que siga igual al autorizar. Evidencia: `target/subidas-entrega/version-readonly.json`; no se guardaron credenciales ni nombre de base.
- Fuente congelada: `target/subidas-entrega/cb483b7/source.tar`, generada mediante `git archive` del commit exacto. Sin archivos reales `.env`/`.env.vps`. Los cambios sin commit del usuario quedan fuera.
- Payload: `target/subidas-entrega/cb483b7/api-cb483b7.tar.gz`, misma fuente más `BUILD_SHA`, con metadata/hash en `package.json` de esa carpeta. 2,942,478 bytes, SHA256 `983daa4b24b6f711174333f9563116af260a3568ab219eab93e7fdd3ee8fffe9`. Validación `package-validation.json`: 751 archivos esperados, ninguna diferencia en lista, rutas peligrosas ni archivos excluidos; BUILD_SHA exacto. No se copió a la VM.

### Cambios que viajarían respecto de la versión observada

| Commit | Cambio |
|---|---|
| `2e2bff8` | Corrección del revisor de flyers para teléfono/precios propios |
| `ce19a22` | Hasta tres objetivos de redes y cinco objetivos nuevos |
| `cb483b7` | Contrato de subidas Android: clave idempotente, firmas, consulta/recuperación y finalización desde worker |

No presentar la entrega como si contuviera únicamente uploads. La autorización debe referirse a este conjunto exacto. No crear commits ni cherry-picks para cambiarlo sin una instrucción nueva.

## Verificación antes de solicitar la entrega

- Evidencia previa: 52 pruebas seleccionadas de API, 35 Flutter, análisis y firmas APK/AAB del checkpoint D31.
- Suite completa del candidato terminada: 183 suites/1,339 tests. Primera corrida exit1 por una fixture MP3 no versionada ausente; cero fallos de aserción, un error y tres omisiones. Se conservó ese reporte en `initial-results/`, se copió únicamente `latin-pop-06.mp3` local tras verificar SHA256 contra el catálogo del commit y se reejecutó sólo `FfmpegReelRendererIntegrationTest`: exit0, 1/1 aprobada. Fuente/audio originales conservados; payload sin cambios. Resultado consolidado: **1,336 aprobadas, 3 omitidas, 0 fallos/errores**. Las omisiones corresponden a `VisualIdentityEvaluationTest` optativo sin `PICALE_VISUAL_EVAL`.
- Ejecución sobre copia aislada, Java17/H2 en memoria e importación opcional inexistente. Logs `api-candidate-tests.log`, `api-candidate-fixture-retry.log`, reportes iniciales/finales y `verification.json` bajo `target/subidas-entrega/cb483b7/`. Este resultado consolidado no se presenta como una única corrida completa verde, CI Linux o validación de PostgreSQL productivo.
- GitHub CLI está instalado pero no autenticado; no se inventa un check CI remoto ni se inicia login.
- La imagen Docker real se construye en la VM según los scripts del proyecto. No se construyó localmente ni se confirma acceso SSH por la mera existencia de esos scripts. Ejecutar comandos de producción requiere autorización concreta.

## Cambios de base y precauciones observadas

El perfil prod usa Hibernate `ddl-auto: update`. El candidato agrega `media_assets.client_upload_id` con unicidad `(tenant_id, client_upload_id)`; también incorpora `objetivos_elegidos` y un runner que retira `workspaces_objetivo_check`. Confirmar nombres efectivos/esquema al desplegar. H2 no acredita ese DDL en PostgreSQL.

Antes de arrancar la versión nueva, verificar un respaldo de la **base indicada por `DB_NAME` en el `.env.vps` de la VM**. No imprimir ese archivo ni sus valores sensibles. Los scripts existentes son referencias, no prueba de respaldo o de acceso actual.

Hallazgos concretos de los scripts:

- `deploy-vps.sh` comprueba respuesta/base; añadir al procedimiento una comparación explícita del SHA completo después. Que el servidor responda no demuestra que sirva el candidato.
- `deploy-ssh.sh --respaldo` contiene un nombre de base fijo y el script remoto usa `set -e` sin `pipefail`; un gzip válido por sí solo no demuestra que `pg_dump` tuvo éxito. No usar ese respaldo sin comprobar base y éxito de cada fase. Esta preparación no modifica el script existente.
- `deploy-ssh.sh` etiqueta la imagen anterior, pero su texto final no basta como prueba de salud: verificar `healthy`, SHA, base y ausencia de fallo de arranque de forma explícita.

## Secuencia propuesta, sólo tras autorización

1. Reconsultar versión pública. Si cambió respecto del SHA observado, detener la propuesta y comparar el nuevo estado antes de reemplazarlo.
2. Confirmar acceso al destino previsto, entorno/base correctos y ausencia de otro despliegue. Conservar la imagen anterior y registrar su ID real; crear/verificar respaldo contra la base efectiva. No modificar nginx, DNS, R2, MCP ni los otros servicios de la VM.
3. Verificar hash del payload local y remoto. Conservar `.env.vps` de la VM. Usar el flujo de Compose existente con `--project-directory`, `--env-file` y `-f` explícitos; construir sólo el candidato revisado. No ejecutar scripts que empaqueten un HEAD distinto del aprobado.
4. Arrancar y comprobar SHA **completo** `cb483b7df8179a585432d1db24c65defe9b74fcb`, base efectiva coincidente con configuración de servidor, contenedor healthy y arranque correcto. Evitar volcar logs completos o credenciales.
5. Con una sesión de pruebas autorizada, comprobar `/media/usage` y `subidaEnFondo: 1`; hacer prueba controlada de reserva/consulta/confirmación y cuota. No usar credenciales ni medios de otros negocios. Identificar y retirar únicamente los medios de prueba mediante el flujo normal.
6. Registrar evidencia y entonces instalar el APK Android D31 para ejecutar `subidas-moviles-validacion-android.md`. Publicar en Play continúa fuera de esta autorización.

Si falla un gate, detener la entrega y preservar evidencia; no declarar listo por un HTTP 200 o por el mensaje del script.

## Recuperación

Conservar ID/tag de la imagen anterior y respaldo verificado antes de reemplazar. Revertir la imagen requiere comprobar compatibilidad con los datos ya escritos: los objetivos nuevos son valores de enum que la versión anterior puede no reconocer. No prometer rollback automático ni restaurar base/borrar columnas de forma automática; un respaldo no autoriza descartar datos posteriores. Resolver el incidente con el estado real y autorización aplicable.

## Autorización que se solicitará al terminar la preparación

Autorizar únicamente la entrega de la API de este commit y sus tres cambios al destino descrito, con respaldo verificado y comprobaciones posteriores; sin publicar la app. La preparación terminó y se solicita esa decisión concreta. El punto 9 del objetivo exige autorización aplicable para cambios de producción; esta continuación automática no la aporta.

Respuesta recibida: «Autorizar ese despliegue de API». Preflight/respaldo real verificados y job D33 terminado succeeded/exit0. Verificación independiente SSH y HTTP público confirma entrega; PostgreSQL confirma cambios de esquema, health healthy y cero errores de arranque. Backup e imagen previa conservados; entorno privado intacto. No repetir autorización/despliegue. Quedan pruebas autenticadas con cuenta autorizada y matriz física; conexión Pícale consultada requiere reautenticación. No declarar la funcionalidad completa por el despliegue.
