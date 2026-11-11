# Goal: subidas móviles persistentes, nativas y reanudables

Fecha de preparación: 8 de noviembre de 2026.  
Producto: Pícale.  
Estado: plan de ejecución; implementación pendiente.  
Alcance: app Flutter Android/iOS, API Java y almacenamiento Cloudflare R2.

## 1. Objetivo

Permitir que una persona seleccione fotos o videos, comience la carga y cambie de aplicación o bloquee el teléfono sin perder el trabajo. Cuando el sistema interrumpa la ejecución, conservar el archivo y la sesión para recuperar la carga desde las partes confirmadas en R2. El servidor debe completar y confirmar archivos íntegros sin depender de que Flutter permanezca activo.

El resultado debe integrarse con las tarjetas actuales de Contenido, las cuotas, el aislamiento por negocio, la detección de repetidos y los lotes de origen.

Este archivo sirve como especificación y lista de ejecución para un goal posterior. Su creación no significa que el goal esté activado ni que la funcionalidad esté implementada.

## 2. Resultado observable y límites

### Comportamientos requeridos

- Al cambiar a WhatsApp, navegador u otra aplicación, las tareas entregadas al sistema pueden continuar sin el proceso Dart activo.
- Al bloquear la pantalla, la transferencia continúa conforme a las condiciones y restricciones del sistema operativo.
- Si se pierde la red, el trabajo queda registrado y se recupera sin una nueva selección del archivo.
- Si el proceso muere, al abrir Pícale se reconstruyen los trabajos y se consulta lo que realmente llegó a R2.
- En multipart, las partes ya aceptadas no vuelven a enviarse durante una reanudación normal.
- Si todas las partes llegaron, el servidor une y confirma el archivo sin esperar a que la persona abra la app.
- El medio sólo se muestra listo cuando la API confirma su disponibilidad.
- Cancelar y reintentar no crean cargos de cuota o reservas duplicados.
- Cada trabajo conserva su usuario y workspace de origen.
- Ningún trabajo desaparece silenciosamente por un reinicio, un error transitorio o una limpieza automática.

### Límites que el producto debe expresar con precisión

- No se garantiza ejecución continua frente a un cierre forzado manual, apagado, restricciones del fabricante o recursos insuficientes.
- En iOS, cerrar desde el selector de aplicaciones cancela las transferencias de fondo y exige volver a abrir la app para iniciarlas de nuevo.
- Recuperar significa conservar la sesión y continuar desde las partes que R2 tenga; la parte que estaba a medias puede enviarse de nuevo.
- Un PUT simple interrumpido puede requerir reenviar el archivo completo. No presentar ese caso como reanudación por bytes.
- Las URLs firmadas pueden vencer mientras el sistema espera para ejecutar. La recuperación debe poder renovarlas.
- El trabajo incluye subir a Contenido; no incorpora cambios de publicación automática ni de procesamiento creativo.
- El soporte actual es para imágenes y videos aceptados por la API. No ampliar a documentos u otros tipos de archivo sin una decisión explícita.

## 3. Repositorios y evidencia inicial

Raíz compartida local: `C:/Users/bicos/Downloads/PULSO APP`.

| Repositorio | Ubicación relativa | Función |
|---|---|---|
| API | `metricol.api` | Autorizar, reservar cuota, firmar, verificar, completar y limpiar |
| App Flutter | `metricol_app` | Preparación local, trabajos persistentes, transferencias y presentación |
| Web | `metricol_web` | Cliente existente que debe mantener compatibilidad; fuera de implementación salvo dependencia comprobada |

Hallazgos comprobados al preparar el plan:

| Archivo | Situación actual |
|---|---|
| `metricol_app/lib/services/cola_de_subidas.dart` | Singleton con lista en memoria y ejecución secuencial Dart; sobrevive a navegación interna, no a muerte del proceso |
| `metricol_app/lib/services/media_service.dart` | Reserva por presign, PUT con `dart:io`, confirmación y reintentos de confirmación |
| `metricol_app/lib/models/storage_usage.dart` | No interpreta todavía `multipartFromBytes` |
| `metricol_app/lib/services/session_service.dart` | Vacía parte de la cola al cambiar de negocio o cerrar sesión; requiere adaptación |
| `metricol_app/android/settings.gradle.kts` | Kotlin `2.2.20` observado; verificar compatibilidad con la versión seleccionada del paquete |
| `metricol_app/pubspec.yaml` | Versión observada `1.0.13+14`; no hay motor de transferencias persistente instalado |
| `metricol.api/.../controller/MediaController.java` | Endpoints multipart disponibles |
| `metricol.api/.../service/MediaService.java` | Apertura, firmas, consulta, unión, cancelación y confirmación multipart |
| `metricol.api/.../service/storage/MediaOrphanWorker.java` | Rescate periódico mediante confirmación del objeto completo y limpieza de pendientes antiguos |
| `metricol.api/.../service/storage/MediaAssetReconciler.java` | Puede abortar multipart cuando no existe objeto final; necesita coordinación con recuperación y finalización |
| `metricol.api/.../repository/MediaAssetRepository.java` | Busca pendientes por `coalesce(upload_renewed_at, created_at)` |
| `metricol.api/src/main/resources/application.properties` | Multipart apagado por defecto, umbral de 100 MiB, partes de 16 MiB, firmas de parte de una hora |

Los valores anteriores describen el código local observado. No prueban el estado desplegado, los valores de producción ni el comportamiento de una app instalada. Revalidarlos al comenzar.

Documentación relacionada:

- [Subida por partes](subida-por-partes.md).
- [Correcciones anteriores de subidas móviles](correccion-subidas-movil.md).

## 4. Decisión de arquitectura

Usar `background_downloader` como candidato principal al motor nativo de transferencias. Encapsularlo detrás de un servicio de Pícale. Mantener la API y R2 existentes; extender el servidor para finalizar cargas completas de forma autónoma.

La selección definitiva del paquete depende de una prueba técnica contra R2 y compilaciones de release en ambas plataformas. Fijar una versión publicada concreta; no depender de `main` ni copiar APIs de una documentación que no corresponda a esa versión.

```text
Contenido / captura / otros puntos de entrada
                    |
             GestorDeSubidas
          /          |          \
 Archivo local   Registro de    Adaptador del motor
 duradero        trabajo        background_downloader
                                  |
                           Android / iOS
                                  |
                           PUT directo a R2
                                  |
API: sesión -> consulta de partes -> unión -> confirmación -> READY
```

### Reglas de diseño

1. El motor administra ejecución nativa, progreso y registros de transferencia.
2. Un registro de dominio pequeño administra archivo, propietario, sesión, lote y tareas asociadas. No duplicar todo el motor.
3. La interfaz observa estados; no es responsable de mantener viva la carga.
4. R2 es la autoridad sobre las partes recibidas. El contador local ayuda a presentar progreso, no demuestra integridad.
5. La API es la autoridad sobre disponibilidad, cuota, permisos y estado final.
6. La finalización remota debe funcionar sin callbacks de Flutter.
7. La limpieza se coordina mediante la sesión; no interpretar silencio de Dart como abandono.
8. Operaciones HTTP hacia R2 no deben llevar el JWT de Pícale.
9. Reutilizar servicios existentes y evitar introducir un microservicio, proveedor o protocolo nuevo si la prueba del motor cumple.

## 5. Fase 0: inspección y línea base

- [ ] Leer los `AGENTS.md` aplicables en los repositorios y directorios que se modificarán.
- [ ] Revisar `git status` en API y móvil; preservar cambios existentes y registrar el alcance propio.
- [ ] Inventariar todos los puntos que llaman a `MediaService.upload`, `preparar`, `enviar` y a `ColaDeSubidas`.
- [ ] Identificar rutas de Contenido, asistente, captura y creación de publicaciones que deben usar el gestor común.
- [ ] Separar cargas pequeñas especiales, como logo/perfil, y decidir si se migran o conservan su camino actual.
- [ ] Revisar pruebas existentes de cola, media, selector y lotes.
- [ ] Revisar autenticación, permisos, tenant, cuota y detección de repetidos en ambos caminos de API.
- [ ] Verificar versiones efectivas de Flutter, Dart, Gradle, Kotlin, Android SDK y mínimo iOS.
- [ ] Confirmar acceso a Mac/Xcode, firma y iPhone para la verificación iOS. Windows permite desarrollar parte del código, pero no acredita una compilación iOS.
- [ ] Establecer bucket/prefijo y cuenta aislados para pruebas externas autorizadas.
- [ ] Registrar valores de configuración relevantes sin copiar secretos a documentos ni logs.

Entregable: inventario de puntos de integración, línea base de pruebas y riesgos concretos. No asumir que la revisión anterior cubre cambios posteriores.

## 6. Fase 1: prueba técnica del motor

Construir una prueba acotada antes de reemplazar el flujo completo.

### Pruebas obligatorias

- [ ] Seleccionar una versión publicada del paquete y revisar licencia, mantenimiento, requisitos y limitaciones de esa versión.
- [ ] Subir un archivo por PUT binario a una URL firmada real de prueba.
- [ ] Verificar método, cuerpo, `Content-Length` y encabezados efectivos.
- [ ] Comprobar que los encabezados agregados por el paquete no rompan la firma.
- [ ] Subir una parte multipart utilizando un rango del archivo original.
- [ ] Confirmar límites inclusivos/exclusivos del rango y contenido exacto de primera, intermedia y última parte.
- [ ] Verificar que el encabezado `Range` usado para recortar localmente no se envíe indebidamente a R2.
- [ ] Revisar manejo de `Content-Type`: la firma actual de partes no lo requiere; si el motor lo agrega, validar compatibilidad o adaptar la firma de forma explícita.
- [ ] Confirmar que el modo binario no envía un cuerpo `multipart/form-data`.
- [ ] Cambiar de app y bloquear pantalla en Android e iOS físicos.
- [ ] Terminar el proceso por el sistema y reconstruir las tareas al abrir.
- [ ] Simular 403 por firma vencida y demostrar recuperación con una firma nueva.
- [ ] Verificar reintentos y duración máxima de cargas en Android anteriores a 14.
- [ ] Diferenciar capacidades de carga de las de descarga: `allowPause` no demuestra reanudación de un upload.
- [ ] Compilar release Android con reducción de código y release iOS con la configuración real.
- [ ] Medir uso de memoria y espacio temporal, especialmente por copias de rangos en iOS.

No confiar en una cola Dart auxiliar para mantener partes pendientes: las tareas deben quedar entregadas al planificador nativo. Si una cola nativa intermedia pierde pendientes al morir el proceso, el registro de dominio debe permitir reconstruirlos sin perder la sesión.

### Criterio de avance

Avanzar si funciona PUT firmado, rango binario, persistencia y transferencia con pantalla bloqueada en ambas plataformas. Si una limitación es corregible mediante configuración o un adaptador pequeño, documentarla y resolverla. Si exige mantener un fork importante o no cumple el caso principal, registrar evidencia y elegir otra solución antes de integrar todo el producto.

Entregable: decisión técnica con versión fijada, plataformas probadas, resultados y limitaciones. No cambiar de proveedor de almacenamiento como efecto incidental.

## 7. Fase 2: sesión de subida y contrato API

### Reutilización de endpoints

| Endpoint existente | Uso previsto |
|---|---|
| `GET /media/usage` | Cuota y capacidad multipart para nuevas sesiones |
| `POST /media/presign` | Reserva y firma simple |
| `POST /media/{id}/presign` | Renovación simple con la misma reserva |
| `POST /media/{id}/confirm` | Confirmación rápida de PUT simple |
| `POST /media/multipart` | Apertura de sesión multipart |
| `POST /media/{id}/multipart/parts` | Firmar números de parte |
| `GET /media/{id}/multipart/parts` | Reconciliar partes reales |
| `POST /media/{id}/multipart/complete` | Solicitar finalización inmediata |
| `DELETE /media/{id}/multipart` | Cancelación de multipart |

### Cambios de contrato necesarios

- [ ] Incorporar una clave de idempotencia estable creada por el móvil antes de la primera petición de reserva.
- [ ] Aplicar unicidad en base de datos con scope de propietario/workspace; un chequeo previo en memoria no basta.
- [ ] Misma clave y mismos metadatos: devolver la misma sesión sin consumir otra reserva o contador diario.
- [ ] Misma clave con tamaño, huella o identidad incompatibles: responder conflicto explícito.
- [ ] Resolver la respuesta perdida durante apertura: permitir recuperar el `assetId` por la misma clave.
- [ ] Exponer consulta de sesión por ID para trabajos pendientes y terminales. Debe permitir distinguir `READY`, cancelado, vencido y recuperable.
- [ ] Conservar el comportamiento de clientes antiguos cuando no manden los campos nuevos.
- [ ] Exponer expiración de sesión y de firmas usando instantes UTC inequívocos.
- [ ] Definir una capacidad/versionado para que una app nueva no asuma que un backend antiguo admite el protocolo completo.
- [ ] Permitir completar, consultar o cancelar sesiones existentes aunque se deshabilite abrir nuevas sesiones multipart.
- [ ] Validar límites del lote, propiedad, permisos actuales, cuota y repetidos en ambos caminos.

### Datos de sesión propuestos

Reutilizar `MediaAsset` cuando sea suficiente. Crear una tabla complementaria sólo si hace falta separar el ciclo de transferencia del ciclo del medio.

| Dato | Propósito |
|---|---|
| `clientUploadId` | Apertura idempotente y recuperación tras respuesta perdida |
| Propietario y workspace | Aislamiento; no se deducen del workspace que esté abierto después |
| Metadatos de archivo | Tamaño declarado, MIME, nombre y huella |
| Modalidad y parámetros multipart | Reconstruir tareas sin una nueva reserva |
| Estado de sesión | Coordinar completar, cancelar, vencer y fallar |
| `expiresAt` | Límite explícito de recuperación |
| `nextCheckAt`, intentos y último error | Controlar consultas y fallos del worker |
| Versión/lease de finalización | Evitar que dos procesos completen o cancelen simultáneamente |
| Lote e índice | Mantener intención de selección y agrupación |

No asumir que la huella actual verifica criptográficamente el objeto almacenado. Auditar cómo se calcula; un ETag multipart tampoco es un SHA-256 del archivo completo. Distinguir identidad/deduplicación de verificación de integridad.

## 8. Fase 3: finalización autónoma y limpieza

### Worker de finalización

- [ ] Reutilizar la infraestructura programada existente con consultas específicas para sesiones recuperables.
- [ ] Procesar lotes acotados e indexados; no consultar todos los objetos en cada vuelta.
- [ ] Aplicar el tenant antes de abrir la transacción correspondiente.
- [ ] Tomar un lease o reclamar la sesión con actualización condicional antes de finalizar.
- [ ] Consultar las partes reales de R2, con paginación si corresponde.
- [ ] Validar conjunto exacto de partes, ausencia de números extra y tamaño individual esperado; validar también suma total.
- [ ] Una parte inválida no debe provocar publicación de un medio corrupto.
- [ ] Unir usando la lista y ETags obtenidos por el servidor.
- [ ] Ejecutar el mismo camino normal de confirmación, derivados, cuota y estado del medio.
- [ ] Hacer idempotentes los efectos posteriores; evitar miniaturas, análisis o cobros duplicados.
- [ ] Manejar respuesta perdida de `CompleteMultipartUpload`: consultar el objeto final y recuperar la confirmación.
- [ ] Manejar caída después de unir pero antes de persistir el resultado; `NoSuchUpload` por sí solo no demuestra pérdida del archivo.
- [ ] Evitar mantener transacciones o conexiones de base abiertas durante operaciones lentas de R2.
- [ ] Reintentar fallos de R2 con backoff y conservar datos necesarios para recuperar.
- [ ] La ruta HTTP de completar debe usar el mismo mecanismo de coordinación que el worker.

### Expiración y coordinación con limpieza

- [ ] El limpiador antiguo debe excluir o tratar explícitamente las sesiones nuevas protegidas por su plazo.
- [ ] Una transferencia activa no debe requerir heartbeats Dart para seguir protegida.
- [ ] No fijar un plazo de sesión menor que el horizonte de las firmas válidas más un margen de terminación.
- [ ] La expiración debe considerar también la regla real de aborto multipart del bucket.
- [ ] Consultar partes desde el servidor puede ayudar a detectar avance; no renovar eternamente una sesión sin progreso.
- [ ] Definir retención de recuperación y expiración absoluta acotada. Registrar los valores elegidos y su justificación operacional.
- [ ] Una sesión pausada durante más tiempo que su retención debe mostrarse vencida; conservar el archivo local para reiniciar con consentimiento del usuario.
- [ ] Abortar en R2 antes de liberar definitivamente la reserva multipart; si R2 falla, conservar una tarea de limpieza recuperable.
- [ ] El aborto debe tratar de forma idempotente una subida ya inexistente.
- [ ] Si la unión ganó la carrera con la cancelación, seguir una política explícita: cancelar la sesión pendiente o informar que el medio ya está disponible; no borrar un `READY` utilizado por otra operación.

Cadencia inicial sugerida para finalización: 30–60 segundos, con backoff y ajuste según volumen/costo. Es una propuesta por validar, no una configuración ya existente.

## 9. Fase 4: gestor móvil y archivo local

### Preparación durable

- [ ] Crear `clientUploadId` y persistir intención antes de pedir la reserva.
- [ ] Copiar por streaming a almacenamiento privado de soporte de aplicación, fuera de caché purgable.
- [ ] Guardar ruta relativa a una base estable; evitar depender de rutas absolutas de contenedor iOS entre ejecuciones.
- [ ] Usar archivo temporal de preparación y renombrado final para distinguir copias completas.
- [ ] Comprobar espacio disponible, tamaño copiado y legibilidad.
- [ ] Resolver eliminación del original durante la copia sin dejar un trabajo fingiendo estar listo para subir.
- [ ] Detectar archivo vacío o MIME no admitido antes de gastar datos.
- [ ] Mantener detección de repetidos y confirmación explícita de «subir de todos modos».
- [ ] No comprimir ni modificar silenciosamente videos como parte de esta mejora.
- [ ] Excluir copias temporales de respaldos cuando corresponda y verificar protección de archivos con teléfono bloqueado en iOS.

### Registro de dominio local

Guardar al menos: ID local, usuario, workspace, `assetId` opcional, ubicación relativa, nombre, MIME, bytes, huella, lote, modalidad, parámetros de partes, tareas asociadas, estado, versión del registro, error y fechas.

La persistencia del motor cubre tareas; el registro de dominio debe cubrir también fallos antes de que exista una tarea nativa. Usar almacenamiento transaccional o escritura atómica con recuperación probada. No borrar trabajos activos usando la política genérica de retención de tareas del paquete.

### Adaptador de transferencia

- [ ] Encapsular llamadas al paquete en un adaptador sustituible.
- [ ] Registrar listeners antes de recuperar eventos de arranque para no perder finalizaciones.
- [ ] Usar IDs deterministas por generación de sesión, archivo y número de parte.
- [ ] Para PUT simple, usar MIME exacto compatible con la firma.
- [ ] Para multipart, convertir número de parte a rango de bytes con pruebas de límites.
- [ ] Entregar todas las partes necesarias de archivos acotados al sistema; si se usan ventanas para archivos grandes, demostrar cómo se programa la siguiente con Flutter suspendido.
- [ ] Respetar el máximo actual de 50 firmas por petición y soportar archivos con más de 50 partes sin cortes funcionales.
- [ ] Evitar depender de callbacks experimentales del paquete para la corrección de la finalización.
- [ ] Resolver expiración de firmas con recuperación autenticada; un retry sobre la misma URL vencida no lo corrige.
- [ ] Registrar fallos de entrega al planificador nativo y recuperarlos al siguiente arranque.
- [ ] Configurar límites de concurrencia soportados realmente por la versión elegida; no usar una cola sólo en memoria como garantía.

La concurrencia de dos partes por archivo puede ser el punto de partida. Medir antes de aumentar. Verificar que una parte se complete dentro de los límites de Android anteriores a 14 en redes lentas; ajustar tamaño/ejecución sin prometer pausa de uploads por una capacidad que sólo corresponde a descargas.

## 10. Fase 5: estados, recuperación y experiencia

Estados de dominio sugeridos:

| Estado | Significado y presentación |
|---|---|
| `PREPARING` | Copiando y validando el archivo; «Preparando…» |
| `QUEUED` | Trabajo durable pendiente de ejecución; «En fila» |
| `UPLOADING` | Hay tareas nativas enviando; «Subiendo · N %» |
| `WAITING_NETWORK` | Esperando conectividad o Wi-Fi elegido |
| `WAITING_SYSTEM` | El planificador aplazó ejecución; no mostrar avance ficticio |
| `VERIFYING` | Transferencia terminada, pendiente de unión/confirmación; «Guardando…» |
| `READY` | API confirmó el medio |
| `RETRYABLE` | Fallo recuperable; conservar archivo y sesión |
| `PAUSED_AUTH` | Requiere acceso autorizado al negocio original |
| `CANCEL_REQUESTED` | Cancelación registrada, remota pendiente |
| `CANCELED` | Cancelación atendida según política |
| `EXPIRED` | Sesión remota vencida; archivo local recuperable si existe |
| `FAILED` | Problema definitivo con explicación y acción disponible |

### Algoritmo de arranque y regreso al frente

1. Leer trabajos locales y reconocer su versión de esquema.
2. Inicializar observadores y recuperar registros/eventos del motor.
3. Filtrar presentación por usuario y workspace, sin borrar datos de otros ámbitos.
4. Consultar estado remoto de sesiones con autorización vigente.
5. Si la API dice `READY`, actualizar la tarjeta y programar limpieza local.
6. Si hay tareas nativas vigentes, asociarlas; no crear duplicados.
7. Si la sesión multipart sigue pendiente, consultar partes reales y calcular faltantes.
8. Validar que el archivo local corresponde a la misma sesión antes de reenviar.
9. Renovar firmas y registrar tareas faltantes cuando la app tenga autorización y el sistema permita programarlas.
10. Si todo llegó, solicitar finalización inmediata o esperar al worker.
11. Si la sesión expiró o falta el archivo, mostrar una acción explícita; no abrir otra reserva silenciosamente.
12. Repetir de forma idempotente con exclusión para no ejecutar dos reconciliaciones locales a la vez.

### Presentación

- [ ] Reutilizar las tarjetas actuales de Contenido y sus señales de refresco.
- [ ] Progreso agregado por bytes: partes aceptadas más bytes en tránsito, sin duplicarlos al reintentar.
- [ ] Reservar «Listo» para la confirmación remota; 100 % transferido puede mostrar «Guardando…».
- [ ] Mantener tarjeta hasta que la lista remota contenga el medio. El timeout actual de tres minutos no debe ocultar trabajos pendientes.
- [ ] Permitir cancelar y reintentar por archivo, con mensajes específicos.
- [ ] Ofrecer «Sólo Wi-Fi» si se incorpora como preferencia; respetarla en el planificador.
- [ ] Mostrar notificación de progreso donde el sistema la requiera; evitar una notificación de éxito por cada parte.
- [ ] No anunciar «Video listo» cuando únicamente terminó el PUT. Si no puede saberse en fondo, anunciar sólo transferencia finalizada o usar aviso del backend tras `READY`.
- [ ] Mantener lote e índice desde la selección; auditar la espera de hermanos pendientes del agente para que una carga larga no rompa agrupaciones.
- [ ] Unificar los puntos de entrada inventariados o documentar claramente los que sigan con un flujo distinto.

## 11. Sesión, permisos y seguridad

- [ ] Vincular cada trabajo al usuario y workspace original; no usar ciegamente el token mutable de `ApiHelper`.
- [ ] Cambiar de negocio sólo cambia presentación y autorización aplicable; no reasigna ni vacía trabajos durables.
- [ ] Política inicial de logout: detener tareas nativas y conservar el registro/archivo local para recuperación del mismo usuario. Verificar carreras con transferencias ya en vuelo.
- [ ] Explicar que detener tareas locales no revoca instantáneamente una URL firmada ya emitida. No prometer revocación inmediata de URLs de R2.
- [ ] Operaciones de firma y recuperación siguen validando membresía y permiso `POST_CREATE`.
- [ ] Si se revoca acceso al negocio, conservar estado bloqueado y no confirmar desde el cliente con credenciales de otro negocio.
- [ ] No introducir copias del JWT en metadatos, logs o parámetros visibles del planificador.
- [ ] Preferir que tareas ya firmadas no necesiten JWT; solicitar nuevas firmas sólo con sesión autorizada.
- [ ] Si se necesitan credenciales desde callbacks nativos, diseñar almacenamiento seguro y alcance mínimo antes de implementarlo; no ampliar automáticamente a una reescritura completa de autenticación.
- [ ] No registrar URLs firmadas completas, nombres sensibles, contenido de archivos ni credenciales.
- [ ] Validar permisos del manifiesto/servicios Android que exija la versión seleccionada. No agregar permisos de batería o modos de fondo ajenos al caso.

## 12. Pruebas y criterios de aceptación

### Matriz automatizada mínima

| Caso | Resultado exigido |
|---|---|
| Respuesta de apertura perdida | Reintento devuelve misma sesión y misma reserva |
| Aperturas concurrentes con misma clave | Una sesión efectiva; conflicto controlado si metadatos difieren |
| Reinicio durante copia local | Recuperación o fallo explícito; nunca subir una copia incompleta |
| Firma vencida | URL nueva para la misma sesión/parte, sin duplicar cuota |
| Parte con bytes incorrectos | Rechazo de finalización y diagnóstico recuperable |
| Partes fuera de conjunto o suma correcta con tamaños incorrectos | Rechazo; no basta la suma total |
| Worker y cliente finalizan juntos | Un resultado final y efectos posteriores idempotentes |
| R2 une pero respuesta se pierde | API recupera objeto y confirma |
| Caída entre R2 y persistencia de BD | Recuperación sin perder ni duplicar el medio |
| Cancelación compite con finalización | Estado terminal consistente según política |
| Fallo de R2 al abortar | Reserva/datos de recuperación conservados hasta limpieza efectiva |
| Sesión aún protegida y limpieza antigua | No abortar carga válida |
| Bandera multipart apagada con sesión abierta | Sesión existente aún consultable/completable/cancelable |
| Cambio de negocio/usuario | Cero envío o confirmación bajo propietario incorrecto |
| Mismo archivo con otro nombre | Conservar deduplicación y decisión del usuario |
| Más de 50 partes | Firma por lotes y ejecución completa sin depender de pantalla abierta |
| Migración local | Registros previos legibles o tratamiento explícito, sin borrar pendientes |
| Clientes antiguos | Contratos actuales siguen funcionando |

### Dispositivos reales

- [ ] Android 11/13 según soporte vigente, Android 14 y Android 15/16.
- [ ] Al menos un Pixel y un Samsung; agregar fabricante con gestión agresiva de batería si está disponible.
- [ ] iPhone con mínimo soportado y versión vigente disponible.
- [ ] Fotos, video pequeño, video sobre el umbral multipart y archivo cercano al tope autorizado de la cuenta.
- [ ] Lote mixto y archivo con más de 50 partes si el tope de cuenta lo permite.
- [ ] Cambio de app y pantalla bloqueada por 15–30 minutos, con una transferencia que dure lo suficiente.
- [ ] Corte de Wi-Fi, paso a datos y modo avión; probar preferencia Wi-Fi si existe.
- [ ] Terminación del proceso por el sistema, cierre forzado manual, reinicio y actualización de app con pendientes.
- [ ] Poco espacio local y archivo de origen eliminado.
- [ ] Firma vencida mientras el sistema retrasa la tarea.
- [ ] Notificaciones denegadas y restricciones de batería: observar comportamiento real y mensaje adecuado.

### Definition of Done

1. En la matriz crítica ejecutada no hay pérdida silenciosa, cruces de negocio ni reservas duplicadas.
2. Cambiar de aplicación y bloquear pantalla completan cargas de prueba en Android e iOS físicos.
3. Tras interrupción, multipart conserva partes completas y continúa con las faltantes.
4. La API finaliza un multipart completo sin que Flutter llame a `complete`.
5. La finalización autónoma cumple el objetivo medido de hasta dos ciclos del worker, más tiempo de R2/confirmación, en el entorno de prueba.
6. La limpieza no aborta sesiones válidas y termina limpiando canceladas/vencidas con R2 disponible.
7. Las pruebas pertinentes pasan y existen compilaciones release verificadas de ambas plataformas.
8. Se registra evidencia de pruebas externas, dispositivos, builds y limitaciones pendientes.
9. Se documentan configuración, despliegue, recuperación, rollback y diagnóstico.

No declarar iOS verificado por una lectura de código, pruebas Dart o build Android. Si falta Mac/iPhone, registrar ese bloqueo y separar implementación de verificación.

## 13. Observabilidad y operación

Correlacionar eventos mediante `clientUploadId`, `assetId`, workspace, plataforma y versión de app. Evitar datos sensibles y cardinalidad ilimitada en métricas agregadas.

Eventos propuestos: trabajo creado, archivo preparado, sesión abierta/recuperada, tareas entregadas, parte completada, firma renovada, sesión reconciliada, finalización reclamada, objeto unido, medio confirmado, cancelación solicitada, aborto terminado y sesión vencida.

Medir:

- Éxitos y fallos por plataforma/build.
- Tiempo desde selección hasta transferencia y desde transferencia hasta `READY`.
- Reintentos y bytes retransmitidos.
- Recuperaciones después de interrupción.
- Firmas vencidas y sesiones pausadas por autenticación.
- Sesiones con todas las partes pendientes de finalizar.
- Antigüedad de sesiones y cola de limpieza.
- Reservas duplicadas por clave y errores de coordinación.
- Costo/volumen de consultas `ListParts`.

Crear una guía de soporte que permita diagnosticar con un ID, sin pedir tokens ni URLs firmadas a la persona.

## 14. Despliegue y rollback

1. Aplicar migraciones aditivas e índices, con respaldo y revisión de datos.
2. Desplegar API compatible con clientes actuales; nuevas aperturas desactivadas.
3. Probar contratos y coordinación con almacenamiento aislado.
4. Distribuir build interno móvil con motor integrado y bandera controlada.
5. Activar cuentas internas y verificar cargas reales, cuotas y agrupación.
6. Publicar Android e iOS según sus canales de prueba; registrar builds efectivos.
7. Ampliar gradualmente según evidencia. Si el backend sólo admite `APAGADA`, `SIN_LIMITES` y `TODAS`, no prometer porcentajes sin implementar un mecanismo adicional.
8. Mantener consultas, finalización y cancelación para sesiones abiertas al apagar nuevas aperturas.
9. Conservar el contrato de PUT simple para versiones antiguas.
10. Ajustar TTL y limpieza sólo tras revisar población de versiones instaladas y reglas del bucket.

Rollback funcional: detener nuevas sesiones del motor afectado, dejar drenar/finalizar las existentes y conservar registros/archivos recuperables. No borrar sesiones como mecanismo de rollback ni volver automáticamente a PUT completo si eso duplica una carga ya iniciada.

La implementación local y las pruebas aisladas forman parte del trabajo. Publicar builds, desplegar producción o cambiar infraestructura requieren la autorización aplicable a la sesión de ejecución; este documento no constituye por sí solo una orden de despliegue.

## 15. Organización de entregas

| Entrega | Contenido | Evidencia para cerrar |
|---|---|---|
| A. Viabilidad | Prueba del paquete y firmas/rangos | Releases y pruebas físicas acotadas |
| B. Sesiones API | Idempotencia, estado, expiración y compatibilidad | Tests de concurrencia y respuestas perdidas |
| C. Finalización | Worker, coordinación y limpieza | Completar sin app y recuperar caída entre R2/BD |
| D. Motor móvil | Archivo durable, registro y adaptador | Transferencia en fondo y recuperación |
| E. Producto | Tarjetas, múltiples entradas, lotes y sesión | Pruebas funcionales y aislamiento |
| F. Liberación | Matriz física, métricas y rollout | Informe verificable y builds identificados |

Dependencias: A valida la elección del motor; B precede a C y a la integración completa D; E depende de D; F depende de todas. Preferir cambios revisables y coherentes sin dejar una versión distribuible usando contratos incompletos.

No fijar una fecha final antes de cerrar A y confirmar recursos iOS. Estimar el resto con los resultados observados, especialmente duración en Android antiguo, consumo de disco iOS y recuperación de firmas vencidas.

## 16. Instrucción lista para iniciar el goal

```text
Implementa el goal descrito en docs/goal-subidas-moviles-segundo-plano.md
en metricol.api y ../metricol_app.

El objetivo es que las fotos y videos se transfieran con un motor nativo,
conserven archivo y sesión al interrumpirse, recuperen las partes ya recibidas
y sean completados por el servidor sin depender de Flutter activo.

Comienza por inspección y prueba técnica de background_downloader con una
versión publicada fijada. Verifica PUT firmado y rangos binarios antes de
integrar el producto. Si la prueba revela un impedimento, documenta evidencia
y resuelve la decisión de motor antes de construir el flujo completo.

Reutiliza endpoints multipart, almacenamiento R2, tarjetas de Contenido,
cuotas y servicios existentes. Agrega idempotencia de apertura, consulta de
sesión, expiración coordinada, finalización autónoma y cancelación consistente.
Preserva propietarios, lotes, detección de repetidos y clientes antiguos.

Lee instrucciones locales y respeta cambios existentes en ambos repositorios.
Realiza pruebas pertinentes y deja evidencia de compilaciones y escenarios
físicos. No declares como verificadas plataformas que no hayas probado.

Continúa hasta cumplir los criterios de aceptación o identificar un bloqueo
externo concreto. Si falta equipo iOS u otro acceso, completa trabajo
independiente y registra exactamente qué falta validar. No inventes resultados.

Entrega archivos modificados, resumen de arquitectura, migraciones,
configuración, pruebas ejecutadas, limitaciones y procedimiento de liberación.
No despliegues producción ni publiques builds sin autorización aplicable.
```

## 17. Referencias técnicas a revalidar al ejecutar

- [background_downloader: repositorio oficial del proyecto](https://github.com/781flyingdutchman/background_downloader).
- [Versiones publicadas del paquete](https://pub.dev/packages/background_downloader/versions).
- [Cargas binarias y formularios](https://github.com/781flyingdutchman/background_downloader/blob/main/doc/uploads.md).
- [Registro persistente y recuperación de eventos](https://github.com/781flyingdutchman/background_downloader/blob/main/doc/database.md).
- [Ciclo de tareas, colas y límites de callbacks](https://github.com/781flyingdutchman/background_downloader/blob/main/doc/lifecycle.md).
- [Parámetros y configuración de UIDT](https://github.com/781flyingdutchman/background_downloader/blob/main/doc/parameters.md).
- [Historial y soporte de carga parcial por rango](https://github.com/781flyingdutchman/background_downloader/blob/main/CHANGELOG.md).
- [Android: opciones para transferencias en segundo plano](https://developer.android.com/develop/background-work/background-tasks/data-transfer-options).
- [Android: trabajos de transferencia iniciados por el usuario](https://developer.android.com/develop/background-work/background-tasks/uidt).
- [Android: límites de servicios en primer plano](https://developer.android.com/develop/background-work/services/fgs/timeout).
- [Apple: sesiones de fondo y cierre forzado](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/background(withidentifier:)).
- [Apple: transferencias de fondo y cargas desde archivo](https://developer.apple.com/documentation/foundation/downloading-files-in-the-background).

Los enlaces a `main` ayudan a investigar; la implementación debe seguir el código y documentación de la versión publicada seleccionada. Revisar diferencias antes de usar parámetros, callbacks o garantías de persistencia.
