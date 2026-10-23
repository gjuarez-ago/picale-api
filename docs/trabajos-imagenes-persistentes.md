# Trabajos de imágenes resistentes a reinicios

Fecha: 21 de octubre de 2026. Alcance: creación desde `/api/v1/content/generate`, consumida por asistente/MCP y móvil. No cambia la interfaz ni publica contenido.

Implementación y regresión completadas. Pruebas finales: **127 API**, **14 MCP**, **5 móvil**; cero fallos. Análisis de los dos archivos móviles: sin incidencias. Logs: `target/durable-jobs-verification.out.log` (API), `target/durable-jobs-final.out.log` (MCP), `durable-jobs-model-tests.out.log` y `durable-jobs-analyze.out.log` (móvil).

## Implementación

- `ContenidoJobs` dejó de usar mapas como autoridad. PostgreSQL conserva cola, estado, versiones y resultados; el executor solo presta tres hilos por proceso.
- El alta del trabajo y el débito de créditos internos se confirman en **una misma transacción**. Un conflicto de admisión o fallo de serialización revierte ambos.
- Una restricción única sobre `active_workspace` admite un solo trabajo activo por negocio. Cada claim bloquea la fila, asigna un propietario y un lease; otros procesos no pueden ejecutar ese trabajo mientras su lease siga vigente.
- Poll de cola cada 5 segundos (`app.content-jobs.poll-ms`) y heartbeat cada 15 segundos (`app.content-jobs.heartbeat-ms`). El lease es de 3 minutos. No configurar heartbeat/poll con intervalos cercanos o superiores al lease.
- Se persiste una marca antes de cualquier llamada pagada. La propiedad vuelve a comprobarse antes de los envíos de imágenes y la revisión. Si se pierde el lease, no se inicia otro envío desde ese worker.
- Un trabajo en cola o adquirido **sin haber empezado llamadas pagadas** puede retomarse con la misma preparación y referencia de crédito. No se vuelve a preparar ni cobrar.
- Un trabajo que pudo haber llamado al proveedor termina como `UNAVAILABLE` al expirar su lease. No se declara cancelado ni se regenera automáticamente, aunque el proveedor pudiera haber terminado.
- Cada diapositiva se guarda y hace checkpoint antes de esperar las demás. Un fallo posterior no elimina piezas guardadas ni devuelve automáticamente un crédito de una generación parcialmente realizada.
- Los assets llevan trabajo, versión e índice. Si el proceso cae entre guardar el asset y guardar el checkpoint, o llega tarde un resultado, el estado incierto puede recuperarlo desde los assets de ese negocio. No marca un carrusel parcial como completo.
- La revisión visual sigue siendo una llamada por versión completa, no una por diapositiva.

## Estados y créditos

| Estado | Qué significa | Recuperación |
|---|---|---|
| QUEUED | Preparación y crédito registrados; sin ejecución adquirida | Otro worker puede adquirirlo. |
| THINKING / CREATING | Worker con propietario/lease | Mantiene heartbeat y guarda avance. |
| READY | Versiones completadas y resultado guardado | GET devuelve el resultado desde la base. |
| FAILED | Fallo definitivamente anterior al proveedor | Devolución interna atómica; no se repite. |
| UNAVAILABLE | Hubo o pudo haber una llamada externa y no se verificó todo | Mantener piezas, revisar Contenido y conciliar; nunca reintentar o devolver automáticamente. |

`FAILED` y `UNAVAILABLE` son terminales para el sondeo, no instrucciones para crear otro trabajo. MCP conserva resultados parciales y no promete gratuidad ni devolución. El móvil detiene su espera y muestra el error real en lugar de prometer que se devolvieron créditos. El asistente web ya representa `UNAVAILABLE` como trabajo sin verificar.

La incertidumbre no se resuelve inventando un estado: si el proveedor terminó pero la respuesta nunca llegó, no hay una API de recuperación implementada que permita asegurar el resultado. Hace falta revisión operativa antes de volver a generar o ajustar créditos; este cambio no añade una pantalla administrativa ni realiza esos ajustes por cuenta propia.

## Referencias e instrucciones congeladas

Para no cambiar una petición confirmada después de un reinicio, se guarda una preparación comprimida que incluye las referencias binarias ya validadas. No son credenciales ni logs, pero sí contenido privado del negocio: debe protegerse con los mismos controles de acceso y respaldo que la base de datos.

- Límite de snapshot descomprimido: 96 MiB. Si se excede, se rechaza y revierte el crédito; no recorta instrucciones o fotos silenciosamente.
- La copia binaria se elimina al terminar o quedar incierto el trabajo. Las imágenes finales siguen en el almacenamiento de medios existente.
- Las instrucciones, identidad/contexto confirmado sin binarios, referencia de crédito y resultados permanecen en la fila para auditoría. No se incorporó un borrado automático de ese historial.
- No cambiar esquemas de preparación de trabajos pendientes sin una migración compatible. Si una preparación no puede recuperarse antes de llamar al proveedor, el trabajo falla con devolución interna; si ya pudo ejecutar, se trata como incierto.

## Migración y operación

Aplicar [20261021_contenido_jobs.sql](../sql/20261021_contenido_jobs.sql) antes de desplegar API; incluye tabla, índices y vínculo de assets. Desplegar también la compatibilidad MCP y móvil con `UNAVAILABLE`.

La migración se validó en PostgreSQL local con tablas temporales y `ROLLBACK`; no se ejecutó en producción. No modifica créditos, licencias ni reglas de marca.

API y MCP se reiniciaron localmente tras comprobar cero turnos de asistente activos y cero trabajadores de imágenes activos. Logs de arranque en ambos repositorios: `target/durable-jobs-20261021-095800.out.log`. No se reinstaló ni desplegó la app móvil; sus cambios están en fuentes y pruebas.

Tras arrancar: web, API y MCP respondieron HTTP 200; PostgreSQL contiene la tabla y los tres campos de vínculo de assets. La cola nueva está vacía, sin generaciones reales iniciadas por estas pruebas. La conversación existente y su galería READY se recuperaron por GET sin consumir otra consulta al modelo ni otra imagen.

Antes del primer despliegue, drenar los trabajos de la versión antigua: su cola en memoria no puede reconstruirse retrospectivamente. No hay que generar otra vez ni importar estados ficticios para cubrir esa limitación.

Reversión: conservar tabla, filas y assets; no resetear IDs o referencias de crédito. No arrancar una versión antigua que ignore esta cola mientras existan trabajos activos o inciertos sin una revisión operativa. Nunca limpiar `external_started` para «desbloquear» una generación.

El mecanismo de leases corresponde a estos trabajos de imágenes. No convierte por sí mismo la recuperación de turnos del chat ni otros workers de la plataforma en un diseño multiinstancia.

## Verificación sin servicios pagados

Pruebas dirigidas:

```powershell
# API
mvn -q '-Dtest=ContenidoJobsTest,CampaignImageServiceTest,ContenidoControllerGenerationTest,AssistantOrchestratorTest,AssistantGenerationRegistryTest' test

# MCP
mvn -q '-Dtest=EstadoGeneracionDurableTest,GenerarContenidoToolTest,PoliticaDelCatalogoTest' test

# Móvil
flutter test test/campaign_image_model_test.dart
flutter analyze lib/models/content_job.dart lib/pages/imagenes/asistente_imagenes_page.dart
```

`ContenidoJobsTest` usa una base H2 real y proveedor simulado: recuperación desde otra instancia del servicio, carrera entre dos workers, lease vigente/expirado, resultado incierto, referencias congeladas, piezas parciales/tardías, aislamiento y devolución única. La prueba de admisión rechazada también guarda un movimiento real dentro de la transacción y comprueba que se revierte junto con el alta fallida.

No se autorizaron ni ejecutaron nuevas generaciones pagadas para este cambio. Las pruebas de interrupción son controladas; no equivalen a afirmar una prueba de caída de un proveedor real o un despliegue distribuido completo.
