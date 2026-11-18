# Manifiesto multipart móvil v4 — D47

Unidad autorizada por «vamos», después del siguiente paso propuesto: guardar el
avance de cada parte antes de conectar las tareas nativas Android. Implementación
local del modelo y almacenamiento. **No habilita todavía transferencias multipart
en la app**, no cambia la API productiva D46 ni genera un APK.

## Archivos y activación explícita

En `metricol_app`:

- `lib/models/sesion_de_subida_por_partes.dart`: sesión, rangos, intentos y
  reconciliación del snapshot remoto, sin llamadas a red o plugins.
- `lib/models/trabajo_de_subida.dart`: asociación de sesión con usuario, negocio,
  archivo preparado, SHA256 local, huella, lote, posición y assetId.
- `lib/services/registro_de_subidas.dart`: lector/escritor v4 opcional y
  actualizaciones serializadas con comprobación de continuidad.
- `test/registro_multipart_test.dart`: recuperación en otra instancia, migración,
  concurrencia, identidad, rangos, generaciones y conservación ante errores.

El constructor predeterminado de `RegistroDeSubidas` conserva v3. Sólo
`RegistroDeSubidas(carpeta, admiteMultipart: true)` permite migrar y escribir v4.
El orquestador actual `SubidasEnFondo` sigue usando el constructor predeterminado:
no interpreta una sesión multipart como un PUT completo. Tampoco se cambió
`multipartMobileReady=false` en el servidor.

La versión **4 del archivo local** es distinta de la versión **2 del protocolo
HTTP**. No incrementar una capacidad para dar por implementada la otra.

## Contenido durable

La sesión guarda clientUploadId y assetId, identidad local de metadatos, cantidad
de bytes, tamaño de parte, vencimiento y próxima consulta UTC, revisión de consulta,
partes confirmadas e historial de intentos. No guarda tokens, URLs firmadas,
credenciales ni uploadId interno de R2.

La identidad local incluye propietario, negocio, lote/posición, nombre y archivo
privado, MIME, bytes, huella y SHA256 completo local. Se coteja con el trabajo al
leer y antes de guardar. No pretende ser el hash interno de identidad de la API
ni una firma de seguridad del archivo JSON.

Los rangos se calculan desde parámetros inmutables: inicio inclusivo y final
exclusivo. Las partes son contiguas; la última puede ser menor. No se crean copias
de archivo por parte. Se validan números, límite de 10,000 partes y tamaño de parte
entre 5 MiB y 5 GiB. Los límites de producto y espacio siguen siendo requisitos
adicionales del preparador y la API.

## Planificación y eventos

1. Consultar el snapshot completo de partes de la API. Debe coincidir assetId,
   total, tamaño y cantidad de partes; no admite duplicados, números sobrantes,
   tamaños incorrectos ni una suma compensada. Un error no cambia el snapshot.
2. `planificar(numero, ahora: instanteUtc)` devuelve un estado nuevo con intento
   `planificado`. Exige sesión no vencida y consulta remota previa. Cada intento
   tiene un ID determinista de 128 bits derivado de SHA256 de clave, parte y
   generación, y se conserva al reconstruir desde disco.
3. **Guardar antes de entregar al sistema.** Usar `registro.actualizar` y esperar
   su Future antes de llamar al motor. El callback de actualización es síncrono;
   no debe ejecutar red ni operaciones nativas dentro de la cola de persistencia.
4. Con evidencia del motor, actualizar a entregado/enviado/fallido/cancelado. Un
   timeout consultando al motor no prueba ausencia ni cierre de una tarea.
5. No crear otra generación mientras la anterior esté activa. Tras cerrarla,
   exigir otra consulta remota posterior al cierre antes de reintentar. Esta
   revisión también evita usar una consulta que llegó mientras la parte seguía
   activa. Conservar IDs anteriores e ignorar eventos de generaciones antiguas.

`enviado` no suma bytes confirmados. Sólo las partes de la consulta remota los
suman. Tener todas las partes confirmadas tampoco marca el trabajo como `lista`:
eso requiere READY de la API, que deberá consumir el futuro orquestador.

## Escritura y continuidad

Un único propietario Dart del registro serializa lectura/modificación/escritura.
`actualizar` lee dentro de esa cola para no perder avances de callbacks
simultáneos. Se valida el contenido, se escribe con flush en archivo temporal y
se reemplaza por rename; el snapshot previo se conserva en `.bak`.

Una escritura atrasada no puede olvidar intentos, retirar una sesión multipart,
cambiar asset/identidad/tamaño/plazo, retroceder una revisión ni reabrir intentos
terminales. Tras un fallo, la cola sigue disponible y el último archivo válido
permanece intacto. Esta etapa aún no ofrece purga de trabajos multipart: deberá
añadirse con comprobación de estado remoto terminal y tareas nativas detenidas.

El contrato es un escritor por registro en el isolate de dominio; no es un
candado entre procesos o isolates. El plugin conservará sus propias tareas en su
almacenamiento. Un reemplazo con flush no promete durabilidad ante toda falla de
hardware o pérdida de energía.

## Migración y errores

El lector v4 puede leer v1/v2/v3 y escribirlos como trabajos simples v4 sin cambiar
IDs nativos, archivo ni propietario. V1 conserva propietario nulo: no se adopta
automáticamente al usuario actual. No hay conversión de una sesión simple ya
abierta a multipart.

El lector predeterminado v3 rechaza un archivo v4 antes de recuperar un backup
viejo o sobrescribir datos. Versiones/modalidades/estados desconocidos también se
conservan sin reinterpretarlos.

En modo v4, un archivo dañado **no se sustituye automáticamente por `.bak`**:
ese backup podría olvidar una generación ya entregada al sistema. Se conservan
ambos archivos y se informa el fallo. La reparación con inventario nativo y sesión
remota es trabajo posterior; no existe todavía un reparador automático. La
recuperación histórica de backup v3 se conserva para el motor simple actual.

## Validación y siguiente unidad

Validación: **397 pruebas de la app aprobadas**; **27 pruebas finales del registro aprobadas** (17 nuevas multipart y 10 anteriores), sin fallos. Análisis final de los cuatro archivos modificados, con --fatal-infos, sin incidencias. La suite completa pasó antes del último endurecimiento de campos obligatorios v4 y correcciones de estilo; el pase focalizado final verifica ese código. La evidencia y hashes se registran en `target/subidas-d47/report.json`. Las pruebas
usan disco temporal y objetos de dominio; no representan reinicio real de Android,
transferencia por rangos ni ejecución con pantalla bloqueada.

Siguiente unidad: adaptar `MediaService` a respuestas multipart v2 y conectar este
registro al orquestador Android, con reconciliación de tareas antes de cualquier
handoff/reintento, firma por tandas, cancelación y confirmación remota. Después,
validar integración nativa y APK; mantener deshabilitada la capacidad móvil hasta
tener las comprobaciones necesarias. iOS continúa pendiente de Mac.
