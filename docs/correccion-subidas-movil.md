# Corrección de subidas móviles — 22 de octubre de 2026

## Incidente

Reporte: selección/subida de fotos y videos desde Contenido, fallos con varios archivos y asociación incorrecta. Negocio indicado: **CMRG S.A. de C.V.**

El negocio no apareció en la base local. El intento de diagnóstico de producción fue rechazado porque Google Cloud necesita reautenticación (`gcloud auth login`). **No se ha confirmado la causa de los cinco archivos concretos ni modificado sus originales en producción.**

## Fallos comprobados en código y cambios

- Contenido abría `pickMultiImage`: ahora comparte un selector mixto y un diálogo de carga con el acceso del agente.
- Una extensión desconocida se clasificaba como JPEG: ahora se inspeccionan hasta 64 bytes y se reconocen también videos M4V/3GP/MKV/AVI. Un archivo desconocido no se presenta como foto.
- La transferencia no tenía límite de espera: conexión de 30 s, inactividad de 90 s y máximo de 30 minutos por archivo. Conserva el envío por streaming, sin cargar videos completos en memoria.
- El diálogo muestra cada archivo, progreso, confirmación, error y reintento de pendientes. Conserva los éxitos y el mismo ID de reserva durante los reintentos en esa pantalla.
- Confirmación idempotente: hasta tres intentos ante errores transitorios; si los bytes ya llegaron, reintentar no repite el PUT.
- Renovación de firma sobre el mismo archivo pendiente, sin otra reserva de cuota; valida el negocio y actualiza la referencia temporal usada por la limpieza de pendientes.
- Una selección lleva `uploadBatchId` y posición. Las fotos del agente se agrupan por esa identidad, no solo por proximidad horaria. Espera los hermanos pendientes recientes; los intentos abandonados no bloquean indefinidamente el resto.
- Ni confirmación ni reconciliación de fondo aceptan como completo un objeto menor al declarado. Los originales incompletos se conservan pendientes para revisión/reintento.
- Android: al entrar a Contenido se ofrece recuperar una selección que el sistema haya devuelto tras cerrar la actividad del selector. Se pregunta antes de subir al negocio actualmente abierto.
- Se comprueba que la sesión no cambió entre reserva, transferencia y confirmación ni entre archivos del lote.

## Alcance y límites

- No combina automáticamente videos y fotos en una sola publicación. Preserva el lote de origen; los formatos y decisiones del agente siguen sus reglas actuales.
- No reconstruye lotes históricos ni asegura que los cinco archivos reportados estén recuperados: requiere inspeccionar CMRG en producción.
- La carga sigue siendo de primer plano. El diálogo indica mantener la app abierta. No se implementó un servicio nativo de transferencias en segundo plano ni una cola persistente de uploads tras cerrar la app.
- La recuperación de Android cubre el selector; no promete reanudar una transferencia que el sistema haya matado.
- Los tests utilizan un servidor HTTP local, servicios simulados y H2; no representan una prueba en el teléfono del usuario ni en R2 de producción.

## Activación

1. Aplicar `sql/20261022_media_upload_batches.sql` antes de desplegar la API. Es aditivo y no borra archivos.
2. Desplegar el backend con el endpoint de renovación y el agrupamiento explícito. El árbol de trabajo contiene otros cambios: no desplegarlo completo sin revisar el alcance.
3. Compilar y distribuir una nueva versión de la app móvil. Reiniciar la API no actualiza una app ya instalada.
4. Verificar en CMRG: selección de cinco archivos mixtos, confirmación de cada ID, MIME/bytes en almacenamiento, miniaturas y lote de origen. Revisar también corte de conexión y reintento, sin publicar contenido.

## Pruebas

Resultado: **23 pruebas aprobadas** (12 Flutter y 11 API), sin fallos. Análisis estático de los siete archivos Dart revisados: sin incidencias. La ejecución final de API usó H2 aislado y terminó con `BUILD SUCCESS`.

- Flutter: `media_upload_test.dart`, `subir_contenido_test.dart`, `mandar_al_asistente_test.dart`, `contenido_campana_test.dart`.
- API: `MediaTopeDiarioTest`, `OrganizadorDeContenidoTest`.
- Resultados de la ejecución final: `target/mobile-upload-final.out.log` (API) y `../metricol_app/mobile-upload-verified.out.log` (Flutter).

No desplegado en producción ni instalado en el teléfono durante esta intervención.
