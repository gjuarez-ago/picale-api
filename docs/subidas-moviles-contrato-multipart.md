# Contrato multipart para subidas móviles

Revisión D42 y segunda revisión D42-R1, 11 de noviembre de 2026, America/Mexico_City.

Actualización D43: coordinación durable implementada localmente. Ver
[decisiones, plazos, esquema y límites de coordinación](subidas-moviles-coordinacion.md).
Las secciones D42 conservan el contexto del contrato auditado; la coordinación
ya no es una implementación pendiente, aunque su despliegue y validación en
PostgreSQL/R2 real siguen pendientes.

Actualización D44 (12 nov): apertura antes/después de reserva invertida,
admisión serializada, identidad/propietario inmutables y respuestas UTC/versionadas.
Ver [contrato de apertura e incertidumbre remota](subidas-moviles-apertura.md).
La capacidad API v2 no habilita Android: multipartMobileReady permanece false.

## Alcance y estado

Esta unidad endurece el contrato existente del backend. No habilita multipart en
Flutter ni anuncia una versión nueva del protocolo. El motor Android conserva el
PUT completo y el manifiesto local v3. La autorización del despliegue anterior
correspondía a `cb483b7`; estos cambios locales requieren otra autorización para
producción cuando exista una entrega revisable.

No se debe interpretar `subidaEnFondo: 1` como soporte del protocolo multipart
móvil completo. `multipartFromBytes` sólo permite decidir si se admiten nuevas
sesiones en ese negocio; tampoco demuestra coordinación entre cancelación,
finalización y limpieza.

## Contrato compatible existente

Todas las rutas parten de `/api/v1/media`, usan la sesión de la API y exigen
`POST_CREATE`. Los PUT firmados de las partes van directamente a R2, con el rango
de bytes correspondiente como cuerpo, sin token de la API ni Content-Type.

| Operación | Resultado y regla |
|---|---|
| `POST /multipart` | Mismo pedido que presign. Devuelve assetId, sizeBytes, partSizeBytes, partCount, contentType, url y estado. Sin clientUploadId se conserva el comportamiento anterior. |
| Repetir apertura con clientUploadId | Reutiliza la fila encontrada en el workspace. No abre otro uploadId ni reserva otra fila. Los metadatos incompatibles generan UPLOAD_ID_CONFLICT. La bandera de apertura de sesiones nuevas no debe impedir recuperar una existente. |
| `POST /{id}/multipart/parts` | Firma números válidos de la sesión. El DTO HTTP acepta hasta 50 números por petición; pedir otra tanda permite atender archivos con más de 50 partes. Devuelve signed y expiresInSeconds. |
| `GET /{id}/multipart/parts` | Consulta las partes realmente almacenadas. R2StorageService pagina ListParts y ordena el resultado. D43: lectura sin transacción remota ni renovación del plazo absoluto. |
| `POST /{id}/multipart/complete` | Usa partes y ETags consultados por el servidor. Exige números exactos, únicos y tamaños individuales correctos. Luego utiliza la confirmación normal. READY repetido devuelve el medio existente. |
| `DELETE /{id}/multipart` | D43: decisión durable, aborto y comprobación remotos antes de liberar reserva; conserva fila CANCELLED/EXPIRED. Nunca borra un objeto final; READY informa conflicto explícito. |
| `GET /subidas/{clientUploadId}` | Recupera assetId; puede intentar completar y confirmar. D43 añade CANCELLED, EXPIRED, CANCELLING y EXPIRING a READY/PENDING/NO_EXISTE. |

### Revisión de la consulta paginada (D42-R1)

La consulta de partes valida que una respuesta truncada incluya un marcador
siguiente, estrictamente mayor al anterior y dentro de 1 a 10,000. Si falta,
retrocede, se repite o rebasa ese límite, falla de forma recuperable sin volver
a pedir la página. El mensaje no incluye URLs ni credenciales. Esta protección
evita un bucle que podía retener el hilo y la transacción de consulta.

Las pruebas nuevas ejecutan R2StorageService real con S3Client simulado, por
debajo del mock de almacenamiento utilizado por las pruebas de MediaService.
Verifican paginación/identidad/orden, desaparición de sesión entre páginas y
propagación de errores sin devolver éxito parcial ni ausencia falsa. No
demuestran comportamiento de una conexión real al bucket.

## Validación de cierre

Para un archivo de B bytes, parte de P bytes y N partes:

- Los números deben ser exactamente 1 a N, sin sobrantes ni duplicados.
- Cada parte anterior a N debe medir P; la última mide B − (N − 1) × P.
- La suma debe ser B. Una suma correcta no compensa tamaños individuales erróneos.
- Cada parte necesita un ETag no vacío para la petición de unión.
- La petición de unión se construye en orden ascendente, independientemente del
  orden de la lista recibida del almacenamiento.
- Un ETag no se presenta como SHA-256 del archivo completo. Estas comprobaciones
  validan la estructura y los tamaños; no demuestran igualdad criptográfica de los bytes.

Ejemplo probado: 300 MiB con partes de 16 MiB requiere 19 partes: 18 de 16 MiB y
una de 12 MiB. Cambiar las dos primeras a 15 y 17 MiB conserva la suma, pero se
rechaza. Agregar una parte 20 también se rechaza; no se recorta silenciosamente
la lista recibida. Otra prueba usa partes de 5 MiB para ese archivo: las 60
partes se firman en lotes de 50 y 10; una petición de 51 se rechaza.

Si ListParts está vacío porque R2 ya consumió uploadId al unir, el servidor
consulta el objeto final. Sólo un objeto con el tamaño declarado exacto permite
seguir por confirmación y limpiar el uploadId pendiente. Sin objeto, o con un
tamaño distinto, se conserva la reserva y la información de recuperación.
Un vacío por sí solo no se interpreta como READY ni como sesión vencida.

La misma comprobación de tamaño se aplica si CompleteMultipartUpload devuelve
un error S3 y se intenta recuperar el resultado de una unión anterior. No se
debe aceptar cualquier objeto existente bajo la clave.

## Brechas que bloquean conectar multipart móvil

| Pendiente | Riesgo actual | Unidad siguiente |
|---|---|---|
| Reclamo durable común entre HTTP y workers | Implementado D43 con bloqueo corto, token y decisión persistente. | Validar concurrencia en PostgreSQL y despliegue sin mezclar workers antiguos. |
| Cancelación durable y estados terminales | Implementados D43; reserva conservada ante error, terminal persistido después del aborto. | Validar R2 real con partes en vuelo y consumo de estados desde el móvil. |
| Apertura concurrente con la misma clave | D44 reserva bajo bloqueo por negocio antes de Create; respuesta perdida conserva clave y SENT para reconciliar sin otro Create. | Validar PostgreSQL/R2 reales y procedimiento de revisión para aperturas inciertas sin ID visible. |
| Identidad de sesión completa | D44 guarda propietario/snapshot inmutable y compara lote/nombre/modalidad; v2 no adopta sesiones heredadas sin identidad. | Validar autenticación real y consumo de identidad desde el futuro manifiesto móvil. |
| Plazos UTC y lease | D43 persiste protección y plazo; D44 expone UTC/versión en respuestas. | Verificar serialización/compatibilidad y TTL históricos/lifecycle real antes del despliegue. |
| Reintentos de worker | D43 incorpora cola acotada/indexada, próximo intento, backoff y lease durable. | Validación operativa y medición de backlog/errores en entorno de pruebas. |
| Recuperación completa de red | D43 conserva decisión y reserva ante timeout; recupera partes/objeto después de reiniciar. | Verificar abortos y respuestas perdidas con R2 real; conservar datos ante fallos permanentes. |
| Capacidad de protocolo nuevo | D44 expone multipartSessionVersion=2 para API y multipartMobileReady=false. subidaEnFondo=1 permanece. | No activar motor multipart móvil hasta validación operativa e integración nativa. |
| Manifiesto móvil | v3 sólo admite una tarea de PUT completo. | Migración explícita posterior con partes, rangos y generación de tareas. No insertar varias tareas en v3. |

El coordinador D43 resuelve localmente la carrera con cancelación y limpieza.
Antes de conectar partes Android faltan los demás puntos del contrato, su
versionado y las validaciones de despliegue. Ninguna prueba local sustituye la
prueba de bucket o las validaciones físicas de Android/iOS.

### Criterios de aceptación de la unidad de coordinación D43

1. Dos solicitudes de completar, o HTTP y worker a la vez, comparten una decisión
   durable. No ejecutan efectos de confirmación dos veces. La espera y el trabajo
   remoto no mantienen una conexión de base abierta.
2. Cancelar frente a completar tiene un ganador explícito. Si el medio ya quedó
   READY, cancelar devuelve ese resultado o un conflicto comprensible; nunca
   elimina el objeto ni el medio usado por otra operación.
3. Si gana cancelar, la decisión se guarda antes de actuar en R2. Un fallo al
   abortar conserva sesión, reserva y un próximo intento. Una repetición observa
   la decisión anterior y no crea otra sesión ni cobra cuota de nuevo.
4. Un proceso que muere antes o después de una operación remota deja un estado
   recuperable. Otro proceso sólo lo reclama tras vencer el lease y reconcilia
   el objeto/partes reales antes de actuar.
5. Limpieza, finalización y rutas HTTP usan el mismo protocolo. Se prueban con
   intercalaciones controladas y con la restricción real de la base; un mock de
   repositorio o un candado Java no demuestra la garantía.
6. La implementación documenta la transición desde las filas anteriores, el
   esquema necesario, los plazos UTC, los errores de contrato y su reversión.
   Sólo entonces se propone capacidad nueva y despliegue para revisión.

## Evidencia local

Las pruebas de SubidaPorPartesTest ejecutan MediaService con repositorio real H2
en memoria y R2 simulado. No usan la cuenta de Juan Rodríguez ni suben archivos al
bucket. Los logs de esta revisión se conservan en target/subidas-d42-*.log; los
reportes de Surefire son reemplazables en la siguiente ejecución.

Resultado final: **93/93 pruebas en nueve clases, sin fallos, errores ni
omisiones**, Maven exit 0, 3 min 46 s. SubidaPorPartesTest tiene 33 casos, 16
nuevos. La corrida previa sobre código anterior reprodujo nueve fallos entre
27 casos. Incluye validación de cuotas, consultas, recuperación, PUT simple,
repetidos, organización y eliminación. No es la suite completa del proyecto.

Los hashes de MediaService y SubidaPorPartesTest se conservaron durante la
corrida y se verificaron al terminar, junto con los nueve XML de Surefire.
Reporte: `target/subidas-d42-report.json`; entradas:
`target/subidas-d42-inputs.json`. Ninguna prueba usó un dispositivo físico ni
demuestra la carrera de cancelación todavía pendiente.

Segunda revisión D42-R1 cerrada: **104/104 pruebas en diez clases**, sin fallos,
errores ni omisiones, exit 0, 3 min 38 s. Son las 93 anteriores más 11 del
adaptador de consulta R2. Se reprodujeron seis fallos antes de añadir la
protección del marcador. El primer intento de compilar el auxiliar tuvo un
error de tipo del builder SDK, corregido sin cambiar las expectativas; ese
intento no se cuenta como reproducción funcional.

Evidencia: `target/subidas-d42-review-repro.log` (fallos reproducidos),
`target/subidas-d42-review-tests.log` (pase final) y
`target/subidas-d42-review-report.json` (diez XML y cuatro hashes de fuentes
verificados). La nueva prueba se encuentra en
`src/test/java/com/metricol/api/service/storage/R2MultipartConsultaTest.java`.
No hay despliegue, cambio de APK ni prueba contra R2 real en esta revisión.
