# Cliente móvil de sesiones multipart v2 — D48

13 de noviembre de 2026, America/Mexico_City. El usuario autorizó «Okis va» al
proponer conectar el cliente con apertura/recuperación, consulta y renovación de
permisos. Se implementa el cliente de control HTTP en Flutter. **No entrega
todavía rangos al motor Android ni activa multipart en la app.**

## Entrada y operaciones

`MediaService.multipartPara(userId: ..., workspaceId: ...)` crea un
`ClienteDeSesionesMultipart` ligado al token presente en ese momento. Usuario y
negocio deben proceder de la sesión autenticada, no de los metadatos del archivo.
La identidad del token y los metadatos se verifican antes y después de cada
petición. El token sólo se conserva en memoria dentro del cliente.

| Método | Ruta | Resultado |
|---|---|---|
| `abrir(trabajo)` | POST /media/multipart | Sesión v2 con la misma clientUploadId y metadatos de origen |
| `recuperar(trabajo)` | GET /media/subidas/{clientUploadId} | Estado explícito y sesión; READY incluye medio confirmado |
| `consultarPartes(trabajo)` | GET /media/{assetId}/multipart/parts | Nuevo snapshot local reconciliado; no modifica el trabajo recibido |
| `firmarPartes(trabajo, numeros)` | POST /media/{assetId}/multipart/parts | Permisos efímeros para una tanda de 1 a 50 partes |

No hay reintento automático de apertura. Si se pierde la respuesta, el cliente
propaga el error; el futuro orquestador consultará la misma clave y sólo repetirá
la apertura cuando corresponda, con esa misma identidad. NO_EXISTE no causa otra
reserva, otra clave ni borrado del registro. Un HTTP404 no se transforma en
NO_EXISTE ni en una lista vacía. Errores401/404/409/500 y timeouts se propagan sin
fallback a PUT simple.

## Validación del contrato

- Estados conocidos: OPENING, PENDING, READY, CANCELLING, CANCELLED, EXPIRING,
  EXPIRED y NO_EXISTE. Un estado desconocido falla conservadoramente.
- Se exige protocolo2 en sesiones existentes, tamaño/MIME concordantes,
  partSize/partCount coherentes y vencimiento UTC explícito. No se adopta una
  sesión simple antigua como multipart. El assetId debe ser UUID y coincidir
  con el ligado al trabajo; el plazo y tamaño de parte no cambian al recuperar.
- La recuperación comprueba clave, modo, nombre y lote/posición. Un UUID de lote
  con letras mayúsculas es equivalente a su forma normalizada por el servidor;
  no se reescribe la identidad local.
- READY de la consulta exige un medio con el mismo ID y status READY. READY de
  la apertura no contiene ese medio: el orquestador deberá consultarlo antes de
  presentar el archivo como disponible.
- Una respuesta de sesión conserva los intentos y partes ya registrados.
  No sustituye una consulta ListParts. Ésta valida números, duplicados, rangos,
  tamaños individuales y suma total, mediante el modelo D47.
- Las firmas deben devolver exactamente las partes solicitadas, sin duplicados
  ni sobrantes. Se validan HTTPS, ausencia de credenciales/fragmento, plazo UTC
  futuro y acotado por el vencimiento absoluto. La firma común debe corresponder
  al menor vencimiento devuelto. No se firman partes confirmadas ni sesiones
  vencidas; archivos con más de50 partes se atienden por varias tandas.
- Las firmas no incrementan la revisión remota ni borran intentos. No prueban
  recepción de bytes ni cancelación de una tarea anterior.

Las excepciones propias del parser son genéricas, sin incluir URLs firmadas ni
cuerpos de respuesta. `PermisoDeParte`/`PermisosMultipart` no ofrecen toJson; las
URLs quedan en memoria de estos objetos y fuera del manifiesto de dominio.
El cliente no hace PUTs ni agrega el token de la API a las URLs de almacenamiento.

## Compatibilidad y capacidad

La ruta simple existente, su parser tolerante y `EstadoDeSubida` permanecen
separados del nuevo contrato estricto. No se cambia la inicialización del motor
ni el registro v3 predeterminado de D47.

`StorageUsage` ahora interpreta multipartSessionVersion, multipartMobileReady y
multipartFromBytes. `admiteMultipartMovil` exige versión exactamente2, bandera
booleana verdadera y umbral positivo. Campos ausentes, formatos incompatibles o
versiones futuras no habilitan el motor. El orquestador también deberá comprobar
plataforma, tamaño de archivo y sus demás requisitos. No basta subidaEnFondo=1.
La API D46 mantiene multipartMobileReady=false.

## Responsabilidades del siguiente paso

El cliente valida metadatos preparados, propietario, huella/SHA256 y lote. No
abre el archivo ni recalcula su SHA256: el preparador deberá garantizar la copia
privada íntegra antes de cualquier reserva o entrega nativa.

Las respuestas no se guardan automáticamente. El futuro orquestador deberá
persistir assetId y sesión juntos, usar las actualizaciones serializadas D47,
reconciliar tareas nativas y partes remotas antes de planificar/reintentar, y
esperar la escritura durable antes de entregar un rango a Android. Tendrá que
descartar permisos de una operación obsoleta antes de usarlos. Este cliente no
implementa todavía la secuencia nativa, cancelación, complete, purga ni reparación
de un manifiesto corrupto.

## Evidencia y límites

Validación: **423 pruebas de la app aprobadas** y **56 comprobaciones finales aprobadas** en tres clases; sin fallos. Análisis estático final de cinco archivos con --fatal-infos limpio. El pase final cubre la normalización de UUID añadida al cierre de la revisión, además de la suite completa previa.

Las pruebas nuevas usan ApiHelper simulado y DTO coherentes con las clases Java
desplegadas en D46. Las pruebas existentes del envío simple usan su receptor
local. Los resultados y hashes finales están en `target/subidas-d48/report.json`.

Esto no acredita una llamada autenticada productiva, una transferencia multipart
real a R2, reinicio/pantalla bloqueada Android o ejecución iOS. No se desplegó
API, no se publicó app, no se generó APK ni se modificó el bucket. La sesión
productiva de usuario y la integración nativa siguen pendientes.


## Segunda revisión D48-R1 (13 nov 2026)

Se reprodujeron y corrigieron tres hallazgos. La primera corrida tuvo12 casos:3 aprobados y9 fallidos, exit1. No eran errores de compilación: los asserts demostraron comportamientos incorrectos.

1. **Respuesta de cuenta anterior:** ApiHelper procesaba un401 y ejecutaba onSessionExpired sobre la cuenta recién abierta antes de que el cliente multipart detectara el cambio de token. Además, un200 atrasado ejecutaba el parser. Ahora las solicitudes JSON que pasan por _ejecutar capturan el token antes del envío y verifican su continuidad antes de _leer. Los401 vigentes siguen notificando vencimiento. Se prueba con el ApiHelper real y transporte HTTP MockClient, incluyendo cabecera Bearer, envoltura result y GET/POST; no con un helper enteramente simulado. El método legacy postFile no fue modificado en esta unidad.
2. **Asociación local inconsistente:** los constructores públicos permitían enlazar una sesión cuyo clientUploadId o tamaño no correspondían al trabajo. La comprobación previa ahora exige coincidencia de clave, bytes, assetId e identidad en todas las operaciones, antes de HTTP. No se depende sólo del validador de disco o del servidor.
3. **Plazos malformados:** DateTime.tryParse aceptaba30febrero, mes13, hora24 y minuto60 normalizando sus valores. Ahora se exige estructura ISO con zona explícita y se comprueban calendario, hora y offset antes de convertir; fracciones de hasta9dígitos y offsets válidos siguen admitidos.

Resultado final: **436 pruebas de la app aprobadas**, **68 pruebas focalizadas aprobadas**, cero fallos/errores, exit0. Análisis de siete archivos con --fatal-infos sin incidencias, exit0. Los cuatro logs y siete hashes quedan en target/subidas-d48-review/report.json. Tras las comprobaciones sólo se aclaró un comentario de ApiHelper.

Esta evidencia no acredita autenticación productiva, envío de rangos nativos o background Android. No se cambió API productiva, bandera móvil, bucket, APK ni publicación. La siguiente unidad sigue siendo integrar el orquestador Android.
