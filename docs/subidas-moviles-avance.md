# Avance: subidas móviles persistentes

Última actualización: 13 de noviembre de 2026, America/Mexico_City.

## Checkpoint actual: D49-R1, coordinador revisado (13 nov 2026)

- Solicitud «revisa que haya quedado por favor»; tras interrupción «mira», usuario pide «retoma». Se continúa la misma revisión sin depender de información visual que no llegó.
- Primera reproducción9casos:1pase/8fallos reales. Corregidos: regresión de cancelando al integrar OPENING/EXPIRING; eventos fallidos tardíos tras intento enviado; permiso vencido durante commit; reintento manual perdido frente a consulta automática; propietarios simultáneos de carpeta; cierre bloqueado por consumidor pausado y petición HTTP nueva tras cerrar.
- Prueba adicional confirmó que cancelar esperaba una petición HTTP antes de guardar intención. Ahora persiste primero por cola de IO y luego espera el ciclo remoto. Toda modificación conserva cancelando hasta terminal; se prueba reiniciar con HTTP viejo aún pendiente, sin nuevas entregas. Inyección de error ajustada al commit de integración tras agregar escritura anticipada, sin debilitar la aserción.
- Exclusión por ruta canónica entre coordinadores del mismo isolate (no entre procesos/isolate). Propiedad retenida hasta arranque/ACK nativos/lecturas y escrituras locales quiescentes; fallo de inicio libera propiedad tras limpieza. Close coalescido y desacoplado de consumidores pausados. Fuera de cierre no hay nuevos efectos de resultados obsoletos.
- Final: suite app479 y foco de revisión14 aprobados,0fallos/errores,exit0. Análisis dos archivos cambiados --fatal-infos limpio,exit0. Ocho hashes de fuentes/pruebas coinciden antes/después de corrida final. Evidencia target/subidas-d49-review/report.json y cinco logs asociados; corrida intermedia477 es histórica, anterior al último ajuste de cancelación.
- Sin API productiva/bucket/flag/selección/Cola/pantallas/APK/Android físico. Pruebas de adaptador real sobre puerto simulado, API simulada y archivos temporales. Detalle en sección D49-R1 de [coordinador Android](subidas-moviles-coordinador-android.md).
- Siguiente unidad: integrar selección/preparación/cola/tarjetas sin doble registro ni migración silenciosa de simples; después canario nativo bytes/cabeceras/background/red y APK antes de activar. Mantener pendientes purga/reparación conservadora e iOS/Mac.

## Checkpoint histórico: D49, coordinador de partes Android implementado (13 nov 2026)

- Usuario autoriza «Okis va ayudame hacerlo» tras proponer unir registro/API/motor. Implementados CoordinadorDeSubidasMultipart, entrada crearAndroid opt-in y export en services.dart. La selección/Cola/pantallas actuales NO lo invocan todavía; capacidad productiva false, sin APK nuevo. No presentar como función Android probada o activada.
- Carpeta privada subidas-multipart y registro4 separados de subidas/registro3, grupo picale-partes separado de picale-subidas. MotorNativoDeSubidas/PuertoBackgroundDownloader admiten grupo configurable; compatibilidad por defecto preservada. Payload identity añade Range/metaData.
- Registro.agregar inserta serialmente sin sobrescribir trabajos; Trabajo.conSesion liga asset/sesión atómicamente. Cliente API incorpora completar (exigeREADY) y cancelar (ACK no terminal); vigente controla token en memoria.
- Recupera la misma clave, espera OPENING, no recrea una sesión conocida NO_EXISTE. Native inventory antes de ListParts; consulta fallida conserva incertidumbre. Verifica SHA256 privado antes de reservar/entregar, firma tandas<=50, guarda intento antes de handoff y reutiliza ID tras ACK perdido; nuevas generaciones sólo después de terminal+consulta. No cancela ni borra final READY. Completa con estado verificando y exige medioREADY.
- Cancelación frena entregas inmediatamente, persiste intención y comprueba tareas ausentes antes de abortoAPI; respuesta/APIaborto inciertos conservan cancelando y copia. READY ganador conserva medio. Cierre espera ACK nativo pendiente y escrituras, desvincula eventos pero no cancela bytes aceptados. Contexto inválido no aplica resultados. Progreso ponderado sin dobleconteo; sin bucle automático de reenvío de fallos.
- SDK9.5.9 Kotlin instalado inspeccionado: Range gobierna stream/Content-Length y no se reenvía como cabecera genérica; runner sí añade Content-Type (candidato octet-stream). No afirmar ausencia de esa cabecera ni compatibilidad R2 nativa ya ejecutada. Falta canario real con esos bytes/cabeceras.
- Incidencias corregidas: whenComplete devolvía el mismoFuture retirado de mapa y bloqueaba cierres de ciclo; limpieza ahora void. En pruebas se drenaban sólo eventos del dominio y no del adaptador, causando verificación prematura y cinco carpetas con JSON aún abiertos; ahora se drenan ambos. Intento amplio preliminar detenido por variable local mal referida, corregida. No se ampliaron timeouts para ocultar el bloqueo; el caso255MiB tiene límite3min por trabajo de hashing real.
- Final: suite app465 aprobada, incluidas29coordinador, cero fallos/errores, exit0. Análisis siete archivos --fatal-infos limpio, exit0; hashes coincidentes antes/después. Motor/adaptador reales con Puerto falso y API simulada; no WorkManager/dispositivo/R2 real. Evidencia target/subidas-d49/report.json y logs mobile-upload-d49-suite-final.log/mobile-upload-d49-analyze-final.log. Seis temporales fallidos conocidos limpios por borrado individual (recursivo rechazado por revisión automática); no procesos de prueba pendientes.
- Sin cambios API/bucket/credenciales/dependencias/Kotlin/APK/publicación. Detalle [coordinadorAndroid](subidas-moviles-coordinador-android.md). Siguiente unidad: enrutar selección/preparación/Cola/tarjetas al coordinador sin doble registro o migración silenciosa de sesiones simples; validar canario nativo/SDKcabeceras/background/red y APK antes de activar; purga y reparador de incertidumbre pendientes. iOS sigue pendienteMac.

## Checkpoint histórico: D48-R1, revisión del cliente API (13 nov 2026)

- Usuario solicita «revisa que haya quedado bien». Se revisó contra contrato Java y se añadió cliente_multipart_revision_test.dart con12casos; antes de corregir:3pases/9fallos reales, exit1.
- R1: respuesta401 vieja podía ejecutar onSessionExpired sobre nueva cuenta;200 viejo también ejecutaba parser. ApiHelper._ejecutar ahora liga token al envío y descarta respuesta antes de _leer si cambió. Se prueba helper real con MockClient y envoltura result;401 vigente mantiene expiración. Alcance: rutas JSON de _ejecutar; postFile legacy intacto.
- R2: constructor público permitía sesión local con clave/tamaño incompatibles; guardia previa de todas las operaciones valida clientUploadId/bytes/assetId/identidad antes de HTTP.
- R3: tryParse normalizaba fechas imposibles; parser de plazos ahora valida estructura ISO/zona/calendario/hora/offset; conserva fracciones y offsets válidos.
- Final: suite app436 y foco68 aprobados sin fallos/errores; análisis siete archivos --fatal-infos limpio, exit0. Sólo comentario aclaratorio después. Evidencia/hashes/logs: target/subidas-d48-review/report.json; sección revisión en [cliente API v2](subidas-moviles-cliente-api-v2.md).
- Sin cambios de API productiva, bucket, APK, publicación, flags, motor nativo o dependencias. Pendiente orquestación de rangos Android, cancelación/finalización integral y pruebas reales.

## Checkpoint histórico: D48, cliente API multipart v2 (13 nov 2026)

- Usuario autoriza «Okis va» para el punto1 propuesto: conectar app con apertura/recuperación, consulta de partes y renovación de permisos. Se implementa el cliente de control; no se entregan rangos al motor Android todavía.
- MediaService.multipartPara(userId,workspaceId) expone ClienteDeSesionesMultipart, ligado al token/propietario/negocio originales en memoria. Comprueba identidad antes/después de HTTP; rechaza respuesta de operación obsoleta. Trabajo preparado exige copia/huella/SHA/lote; no recalcula el archivo, responsabilidad del preparador antes de reservar.
- DTO estricto v2: OPENING/PENDING/READY/CANCELLING/CANCELLED/EXPIRING/EXPIRED/NO_EXISTE. No convierte errores/estados desconocidos en ausencia. Recuperación coteja identidad y conserva intentos/partes; READY en GET exige medio confirmado. Apertura perdida no se reintenta automáticamente ni cambia clave; recuperar NO_EXISTE tampoco abre otra sesión. HTTP404/409/5xx/timeouts no disparan fallback.
- ListParts usa reconciliación D47 y valida parámetros, números, tamaños y suma; firma sobre el mismo asset en tandas1..50, URLs HTTPS/UTC futuras y acotadas, sin duplicados/sobrantes. Firmar no es consultar partes, cambiar generación ni cancelar una tarea. Permisos en memoria, fuera de manifiesto. UUID de lote normalizado por API se acepta sin cambiar identidad local.
- StorageUsage consume versión/flag/umbral; requiere exactamente protocolo2+flag booleana true+umbral válido para capacidad móvil. Campos ausentes o versión futura no habilitan. El camino simple y registro3 predeterminados se conservan; servidor D46 mantiene multipartMobileReady=false.
- Validación: suite app423 aprobada y pase focalizado final56 en cliente multipart/envío simple/registro4, exit0. El foco final cubre además normalización UUID añadida durante la revisión. Análisis cinco archivos --fatal-infos sin incidencias, exit0. Primera pasada53 aprobada; análisis inicial tenía cuatro lints de llaves y una aserción nula innecesaria, corregidos. Hashes de cinco fuentes, seis contratos Java y tres logs en target/subidas-d48/report.json.
- Sin despliegue/cambio API, R2, APK, publicación, dependencias, emulador o código nativo. ApiHelper simulado en pruebas nuevas; receptor local en tests de subida simple. No acredita sesión productiva Juan, multipart R2 real, background Android ni iOS.
- Detalle: [cliente API v2](subidas-moviles-cliente-api-v2.md). Siguiente unidad: orquestación Android que persiste asset+sesión atómicamente, reconcilia nativo/remoto, planifica y entrega rangos tras commit; luego cancelación/finalización integral, integración real y APK. No activar capacidades sólo por estos tests.

## Checkpoint histórico: D47, persistencia multipart móvil preparada (12 nov 2026)

- Usuario autoriza «vamos» después del paso propuesto: guardar el avance de cada parte. Se implementan modelo y registro v4 en Flutter; el motor Android todavía NO consume ni entrega partes.
- SesionDeSubidaPorPartes: snapshot inmutable con identidad local, assetId/clientUploadId, bytes/tamaño/plazo UTC, rangos calculados, partes confirmadas por API y generaciones/IDs nativos deterministas. Una tarea enviada no confirma bytes ni READY. Intentos activos no se duplican; reintento exige consulta posterior al cierre y plazo vigente; callbacks antiguos se ignoran.
- RegistroDeSubidas mantiene v3 por defecto y ofrece migración explícita mediante admiteMultipart:true. El orquestador actual conserva v3 y rechaza v4 sin adoptar backups viejos. V1/v2/v3 migran como simples, preservando identidad/tarea/archivo y propietario nulo de v1. No se convierten sesiones simples remotas a multipart.
- Actualización lectura/modificación/escritura serializada por propietario Dart; guardar y esperar antes de handoff. Rechaza snapshots atrasados que perderían sesiones/intentos. No implementa cerrojo entre isolates/procesos ni purga multipart todavía. Si v4 está corrupto conserva main y backup, sin rollback automático que olvide tareas entregadas; reparación con inventario nativo/remoto sigue pendiente.
- Validación: suite completa 397/397 aprobada; comprobación final de registro 27/27 (17 nuevas multipart y10 previas), exit0. Primera pasada de análisis encontró dos lints de llaves, corregidos; análisis final de cuatro archivos con --fatal-infos limpio, exit0. Último pase focalizado cubre además los campos obligatorios del parser v4 endurecidos al cerrar la revisión. Evidencia/hash de cuatro fuentes y tres logs: target/subidas-d47/report.json.
- Sin cambios/despliegue de API, APK, publicación, bucket, emulador, dependencias ni código nativo. API productiva continúa D46; multipartMobileReady=false. No se acredita reinicio Android real, pantalla bloqueada, red interrumpida o iOS.
- Contrato y límites: [subidas-moviles-manifiesto-v4.md](subidas-moviles-manifiesto-v4.md). Siguiente unidad: DTO/MediaService multipart v2 y orquestación Android con reconciliación, rangos, firmas por tandas, cancelación/confirmación; después pruebas nativas/APK. No habilitar capacidades basándose sólo en estas pruebas de persistencia.

## Checkpoint histórico: D46, API liberada (12 nov 2026)

- Nueva autorización expresa: «Puedes liberar la api por favor». API publicada en api-videos-prod, sin publicación ni reconstrucción de la app.
- Versión productiva: `0bbace69e3173e3767168ba662eb0bf5951a4a8e`, imagen `sha256:a98e1827e1fbae1e4951236e791b8dea7a933b0fb8d4227c9b54e6a516fb2830`. Salud healthy y SHA público comprobado desde el cliente. Verificación independiente final: 12 nov 22:28:54 UTC, cero errores observados desde arranque 22:26:08 UTC.
- Fuente aislada desde cb483b7 con D43/D44 y arnés D45; 710 archivos cotejados. Suite completa exacta: 1,414 casos, 1,411 aprobados, tres opcionales omitidos, cero fallos/errores; 187 suites, exit0. Imagen compilada antes del corte; audios de catálogo base48 reutilizados y verificados por SHA256.
- Git: commit inmutable guardado sólo en `refs/codex/releases/subidas-d46`; rama e índice de trabajo preservados, HEAD sigue cb483b7. No confundir HEAD con la versión productiva. Sin push. Cambios locales ajenos de IA/catálogo/documentos comerciales excluidos.
- Preflight PostgreSQL18.4 y base configurada correcta. Cero pendientes multipart/simples; se detuvo el único binario anterior y se comprobó cero multipart antes de migrar. Respaldo completo de 1,896,028 bytes, SHA256 `9bafa752caf7188bcadca241c883ebaa7214652d145bbb244f83e588befe1f3a`; restaurado en PostgreSQL sin red, conteos cotejados y migración ensayada dos veces sobre copia real. Luego migración transaccional productiva, 13 columnas/scope/índice/unicidad verificados. Esquema aditivo y datos conservados.
- Configuración .env.vps y compose conservan hashes; modo multipart existente SIN_LIMITES sin cambios, multipartMobileReady=false. API/imagen/SHA/base correctos; nginx, proxy y otras APIs conservados. Imagen anterior y respaldo de fuente preservados; sin rollback ni borrado de objetos R2. Contenedor/volumen aislado de restauración retirados.
- Evidencia ignorada: target/subidas-d46/{candidate.json,package.json,payload-verified-fast.json,tests-approved.json,status.json,independent-final.json,public-client.json}. Entrega detallada: [subidas-moviles-liberacion-d46.md](subidas-moviles-liberacion-d46.md).
- Incidencia del verificador: Python desde la VM recibió403 al dominio público; causa sin establecer. Comprobación pública independiente desde el cliente sí devolvió SHA nuevo. Verificación SSH local/esquema/respaldo/salud completa. No confundir con una prueba autenticada de cuenta.
- Pendientes globales: sesión productiva de usuario para E2E, manifiesto/partes Android y pruebas físicas; iOS14 requiere Mac. Sin nuevos APK, Flutter, emulador ni transferencias de prueba en esta liberación. Continuar desde D46; no repetir migraciones ni retroceder a binarios viejos con sesiones nuevas pendientes.

## Checkpoint histórico: D45, validación PostgreSQL/R2 (12 nov 2026)

- Usuario autoriza «Okis va» y después «termina». D45 cerrado:152pruebas sobre PostgreSQL,4casos HTTP/R2 reales,migración y limpieza verificadas. No autoriza por sí sola desplegar código nuevo ni publicar app. API/base productivas sin cambios; sólo prefijo de prueba autorizado en bucket real, limpio al terminar.
- PostgreSQL18.3 instalado en C:/Program Files/PostgreSQL/18/bin aunque no estaba en PATH. Servicio local original18 sigue intacto. Se creó cluster privado en target/subidas-d45/postgres/data,rol subidas_d45,listen127.0.0.1:55445,BD subidas_d45 y subidas_d45_migrations; SHOW data_directory confirmó ruta privada antes de migrar. Archivo Downloads elegido sólo lectura:8348901-uhd_2160_3840_25fps.mp4,12,187,330bytes.
- Initdb58345exit0. Wrapper76743 retuvo pipe de pg_ctl; se creó BD con CLI aparte. Tras las pruebas se verificó data_directory y se detuvo cluster privado con pg_ctl stop exit0;55445 sin listener y servicio original Running. Wrapper76743 terminó exit1 al intentar su createdb anidado cuando el servidor ya estaba cerrado; no se reabrió para ese comando innecesario. No quedan procesos propios de prueba pendientes.
- Primera corrida51294exit1 mezcló una clase fijada a H2 con dialectoPG (y arrancó antes de la BD privada disponible). No cuenta como validación PG. Ajuste sólo de arnés: MediaTopeDiarioTest permite subidas.test.{url,driver,user,password,dialect} opcionales manteniendo defaults H2; no cambió expectativas ni códigoAPI. Corregida17283exit0/3:30min:152/152,0fallos/errores/omitidos en PostgreSQL18.3. Evidencia target/subidas-d45/postgres-suite-final.log y13XML archivados en pg-reports antes de otras ejecuciones.
- Migración real sobre fixture mínimo legado en BD separada: tools/validar-subidas-esquema.ps1 verifica data_directory privado, crea esquema nuevo por ejecución, aplica propuestasD43/D44 dos veces y comprueba13columnas,scope,index,fila vieja y unicidad. Pase en target/subidas-d45/migration.log +migration-input.sql. Primer intento sólo se detuvo por NOTICE de IF NOT EXISTS tratado como error nativo PowerShell; se suprimen NOTICE, no errores. No se probó una copia del esquema/datos productivos ni otra versión mayorPG.
- Nuevo SubidasInfraRealIT, opt-in doble (nombreIT + subidas.real=true), API HTTP en puerto aleatorio, QA aislado, autenticación mediante login real con usuarios temporales y clave JWT local nueva. Usa servicio R2/S3 real, sólo prefijo UUID de ejecución; único mock miniaturas para no lanzar derivados/IA. Plan4casos: apertura concurrente/reanudación/finalizaciónservidor+SHA256/propietario, cancelar parte en vuelo, respuestaCreate perdida inyectada y falloaborto inyectado con recuperación real. Manifest sin JWT/URLfirmada registra claves ANTES de Create y cleanup sólo de claves exactas + comprobación prefijo vacío.
- IntentosLive49887(compilación,Role ambiguo) y89836(contexto QA requería CORS) terminaron sin archivos enviados. Corrida66593 terminó por leer data en lugar del sobre API result; login sí respondía200 pero el arnés reutilizaba token vacío. Se corrigió sólo arnés y se recompiló. Su manifiesto7b40e7df... tiene0claves y quedó limpio. Final75446exit0/3:31min,4/4/0omitidos,target/subidas-d45/live-release.log y live-report.xml. Incluye bind127.0.0.1,token no vacío,sobre result y cuota HTTP antes/después de aborto fallido. Tres fuentes del arnés coinciden con inputs.json al cerrar.
- R2 real aprobado4casos: apertura concurrente y reanudación/finalización por worker sin callbackFlutter,SHA256igual al original,owner realJWT aislado,READY protegido;cancelación parte en vuelo;Create real con pérdida de respuesta inyectada;aborto fallido inyectado con reserva/cuota conservada y recuperación real. Manifiesto ba4d7a61-ab18-432f-8e48-d849bf866901:4claves,5eventos,cleaned=true/objectsRemaining=0/multipartRemaining=0. Fuente Downloads sin cambios. Logs live revisados sin credencialesR2 reales/JWT/URLfirmada. No se enviaron mensajes ni publicaciones.
- Pícale cuenta_actual sigue UNAUTHORIZED/reauth; no pedir otra vez lo ya contestado ni afirmar prueba de Juan Rodríguez realizada. Auth validada fue login real con cuentas temporales de API local y secreto JWT local nuevo, no sesión del conector ni producción.
- Compatibilidad H2:73643exit0/5casos/46.2s,sin cambiar defaults ni expectativas. Reporte final target/subidas-d45/report.json verifica13XML PG/152,4Live,5H2,migración,manifiestos limpios y3hashes del arnés. Las20entradas D44 coinciden; código API sin cambios. Todos los handles58345/76743/51294/17283/49887/89836/66593/75446/73643 terminados. No repetir checks aprobados sin cambio/fallo nuevo. Documento docs/subidas-moviles-validacion-infra.md. Sin Flutter/APK D41, publicación, commit/push, delegación ni emulador; nuevo despliegue/respaldo/esquema destino requiere autorización nueva,cb483b7 no se reutiliza. Objetivo global y físicoAndroid/iOS-Mac pendientes.

### Continuación de D45

```text
Retoma primer checkpoint D45 cerrado y docs/subidas-moviles-validacion-infra.md.
Lee decisiones/goal/contratos y AGENTS/git status; preserva cambios ajenos.
PostgreSQL18.3 privado152/152; HTTP+PG+R2 real4/4; H2compatible5/5;
migraciones D43/D44 aplicadas dos veces a fixture legado fresco, no producción.
Evidencia target/subidas-d45/report.json,pg-reports,live-report.xml y manifiestos.
R2 prefijo ba4d7a61... limpio:0objetos/0sesiones; fuente Downloads inalterada.
JWT de prueba local, no token/credencial de producción; no atribuirlo a Juan.
Pícale cuenta_actual sigue UNAUTHORIZED, no repetir pregunta de reconexión.
Cluster privado target/subidas-d45/postgres/data detenido;55445 cerrado;
servicio original Running. Todos los handles terminaron; no esperar ni repetir.
Código API D44 coincide con20inputs; D45 sólo arnés/testconfig/herramienta/docs.
Siguiente: preparar entrega/despliegue revisable con respaldo/esquema real y
autorización nueva; cb483b7 no cubre lo actual. No desplegar por inferir permiso.
No mezclar binarios previos ni borrar SENT/tombstones; conservar límites D43/D44.
Después manifiesto explícito/partes Android, validación física e iOS/Mac.
API multipartMobileReady=false; app/v3/PUT simple/APK D41 sin cambios.
Sin publicación/commit/push/delegación/emulador; objetivo global pendiente.
```

## Checkpoint anterior: D44, apertura/identidad/plazos API (12 nov 2026)

- Usuario autoriza «Okis va vamos» y después «continua» para cerrar aperturas simultáneas, identidad y plazos/versionado antes de Android. D44 cerrado localmente: implementación,152pruebas finales y verificación de fuentes/reportes; no despliegue ni objetivo global completo. Cambios ajenos preservados, sin delegación.
- Nuevos UploadScope/Repository y AdmisionDeSubidas serializan reserva por negocio en transacción corta. PUT simple y multipart participan. Primero se confirma MediaAsset con clave R2 propia; CreateMultipartUpload ocurre fuera del bloqueo/transacción. Se conservan unicidad tenant/clientUploadId y validaciones de cuota/contador/repetidos.
- Nueva AperturaDeSubidas: RESERVED admite Create una sola vez mediante SENT persistido; ATTACHED habilita partes. Respuesta perdida se reconcilia con ListMultipartUploads por clave exacta, paginación y abortos de sobrantes antes de firmar. ID elegido se persiste bajo bloqueo. SDK Create dedicado sin reintentos y timeout2min; cliente general anterior conservado.
- Límite explícito: SENT sin ID visible no repite Create ni libera reserva por suponer ausencia. Guarda OPENING_UNKNOWN/nextCheck; cancelar deja decisión durable y UPLOAD_OPENING_UNCERTAIN hasta resolver ID/aborto. Puede requerir revisión operativa si una caída ocurrió entre admitir y enviar Create. No prometer progreso automático ni cuota liberada tras24h para esa ambigüedad; detalles en docs/subidas-moviles-apertura.md.
- IdentidadDeSubida captura usuario autenticado y digest de metadatos inmutables: versión/negocio/usuario/modalidad/nombre/MIME/bytes/huella/lote/índice. Guarda propietario y tamaño declarado separado del real. Otro usuario del negocio no puede operar la sesión. Workers internos conservan scope tenant. V2 exige campos completos/multipart; legado sin snapshot/propietario sólo mantiene recuperación v1, no se adopta silenciosamente como v2.
- Respuestas: versión de sesión, plazos UTC, nextCheck, modo y parámetros en estado; firma individual y agregada con expiración UTC. usage expone multipartSessionVersion=2 y multipartMobileReady=false; subidaEnFondo=1 permanece. Flutter/manifiesto v3/APK D41 sin cambios.
- Compilación19062exit0/1:38min terminada. Primer intento43925 terminó por inferencia ambigua de assertThat en auxiliar nuevo; corregida declaración de contadores sin cambiar expectativas,target/subidas-d44-focused.log. Pase enfocado43997:85casos,84aprobados/1fallo sólo por frase compatible «otro archivo»;18nuevos de apertura y6SDK pasaron. Se restauró esa frase conservando detalle de identidad; logs *-focused2. Todos esos handles terminaron.
- Verificación final152/152 en13clases (124anteriores+22apertura+6SDK),0fallos/errores/omitidos,exit0,tiempo Maven6:39min. Handle72773terminado,target/subidas-d44-release.log. Verificador aprobó13XML posteriores a inputs,conteo152 y20hashes seleccionados sin cambios; target/subidas-d44-report.json y target/subidas-d44-inputs.json. No es snapshot total del repo ni suite completa. Todos los handles19062/43925/43997/72773 terminaron; ningún comando propio pendiente. Diff check sin errores. No repetir lo aprobado sin nuevo cambio/fallo.
- Esquema propuesto no ejecutado: docs/sql/subidas-d44-apertura.sql (tabla admisión+5columnas, requiere D43). Documento docs/subidas-moviles-apertura.md y referencia oficial Cloudflare verifican soporte documental ListMultipartUploads, no el bucket. PostgreSQL/migración/concurrencia real, permisos R2, pérdida de respuesta/aborto reales, autenticación y móviles/iOS-Mac aún pendientes.
- Sin producción, bucket, credenciales, publicación, commit/push, delegación ni emulador. Permiso cb483b7 no cubre despliegue nuevo; drenar binarios/operaciones anteriores antes de usar RESERVED/SENT. Después de cerrar esta unidad: validar entorno/API y migración explícita de manifiesto/partes Android, sin introducir varias tareas en v3.

### Continuación de D44

```text
Retoma el primer checkpoint D44 cerrado de docs/subidas-moviles-avance.md.
Lee docs/subidas-moviles-apertura.md,coordinacion,contrato/decisiones/goal y
AGENTS/git status; preserva cambios ajenos. API local, sin desplegar.
Final152/152 en13clases,28nuevos,exit0. Evidencia target/subidas-d44-release.log,
*-report.json y *-inputs.json (20entradas seleccionadas verificadas,13XML frescos).
Handles19062/43925/43997/72773 terminados; no esperar ni repetir checks sin cambio.
Admisión por negocio y reserva ANTES de Create; red fuera de transacción.
RESERVED admite Create una vez; SENT incierto sólo lista/reconcilia clave exacta,
jamás vuelve a crear por vencer lease ni suponer ausencia. Mantiene reserva y
cancelación pendiente hasta resolver ID/aborto; puede requerir revisión operativa.
No quitar retry0/timeout del cliente Create ni resetear SENT a RESERVED a ciegas.
Conservar snapshot/propietario/bytes declarados/version; no adoptar legado como v2.
API multipartSessionVersion=2,multipartMobileReady=false,subidaEnFondo=1.
D43 conserva refresh bajo bloqueo contra OSIV, decisiones durables y tombstones.
Siguiente unidad al autorizar el usuario: validación PostgreSQL/R2/autenticación
y preparación operacional, o migración móvil explícita aislada sin activar
producción. No afirmar validaciones realizadas ni publicar motor multipart.
SQL D43/D44 propuestas no ejecutadas. Nuevo deploy exige autorización nueva y
drenar binarios/operaciones previas; cb483b7 no cubre lo nuevo.
Flutter/v3/PUT simple/APK D41 iguales. No varias tareas en v3, no publicación,
commit/push/delegación/emulador. Mac/iOS/físicos y objetivo global pendientes.
```

## Checkpoint anterior: D43, coordinación durable API (11 nov 2026)

- Usuario autoriza «Okis va llevalo acabo» tras proponer coordinación de cierre/cancelación/limpieza. D43 cerrado localmente: implementación,124pruebas finales y verificación de fuentes/reportes; no objetivo global completo ni despliegue.
- Nuevos UploadDecision, CoordinadorDeSubidas y MultipartRecoveryWorker. Ocho campos opcionales en MediaAsset e índice de reintentos; propuesta PostgreSQL no ejecutada en docs/sql/subidas-d43-coordinacion.sql. Decisión persistente separada de estado del medio, lease5min con UUID, CHECKING sólo lee; COMPLETING/CANCELLING/EXPIRING no se cambian por una decisión opuesta al vencer lease. READY no se borra por cancelar.
- MediaService.complete/confirm, worker y reconciliador delegan al coordinador. R2 se consulta fuera de transacciones de coordinación/limpieza. Confirmación bloquea/revalida y despacha miniatura tras commit. Cancelación aborta y verifica partes/objeto antes de liberar cuota; conserva registro CANCELLED/EXPIRED con estado de medio RELEASED. No llama a deleteByKey; borrado/liberación genéricos no eluden la cancelación.
- Plazos internos Instant UTC:24h apertura nueva, protección firma+10min, TTL acotado a plazo; adopción conservadora de filas heredadas. Consulta de partes sin renovación ni transacción remota. Cola global hasta100filas cada2min, nextAttempt/lease/backoff persistidos. Limpiador y rescate anteriores excluyen multipart para no ocupar sus lotes con sesiones protegidas. Test resources desactivan sólo el nuevo worker automático durante pruebas; pruebas lo invocan explícitamente.
- Verificación intermedia:compile96792exit0/41.6s;56tests/3clases/15nuevos74923exit0/2:36min;122tests/11clases/18nuevos10176exit0/1:57min;123tests/19nuevos79509exit0/3:51min tras excluir multipart de limpieza antigua. Todos esos handles terminados. Logs target/subidas-d43-{compile,focused,tests,final}.log. Inputs12 de esa corrida en target/subidas-d43-inputs.json; se actualizarán si cambia código.
- Revisión adicional:prueba de contexto de petición con entidad almacenada antes de que otro coordinador cancele. Reprodujo1fallo/0errores,exit1/3:03min,handle29323terminado,log target/subidas-d43-osiv-before.log. La consulta con bloqueo devolvía la instancia administrada vieja (OSIV está activado por defecto en este proyecto). Corregido con fragmento MediaAssetUploadLocks/Impl: transacción obligatoria, consulta bajo @TenantId/PESSIMISTIC_WRITE y refresh bajo el mismo bloqueo antes de validar decisión/token. Pase124/124,20casos del coordinador y104anteriores,exit0/4:52min,handle92497terminado,log target/subidas-d43-reviewed.log. Ajuste posterior de cierre de consulta: getResultList en vez de getResultStream sin cierre; no dejar cursor remoto pendiente fuera del bloqueo corto.
- Código congelado final. Entrega verificada124/124 en11clases,20nuevos de coordinación,0fallos/errores/omitidos,exit0/3:06min,handle17661terminado,log target/subidas-d43-release.log. Inputs14 coinciden al terminar y11XML son posteriores a inputs,conteo124 verificado. Reporte target/subidas-d43-report.json,entradas target/subidas-d43-release-inputs.json; hashes de entradas seleccionadas, no snapshot total del repositorio. Comprobación diff sin errores. Todos los handles96792/74923/10176/79509/29323/92497/17661 terminaron; ningún comando propio pendiente. No repetir lo aprobado sin cambio o fallo nuevo.
- Documento técnico/contrato actualizado en docs/subidas-moviles-coordinacion.md y docs/subidas-moviles-contrato-multipart.md. H2 en memoria y R2 simulado; no había docker/psql/pg_ctl disponibles en PATH. PostgreSQL real/migración, bucket real con partes en vuelo, autenticación y físicos/iOS siguen sin verificarse. No mezclar workers viejos/nuevos al desplegar ni revertir a binario viejo sobre sesiones coordinadas pendientes.
- Sin Flutter/APK D41, bucket, credenciales, producción, publicación, commit/push, delegación ni emulador. API nueva aún no anuncia protocolo multipart móvil completo. Quedan apertura concurrente sin huérfanos, identidad/propietario completos y plazos/versionado en respuestas, antes de migrar manifiesto y conectar Android. Autorización cb483b7 no cubre un nuevo despliegue. Cambios ajenos preservados.

### Continuación de D43

```text
Retoma el primer checkpoint D43 cerrado de docs/subidas-moviles-avance.md.
Lee docs/subidas-moviles-coordinacion.md,contrato/decisiones/goal y AGENTS/git status.
Preserva cambios ajenos. Coordinador durable API implementado, sin desplegar.
Final124/124 en11clases,20nuevos,exit0/3:06min. Logs/report/inputs14 en
target/subidas-d43-{release.log,report.json,release-inputs.json}.
Todos los handles96792/74923/10176/79509/29323/92497/17661 terminaron;
no esperar ni repetir checks aprobados sin cambio/fallo nuevo.
CHECKING sólo lee; antes de mutar R2 persistir COMPLETING. No cancelar esa
decisión al vencer lease. Cancelar aborta/verifica antes de soltar cuota y
conserva CANCELLED/EXPIRED; jamás deleteByKey ni borrar READY por cancelar.
findForUploadUpdate debe refrescar bajo bloqueo: OSIV daba una copia vieja,
reproducida y corregida. No quitar refresh ni sustituirlo por @Lock solamente.
Siguiente unidad de código al autorizarla el usuario: D44,cerrar apertura
concurrente/idempotente sin huérfanos,identidad/propietario y respuestas UTC/
versionado. Luego migración explícita de manifiesto y partes Android.
No integrar multipart en v3 ni atribuirlo al APK D41. PostgreSQL/migración,
R2 real/autenticación/físicos/iOS-Mac pendientes. SQL propuesta no ejecutada;
sin deploy con permiso cb483b7 ni mezcla/reversión a workers viejos sin drenaje.
Sin publicación/commit/push/delegación/emulador; objetivo global pendiente.
```

## Checkpoint anterior: D42-R1, segunda revisión del contrato (11 nov 2026)

- Usuario solicita revisar que haya quedado bien. Baseline verificado:2fuentes D42 y hash de su log coinciden con inputs/reporte anterior;93pruebas no se atribuyeron a otras fuentes. Cambios ajenos preservados, sin delegación.
- Hallazgo adicional en R2StorageService.partesSubidas: página truncada sin marcador siguiente, repetido, regresivo o fuera del rango de partes permitía consultas inválidas/repetidas; podía retener hilo y transacción del servicio que consulta. Corregido con avance estricto, marcador obligatorio y límite10,000. Error recuperable sin retornar lista parcial ni convertirlo en ausencia.
- Nuevo R2MultipartConsultaTest,11casos con adaptador R2 real y S3Client simulado. Verifica identidad bucket/key/uploadId al paginar, orden, NoSuchUpload entre páginas, errores S3/transporte y6variantes de marcador inválido. No se usa Spring ni bucket en la reproducción aislada. Primer intento87716 sólo falló al compilar una declaración del auxiliar por tipo del builder SDK; se corrigió con var sin cambiar expectativas; log target/subidas-d42-review-before.log. Reproducción válida13804:11casos,6fallos/0errores,exit1/1:16min,target/subidas-d42-review-repro.log. Ambos handles terminados.
- Verificación final cerrada:104/104,10clases (93anteriores+11nuevas),0fallos/errores/omitidos,exit0/3:38min,target/subidas-d42-review-tests.log. Handle10128 terminado; ningún comando propio pendiente. Fuente final congelada;4hashes en target/subidas-d42-review-inputs.json coinciden al terminar. Reporte verifica10XML/count104 y hashes:target/subidas-d42-review-report.json. H2 en memoria subidas_d42_review/importación opcional inexistente para no cargar .env/R2 simulado/JDK17. No hay cambio en MediaService ni SubidaPorPartesTest respecto a D42. No repetir checks aprobados sin cambio o fallo nuevo.
- Alcance propio de revisión:8líneas nuevas/1reemplazada en R2StorageService, prueba nueva y documentos/checkpoints. No avanzar a D43: coordinación completar/cancelar/limpiar, estados terminales, plazos e identidad siguen pendientes. Registro móvil v3/PUT simple/APK D41 sin cambios. Sin producción, bucket, credenciales, publicación, commit/push, emulador ni validación física/iOS. La autorización antigua cb483b7 no cubre nuevos despliegues. Objetivo global pendiente.

### Continuación de D42-R1

```text
Retoma el primer checkpoint D42-R1 cerrado de docs/subidas-moviles-avance.md.
Lee contrato multipart/decisiones/goal y AGENTS/git status; preserva cambios ajenos.
La segunda revisión añadió protección contra paginación sin avance de ListParts.
11casos nuevos con R2StorageService real/S3Client simulado,6fallos reproducidos;
104/104 finales en10clases,exit0/3:38min. Inputs4fuentes/reporte en
target/subidas-d42-review-{inputs,report}.json. Handles87716/13804/10128
terminados. No esperar ni repetir verificación sin cambio o fallo nuevo.
Siguiente unidad, sólo cuando el usuario la solicite: D43 coordinador durable
común de completar/cancelar/limpiar; criterios en
docs/subidas-moviles-contrato-multipart.md.
No conectar multipart móvil antes de resolver carreras/plazos/estados terminales.
v3/PUT simple/APK D41 siguen iguales. Sin nuevo deploy con permiso cb483b7,
ni publicación/commit/push/delegación/emulador. Físicos e iOS/Mac pendientes.
```

## Checkpoint anterior: D42, contrato multipart API (11 nov 2026)

- Usuario: «Okis vamos con lo siguiente», después de proponer revisión del contrato multipart API. D42 cerrado localmente: auditoría, endurecimiento compatible y93pruebas aprobadas; no equivale a cerrar todo el protocolo móvil.
- Alcance propio: MediaService, SubidaPorPartesTest, docs/subida-por-partes.md, nuevo docs/subidas-moviles-contrato-multipart.md y checkpoints/decisiones. Cambios ajenos preservados. Flutter/SDK/manifiesto v3 no se editan; APK D41 sigue siendo el último de la app.
- Reproducción previa: target/subidas-d42-before.log,27tests,7fallos+2errores esperados,exit1,2:38min. Diez casos nuevos sobre17existentes;9 detectan fallos anteriores y uno comprueba rechazo ya existente. Handle64778 terminado.
- Correcciones: no filtrar silenciosamente partes extra; números exactos/únicos, tamaños individuales y ETag obligatorio; orden ascendente al unir. Apertura idempotente existente se recupera aun con bandera APAGADA. Si R2 ya unió pero no se guardó, ListParts vacío más objeto final de tamaño exacto permite confirmación; error S3 al unir exige también tamaño exacto al recuperar. Error de lectura no libera reserva ni se interpreta como ausencia.
- Pruebas finales:16casos nuevos en total. Incluyen60partes en lotes50+10/rechazo51, bandera apagada con sesión abierta, aislamiento por workspace, rechazo de objetos incorrectos y conservación de datos ante fallo remoto. Batería9clases:93/93,0fallos/errores/omitidos,exit0,3:46min;33son de SubidaPorPartesTest. Handle49845 terminado,log target/subidas-d42-tests.log. H2 en memoria,importación opcional inexistente para no cargar .env,R2 simulado,JDK17; sin acceso al bucket. Verificación posterior de9XML y hashes de2fuentes aprobada; target/subidas-d42-inputs.json y target/subidas-d42-report.json. Ningún comando propio pendiente. No repetir checks sin cambio o fallo nuevo.
- Contrato y brechas en docs/subidas-moviles-contrato-multipart.md. No se anuncia capacidad nueva ni subidaEnFondo>1. Falta coordinación durable completar/cancelar/limpiar, estados terminales, apertura concurrente recuperable, identidad completa y plazos UTC/lease. No conectar multipart móvil antes de resolverlos.
- Sin despliegue/producción/R2/credenciales/publicación/commit/push/delegación/emulador ni nuevas pruebas físicas. Autorización cb483b7 no cubre estos cambios. Objetivo global pendiente. Siguiente unidad: D43, coordinación durable compartida por HTTP, worker y limpieza con cancelación segura.

### Continuación de D42

```text
Retoma el primer checkpoint de docs/subidas-moviles-avance.md: D42 cerrado.
Lee docs/subidas-moviles-contrato-multipart.md, decisiones/goal y AGENTS/git status.
Preserva cambios ajenos. API local tiene validación exacta de partes y recuperación
de unión terminada, sin capacidad multipart móvil nueva. 93/93tests en9clases,
33multipart;16casos nuevos,9fallos anteriores reproducidos. Reporte y hashes en
target/subidas-d42-report.json. Handles64778/49845 terminaron; no esperar ni repetir.
Siguiente D43: implementar coordinador durable compartido por cierre HTTP,
rescate y limpieza; cancelación idempotente recuperable sin borrar READY.
Usar criterios de aceptación del documento de contrato, probar carreras y
recuperación sin transacciones largas sobre R2. No basta un lock en memoria.
No conectar partes Android antes de cerrar coordinación/contrato; v3 y PUT
simple siguen vigentes, APK D41 no cambia. Sin nuevo deploy con permiso cb483b7,
ni publicación/commit/push/delegación/emulador. Físicos e iOS/Mac pendientes.
```

## Checkpoint anterior: D41, revisión del adaptador D40 (11 nov 2026)

- Usuario: «revisa que haya quedado bien», referido al adaptador recién entregado. D41 cerrado localmente: correcciones,133tests, análisis22, APKrelease y firma verificados; no avance a multipart. Antes de editar,28fuentes D40 y su APK coincidían con el reporte; no era un binario obsoleto. Carreras adicionales reproducidas y corregidas; objetivo global pendiente.
- Reproducción:10casos fallaban en `../metricol_app/mobile-upload-motor-review-before.log`; pase corregido44/44 en *-tests-initial. Fuente nueva de pruebas `test/motor_revision_test.dart`,12casos totales. Corrección limitada a `lib/services/motor_de_subidas.dart`; dominio, registro v3, API y código nativo no cambiaron.
- Corregido1: coalescencia por ID se hacía antes de validar el segundo pedido. Dos solicitudes simultáneas con otro archivo/grupo podían recibir la misma aceptación. Ahora se valida grupo/tipo y se compara la carga con la solicitud en curso antes de compartir resultado; tareas incompatibles no se entregan ni se presentan como aceptadas.
- Corregido2: allTasks e historial se leían en momentos distintos; un evento running entre ambas lecturas podía generar ausencia falsa, otro enqueue, borrado de historial activo o cancelación confirmada erróneamente. Contador de cambios por ID invalida una ausencia si recibió estado durante la consulta y reconsulta de forma acotada. Presencia positiva conserva trabajo; lectura inestable que no se estabiliza falla sin efectos. Tarea activa e historial de otro archivo se rechazan en vez de mezclar identidad/estado/progreso.
- Corregido3: entregar/cancelar después de cerrar podían unirse a futures existentes y devolver aceptación/éxito. Ahora todas las llamadas nuevas se rechazan al cerrar, antes de coalescer/esperar una operación anterior. Solicitudes ya enviadas siguen resolviéndose; cerrar no cancela implícitamente tareas del sistema. Dos casos adicionales comprueban consulta/historial cerrados sin esperar una entrega bloqueada.
- Verificación de código:133/133 exit0/66s (121anteriores+12nuevas), análisis22 limpio exit0/16.4s. Análisis inicial sólo pidió llaves en un auxiliar nuevo, corregidas sin cambiar lógica/aserciones; log *-analyze-initial preservado. Handles43168suite,46025reproducción,29194pase44,57294análisis inicial y1923final terminados. Código congelado; no repetir checks sin cambio. Puerto/API simulados con adaptador real; no prueba física ni transacción atómica con WorkManager. Detección de cambios observados durante lectura, sin inventar garantías del sistema operativo.
- Build único74991 terminó exit0/1001.7s, `../metricol_app/mobile-upload-motor-review-apk.log`. Verificación exit0:29fuentes/config coinciden con inputs anteriores al build, plugin centralizado, logs aprobados, APK posterior a inputs, firma válida/no debuggable y copia idéntica. Inputs/reporte/scripts/firma/metadata en `../metricol_app/build/mobile-upload-motor-review`.
- APK D41 estable: `../metricol_app/build/mobile-upload-motor-review/app-release.apk`,75,822,720bytes,SHA256 `a05687481cf5162f63d10e24622aabd6ea7687e5fb2126c2cf7bc67115b286a0`. Paquete com.rodtech.picale,1.0.13+14/min24/target36. Sin AAB/publicación; D40/D39 y anteriores históricos, no incluyen esta revisión. Handles46025reproducción/29194pase44/43168suite133/57294análisis inicial/1923análisis final/74991build terminados, verificación terminó directamente. Ningún comando propio pendiente; no repetir lo aprobado sin cambio.
- Sin nuevos Java/producción/R2/credenciales/publicación/AAB/commit/push/delegación/emulador ni validación Android física/iOS. Cambios ajenos preservados. Después de cerrar revisión, siguiente unidad D42: auditar/cerrar contrato multipart/coordinación API y después partes móviles, con autorización específica antes de otro despliegue. Objetivo global pendiente.

### Continuación de D41

```text
Retoma D41 cerrado en primer checkpoint de docs/subidas-moviles-avance.md.
Lee decisiones/goal y AGENTS/git status API/Flutter; conserva cambios ajenos.
Revisión D40 cerrada:10fallos reproducidos/corregidos y12casos nuevos,
133tests/66s,análisis22limpio/16.4s,APKrelease1001.7s/firma válida.
Inputs29 coinciden con fuentes finales en build/mobile-upload-motor-review/report.json.
APK SHAa0568748... local,1.0.13+14 no publicado. Todos los handles terminaron;
no esperar/reiniciar74991 ni repetir lo aprobado. APKD40 es histórico.
Conservar v3/propietarios/lotes/consentimiento/tombstones y PUT simple; no avanzar
multipart/iOS ni alterar API/producción. Sin commit/push/delegación/emulador.
La consulta nativa inestable nunca es ausencia; solicitud incompatible no se
coalesce; llamada nueva después de cerrar no comparte una operación anterior.
Siguiente D42: auditar/cerrar contrato y coordinación multipart existentes,
sesiones/partes/finalización/cancelación idempotentes; luego conectar partes
móviles con migración explícita del manifiesto, por unidades verificables.
API cb483b7 ya desplegada; no reutilizar autorización. Físicos/iOS14/Mac y
autenticación mantienen dependencias anteriores. No objetivo global completo.
```

## Checkpoint anterior: D40, adaptador del motor (11 nov 2026)

- Usuario autoriza el punto1 «adaptador del motor de subidas» con «Okis va». D40 cerrado localmente:121tests, análisis21limpio, APKrelease y firma verificados. Centralizados arranque, consultas, entrega, cancelación e historial; PUT simple/registro v3 y guardas anteriores conservados. Multipart/API/iOS y objetivo global siguen pendientes.
- Nuevo `lib/services/motor_de_subidas.dart`: contrato MotorDeSubidas, estados/resultado de entrega explícitos, MotorNativoDeSubidas y frontera PuertoDeSubidas con implementación background_downloader9.5.9. Sólo esa implementación crea/usa FileDownloader, de forma perezosa. SubidasEnFondo ya no accede al singleton/DB nativo ni interpreta taskForId como prueba de ausencia.
- Arranque único/coalescido; suscripción antes de configurar/recuperar eventos. Eventos de arranque se almacenan hasta terminar configuración y se despachan en orden por ID. Fallo de arranque quita listener/buffer y permite retry sin duplicados. Fallo de consumidor se registra por tipo y no rompe eventos siguientes. Cerrar sólo desvincula; no cancela transferencias ni limpia datos activos.
- Plugin real: notificaciones previas conservadas, foreground Android, tracking sólo del grupo picale-subidas, markDownloadedComplete=false, start sin autoClean ni rescheduleKilledTasks. La recuperación del registro de dominio decide consulta API/firma nueva, evitando competir con el timer tardío de5s del paquete. Versiones/dependencias/nativo Android/iOS sin cambios.
- Consultas: allTasks incluye tareas nativas, pausadas y waitingToRetry; DB aporta estado/progreso. Una tarea presente gana sobre un registro terminal viejo; historial running sin tarea real se considera ausente. Error de consulta se propaga, nunca se convierte en ausencia. IDs de otro grupo/tipo o reutilizados para otro archivo se rechazan sin tocar esos datos.
- Operaciones por ID serializadas y entregas/cancelaciones coalescidas. Entrega distingue aceptada/existente/completa/rechazada. Completa implica confirmar API, no READY local. Cancelación requiere que ya no aparezca activa después de llamar al plugin; ACK por sí solo no libera la copia. Historial de tarea activa no se borra. El SDK conserva sus tareas a través de cerrar/reabrir el adaptador.
- Recuperación conserva usuario/negocio/lote/índice/ID: tarea presente no pide otro permiso ni reenfile; tarea ausente se recupera con firma nueva; completa se confirma incluso si falta copia; cancelación local persistida jamás se reenfila. Fallo/cancelación nativos sin decisión local de detener conservan recuperación anterior. Pausas/fallos explícitos del dominio siguen sus reglas de consentimiento/READY.
- Consulta/suscripción recuperan porcentaje del historial/evento reciente; seguir reproduce último avance válido al crear tarjeta. Reintento nuevo limpia porcentaje anterior; cola notifica al terminar entrega. Caché de progreso sólo en memoria; no cambia JSON v3 ni persiste firmas/tokens. Logs nativos sólo tipos/códigos, sin excepciones completas con URL firmada.
- Pruebas nuevas: `test/motor_de_subidas_test.dart`21casos y `test/motor_subidas_recuperacion_test.dart`13casos. Helpers puerto_de_subidas_falso y motor_de_subidas_falso; los tests anteriores migran a MotorDeSubidas inyectado sin relajar aserciones. Puerto/API simulados, IO/registro/gestor reales: no prueba física ni de proceso Android muerto. Nuevo constructor de pruebas puede iniciar el adaptador real con puerto simulado; producción conserva su inicialización propia.
- Evidencia final de código:121/121 exit0/133s (87anteriores+34nuevas), análisis21limpio exit0/9.9s. Logs `../metricol_app/mobile-upload-motor-tests.log` y *-analyze.log. Pases64/64 y118/118 preservados como *-tests-initial/*-tests-pass1. Una comprobación de porcentaje leía antes del callback; se sustituyó por avance observable con límite5s, manteniendo expectativa0 para otro intento;13/13pasaron antes de suite definitiva. Estilos de llaves corregidos sin cambiar lógica/aserciones; logs iniciales preservados. Handles37169/9376 terminados; no repetir checks sin cambio.
- Build único42054 terminó exit0/647.0s, `../metricol_app/mobile-upload-motor-apk.log`. Verificador exit0:28fuentes/config coinciden con inputs anteriores al build, FileDownloader sólo referenciado en el adaptador, APK posterior a inputs, firma válida/no debuggable y copia idéntica. Reporte/inputs/scripts/firma/metadata en `../metricol_app/build/mobile-upload-motor`.
- APK D40 estable: `../metricol_app/build/mobile-upload-motor/app-release.apk`,75,822,720bytes,SHA256 `da00d290b3ff5931babceb58885d7f10d4247d421d16ca393fa244414ac0ae77`. Paquete com.rodtech.picale,1.0.13+14/min24/target36. Sin AAB/publicación. APK D39/D38/D37/D36/D31 conservados como históricos y no acreditan este adaptador.
- Todos los handles de D40 terminados:19520/6211análisis iniciales de estilo,74033pase64,64696pase118,26585pase con comprobación prematura de progreso,1820análisis intermedio limpio,47633regresión13,37169suite121,9376análisis final y42054build. Verificación terminó directamente exit0. Ningún comando propio pendiente; no repetir suites/builds aprobados sin nuevo cambio.
- Cambios ajenos conservados. API sólo continuidad/decisiones/validación; sin nuevo Java/producción/R2/credenciales/publicación/commit/push/delegación/emulador. Físicos/iOS14/Mac y prueba autenticada con dependencias registradas, sin repetir reconexiones ni deploy cb483b7. Después de cerrar D40, D41 auditar/cerrar contrato multipart y coordinación API, luego conectar partes móviles por unidades.

### Continuación D40

```text
Retoma D40 cerrado en primer checkpoint de docs/subidas-moviles-avance.md.
Lee decisiones/goal, AGENTS/git status API/Flutter y conserva cambios ajenos.
Adaptador completo implementado:121tests/133s, análisis21limpio/9.9s,
APKrelease647.0s/firma válida. Inputs28 coinciden con fuentes finales en
build/mobile-upload-motor/report.json. APK SHAda00d290... local,1.0.13+14
no publicado. Todos los handles terminaron: no esperar/reiniciar42054 ni repetir
lo aprobado. APK D39 y anteriores son históricos.
Motor real sólo en motor_de_subidas.dart; tareas esperando no son ausentes,
historial running no prueba actividad, consulta fallida no permite reenvío.
SDK sin timer rescheduleKilledTasks/autoClean; recuperación por dominio/API.
No perder dueños/lotes/v3/tombstones/consentimiento ni reactivar cancelaciones.
Siguiente D41: auditar contrato multipart/coordinación API existente y cerrar
sesiones/partes/finalización/cancelación idempotentes por una unidad verificable;
después conectar partes móviles con migración explícita de manifiesto. No asumir
que PUT simple soporta resume por bytes ni cambiar iOS sin su validación.
Sin deploy/publicación/commit/push/delegación/emulador, iOS14/Mac y físicos
pendientes. API cb483b7 ya desplegada; permiso no reutilizable para otro commit.
Guardar checkpoint/prompt por unidad; no declarar objetivo global completo.
```

## Checkpoint anterior: D39, segunda revisión de D38 (11 nov 2026)

- Usuario: «dale una segunda revisada», referido a la etapa Android recién entregada. Revisión D39 cerrada localmente con correcciones,87/87pruebas exit0/86s, análisis16archivos limpio exit0/12.6s, APKrelease640.5s y firma verificada. No se adelantó multipart ni despliegue. Objetivo global aún pendiente.
- Reproducción: ocho casos fallaban antes de los arreglos, `../metricol_app/mobile-upload-d38-review-before.log`. Primer pase corregido39/39 en *-tests-pass1. Suite definitiva añade12casos a los75anteriores:87únicos en *-tests.log. No son sólo cambios de etiquetas: ejercitan archivos privados reales, registro atómico, compuertas de persistencia, eventos y cola; motor/API están simulados, no Android físico.
- Corregido1: tarjeta restaurada se empezaba a escuchar antes de añadirla a la cola; la guarda de vigencia la descartaba y no recibía progreso/READY. Ahora se añade primero y todas las restauraciones comparten la traducción de fases. Una restauración antigua se descarta si cambian sesión, usuario, negocio o servicio durante sus esperas.
- Corregido2: quitar un objeto antiguo tras reemplazo por vencimiento podía cancelar el ID viejo y borrar el archivo compartido con el nuevo. Se exige objeto registrado/vigente y se protege una tarjeta ya lista. La UI ignora respuestas de quitar de una tarjeta sustituida y conserva READY si ganó la carrera. Cancelación cierra canales de progreso y no elimina copia referenciada por otro trabajo activo.
- Corregido3: reintento exigía archivo local antes de preguntar si API ya lo rescató; además restauración omitía fallos/requiere_archivo. Ahora se reconcilia READY primero para trabajos preparados/con reserva, sin otro permiso/envío. Las tarjetas detenidas no se reenvían automáticamente sólo por consultar. Reintentar verificación sólo confirma; se conserva dueño/token/ID después de consultar y se recalcula la posición tras la espera.
- Corregido4: cancelación entre guardado de permiso READY y confirmación podía resucitar el estado, confirmar y perder el tombstone. Guardas después de persistir y antes de confirmar, validando propietario/token y registro vigente. Confirmaciones simultáneas coalescidas; eventos tardíos no retroceden verificación/READY/cancelación. La coordinación con reserva/finalización remota sigue pendiente.
- Pruebas nuevas también cubren cambio de sesión durante GET, cancelación durante GET, confirmaciones concurrentes y restauración antigua que vuelve después de cambiar de cuenta. Nuevas costuras de prueba inyectan registro/historial y el gestor en cola; NO equivalen a extraer el adaptador nativo completo. Constructor de pruebas representa motor simulado iniciado; producción conserva inicialización real/listener antes de start.
- Fuentes Flutter de esta revisión: subidas_en_fondo, cola_de_subidas, celda de contenido; test/subidas_segunda_revision_test.dart y fake de subidas_en_fondo_test. Registro/modelo v3 y preparador conservados. API sólo documentación, cambios ajenos preservados. Sin producción, R2, credenciales, commit/push, publicación, delegación, emulador o pruebas físicas/iOS.
- Evidencia final en `../metricol_app/mobile-upload-d38-review-tests.log`, `*-analyze.log`, `*-apk.log`. Análisis inicial sólo observó llaves faltantes en auxiliar nuevo, corregido sin cambiar aserciones; log *-analyze-initial conservado. Build único2812 terminó exit0/640.5s; verificación exit0. Inputs23fuentes/config capturados antes del build y hashes posteriores idénticos, `build/mobile-upload-d38-review/inputs.json` y `report.json`; scripts capture-inputs/verify conservados. Firma válida/no debuggable, APK posterior a inputs y copia idéntica verificados. No nueva suite/build sin cambios que lo justifiquen.
- APK revisado estable: `../metricol_app/build/mobile-upload-d38-review/app-release.apk`,75,822,720bytes,SHA256 `5696654671e282aff2dda0489bbe7eaf1b4e75f985cb4916cd806e523e63a3a6`. Paquete com.rodtech.picale,1.0.13+14/min24/target36; sin AAB/publicación. D38/D37/D36/D31 se conservan como históricos y no incluyen esta revisión. Handles58908reproducción/63294pase39/55553suite87/56966análisisestilo/74259análisisfinal/2812build todos terminados; verificación terminó directamente. No comando propio pendiente.
- Siguiente código después de cerrar revisión: D40, adaptador completo del motor, antes de multipart/coordinación API. No marcar terminado el objetivo global. Persisten acceso autenticado, Android físico e iOS14/Mac pendientes según decisiones anteriores; no repetir reconexiones ni despliegue cb483b7.

### Continuación tras D39 cerrado

```text
Retoma D39 cerrado en primer checkpoint de docs/subidas-moviles-avance.md.
Lee decisiones/goal, AGENTS y git status API/Flutter; conserva cambios ajenos.
Segunda revisión de D38 cerrada:8fallos reproducidos/corregidos,12casos nuevos,
87tests,análisis16limpio,APKrelease640.5s/firma válida;23fuentes sin cambios
entre inputs y verificación en build/mobile-upload-d38-review/report.json.
Todos los handles terminaron; no esperar/reiniciar2812 ni repetir lo aprobado.
APK SHA56966546... es local,1.0.13+14 no publicado. D38 anterior es histórico.
Siguiente D40: adaptador nativo completo sustituible; encapsular inicio/listeners
antes de start, entrega, consulta de estados/tareas, cancelación e historial.
Probar espera del sistema frente a tarea ausente y evitar entrega duplicada.
La inyección de pruebas de D39 no constituye ese adaptador completo.
Conservar dueño/token/lote/ID/v3, copia íntegra, consentimiento y tombstones;
READY remoto gana sin requerir copia; tarjetas antiguas no borran otra generación.
Luego multipart/coordinación API por unidades. Cancelación actual local, no
liberar reservas ni borrar READY. Sin deploy/publicar/commit/push/delegar/emulador.
API cb483b7 ya desplegada, aprobación no reutilizable para otro commit.
iOS14 pendiente de Mac, físicos/autenticación con dependencias registradas.
Guardar checkpoint y prompt por unidad; no declarar objetivo global completo.
```

## Checkpoint anterior: D38, preparación y estados recuperables (11 nov 2026)

- Usuario: «okis hazlo», referido a continuar la siguiente unidad de código. Unidad D38 cerrada localmente: pruebas, análisis, APK y firma verificados. El objetivo global sigue pendiente; no publicar/desplegar.
- Registro v3, migración explícita de v1/v2: propietario, lote e índice conservados; v1 sigue sin dueño. Toda selección Android se registra en un snapshot antes de copiar o calcular huellas, incluso sin respuesta de red. No persistir tokens ni URLs firmadas.
- Preparador nuevo: streaming a `.part`, espacio libre mediante canal Android limitado al directorio privado, tamaño/mtime del origen, validación de tipo existente y SHA256 completo de integridad separado de la huella de duplicados. Guarda digest antes del rename y copia preparada después. Archivo privado final con ruta relativa. La referencia absoluta al origen es temporal y exclusiva del camino Android; no trasladarla al futuro contenedor iOS.
- Recuperación: copia parcial sin digest se rehace desde el origen; temporal validado o final renombrado se recupera verificando tamaño/digest aunque falte el original. Original ausente/cambiado o copia alterada conserva tarjeta y pide revisión; no reserva ni entrega bytes incompletos. Sin compresión ni carga completa del video en memoria.
- Fases durables: preparando, en cola, subiendo, esperando conexión/sistema, verificando, requiere sesión/archivo, fallo/vencida, cancelando/cancelada. Espera del planificador sigue activa; no se presenta como transferencia fallida. `lista` exige respuesta de API con medio. Duplicados conservan explicación/asset anterior y la autorización explícita para repetir.
- Cancelación local: intención antes de llamar al motor, espera al pipeline de preparación/permiso, elimina copias después de respuesta positiva del motor; conserva tombstone y reintenta una cancelación no confirmada al volver. Tarjeta permanece si falla. Carrera con READY/reserva remota aún requiere la futura coordinación API; esta unidad no libera reservas remotas ni borra medios publicados.
- Límites: el registro sobrevive una preparación interrumpida; no afirma que Dart termine de copiar con el proceso muerto. Si el selector pierde el original antes de terminar, se requiere elegirlo de nuevo. Falta demostrar comportamiento físico/bloqueo de pantalla. Motor Android continúa PUT simple; multipart, adaptador completo, coordinación API e iOS14 nativo son unidades posteriores.
- Evidencia final:75/75 exit0/89s, regresión del cierre31/31 exit0/83s y análisis15archivos limpio exit0/211.9s. La regresión repite casos de la suite; no son106casos únicos. Incluye recuperación de duplicados sin perder una autorización durable, que la tarjeta ahora conserva con OR. Logs `../metricol_app/mobile-upload-preparation-tests.log`, `*-recovery-tests.log`, `*-analyze.log`. Pases fallidos/intermedios preservados como *-before/*-pass1..4. Se corrigieron autoespera de Future, errores de compilación del caso nuevo y timeouts/IO de Windows; ejecución final secuencial con límite2m, sin rebajar aserciones.
- APK definitivo: build71796 exit0/574.2s, `mobile-upload-preparation-apk.log`. Firma válida/no debuggable; copia estable `../metricol_app/build/mobile-upload-preparation/app-release.apk`,75,822,720bytes,SHA256 `571354541284a598cce84380fd82f311f7ec3da071a1e4e2928fcd84c5dbef3e`. Paquete com.rodtech.picale, versión1.0.13+14, min24/target36. `build/mobile-upload-preparation/report.json` registra firma/tests/hashes22fuentes y configuración, con verifier específico. Sin AAB nuevo ni publicación. APK D37/D36/D31 conservados como históricos.
- Build inicial75259 exit0/1270.2s es intermedio previo al último OR; log *-apk-initial preservado, no entregarlo como final. Handles de diagnóstico18163, análisis49976, regresión83515, build71796 y verificación71176 todos terminados. No proceso propio pendiente ni motivo para repetir lo aprobado. Limpieza de dos directorios temporales propios de pases fallidos completada por rutas literales tras inspección, sin borrado recursivo.
- Cambios ajenos conservados. Ningún nuevo cambio Java, API producción, R2, credenciales, publicación, commit/push, delegación, emulador o prueba física/iOS.
- Siguiente unidad de código D39: encapsular el motor nativo en un adaptador sustituible con inicio/listeners, entrega, consulta de tareas/estados, cancelación e historial. Probar recuperación real de fases nativas y deduplicación de entrega, distinguiendo una tarea que espera del sistema de una ausente. Preservar preparación v3, tombstones, dueño/lote y consentimiento de repetidos. Después implementar multipart y coordinación API por unidades. No purgar tombstones ni liberar reservas remotas como efecto colateral de extraer el adaptador.

### Continuación D38

```text
Retoma D38 cerrado en docs/subidas-moviles-avance.md; lee decisiones y goal,
AGENTS y git status API/Flutter, conserva cambios ajenos.75tests+31regresiones,
análisis15 limpio, APKrelease574.2s/firma válida;
evidencia en build/mobile-upload-preparation/report.json.
Todos los handles terminaron, no reiniciar71796 ni
repetir suites/builds aprobados sin cambios. APKD38 SHA57135454... es local,
1.0.13+14 no publicado. Preparación v3 y estados listos; PUT simple vigente.
Siguiente D39: adaptador sustituible del motor (inicio/listeners antes de start,
enqueue/consulta/cancelación/historial), pruebas de recuperación/deduplicación
y tareas esperando al sistema. Conservar identidad/lote/v1 sin dueño, copia
validada/digest, consentimiento de repetidos y tombstones. No confundir tests
de archivos con muerte real de Android. Después multipart/coordinación API
por unidades; cancelación actual sólo local, sin liberar reservas ni borrar READY.
Sin deploy/publicación/commit/push/delegación/emulador. API cb483b7 ya desplegada,
no reutilizar esa autorización para otro commit. iOS14 pendiente de Mac.
Guardar checkpoint/prompt al terminar unidad; objetivo global no completado.
```

## Checkpoint anterior: D37, segunda revisión cerrada (11 nov 2026)

- Petición actual: «Dale una segunda revisada por favor», seguida de «termina por favor». Se cierra la revisión de D36; no se adelanta implementación de multipart/iOS durante esta revisión. Estado: verificada localmente. Objetivo completo todavía pendiente.
- Hallazgo corregido1: cambio de sesión durante huella/preparación podía reservar/subir con otra cuenta en fallback. Guardas en MediaService antes/después de huella y permiso; en cola, identidad/token/tarjeta vigente antes y después de esperas en ambos caminos. Cada selección usa el MediaService actual, no una referencia anterior al cambio de cuenta. Eventos de una tarjeta/trabajo sustituido no actualizan otra tarjeta.
- Hallazgo corregido2: timeout durante reintento dejaba estado subiendo sin tarea entregada y el siguiente intento podía esperar indefinidamente. Reintento ahora devuelve trabajo persistido en fallo recuperable, conserva clave/lote y agrupa llamadas simultáneas. Expiración reemplaza viejo/nuevo en un único snapshot, sin hueco vacío ni perder referencia si falla permiso. Historial nativo vencido se deja a retención del motor, sin bloquear la transición con borrado de historia; no se cancela ni borra una transferencia activa por esa limpieza.
- Endurecimiento3: estados o taskIds desconocidos no se reinterpretan como subida simple ni usan backup viejo; se detienen conservando archivo para revisión. Los nuevos estados de la siguiente unidad deben versionar/migrar explícitamente el formato. No se declara que dichos estados ya estén implementados.
- Flutter editados: modelo TrabajoDeSubida y servicios media_service/cola_de_subidas/subidas_en_fondo; pruebas registro/subidas/media/cola y formato de session_subidas_identity_test. Nuevo soporte test/support/archivo_pausado.dart usa archivo real y compuerta de IO determinista. API sólo continuidad/decisiones, sin fuentes Java/producción. Cambios ajenos preservados; sin commit/push/delegación/emulador/publicación.
- Evidencia de reproducción: mobile-upload-review-before.log conserva4fallos nuevos antes de correcciones (contexto, retry y vencida); pasadas intermedias preservadas como *-pass1/*-pass2. Se eliminó espera fija20–100ms de pruebas de cola, sustituyéndola por fin observable con límite5s tras observar un fallo intermitente de timing; no se rebajaron aserciones.
- Verificación final:55/55 pruebas seleccionadas exit0/7s, `../metricol_app/mobile-upload-review-tests.log`; analyze13archivos limpio exit0/3.7s, mobile-upload-review-analyze.log. BuildAPKrelease15738 exit0/331.4s, mobile-upload-review-apk.log. Firma válida/no debuggable, APK estable `../metricol_app/build/mobile-upload-review/app-release.apk`,75,806,336bytes,SHA256 `5814d2995c6fc4f968ba0539995a49a2557633d172fd50e1616a0a5d2eb3bc42`. Reporte/firma/metadata/verifier con hashes15fuentes/config en build/mobile-upload-review. Mismo paquete/version1.0.13+14, sin AAB nuevo ni publicación. D36/D31 APK conservados como históricos.
- Handles38179/51021 iniciales fallidos;31397/87467/15738 finales terminales. No proceso propio pendiente. Sin pruebas Android físicas, nuevo R2, acceso autenticado, despliegue API o iOS: éstos no se acreditan por mocks ni por APK. Backend cb483b7 permanece sin cambios de esta revisión.
- Siguiente acción exacta al continuar código: preparación durable/estados (intención antes de copia, staging temporal/validación/rename, recuperación de cortes/red/auth/cancelación) conservando identidad y lote y versionando formato explícitamente. Después multipart nativo/partes reales y coordinación API. No repetir revisión/tests/builds aprobados sin nuevo cambio. No rehacer despliegue, pedir reconexión idéntica ni asumir Android físico/iOS resueltos.

### Prompt vigente tras segunda revisión

```text
Retoma desde D37/primer checkpoint de docs/subidas-moviles-avance.md. Lee
decisiones/especificación; revisa AGENTS y git status API/Flutter y conserva
cambios ajenos. Segunda revisión cerrada:55tests/analyze13/APKrelease331.4s/firma
válida, evidencia en build/mobile-upload-review y logs mobile-upload-review-*.
Todos los handles terminaron, no esperar/reiniciar15738 ni rehacer lo aprobado.
Ya corregidos cambios de cuenta durante huella/fallback, servicios antiguos,
reintentos colgados y reemplazo vencido no atómico; no perder guardas ni lote.
Estados/taskIds desconocidos se conservan sin reinterpretar; la unidad siguiente
debe versionar/migrar al agregar estados. Ahora preparación durable y estados
antes de multipart; implementar/verificar unidades coherentes, guardar checkpoint.
API cb483b7 ya desplegada, sin nuevos cambios Java; no deploy/commit/push,
delegar/emulador/publicar. APK D37 es local, versión1.0.13+14 no publicada.
Pruebas autenticadas/físicas/iOS siguen pendientes con dependencias registradas.
No inventar acceso, resultados ni declarar objetivo completo por esta revisión.
```

## Checkpoint anterior: D36, registro por usuario y lote (11 nov 2026)

- Nueva instrucción del usuario: «Okis trabajemos con esto por favor», referida al código pendiente (partes, iOS, registro completo, estados y coordinación). Autoriza continuar implementación local por unidades; el bloqueo de autenticación D35 no impide este código. No redeploy/publicación/commit/push/delegación/emulador. iOS nativo sigue pendiente de Mac y mínima14 ya autorizada.
- Unidad actual: registro durable versionado e identidad/lotes antes de estados/preparación y multipart. Unidad local verificada:48tests,análisis12archivos,APKrelease y firma. No declarar terminado multipart, iOS ni objetivo completo.
- Flutter nuevos: `lib/models/trabajo_de_subida.dart`, `lib/services/registro_de_subidas.dart`, `lib/utils/lote_de_subida.dart`, `test/registro_de_subidas_test.dart`, `test/session_subidas_identity_test.dart`. Editados: subidas_en_fondo/cola_de_subidas/media_service/session_service y tests correspondientes. Reexport del modelo preserva imports existentes. API: sólo continuidad/decisiones, ninguna fuente Java ni producción modificada; cambios sucios ajenos preservados.
- Registrov2: userId/workspaceId, UUID de lote e índice original, modalidad simple, taskId asociado, fechas creación/actualización y versión. Permiso nativo envía uploadBatchId/index existentes de API. Mismo trabajo al recuperar/reintentar conserva clave/propietario/lote; sesión sincroniza usuario y negocio y oculta tarjetas anteriores también al cambiar usuario dentro del mismo negocio. Guardas impiden mostrar/reintentar/cancelar/renovar/confirmar trabajo de otra identidad.
- Compatibilidad: listasv1 se leen con dueño desconocido y quedan preservadas, no se adjudican al usuario que inicia sesión; aviso de revisión en Contenido. No se descartan registros porque falte archivo. Versiones/modalidades futuras se rechazan sin sobrescribir. Rutas fuera del sandbox se rechazan. JSON atómico serializado, snapshot previo válido .bak y recuperación que conserva el archivo ilegible; aviso al recuperar backup. Sin tokens/URLs firmadas persistidos.
- Pruebas: 48/48 seleccionadas exit0, log `../metricol_app/mobile-upload-d36-tests.log`; análisis12archivos limpio exit0/5.8s, `mobile-upload-d36-analyze.log`. Incluye nueva instancia leyendo disco, v1 sin adopción, futuro/corrupción sin pérdida del original, rutas peligrosas, snapshots concurrentes, cambio de cuenta dentro de mismo negocio y transporte de lote. Son tests locales, no proceso Android muerto ni prueba física/R2. Primer pase falló por auxiliar de test llamado liberar que colisionaba con método heredado; renombrado, logs iniciales preservados *-initial.log, no se rebajaron aserciones.
- APK release handle86488 terminó exit0/510.1s, `mobile-upload-d36-apk.log`. Firma apksigner válida, no debuggable, paquete com.rodtech.picale, min24/target36, versión1.0.13+14 sin publicar. Copia estable `../metricol_app/build/mobile-upload-d36/app-release.apk`,75,789,952bytes,SHA256 `20aaf326951919fc3d6a88ac51dae084490dffa1f6700ebdd6a0a11b1d3f0628`. Reporte `build/mobile-upload-d36/report.json` con hashes14fuentes/config, tests y firma; logs metadata y verifier específico en esa carpeta. No AAB nuevo.
- APK D31 anterior archivado/hash verificado en `build/mobile-upload-android-final/app-release-d31.apk`; report.json D31 apunta a esa ruta histórica. Su AAB y fuentes/hashes siguen siendo evidencia histórica, NO de D36. No ejecutar verifier D31 para reetiquetar código nuevo. Handles24219/74690 primeros fallidos por fixture;43897 y86488 finales aprobados/terminados, ningún proceso propio pendiente. git diff --check de cambios de esta unidad aprobado en ambos repositorios.
- Siguiente acción exacta: implementar unidad de estados/preparación durable: intención PREPARING antes de copiar, archivo temporal y rename validado, recuperación de copia interrumpida y estados de espera/red/autorización/cancelación, conservando dueño/lote/v1 y READY sólo por API. Añadir pruebas de cortes antes/durante/después de copia y reanudación sin otra reserva. Después multipart nativo/partes reales de R2 y coordinación API. No introducir multipart sobre preparación incompleta. No repetir suites/builds aprobados sin cambio. Conector/Android físico/iOS siguen con sus dependencias anteriores; no consumir tokens reintentándolos para trabajar código local.

### Prompt vigente D36

```text
Continúa código pendiente autorizado en D36 desde primer checkpoint de avance.
Lee decisiones/especificación; AGENTS/git status API/Flutter, preserva cambios.
Registrov2 usuario/lote/índice y aislamiento ya verificados:48tests,analyze12
limpio,APKrelease510.1s/firma válida,report en build/mobile-upload-d36. Todos
los handles terminaron; no esperar/reiniciar86488. APKD36 SHA20aaf326... listo;
APK/AAB D31 son históricos, APK D31 archivado; no afirmar que llevan D36.
Ahora estados/preparación durable (intención antes de copiar, staging temporal,
validación/rename, recuperación de cortes, red/auth/cancelación y READY por API),
con pruebas significativas; luego multipart por unidades verificables.
esta orden del usuario autoriza código local sin depender del conector fallido.
Conservar v1 sin dueño y archivos, no adoptar trabajos por login ni sobrescribir
versiones futuras. No repetir tests/builds ya aprobados salvo cambios/fallos.
API cb483b7 ya desplegada; no redeploy, commit/push/delegar/emulador/publicar.
iOS sigue pendiente de Mac, mínimo14 autorizado; no inventar pruebas físicas.
No goal completo al cerrar una unidad. Checkpoint después de cada unidad.
```

## Checkpoint anterior: reconexión confirmada; herramienta aún rechaza acceso (11 nov 2026)

- Usuario confirmó «Ya reconecté con Juan Rodríguez». La pregunta está respondida; no pedir que identifique la cuenta ni repita la misma reconexión. Scope aprobado permanece Juan Rodriguez/workspace `c0d4be15-542f-4f9e-a2c5-0e0e13f98523`, user `23a2f1a1-4717-403f-8617-97c78eb7e051`.
- Resultado real después de esa respuesta y revalidado en esta continuación: herramienta conectada Pícale panel devuelve `UNAUTHORIZED`, «This app connection requires reauthentication. Reconnect the app and try again.» Esto describe la conexión herramienta/conversación; NO prueba que la app móvil ni el login de Juan estén vencidos. No atribuir el error al servidor/API sin más evidencia.
- Diagnóstico ya intentado: búsqueda de plugins PICALE devuelve lista vacía, pese a herramientas existentes; no basta para concluir desinstalación de plugin privado. No se reinstaló, cambiaron permisos ni crearon credenciales. Fallback CUA: inventario reportó browser access host unavailable; al vincular Chrome terminó automáticamente por no poder determinar URL actual en Windows con confianza para aplicar política. Se obedeció stop y completion_summary; no reintentar vía otro driver para sortear ese bloqueo.
- Estado: smoke autenticado pendiente por acceso efectivo de herramientas. Cuenta/identidad sí verificados, prueba aún no ejecutada; cero archivos/R2/medios/sesiones creados y nada por limpiar. API cb483b7/despliegue y builds anteriores conservados, sin nuevas modificaciones de producto, tests/builds/redeploy ni publicación. No proceso propio vivo que esperar.
- Auditoría D35 3/3: tercera observación consecutiva tras reconexión confirmada mantiene UNAUTHORIZED. Turno anterior y actual sin progreso funcional ni proceso vivo; git status preservado y sin acceso alternativo permitido nuevo. Se marca goal blocked por acceso efectivo de herramientas, no por falta de permiso del usuario ni por sesión móvil. No completar objetivo, repetir reconexión o tests/builds/redeploy; reanudar con cambio real de acceso y comenzar auditoría nueva si persiste entonces.
- Clasificación: turno de aclaración anterior sin progreso funcional; esta continuación revalida la misma dependencia externa. Auditoría conservadora 2/3 contando el intento después de la reconexión como primera observación; no reutilizar los conteos D32. Aún no marcar goal completo ni bloqueado anticipadamente. Sólo se actualizan continuidad y evidencia local segura, no funcionalidad.
- Siguiente acción exacta: continuar sólo con un cambio efectivo de acceso (herramienta Pícale devuelve sesión utilizable y scope esperado, o superficie de navegador permitida/autenticada disponible). No más confirmaciones verbales idénticas ni tokens por chat, ni JWT con secreto, reset de contraseña, cambios de permisos o automatización que eluda el stop. Si una continuación sin cambio confirma tercera observación y no existe otra acción útil dentro de etapa, aplicar blocked del goal. Cuando haya acceso legítimo ejecutar smoke de la matriz y cleanup propio; físicos/iOS siguen pendientes.

### Prompt vigente tras comprobar reconexión

```text
Retoma D35/primer checkpoint de docs/subidas-moviles-avance.md. Revisa AGENTS,
git status de ambos repositorios y decisiones; conserva cambios ajenos.
Juan Rodriguez y su workspace c0d4be15-542f-4f9e-a2c5-0e0e13f98523 ya están
identificados/autorizados para prueba. Usuario YA confirmó reconexión, no
preguntar otra vez. Pícale panel sigue UNAUTHORIZED desde esta conversación;
no implica sesión móvil vencida. CUA detuvo acceso por no verificar URL actual,
browser host indisponible. No eludirlo con otro driver ni inventar autenticación.
Incorpora cambio real de acceso si lo hay y verifica identidad/workspace antes
de subir. Si persiste, seguir auditoría de bloqueo (D35 actual2/3, históricos
anteriores no aplican). No proceso propio vivo, no espera de job. No repetir
tests/builds/redeploy: API cb483b7 ya desplegada, APK/AAB D31 verificados.
No commit/push/delegar/emulador/publicar ni alterar credenciales/permisos.
Smoke autenticado todavía no ejecutado, cero medios creados. No confundir
pruebas HTTP/MCP con Android físico/iOS; Mac/USB siguen no disponibles.
Mantener goal completo pendiente y conservar backups/imagen previa.
```

## Checkpoint anterior: cuenta Juan identificada; sesión pendiente (11 nov 2026)

- Usuario eligió «la de juan rodriguez» para pruebas autenticadas. Se resolvió la identidad mediante consulta de sólo lectura filtrada en la base efectiva: una cuenta coincidente (nombre contiene Juan/Rodriguez), UUID `23a2f1a1-4717-403f-8617-97c78eb7e051`. Negocio personal/home `Juan Rodriguez`, workspace `c0d4be15-542f-4f9e-a2c5-0e0e13f98523`, rol ADMIN. No usar sus otros negocios para esta prueba. No pedir nuevamente qué cuenta usar.
- Estado: identificación verificada; autenticación y smoke de uploads pendientes. Conector Pícale panel revalidado con dias1 devuelve requires reauthentication. Se revisó guía plugin-management y capacidades: no hay herramienta de reconexión; no cambiar permisos/instalar duplicados. Login normal/Google existen, no ruta de suplantación de Root encontrada; no crear JWT con secreto productivo, restablecer contraseñas ni enviar mensajes para sortear el login.
- Pregunta enviada al usuario para reconectar Pícale con Juan y avisar; respuesta pendiente. No repetirla ni interpretar la identificación de la cuenta como una sesión ya autenticada. Al reautenticar, comprobar workspace activo exacto antes de subir; si el plugin apunta a otro negocio, no crear medios allí.
- Cambios: sólo docs de continuidad y artefactos locales ignorados `target/subidas-entrega/juan-smoke/{identify.sh,account-identification.json,account-candidates.json,checkpoint.json}`. Consulta inicial exacta no encontró por nombre compuesto; búsqueda acotada por ambos nombres/email local identificó una cuenta. No se exportaron correos, contraseñas, hashes de contraseña ni tokens; sólo identidad/membresía necesaria. Cero medios creados, cero sesiones creadas, nada pendiente de limpiar en R2 de esta unidad. No nuevos procesos persistentes.
- API cb483b7 permanece desplegada; consulta de sólo lectura confirmó SHA. No redeploy, tests/builds repetidos, commit/push, publicación app ni cambios ajenos. Prueba nativa física Android/iOS continúa pendiente; pruebas HTTP/MCP no equivalen a segundo plano físico.
- Siguiente acción exacta: incorporar respuesta de reconexión, invocar panel sólo lectura y verificar conexión/identidad/workspace esperado; si sigue vencida, conservar dependencia sin reintentar en bucle ni pedir tokens/contraseñas en chat. Con sesión legítima y scope confirmado ejecutar smoke autorizado, registrar claves/IDs propios y cleanup exacto según matriz/propuesta. No inventar verificación por mera identificación SQL.

### Prompt vigente tras identificar cuenta

```text
Retoma D34 y primer checkpoint de docs/subidas-moviles-avance.md. Lee decisiones,
especificación y matriz/propuesta; revisa AGENTS/git status de ambos repositorios.
Usuario autorizó pruebas con Juan Rodríguez. Identidad y negocio personal ya
resueltos: user23a2f1a1-4717-403f-8617-97c78eb7e051, workspace
c0d4be15-542f-4f9e-a2c5-0e0e13f98523 (Juan Rodriguez, ADMIN). No preguntar de nuevo
qué cuenta ni tocar sus otros negocios. Pícale panel sigue requires reauth;
se pidió reconexión, falta respuesta. Identificar por SQL NO autentica.
Primero incorpora respuesta y verifica sesión legítima y workspace activo exacto
con herramientas conectadas antes de subir. No crear JWT usando secreto, resetear
contraseña ni pedir tokens por chat. No instalar duplicados/cambiar permisos.
API cb483b7 YA desplegada y verificada, job terminado; no repetir deploy/tests/builds.
Cuando exista acceso legítimo, ejecutar smoke controlado, conservar evidencia
y limpiar sólo medios propios. No publicar app/commit/push/delegar/emulador.
Android físico/iOS siguen pendientes; usuario sin USB/Mac, no insistir.
Preservar cambios ajenos, backup/imagen previa y objetivo completo pendiente.
```

## Checkpoint anterior: API desplegada y verificada operativamente (11 nov 2026)

- Usuario respondió explícitamente «Autorizar ese despliegue de API» a la propuesta de cb483b7 en api-videos-prod con respaldo y sin publicar app. La dependencia de autorización de D32 queda resuelta; no solicitarla otra vez. Alcance: tres commits de la propuesta, ningún cambio sucio local ni publicación de app.
- Estado: despliegue API verificado, no objetivo completo. Commit `cb483b7df8179a585432d1db24c65defe9b74fcb` sirve en producción. Git status/AGENTS de ambos repositorios revisados, fuentes ajenas preservadas. Payload autorizado revalidado local y remoto, SHA256 `983daa4b24b6f711174333f9563116af260a3568ab219eab93e7fdd3ee8fffe9`. Sin commits/push/publicación app. No repetir tests/builds Flutter ni tests API aprobados.
- Preflight SSH real verificado exit0: VM api-videos-prod, SHA e266b2b sin cambios, base efectiva coincide con config, contenedor previo healthy, ninguna otra entrega detectada, ~34GB libres. `target/subidas-entrega/cb483b7/preflight-verified.json` guarda identidad, IDs y hash de .env (sin contenidos/credenciales). SSH nativo Windows rechazó ACL de llave; Git SSH existente funcionó con host conocido estricto, sin cambiar permisos ni copiar llave. Runner Node evita CRLF de stdin PowerShell; intentos previos de sólo lectura preservados.
- Respaldo real creado en servidor: `/home/contacto_rodtech_cloud/respaldos/picale-antes-cb483b7-20261111-170426-1570ce.dump`,1,874,850 bytes, SHA256 `f91c276f974fc3d24a3efb399713f01bb86fff2399096cd039ac3976215abd1b`,223 entradas TOC, pg_dump exit0 y pg_restore completo hacia /dev/null exit0. Base efectiva del contenedor, no nombre fijo. Archivo privado permanece en VM; credenciales sólo stdin/en memoria, nunca logs/conversación. Imagen anterior conservada: `metricol-api:previo-e266b2b-1570ce0986f0`, ID `sha256:ee5d9618a7d6185319eb970a409d4a198584f0b8b80aca5fc72f06872b497eaf`. Evidencia `backup-verified.json`.
- Job remoto propio terminado: PID672656/startTicks647801284, run `/home/contacto_rodtech_cloud/.cache/picale-upload-deploy/picale-upload-cb483b7-1570ce0986f0`. `status-15.json`: succeeded/complete/exit0, processVerifiedLive=false, terminado 2026-11-11T17:23:21Z. No relanzar ni esperar ese PID. Primeros polls de proceso vivo y últimos terminales conservados.
- Historial de espera: build se demoró por429 de kaino.dev/Retry-After143s, comprobado desde host y build, con curl existente vivo. No se duplicó/reinició ni cambió el candidato. Finalmente48/48 audios pasaron checksum. Diagnósticos sólo registran host/códigos/tiempos, sin URLs firmadas ni credenciales.
- Scripts específicos ignorados en target/subidas-entrega/cb483b7: ssh_runner.cjs, preflight.sh, backup.sh, deploy_remote.py, launch.sh, poll.sh. Build aislado desde payload, etiqueta de candidato; sólo después verifica SHA/container/env anterior, conserva src/control anteriores, instala fuente aprobada y recrea sólo servicio API con --no-deps/--no-build. Preserva env/nginx/DNS/proxy/otros servicios. Gates SHA/base/imagen/health/DDL Postgres/errores de arranque; fallo se registra sin rollback destructivo de DB.
- Verificación real: imagen `sha256:1316fc4a6e7eaaa8ffda2f172e519840199fb23754e135e254ca26991f93b932`; contenedor `ac2822dee0ea9b89a0c9115b2dac382d39100f74521a1d996e58bc4f5556a8aa`, healthy, SHA público completo correcto y base efectiva coincidente; .env SHA inmutable, fuente de servidor BUILD_SHA correcta, backup e imagen anterior revalidados. PostgreSQL confirma columna client_upload_id, unicidad tenant/clientUploadId, objetivos_elegidos y retiro del CHECK viejo. Cero ERROR/APPLICATION FAILED de arranque. Reemplazado sólo servicio API con --no-deps/--no-build. Fuente anterior guardada en run/previous-src y controles en previous-control.tar.gz; no rollback de DB ejecutado.
- Evidencias finales en `target/subidas-entrega/cb483b7/`: status-15.json, deployment-independent.json (SSH independiente), deployment-public.json (HTTP externo), deployment-summary.json (estado actual), backup-verified.json. GET público de usage sin sesión responde401: acredita protección, no bandera/carga autenticada. verification.json de D32 conserva su snapshot histórico predeploy; el resumen actual de entrega es deployment-summary.json y package.json/deployment-run.json marcan deployed=true.
- Pendiente concreto: prueba autenticada usage/reserva/consulta/confirmación/cuota y matriz Android física, iOS/Mac. Se consultó conexión Pícale panel de sólo lectura y devolvió requires reauthentication; no se inició login ni modificó negocio. Pregunta enviada al usuario por cuenta/negocio de pruebas (sólo nombre/ID), respuesta pendiente; no volver a preguntar lo mismo ni inventar sesión. No se crearon pruebas R2 de esta unidad ni medios a limpiar.
- Siguiente acción exacta: incorporar respuesta nueva sobre cuenta/sesión de pruebas; con acceso autenticado autorizado verificar contrato y escenario controlado, registrar/retirar sólo datos propios de prueba. Para Android, APK D31 listo y matriz `subidas-moviles-validacion-android.md`; no emulador ni pedir USB otra vez. Sin sesión/dispositivo nuevo no repetir despliegue/tests/builds ni publicar app. iOS sigue sin Mac confirmado. Objetivo completo pendiente.

### Prompt vigente tras despliegue

```text
Retoma D33 y primer checkpoint de docs/subidas-moviles-avance.md. Lee decisiones,
propuesta y matriz Android; revisa AGENTS/git status, conserva cambios ajenos.
API cb483b7 YA desplegada con autorización; no preguntar ni desplegar otra vez.
Job PID672656 TERMINÓ succeeded/exit0, no pollearlo como vivo ni reiniciarlo.
Revisar deployment-summary.json, independent/public/status15 y backup-verified
bajo target/subidas-entrega/cb483b7. SHA público, imagen/base/schema/health/env y
backup/imagen previa verificados. No confundir snapshot D32 predeploy con estado
actual. No repetir tests/builds aprobados, commit/push/delegar/emulador/publicar.
Pendiente acceso autenticado para usage y prueba controlada de uploads/cuota:
Pícale panel requiere reauth y se preguntó cuenta/negocio de pruebas, sin respuesta.
Incorpora respuesta nueva; no repetir pregunta, inventar sesión ni usar negocios
al azar. Android físico requiere matriz con APK D31, usuario sin USB; no insistir.
iOS sigue pendiente de Mac confirmado no disponible. No restaurar DB ni borrar
backup/imagen previa automáticamente. No declarar objetivo completo por deploy.
```

## Checkpoint anterior: preparación concreta de entrega API (11 nov 2026)

- Etapa: entrega controlada del Android D30/D31, únicamente preparación local y observación pública de sólo lectura. Preparación local verificada; pendiente de autorización concreta de despliegue. Objetivo completo pendiente.
- Goal bloqueado tras auditoría D32 3/3 por autorización concreta de producción pendiente; paquete/propuesta/pruebas permanecen preparados. Retomar con respuesta a la solicitud ya presentada, sin repetirla ni ejecutar producción por una continuación automática. Android físico e iOS aún pendientes. Una nueva reanudación reinicia el conteo de auditoría si persiste la misma dependencia.
- El goal se reactivó después del bloqueo histórico. El turno previo fue sin progreso funcional. Se identificó una acción local útil antes de pedir autorización: comprobar versión real, congelar el candidato y verificar la suite completa de su árbol exacto. No se reutiliza el conteo anterior para bloquear esta reanudación.
- Nuevo dato: GET público `/api/v1/ops/version` reportó producción `e266b2bac10c7dc11e28137e7c0f0f9247690ce8`, candidato local `cb483b7df8179a585432d1db24c65defe9b74fcb`. Evidencia `target/subidas-entrega/version-readonly.json`. No se supone que producción siga igual después de esta observación.
- El candidato incluye tres commits: uploads, objetivos de redes y corrección del revisor de flyers. No incluye cambios sucios ajenos del árbol local. Snapshot `git archive` sin `.env`/`.env.vps`, bajo `target/subidas-entrega/cb483b7/`. Payload `api-cb483b7.tar.gz`: 2,942,478 bytes, SHA-256 `983daa4b24b6f711174333f9563116af260a3568ab219eab93e7fdd3ee8fffe9`; metadata en `package.json`. Empaquetado handle86175 terminó exit0. No copiado a servidor.
- Propuesta nueva: `docs/subidas-moviles-entrega-api.md`, destino/commits/schema/comprobaciones/recuperación y alcance exacto a autorizar. `deploy-ssh.sh` tiene respaldo de base fija y remoto sin pipefail; no usarlo ciegamente. No se modifican scripts existentes ni recursos remotos. Ver D32.
- Suite API handle63013 terminada exit1/08:05: 183 suites, 1,339 tests, 0 fallos, 1 error por fixture MP3 no versionada ausente, 3 omitidas. Copia aislada `target/subidas-entrega/cb483b7/source`, Java17/H2 en memoria/config import inexistente y sin configuración real. Reporte inicial preservado en `initial-results/`, log `api-candidate-tests.log`. Se repuso sólo fixture local cuyo SHA coincide con catálogo del commit, original conservado. Reintento de la única suite afectada handle51917 exit0/36.409s, 1/1, log `api-candidate-fixture-retry.log`; no repetición total.
- Resultado consolidado verificado: 1,336 aprobadas, 3 omitidas por VisualIdentityEvaluationTest optativo sin PICALE_VISUAL_EVAL, 0 fallos/errores; `verification.json` explica corrida/reintento. No se atribuye una única corrida completa verde, CI remoto ni PostgreSQL real. Payload comprobado sin cambios, 751 archivos esperados, BUILD_SHA exacto y cero diferencias/excluidos (`package-validation.json`). Handles63013/51917/86175/41862 terminados; no proceso propio pendiente.
- Siguiente acción exacta: solicitar/recibir autorización concreta para API commit cb483b7 y sus tres cambios en la VM indicada, según propuesta y punto9 del objetivo. No ejecutar SSH/despliegue/publicación antes de esa respuesta. Después de autorización, revalidar SHA productivo, destino/base/backup/imagen anterior y seguir gates de la propuesta; si falta o cambió algo, conservar evidencia y resolver antes de mutar. GitHub CLI no autenticado; no iniciar login ni declarar CI remoto verde.
- Android local permanece verificado abajo; no repetir sus tests/builds. Pruebas físicas e iOS siguen pendientes. Esta unidad sólo modifica documentación de API y artefactos locales ignorados; ningún fuente de producción/API/app modificado.

### Prompt vigente tras preparar entrega

Auditoría tras D32 (1/3): el turno anterior fue progreso (candidato congelado, suite completa/reintento y propuesta concreta verificados). Esta continuación no aporta progreso funcional: no llegó respuesta a la autorización solicitada para cb483b7; el registro de entrega permanece deploymentAuthorized=false/deployed=false. No es espera de proceso vivo; los handles propios terminaron. No ejecutar producción, repetir pregunta, tests/builds ni ampliar alcance. El goal permanece pendiente sin aplicar bloqueo antes del umbral; las auditorías previas a D32 son históricas.

Auditoría tras D32 (2/3): turno previo sin progreso funcional. Se revalidan checkpoint y git status de ambos repositorios; sigue sin respuesta la autorización concreta, y metadata de entrega permanece sin autorización/despliegue. No hay proceso propio pendiente ni acción local restante que sustituya esa decisión. Sin fuentes modificadas, tests/builds repetidos ni acciones remotas. Segunda observación consecutiva de la misma dependencia después de D32; goal pendiente, sin bloqueo anticipado.

Auditoría tras D32 (3/3): tercera continuación consecutiva sin la respuesta requerida para modificar producción. Turno previo y actual sin progreso funcional ni espera de proceso vivo; fuentes/metadata preservadas y entrega sin desplegar. Sin otra acción local necesaria pendiente dentro de esta etapa, se aplica blocked al goal por dependencia de autorización, sin completar el objetivo. No volver a preguntar lo mismo ni generar nuevas pruebas/builds/paquetes para evitar el bloqueo. La propuesta concreta y el prompt vigentes quedan listos para la respuesta.

```text
Retoma D32 desde el primer checkpoint de docs/subidas-moviles-avance.md.
Lee decisiones y docs/subidas-moviles-entrega-api.md. Revisa AGENTS/git status
en API y Flutter; conserva cambios ajenos. Preparación local verificada:
payload exacto cb483b7, 751 archivos/hash registrado, suite consolidada 1336
aprobadas/3 omitidas tras corregir sólo fixture local y reintentar una suite.
No repetir pruebas/builds aprobados. Ver verification.json y reportes originales.
Primero incorpora la respuesta a la autorización de despliegue; sin un sí
concreto no ejecutar producción. La propuesta incluye tres commits y DDL,
no sólo uploads. Sólo si está autorizado: revalidar SHA productivo, backup de
base efectiva e imagen anterior; seguir gates de la propuesta, sin publicar app.
No usar ciegamente respaldo fijo de deploy-ssh.sh ni declarar rollback de imagen
seguro con enums nuevos. No delegar/commit/push/emulador. Android físico e iOS
siguen pendientes; usuario sin Mac por ahora. Goal completo no logrado.
```

## Checkpoint anterior: cierre local de Android (11 nov 2026)

- Etapa vigente: integración Android de D30. La decisión del 10 nov sustituye el bloqueo anterior como siguiente acción; las restricciones del probe/Fase1 que siguen abajo son históricas. iOS continúa pendiente de Mac. No usar emulador, delegar, hacer commits/push ni desplegar.
- Estado: unidad local Android verificada; implementación auditada/corregida, tests y paquetes release listos. Etapa de validación física/entrega pendiente. No equivale a objetivo completo, publicación o prueba física.
- Goal bloqueado por dependencia externa tras auditoría posterior 3/3: entrega/despliegue autorizado y validación física pendientes, sin acceso nuevo. El cierre local sigue verificado. Reanudar con autorización concreta de entrega o evidencia/acceso de pruebas reales; una reanudación sin cambio externo inicia un conteo de bloqueo nuevo.
- Revisión real: git status de ambos repositorios y búsqueda de AGENTS sin instrucciones adicionales. API ya contiene commit preexistente `cb483b7`; esta unidad no lo creó ni modificó sus fuentes de producción. Cambios ajenos preservados.
- Móvil: `lib/services/subidas_en_fondo.dart` serializa escrituras/snapshots del registro atómico, conserva copia y clave tras timeout/5xx/408/429 de apertura y verifica negocio/token antes de reservar, renovar o entregar. Reconciliación interrumpida por cambio de sesión no altera trabajos anteriores.
- Móvil: `media_service.dart` corta reintentos de confirmación al cambiar el token. `cola_de_subidas.dart` mantiene estado confirmando cuando no hay resultado de API y reconcilia las tarjetas cuando el servidor confirma después. PUT terminado no es medio READY.
- Pruebas: `subidas_en_fondo_test.dart` añade escenarios de respuesta perdida/misma clave, negocio diferente, cambio de token durante permiso y doce trabajos concurrentes sobre disco real temporal. `media_upload_test.dart` cubre cambio de sesión entre intentos de confirmación. Sin SDK nativo ni R2 en estos tests.
- Resultado final: 35/35 pruebas Flutter seleccionadas, exit 0, `../metricol_app/mobile-upload-android-final-tests.log`. Análisis de nueve archivos sin incidencias, exit 0/23.1s, `mobile-upload-android-final-analyze.log`. API conserva evidencia 52/52 en `target/subidas-fondo-test.log`; no se repitieron porque no hubo cambios Java.
- Build final AAB release exit 0/260.8s (`mobile-upload-android-final-bundle.log`) y APK release exit 0/31.7s (`mobile-upload-android-final-apk.log`), bajo raíz Flutter. APK 75,708,032 bytes SHA-256 `d2ff88c9bc3d74f6fddc2d9896bd194ec3d4305df4ee6b8d5930435673cc5678`; AAB 63,877,894 bytes SHA-256 `c497ea4cbd599b5d221820228593f4cfe039985722df18a6060e0faafe780bc7`. Rutas: `build/app/outputs/flutter-apk/app-release.apk` y `build/app/outputs/bundle/release/app-release.aab`.
- Verificación de artefactos exit 0: apksigner acepta APK, jarsigner acepta AAB, APK sin debuggable, paquete `com.rodtech.picale`, versión `1.0.13+14`, minSDK24/target36. `../metricol_app/build/mobile-upload-android-final/report.json` guarda hashes de ambos paquetes y doce fuentes/configuraciones; logs de firmas/metadata y script reproducible junto al reporte. Firma válida no acredita aceptación/publicación en Play.
- Siguiente acción: entrega controlada según `subidas-moviles-validacion-android.md`, con despliegue API autorizado antes de app y pruebas físicas reales. No hay teléfono conectado ni acceso Mac; sin emulador por D30. No hay proceso/build pendiente de esta unidad ni recursos remotos creados. No desplegar/publicar automáticamente ni repetir builds/tests aprobados salvo cambio o inconsistencia.
- Límites vigentes: PUT completo reinicia el archivo si se interrumpe; no reanudación por bytes/partes en esta integración. No hay nueva prueba física ni validación iOS. Desplegar API antes de distribuir app; despliegue/publicación fuera de esta unidad.
- Se añadió `docs/subidas-moviles-validacion-android.md` con orden de entrega y matriz física pendiente. La revisión de la cola también conserva el token previo a comprobar capacidad/calcular huella: un cambio durante la preparación impide entregar el archivo al negocio nuevo. Primer APK intermedio pasó (395.8s); fue sustituido por el build final anterior tras ese ajuste. Todos los handles propios 1525/56968/53437/54033/61795/6527 terminaron; el primer análisis detectó tres lints y el final está limpio. No se hicieron commits/push, despliegues ni publicaciones.

### Prompt vigente para continuar

Auditoría posterior al cierre local (1/3): el turno anterior fue progreso (correcciones, pruebas y paquetes finales). Esta continuación sólo revalidó los hashes de doce fuentes/configuraciones y dos artefactos: 14 coincidencias, ninguna diferencia. Sin nuevas pruebas/builds ni cambios de producto. La siguiente acción sigue dependiendo de despliegue autorizado y prueba física externa; no hay acceso Android/Mac nuevo ni proceso propio pendiente que esperar. Esta observación no completa el objetivo ni alcanza por sí sola el umbral de bloqueo del goal.

Auditoría posterior al cierre local (2/3): el turno anterior fue sin progreso funcional, no una espera de proceso vivo. Persiste la misma dependencia de entrega autorizada y validación física externa: no llegó acceso/reporte de dispositivo ni autorización de despliegue; sólo existen los gates Windows del preflight iOS y no hay herramientas Apple locales. No se cambió producto ni se repitieron tests/builds. Goal pendiente; todavía no se aplica bloqueo antes del umbral.

Auditoría posterior al cierre local (3/3): tercera continuación consecutiva con la misma dependencia externa y sin progreso funcional. Git status conserva cambios; la evidencia final sólo contiene firmas/metadatos/hashes locales y el preflight iOS sólo gates Windows. Sin nuevo acceso/reporte físico, autorización de despliegue o handle propio vivo. No queda acción ejecutable dentro de la etapa que pruebe la entrega física; no se amplía alcance ni se simula validación. Se aplica estado blocked del goal, sin declararlo completo. El prompt siguiente se usa cuando cambie esa dependencia.

```text
Continúa la integración Android de Pícale según D30/D31 y el primer checkpoint
de docs/subidas-moviles-avance.md. Lee especificación, decisiones y
docs/subidas-moviles-validacion-android.md; revisa AGENTS y git status de API y
Flutter. Preserva cambios ajenos y el commit API preexistente cb483b7. Sin
delegar, emulador, commit/push, despliegue ni publicación automáticos.
Primero verifica el resultado final APK/AAB/análisis y su evidencia del
checkpoint; no repitas pruebas aprobadas sin cambio. Android usa PUT completo
y background_downloader 9.5.9, no reanudación por bytes. La entrega requiere
API compatible antes de app y pruebas físicas de la matriz, todavía pendientes.
No confundir tests locales/compilación con cambio de app o bloqueo real.
iOS permanece pendiente de Mac, ya confirmado no disponible; no preguntar de
nuevo ni declararlo validado. Mantén el objetivo completo pendiente y registra
por separado el cierre local Android, despliegue y pruebas del producto.
```

## Checkpoint anterior (10 nov 2026): Android integrado, sin emulador

- Decisión D30: Android primero en la app real; iPhone sin cambios hasta tener Mac. Sin emulador por pedido del usuario.
- Servidor: clave de subida idempotente, firma de 12 h, `GET /media/subidas/{clave}`, limpieza a 24 h para subidas con clave, rescate que también une partes, `subidaEnFondo: 1` en `/media/usage`. Pruebas: `SubidaEnFondoTest` (8) + subidas existentes, 52/52.
- App: `lib/services/subidas_en_fondo.dart`, integración en `cola_de_subidas.dart`, `contenido_page.dart` y `session_service.dart`; manifiesto con los servicios del motor; `test/subidas_en_fondo_test.dart`.
- Falta: desplegar API (antes que la app), compilar y probar en un teléfono Android real, y publicar la app. Play Console puede pedir la declaración del servicio en primer plano «sincronización de datos».

## Checkpoint anterior (9 nov 2026)

- Etapa autorizada actual: Parte 2 / Fase 1, prueba técnica del motor, retomada por instrucción de continuación.
- Estado: Fase1 BLOQUEADA para ejecución iOS por ausencia de accesoMac confirmada por el usuario. NO-GO integración/liberación. Android emulado/R2 nativo verificados y limpios; iOS14 autorizado, iOS/físicos pendientes. Goal se marca blocked tras auditoría3/3, sin completar el objetivo.
- No había archivos de continuidad al iniciar. Se eligió Fase 0 conforme a la especificación; no se inició automáticamente la siguiente etapa.
- Goal completo: pendiente. Este checkpoint acredita el probe nativo; la integración de transferencias nativas en Pícale sigue pendiente.
- Último cierre: preparación iOS14 (preflight/bootstrap/protocolo) verificada sólo en Windows:4tests lógicos/análisis limpio/rechazoMac esperado. R2Android anterior verificado/limpio; usuario confirmó que por ahora no tiene acceso a Mac; disponibilidad de iPhone no especificada. Falta ejecución Apple real; NO-GO/Fase1 parcial.

## Actualización del usuario: sin acceso a Mac por ahora

- Respuesta explícita: «Por ahora no tengo acceso a Mac.» La dependencia deja de ser disponibilidad sin confirmar y pasa a ausencia de accesoMac confirmada. No volver a preguntar lo mismo mientras no cambie esa condición; no inferir ausencia de iPhone.
- Goal sigue blocked (estado consultado), Fase1 parcial. Esta respuesta aporta contexto, no solicita reanudar ejecución ni avanzarFase2. MínimoiOS14 autorizado y candidato sujeto a pruebas se conservan.
- Sólo documentación de continuidad/evaluación actualizada; sin nuevos tests/builds/R2 ni cambios de producto. Retomar al disponer de Mac/Xcode y dispositivo/reporte real, desde preflight/bootstrap preparados.

## Dependencia externa confirmada: auditoría3/3,20:15local (histórico previo a respuesta)

- Turno previo sin progreso funcional: revalidó la misma dependencia. Esta continuación confirma tercera observación consecutiva contando cierre de preparación20:13 y auditoría20:14. Windows sigue sin xcodebuild/xcrun; no reporteMac recibido ni herramientas conectadasMac/SSH/Xcode. No hay job/handle Apple activo ni respuesta a la pregunta pendiente.
- No existe acción ejecutable desde este entorno que verifique los gates Apple exigidos para cerrar Fase1; preparar más documentación o repetir Android no los satisface. Se aplica status blocked del goal por dependencia externa, no por tiempo/tokens ni por falta de autorización de pruebas. No se completa el goal ni se avanzaFase2.
- Sólo se actualizan avance/decisiones. Código y pruebas aprobadas conservados; ningún test, build, presign, subida ni limpieza remota nueva. Recursos anteriores cerrados.
- Desbloqueo: recibir acceso real a Mac/Xcode y dispositivo, o reporte real del preflight ejecutado allí. Luego node bin/ios_preflight.cjs ios_host_1 UDID_REAL y bootstrap/build del protocolo; iPhone requerido para gates físicos. No asumir que usuario carece de Mac, sólo que no se proporcionó acceso. No repetir pregunta ni mínimo14 ya autorizado.
- Al reanudar un goal blocked, iniciar auditoría de bloqueo nueva según instrucción del goal; el conteo3/3 anterior es histórico, no autoriza bloquear inmediatamente una reanudación. Incorporar cualquier información externa nueva primero.

## Unidad preparada: preflight y bootstrap iOS14

Auditoría de dependencia,20:14local:
- Turno anterior: progreso (preflight/bootstrap/protocolo preparados). Turno actual: sin progreso funcional; sólo revalidación de la misma dependencia, no espera de job vivo. Fuentes preparadas conservan hashes, iOSbuild/runtime=false; gitstatus/AGENTS revalidados y cambios preservados.
- Estado real: Win32NT, cero xcodebuild/xcrun/swift en PATH, sin herramienta conectada de Mac/SSH/Xcode en el catálogo disponible y sin reporte ios_host_1 recibido. Sólo existen gates Windows. No hay endpoint, dispositivo ni handleMac proporcionado. La pregunta sobre disponibilidad sigue sin respuesta; no inferir que el usuario carece de Mac.
- Misma condición observada en cierre de preparación20:13 (1) y esta auditoría20:14 (2). Aún no se marca goal bloqueado antes del umbral de tres turnos. Si otra continuación confirma la misma condición y no hay acción útil dentro de Fase1, aplicar auditoría/status blocked del goal; no repetir código/tests/docs para aparentar avance ni pasar a Fase2.
- Única acción dependiente vigente: recibir acceso/información real de Mac y ejecutar preflight/bootstrap del protocolo. No repetir pregunta pendiente ni decisión iOS14 ya autorizada.

Estado: preparación verificada localmente; build/runtime iOS pendientes de entorno Apple. Parte2/Fase1 pendiente de ejecución Apple/NO-GO; goal bloqueado según auditoría actual3/3. Al preparar esta unidad el turno previo fue progreso (R2Android verificado/limpio); AGENTS, gitstatus, especificación y continuidadD27/D28 revalidados, cambios ajenos preservados. Sin nuevos R2/firmas ni procesos/dispositivos arrancados en esta unidad.

Cambios Flutter, sólo tool/upload_probe:
- bin/ios_preflight.cjs: macOS-only, SDK3.41.6/Dart3.11.4, Xcode completo/SDKiphoneos/CocoaPods y dispositivoUDID explícito. Rechaza override a desktop/iOS13 y diferencia simulador/físico, firma/no verificada y runtime14/no cubierto. No instala herramientas, firma ni construye apps. Windows no invoca Apple/Flutter.
- pubspec.yaml del PROBE: flutter.config.enable-swift-package-manager=false fija CocoaPods sin alterar configuración global/mainapp. FuenteSDK FlutterFeaturesConfig prioriza proyecto/global sobre ambiente; variablefalse sola NO garantizaba desactivarSPM. Preflight exige la opción del proyecto. Pubgetoffline con --enforce-lockfile aprobó sin cambiar versiones.
- test/ios_preflight.test.cjs:4tests con fixtures de tools/devices, no Mac real. Cubren ausenciaMac/sin comandos, SDK/Xcode/Pods incorrectos, schema inesperado, plataforma/device/version, baselineSPM faltante y distinción firma/físico/runtime mínimo.
- integration_test/ios_bootstrap_test.dart: Platform.isIOS/defaultTargetPlatformiOS sin overrides, UUID no sensible, pluginstart/allTasks grupo vacío y reporte privado leído desde disco. NO encola transferencias. AllTasks mezcla holdingQueue y URLSession: no se acepta como prueba de entrega nativa.
- IOS_FASE1_PROTOCOL.md y README: comandos bootstrapdebug/buildrelease/runrelease, extracción segura, bundle/minOS/frameworks/firma, matrizI00–I07 contra M01–M16, canario antesfirmas, rangos/temporales/background/terminación y cleanupUUID. Targets de transporte/auditoríaURLSession/staging/orquestadoriOS todavía NO implementados. No presentarlos como listos por escribir protocolo.

Cambios API: avance, decisionesD29 y evaluaciónE15. No servicios/endpoints/producto modificados. Mainapp sigue13 en archivos; D28autoriza14 y probe ya14. Aplicar en integración usando autorización, no volver a pedir decisión.

Verificaciones y evidencia:
- node --test test/ios_preflight.test.cjs final4/4 exit0/160ms, probe-ios-preflight-tests-final2.log. Son fixtures, no Xcode ejecutado.
- PreflightCLI real windows_ios_gate2 exit2 esperado, macOS-required/projectSpmDisabled=true/tools{}. build/ios-preflight/windows_ios_gate2/report.json y probe-ios-windows-preflight2.log. No xcrun/xcodebuild/swift en PATH actual. Gate1 histórico conservado.
- Primer analyze reportó3lints de llaves, corregidos. Pubgetoffline --enforce-lockfile exit0/probe-ios-bootstrap-pubget.log. Análisis finaltarget exit0/4.4s, probe-ios-bootstrap-analyze-final2.log. No repetir suites Android/R2 ni rebuilds históricos por esto.
- build/ios-preflight/preparation-validation.json confirma logs (lectorconBOMUTF16 para redirecciónPowerShell), hashes de fuentes/pubspec/lock, rechazoWindows y flags iosBuild/runtime/transfers=false. No dar verde a iOS por esta validación.
- Matriz16filas/58enlaces locales existentes; informeSHAcd54a46c56c11aff4a90990daa1840aa7fe9994b1944c7e16b6af3656039779a en evaluation-audit/validation.json. E14AndroidR2 conserva alcance/cleanup; E15 sólo preparación.

Hallazgos que condicionan pruebas:
- Flutter3.41.6 fluttertest fuerza debug: no usar --release en ese comando. Release se construye/ejecuta por comandos separados. FuenteSDK y protocolo distinguen actualdocsFlutter3.47 de baseline fijado.
- Podfile ausente en Windows no prueba fallo: CocoaPods.setupPodfile omite generación sin Xcode. Revisar en Mac, sin regenerarproyecto/editarcaché por anticipado.
- AppDelegate FlutterImplicitEngineDelegate + SceneDelegate: verificar registro/reasociación en lanzamiento de fondo, no sólo UI. HoldingQueue no equivale a URLSessionTask.
- AppleDTS14855 recomienda física/sin debugger para suspensión; forcequit cancela tareas y no sustituye terminación tipo sistema. Hookexit sólo en app de pruebas, NO implementado ni incorporado al producto.
- Swift prepara rangos temporales/lee prefijos; medir picoRAM/disco/copias/limpieza reales. Simulador/iOS18 no prueban físico/runtime14. No usar8787ajeno ni aflojar allowlistAndroid local127:8797 para iPhoneLAN.

Dependencia y siguiente acción exacta:
- Disponibilidad Mac/Xcode/iPhone preguntada al usuario; todavía SIN respuesta. No asumir que existe ni que carece de Mac, no repetir pregunta pendiente. Este entorno es Windows y no puede ejecutar estos builds. No hay handle Mac/Xcode vivo que esperar.
- Al contar con Mac: desde probe ejecutar node bin/ios_preflight.cjs ios_host_1 UDID_REAL y recoger report. Corregir razones reales sin SDK/globalconfig changes indiscriminados. Ejecutar bootstrapdebug, buildrelease y bootstraprelease propios según IOS_FASE1_PROTOCOL.md; recoger artifacts antes de uninstall, confirmar mínima14/firma/registro/sandbox. Si falla, preservar ejecución y corregir causa observada, no simular éxito.
- Sólo después de bootstrap/build reales implementar/verificar iOStransferencias/URLSessionproof/canario y restantesgates. No acumular Swift sin compilación ni declarar transferencias implementadas por protocolo. No iniciarR2 para redactar documentación; reuseautorización previa sólo para futura corrida real delimitada con cleanup.
- Sin acceso real a Mac proporcionado/confirmado no queda una acción local que pruebe los gates Apple; registrar dependencia, mantener Fase1 parcial y no avanzarFase2 por el bloqueo. Androidfísico también pendiente, usuario sinUSB: no insistir.
- No procesos/receptores/emuladores vivos ni R2/cleanupPending de esta unidad; no tocar Pixel_8,8787 ajeno ni DB/app base. Goal completo pendiente.

## Unidad verificada: Android→R2 real con integridad y cleanup

Estado: verificada, Parte2/Fase1. Turno anterior clasificado progreso: preparación local release aprobada. Releídos especificación/continuidad/decisiones/protocolo, AGENTS y git status de ambos repositorios. Cambios ajenos preservados; no delegación, commits/push/deploy ni integraciónFase2.

Resultado:
- Corrida native_r2_android1/host6422 exit0, UUID3e5231c5-0836-4cc9-a4e2-a5878d58d834, PID2274/UID10127, Android13API33 emulado. Nuevo transporte Android real, sin overrideWindows y sin repetir desktop ni canary7. Usuario autorizó archivos de Downloads y limpieza; autorización aplicada sin volver a pedirla.
- Video local15504484bytes, SHA8743f28366fbb11b9ff7f5ed42c2b576357d91cbce12d9d6c1a2caa0f9987c68. APK016D2336EDD18F4DAEC5466AC6BB9426CB91E30B538F3589051A85743E094A66 release/AOT/R8 NOdebuggable aprobado, reutilizado sin reconstruir.
- Firma1s vencida antes de launch, relojAndroid/margen30min comprobados. HTTP200/403/200/200/200. Cinco WorkSpecs/cuatro taskIds lógicos/cinco intentos, estados2/5/2/2/2; taskId parte1 estable al renovar, WorkSpecID distinto. No llamar FAILED al estado5. Fuente privada inmutable antes/después.
- Helper listó partes5242880/5242880/5018724, completó multipart y comparó SHA de ambos objetos con el video original. Resultado y evidencia safe bajo build/native-r2-real/native_r2_android1: report.json, validation.json, r2-verify.log, r2-cleanup.log, sanitized-runtime.log. NO mostrar manifests de otras corridas activas; éste ya cleaned=true/sin bearerURLs.
- App limpió fuente/manifiesto/records, tests terminados. Host detuvo/desinstaló instalación propia antes de cleanup remoto. Helper confirmó ambas keys ausentes y cero multipart del prefijoUUID; manifiesto host saneado. Ausencia de paquete y hashes se revalidaron independientemente; logs sin firmas. Video original conservado.

Cambios Flutter (sólo tool/upload_probe):
- bin/native_r2_real.cjs: orquestador real separado, hash/canaryAPK, guardasAVD/UID/packageausente, helper fresco por corrida, stagingstdin Base64→sandboxUID, deadlines/reloj, metadata nativa y verificación remota. Finally stop/uninstall antes de abort/delete; cleanupPending si no se confirma; SIGINT/TERM solicitan salida por finally. Errores genéricos sin stdout/stderr sensibles.
- bin/native_r2_real_guards.cjs y test/native_r2_real_guards.test.cjs: validación UUID/prefijo/doskeys/bucket/source/SHA/rangos/firmas/margen, pathstyle/virtualhost y resultado Android real con identidad de renovación. No filesystem/red en guardas.
- test/native_r2_cleanup_helper.test.cjs: SDK Java real contra servidorloopback/.envFAKE; recupera uploadId ausente y rechaza key inesperada sin DELETE. No credenciales reales/configuración productiva.
- NATIVE_R2_PROTOCOL.md/README: protocolo y evidencia real, límites e instrucciones de recuperación. Target Dart/APK existentes sin cambios en esta unidad.

Cambios API:
- tools/SubidasR2Probe.java: recuperar ventana createMultipartUpload→persistiruploadId. Cleanup lista sólo prefijoUUID validado, rechaza truncado/key inesperada y aborta sólo multipart.mp4 exacto antes de borrar únicamente doskeys. Sin servicios/endpoints/producto modificados.
- docs/subidas-moviles-avance.md, decisionesD27/D28 y evaluación E14/matriz actualizados. iOS14 autorizado explícitamente por usuario, sujeto a pruebas; no volver a pedir esa decisión. App principal aún declara13; aplicar14 en configuración real durante integración, probe ya14. No es un build iOS aprobado.

Verificaciones:
- GuardasNode3/3 exit0/153ms, probe-native-r2-real-guards.log. HelperSDKlocal1test/2escenarios exit0/26.9s, probe-native-r2-cleanup-helper-tests.log; build/native-r2-cleanup-contract/1794274684161/report.json. No R2/configuración real en esos tests.
- Orquestador syntaxexit0 y corrida real6422exit0. Helper recompilado fresco: sourceSHA64efba31431067c74bf195cd82a86642199c2d188589acda7c35867e68fc2ac2; classSHA5522c77e0b569d217964228360cc8ed5b8a26fe7e24eb9bcbd909b9abeea1c17. validation.json confirma ambos con artefactos actuales.
- Evaluación16filas/52enlaces locales existentes, SHAeb25d699bee49e49f098337160bbfd15f491c536a3eb995d4eb1839acee111cc en evaluation-audit/validation.json. No repetir suites históricas aprobadas; no hubo rebuildFlutter en esta unidad.
- AVD propio launcher18552 API33 cerrado por emukill tras cleanup. No receptor local ni reverse en corrida real; puertos5562/8797 sin listeners, sin handles de trabajo pendientes. No tocar8787 ajeno, Pixel_8 corrupto ni DB/app base.

Límites y siguiente acción exacta:
- Verificado R2 nativo Android emulado; NO iOS, físicos, background firmado/muerte de procesoR2, API/finalización autónoma, cuotas/workspace/lotes/dedupe del producto. La evidencia Android local anterior conserva su alcance; no combinarla como prueba directa background firmado.
- Candidato9.5.9/WorkManager2.11.0 sigue en probe; NO-GO integración/liberación y goal activo. iOS14 autorizado (D28) resuelve decisión de mínimo, no pruebas ni adopción.
- Siguiente unidad de Fase1: preparar protocolo iOS ejecutable contra M01–M16 con target iOS separado/URLSession/rangos/manifest privado/expiración/cleanup y medición de temporales. Inspeccionar proyecto probe/Swift publicado sólo lo necesario; no pretender build ni ejecución en Windows. Determinar acceso real a Mac/Xcode/iPhone sin reiterar USBAndroid ya descartado; ejecutar gates iOS cuando exista entorno. No avanzar automáticamente Fase2 ni cambiar producto.
- No repetir Android/R2/desktop ya verificados. No quedan recursos R2 de esta corrida ni cleanupPending. Mantener artefactos/identidad y reusar autorización de prueba/cleanup para una futura corrida iOS trazable, no limpieza amplia.

## Unidad verificada: preparación local de Android→R2 nativo

Estado: verificada localmente. Alcance EXCLUSIVO local; no helper Java/.env, firmas ni R2 en esta unidad. Turno previo clasificado progreso; AGENTS y git status revalidados, cambios ajenos preservados (incluidos EspecTexto/Redactor y topes_texto nuevos). Fase1 y goal siguen parciales.

Objetivo: preparar un target Android firmado separado, verificar transporte/renovación/logging/cleanup con canario falso y documentar el protocolo real posterior. No repetir desktop ni integrar producto.

Cambios Flutter, sólo tool/upload_probe:
- lib/native_r2_manifest.dart: runtime privado, endpoints/modos estrictos, tres partes contiguas5MiB, taskId estable al renovar, errores genéricos sin URLs.
- integration_test/native_r2_test.dart: Platform.isAndroid sin override, PUTbinario/videoMP4, full200→expired403→renewed200→part2/3, SHA host y espera de WorkSpec terminal tras callback. Gate host de cleanup, borrado de fuente/manifiesto/records. NativeR2Proof.kt y canal MainActivity leen sólo metadata, nunca WorkDB/input_data crudos; acceso sólo package.r2.
- bin/native_r2_local.cjs y receptor lib/bin: sólo127.0.0.1:8797, fixture determinista12582949bytes, canario reflejado en body403, escaneo logcat en memoria/salida allowlisted, desinstalar sólo paquete nuevo.r2 y retirar sólo reverse propio. Captura errores adb sin stderr, incluyendo code/signal/status/duración; staging180s. Finally detiene antes de escanear; colección fallida no acredita verde.
- android/app/build.gradle.kts/proguard-r2.pro: opt-in PICALE_NATIVE_R2_PROBE=1 sólo proceso, package.r2. Excluir kxml2 sólo privado por duplicación XML Android. Release NOdebuggable, R8optimizado para eliminar Log; debug se conserva como reproducer fallido.
- Release usa su bajo UID app únicamente en AVDallowlisted; su0 disponible comprobado. No adbroot ni cambio SDK/caché/producto. Base64 por stdin ASCII→decode privado evita problemas binarios Windows; no es cifrado. SHA/tamaño antes de launch.
- test/native_r2_manifest_test.dart, analysis_options.yaml (excluye sólo fuentes parciales copiadas en build/evaluation-audit), README y NATIVE_R2_PROTOCOL.md. Orquestador real todavía NO implementado: target real no equivale a prueba R2 nativa.

Cambios API: avance/decisiones D26, enlace E08 al backup estable en evaluación. Baseline release3E4A7F... y mapping preservados en build/evaluation-audit/baseline-release antes de reemplazar outputs. Sin servicios/endpoints productivos modificados por esta unidad.

Verificaciones:
- Analyze51188 exit0 (probe-native-r2-prep-analyze-final.log), 4tests contrato aprobados nuevamente81928 exit0/61s tras corregir receptor CLI (probe-native-r2-prep-tests-final.log). node --check pasa.
- Matriz16filas/41enlaces existentes, validation.json actualizado con SHA e1097118816455c4454605a4777f0ab472dfb31bfb37c7b0ee9b830e5cf2ba8f. No repetir suites previas.
- Build DEBUG+R8 96555 exit0/560.3s, C986E4DAAAED33C7290AB2CBFFF58B117631C0A22BA5A9070D578F644F5E5028,64472949bytes. APK debug congelado; canario falló, NO usar firmas reales.
- Build RELEASE40899 exit0/414.6s, app-native-r2-release-safe.apk congelado,17245950bytes,SHA016D2336EDD18F4DAEC5466AC6BB9426CB91E30B538F3589051A85743E094A66. Aapt package.r2 NOdebuggable; ZIP lib/x86_64/libapp.so presente/kernel_blob ausente. Mapping/badging/info en build/native-r2-prep. Runtime/logging release verificados en canary7; R2 real nativo sigue pendiente.

Fallos preservados (no contarlos como éxitos):
- Canary1 receptor CLI importaba Flutter/dart:ui; corregida dependencia sólo host. Canary2/3 staging binario perdió cola37 o cortó en0x1a. Diagnóstico4133bytes: exec-in4096/shell-v2raw26/Base64privado4133exactos; staging-diagnostic.json y staging-base64-diagnostic.json.
- Canary4 SHA completo, pero release+debuggable mezcló runtime precompilado/snapshotdebug y VMfatal. F9C044... inválido conservado (app-native-r2-release.apk); NUNCA instalarlo. Host69862 cerradoexit1 tras marcador explícito host_abort_after_native_fatal; no fue timeout de tarea sana. Sin requests1–4.
- DebugR8 inicial90044 exit1: AndroidXmlResourceParser implementa interfaz duplicada de net.sf.kxml:kxml2:2.3.0; dependencyInsight atribuye AndroidXrunner1.2.0/integration_test. Exclusión privada permitió96555; no parche SDK.
- Canary5/71405 exit1 en staging antes de launch; sin código nativo anterior no atribuir causa exacta. Cero requests y paquete/reverse retirados. Se añadió diagnóstico/margen180s.
- Canary6/23906 exit1: sourceSHA e9abcdfffde5c65f75e8211b8be6606a6af0516702568517623d8fde1277232a,PID2790, full12582949/200 y expired5242880/403 correctos. Canary filtró en logcat nivelI/tagTaskRunner/body403+URL. leak-metadata.json contiene sólo clasificación, nunca línea sensible. Renovación se interrumpió al detener; receiverErrorHttpException. report.verified=false, paquete/reverse retirados, sin activos. R8debug no elimina esas llamadas. No ocultar problema con filtros de logcat ni atribuirlo a R2.

Resultado y cierre,18:25 local:
- Canary7/host68925 exit0, UUIDfbcb5200-6297-469a-8b2b-f932874ef2f9, PID2253/UID10127. Report y validación independiente en build/native-r2-local/native_r2_canary7; sanitized-runtime.log contiene All tests passed. No repetir esta corrida sin cambios que lo justifiquen.
- HTTP200/403/200/200/200 y bytes12582949/5242880/5242880/5242880/2097189 correctos. Cinco WorkSpecs terminales/cinco intentos/cuatro IDs lógicos; estados2/5/2/2/2. Renovación conserva taskId pero no WorkSpecID;5 no es FAILED. Origen SHA e9abcd... inmutable, canario ausente, fuente/manifiesto borrados, paquete.r2 y reverse ausentes revalidados.
- Receptor89702 cerrado tras confirmar5observaciones/0activos; AVD propio cerrado con emukill. Puertos8797/5562 sin listeners, daemons propios cerrados, sin handles de prueba pendientes. No tocar8787 ajeno, Pixel_8 ni datos/app/DB base.
- Evaluación añade E13 con prueba negativa debug y positiva release. Matriz16filas/45enlaces locales existentes; SHA final c99fd26bbdbd351f50b250dac368861effeeda7c49c96785a4aed20b9f3207ee en evaluation-audit/validation.json. D26 y protocolo actualizados. El historial previo de41enlaces corresponde al informe anterior a E13.
- Alcance probado: preparación/transporte binario local con MIMEvideoMP4 sintético (no contenedor MP4 válido), renovación y protección de logs/cleanup del probe release en Android13 emulado. NO es SigV4/R2 real ni prueba background firmado o integración de producto. R2 sigue limpio del desktop previo; no consultado en esta unidad.
- Siguiente acción exacta: en Fase1 completar orquestador host REAL separado que use el APK release016D..., sandbox UID privado y manifiesto runtime. Reutilizar SubidasR2Probe/autorización previa aplicable, esperar expiración real, verificar partes y hashes de ambos objetos; finally detener sólo app propia antes de cleanup exacto UUID y sanear manifiesto. Primero revisar protocolo y guardas sin emitir firmas; ejecutar sólo al estar implementado el finally. No repetir desktop ni avanzar Fase2.
- Dependencias pendientes: Mac/Xcode/iPhone, Androidfísico (usuario sinUSB; no insistir), decisión compatibilidad iOS13/14. NO-GO de adopción y goal activo, no completo. Política logging de producto aún por diseñar; reglas globalesLog del probe no se aplicaron al producto.

## Unidad verificada: evaluación del motor y matriz de Fase 1
- QA final del documento: M06 distingue fixture Android/local 12582949 bytes (5242880+5242880+2097189) del video R2 de15504484 (5242880+5242880+5018724). validation.json guarda ambas series y SHA-256 del informe final7134287775e386d43f540ece6aef678bbda1f814686531cba63476a4676bee93. Se confirmaron16 filas/41 enlaces; no se repitieron transferencias. Puertos de probe sin listeners al cierre.

- Turno anterior clasificado como progreso: Android 13 prolongado acreditado. Esta unidad creó el entregable de evaluación requerido y encontró una brecha de cobertura concreta; no repitió sus pruebas. AGENTS y git status en ambos repositorios revalidados, cambios ajenos preservados.
- **Resultado:** docs/subidas-moviles-evaluacion-motor.md contiene M01–M16 en el orden exacto de las 16 pruebas obligatorias, estados probado/parcial/pendiente, catálogo E00–E12, límites e implicaciones de dominio. Decisión: NO-GO para integrar/liberar; mantener 9.5.9 como candidato aislado. iOS/físicos y R2 firmado nativo no acreditados; iOS 13 del producto frente a 14 del candidato requiere decisión de compatibilidad, sin cambiar mínimos de la app.
- **Brecha nueva:** integration_test/r2_contract_test.dart fija debugDefaultTargetPlatformOverride=TargetPlatform.windows. R2 real aprobado es desktop; Android local no verifica SigV4 y no hubo runtime iOS. El informe conserva el valor de las pruebas existentes, pero no extrapola entre pilas. Próxima unidad es nueva cobertura nativa, no repetición de desktop.
- **Procedencia revisada:** API pública de pub.dev, publicación 9.5.9 del 2026-08-29 y posterior 9.6.6 del 2026-11-05. SDK local 3.41.6/3.11.4 compatible con 9.5.9; 9.6.6 exige Flutter>=3.47/Dart^3.13. Archivo 9.5.9 descargado, SHA-256 ae55e127f87397b372578fc9474568b879fa78326103c342e00160558d08e527 igual a API y lockfile. LICENSE coincide con caché: texto principal BSD de tres condiciones + MIT/localstore; badge unknown no sustituye archivo. Se extrajeron seis fuentes Dart/Swift/Gradle y sus hashes coinciden con caché. No actualización de SDK/paquete ni licencia de terceros modificada.
- **Verificación sin reejecutar:** 16 filas/16 requisitos y 41 enlaces locales existentes. JSON/logs de background, SIGKILL, red, 403, corte causal, recursos y Android 13 contrastados; estados nativos y duraciones comprobados. Receptor background usa SCREEN_STATE_OFF/INTERACTIVE_STATE_SLEEP y LastPausedActivity, no el formato de otros dumps. R2 manifest cleaned=true sin URLs; verify/cleanup previos acreditados, sin consulta nueva al bucket.
- APK release existente inspeccionado con aapt: minSdk24/target36, no debuggable, mapping R8 presente. SHA-256 3e4a7f71ba192aa92d198a136c19909dac303f3863918bc36b87c741434693f8. No build nuevo ni afirmación de release de Pícale/diagnósticos recientes. Fuente Android del paquete declara minSdk23 aunque README publicado menciona21; probe efectivo24. Temporales iOS/retry Dart inspeccionados estáticamente, no perfilado/build iOS.
- **Archivos por repo:** API: nuevo docs/subidas-moviles-evaluacion-motor.md y actualización de avance/decisiones. Flutter: ningún código ni dependencia modificados; sólo evidencia ignorada build/evaluation-audit (metadata, archivo público, fuentes seleccionadas, badging y validation.json). No tests/builds/subidas/AVDs nuevos en esta unidad.
- **Evidencia del audit:** tool/upload_probe/build/evaluation-audit/validation.json, published-metadata.json, source-integrity.json, published-9.5.9/ y release-badging.txt. Descarga/verificación 13568 exit0; todo terminal. ADB sin dispositivos y puertos8797/5560/5562 sin listeners al revalidar. Sin handles vivos, R2 y 8787 ajeno intactos. Sin commit/push/deploy.
- **Siguiente acción exacta:** preparar LOCALMENTE el harness Android→R2 faltante en el probe: target separado con Platform.isAndroid y sin override Windows, manifiesto sensible leído en runtime (no URLs en dart-defines/logs), archivo/rangos persistentes del mismo video local y protocolo de verificación/cleanup con SubidasR2Probe existente. Auditar registros del SDK y manejo del manifiesto para evitar exponer bearer URLs o copiar DB cruda con firmas. Verificar análisis/build/contrato local del nuevo target y dejar comandos concretos para la corrida nativa; no emitir nuevas firmas/PUTs ni tocar bucket durante esa preparación. Reutilizar autorización aplicable de pruebas/cleanup al evaluar la ejecución posterior, sin re-pedirla automáticamente.
- **Pendientes de Fase 1:** firma real con motores móviles, Mac/build-release-runtime/perfilado iOS, teléfonos físicos y compatibilidad iOS13/14. Usuario sin USB, no reiterar esa solicitud. Goal completo pendiente; no iniciar Fase 2, integrar producto ni declarar candidato aprobado sólo por el informe.
## Unidad verificada: Android 13, duración y reintento

- Turno anterior: progreso, recursos Android verificados. Esta unidad añadió imagen/AVD aislados y acreditó un envío nativo prolongado con reintento. AGENTS y git status de ambos repositorios revalidados; cambios ajenos preservados. Se corrigieron erratas de ruta/decimales del registro anterior, sin repetirlo.
- **Resultado terminal:** colector `35748` exit 0, `android13_long1` / `lr-1794251740168`, PID 2265, Android 13/API 33. Primer cuerpo exacto 134217765 bytes recibió 503; reintento automático con mismo taskId recibió 200, mismo tamaño íntegro y duración monotónica **682672 ms (11 min 22.672 s)**. Un único enqueue manual; callbacks enqueued→running→waitingToRetry→enqueued→running→complete. Flutter All tests passed. `collection-report.json` verified=true.
- **Frontera nativa:** segundo request aún activo a 611008→612010 ms, bytes 120193024→120389632 antes/después del snapshot. Mismo WorkSpec `c2d342ce-c77f-4082-89d4-3af2f786acb8` RUNNING/intento 1 en snapshot inicial conservado y frontera; SUCCEEDED/intento 1 al final. Tres snapshots estables/quick_check ok, serial correcto. FGS=true, Display State=OFF + mWakefulness=Asleep, actividad STOPPED al cruzar diez minutos. Al terminar el FGS ya se había detenido normalmente.
- **Alcance temporal preciso:** reloj Stopwatch del MISMO cuerpo HTTP, no 610 s entre snapshots SQL; el primer snapshot se capturó después del inicio. Un cuerpo nativo continuo de 682.672 s y la identidad/contador del WorkSpec acreditan el intervalo probado. No es duración máxima universal ni prueba de todas las versiones anteriores a 14. Sólo API 33 emulado/debug, alimentado (mIsPowered=true); no deep Doze ni OEM/físicos/iOS. Reintento 9.5.9 usa temporizador Dart antes de entregar nueva tarea nativa: no afirmar retry independiente de Dart.
- **Fallo del observador preservado:** host original `92095` exit 1 por buscar Display Power: state=OFF en power; API 33 lo expone como Display State=OFF en display. Original report.json conserva verified=false/error y dumps. La carga sana siguió en el MISMO proceso/conexión; no reinstalación/launch/enqueue adicional. Nuevo colector sólo observa, exige ambos Asleep+displayOFF, contrasta SQL y habilita cleanup al finalizar. No cambió SDK, worker, velocidad ni datos para fabricar duración. El host original también quedó corregido, pero no se repitió toda la transferencia sólo para probar ese camino.
- **Preparación local:** SDK system-images;android-33;default;x86_64 rev2 instalado (`71275` exit 0), zip 694005732 bytes. Licencias SDK existentes, stdin n sin aceptar términos nuevos. Inventario inicial 21.15 GiB libres. AVD NUEVO PicaleUploadProbeApi33 creado sin --force en tool/upload_probe/build/avd-api33, perfil pixel_4, port 5562, proceso ANDROID_AVD_HOME sólo local. Imagen/kernel/AVDs previos intactos. Primer boot tardó casi 8 min, se esperó el proceso vivo sin reiniciarlo. Fingerprint exacto conservado en build-fingerprint.txt.
- **Archivos Flutter afectados:** nuevos lib/long_run_receiver.dart, bin/long_run_receiver.dart, integration_test/native_long_run_test.dart, test/long_run_receiver_test.dart, bin/long_run_audit.cjs y bin/collect_long_run.cjs; bin/workmanager_snapshot.cjs admite --serial 5562 y conserva default 5560; README.md actualizado. Todos en tool/upload_probe. API: sólo avance y decisiones. Sin dependencia nueva, cambio productivo, commit, push o despliegue.
- **Verificación:** dos tests reales del nuevo receptor (`12445` exit 0), analyzer final limpio (`94064`, 25.2 s), sintaxis Node de tres scripts limpia. Build `41112` exit 0 en 449.4 s y confirmación final `1727` exit 0 en 24.4 s; corrección de llaves de estilo sin cambio de lógica. APK SHA-256 `669CAC1B57EC379C70601DC9463CB38DE168C2B03BA9ABFECF5F7D92D4DC2B6F`. Validación independiente de estados [1,1,2], intentos [1,1,1], reloj/progreso de frontera, dos cuerpos/HTTP, Flutter y cleanup guardada en validation.json. Los 22 casos previos se conservan; no inflar sus conteos con repeticiones/diagnósticos.
- **Evidencia:** build/long-run-audit/android13_long1/collection-report.json, collection-runtime.log, collection-heartbeat.jsonl, collection-*-services/power/display/activity.txt, collection-post-cleanup-disk.txt, validation.json, receiver-final.json, build-fingerprint.txt. Reporte/logs/dumps fallidos iniciales preservados en misma carpeta. Snapshots: build/replay-audit/android13_long1_salvage_initial y android13_long1_collect_after_ten_minutes / _completed. Logs raíz: probe-android13-long1-collect.log, probe-long-run-analyze-final.log, probe-long-run-receiver-tests.log, probe-build-native-long-run-final.log, sdk-install-api33.log y avd-create-api33.log.
- **Cleanup verificado:** sólo fixture propio lr-1794251740168.bin eliminado, manifiesto cleaned y du sin ese archivo; bases de datos, gates y evidencias conservados. Receptor final active=[] y dos observaciones exactas 503→200. Receptor `15246` terminado por SIGINT; AVD API 33 cerrado por consola después de verificar nombre y cero requests activos. ADB sin dispositivos, cero listeners 8797/5562 y cero procesos propios de AVD/receptor verificados. Reverse propio ya retirado por primer host; no remove-all. Daemon Gradle 23740 detenido sólo tras identidad/cwd y último build finalizado. SDK/AVD se conservan para validaciones futuras justificadas. Sin handles activos. R2 y 8787 ajeno intactos.
- **Siguiente acción exacta:** consolidar el entregable de Fase 1 en docs/subidas-moviles-evaluacion-motor.md: matriz de CADA prueba obligatoria del apartado 6 de la especificación contra evidencia existente y alcance real (Windows/R2, Android API 33/36 emulado, release Android, iOS/físicos). Inspeccionar los artefactos pertinentes, distinguir probado/parcial/pendiente y cerrar recomendación técnica de versión con límites de retry, replay y temporales. No repetir corridas aprobadas ni confundir evidencia de una plataforma con otra. Si aparece brecha concreta, registrarla y resolver sólo acciones locales autorizadas; no aprobar el motor ni iniciar Fase 2 con evidencia insuficiente.
- **Pendientes externos:** Android/iPhone físicos, Mac/release iOS y memoria/temporales iOS; no se ha acreditado esa plataforma. Android 13 acredita el caso anterior a 14 probado, sin prometer todas las versiones/OEM ni duración ilimitada. Usuario sin USB: no solicitarlo otra vez. Fase 1 sigue parcial; goal completo pendiente.
## Unidad verificada: memoria y disco Android

- Turno anterior clasificado como progreso: cerró el corte causal. Esta unidad implementó y verificó mediciones reales; git status/AGENTS revalidados y cambios ajenos preservados. Se mantuvo Parte 2/Fase 1, sin integrar producto.
- **Resultado terminal:** host `46561` exit 0, `resources_android36_2`, run `rs-1794249201195`, PID 3506/API 36. PUT 16 MiB+37, PUT 128 MiB+37 y rango 8 MiB+37 a offset 64 MiB: tres aceptaciones HTTP 200 exactas, sin intentos extra, Flutter All tests passed. 159 muestras PSS/RSS/du con crudos verificados contra reporte; 9/125/6 muestras durante recepción activa por caso. Home dejó probe PAUSED y launcher resumido, según dump de actividades.

| Caso | PSS máximo observado KiB | RSS máximo observado KiB | Incremento PSS sobre última baseline KiB | Crecimiento fuera de fixtures KiB |
|---|---:|---:|---:|---:|
| Completo 16 MiB+37 |169893|174152|7356|12|
| Completo 128 MiB+37 |161402|173180|399|12|
| Rango 8 MiB+37 |156046|158664|591|16|

- PSS/RSS son de TODO el proceso debug, incluyen Flutter/VM/probe. Baselines PSS: small 153373/162536/162537 KiB; large 161158/161003/161003; range 155452/155439/155455. Diferencias de calentamiento/GC y máximos muestreados impiden extrapolar una ley de memoria o pico absoluto. Preparación de fixtures ocurrió antes de baselines; no GC forzado. Receptor limitado a 1 MiB/s: no benchmark de velocidad.
- du mide bloques asignados, no sólo temporales: crecimiento fuera de fixtures incluye DB/metadatos/cachés/archivos previos. No se observó una copia visible comparable al archivo en las muestras. No cubre archivos abiertos ya eliminados ni picos entre muestras. Sólo Android 36 emulado/debug y estos tamaños; iOS/archivos mayores/dispositivos físicos siguen sin medir.
- **Cleanup comprobado:** dos fixtures propios 147476 KiB asignados→0; sandbox 293356→145884 KiB (4 KiB adicionales de metadatos al cerrar). App comprobó longitudes y borró sólo esas rutas únicas. Receptor final tres observaciones/active=[]; preservados manifiesto, gates, bases de datos y pruebas anteriores. Receptor 6216 terminado por SIGINT y AVD cerrado sólo tras identidad PicaleUploadProbe; ADB sin dispositivos, cero listeners 8797 y procesos propios verificados. Reverse propio retirado. Daemon 3176 detenido sólo tras último build finalizado e identidad. Sin handles activos. 8787 ajeno intacto; R2 no tocado.
- **Archivos Flutter afectados:** nuevos lib/resource_fixture.dart, lib/resource_receiver.dart, bin/resource_receiver.dart, bin/resource_audit.cjs, integration_test/native_resources_test.dart, test/resource_receiver_test.dart; README.md actualizado. Todos bajo tool/upload_probe. API: avance y decisiones. Sin dependencias nuevas/cambios productivos/commits/push/deploys.
- **Verificación:** dos tests reales del receptor aprobados (76628), analyzer limpio (40736 exit 0, 10.2 s), Node syntax check. Build 84620 exit 0, 222.3 s; confirmación 60446 exit 0, 21.7 s. APK SHA-256 D9C024494096166A4F553A95C7661961F746CB9A5DCF4A42B30D82A4BEECACEB. Validación independiente de 159 tríos (477 archivos memory/status/disk) comparados con reporte; 159 muestras verificadas y tres casos. No sumar diagnósticos a los 22 casos anteriores ni repetir suites estables.
- **Evidencia:** build/resource-audit/resources_android36_2/report.json, validation.json, receiver-final.json, flutter.log, activity-after-home.txt y 159 tríos *-memory.txt/*-status.txt/*-disk.txt. Logs probe-resources-analyze-clean.log, probe-resources-receiver-tests-final.log, probe-build-native-resources-final.log y probe-resources-android36-2.log. Directorio base tool/upload_probe.
- **Fallo de preparación preservado:** resources_android36_1/host 22324 terminó antes de crear fixtures/tareas: ADB exec-out devolvió texto cat missing con exit 0, JSON.parse abortó y retiró reverse; preflight posterior falló. Receptor quedó vacío. state() corregido para esperar ausencia explícita, otros errores siguen invalidando. No repetir el fallo ni atribuirlo al motor. Tests iniciales del receptor detectaron active retenido hasta response.close y se corrigió a recepción del body; ahora pasan. Historial de logs conservado.
- **Siguiente acción exacta:** unidad Android anterior a 14: inventariar SDK/espacio/disponibilidad de imagen API 33 y preparar AVD aislado distinto sin tocar Pixel_8/PicaleUploadProbe existente. Diseñar ensayo nativo de duración superior a 10 min, reintentos y Home/pantalla apagada, con condiciones y timeouts del harness compatibles; instrumentar receptor independiente y conservar bytes/estados. Los helpers actuales usan serial 5560: parametrizar para nuevo AVD si se necesita, no conectar al dispositivo incorrecto. No reutilizar target resource sin adaptar su timeout 6 min ni repetir su corrida ya verificada.
- **Pendientes de Fase 1:** duración/reintentos Android anterior14; Android/iPhone físicos; release iOS y memoria/temporales iOS. Recursos Android quedan medidos con los límites expresados, sin aprobar todas las plataformas. Usuario sin USB, no pedirlo otra vez. Sin Mac/iPhone no declarar iOS verificado. No avanzar automáticamente Fase 2.

## Unidad verificada: fallo abrupto dentro de la ventana complete→persistencia
### Resultado terminal y siguiente unidad

- Turno previo de consulta: informativo, sin cambios de implementación (no progreso). Esta continuación sí produjo cambios y evidencia causal nueva; se preservaron cambios ajenos y se revalidó ausencia de AGENTS aplicables.
- **Host 27584 exit 0**, `fault-bgqueue1-1794248186106`: HTTP 200, 5,242,880 bytes exactos; PID 2466 leyó WorkSpec `ec8dc952-18ea-4cb0-bd16-30d1fbe6d24f` RUNNING y murió. Snapshot posterior todavía RUNNING, intento 1. PID 3496 recuperó sin reinstalar ni enqueue manual: MISMO WorkSpec SUCCEEDED, intento 2, dos aceptaciones exactas. Flutter All tests passed; host verified=true/causalCut=true/replayed=true. Corte causal local acreditado, sin atribuir retroactivamente causa al replay original.
- El ajuste fue sólo del instrumento: background task queue y lectura directa fuera del executor de muestreo; marca crítica en logd con PID/taskId, cero errores. Orden→inicio de consulta 0.039428 ms; consulta 0.262967 ms en esta corrida. No duración universal ni garantía temporal. No cambios al SDK, worker, DB o motor productivo; no sleeps para fabricar ventana.
- El SIGKILL dejó última línea JSONL incompleta (`seedTraceTailIncomplete=true`): bytes íntegros preservados; host descartó sólo ese fragmento final y exigió logd independiente + SQLite posterior. No se toleró corrupción intermedia. Las muestras normales y recuperación no tuvieron errores reportados.
- **Archivos propios Flutter:** `android/app/src/main/kotlin/com/rodtech/picale_upload_probe/MainActivity.kt`, `CommitAudit.kt`, `bin/fault_audit.cjs`, `README.md`, todos en tool/upload_probe. API: sólo avance y decisiones. Cambios existentes del usuario preservados; sin commits/push/despliegues.
- **Verificación:** Node syntax check limpio; Flutter analyze 24223 exit 0 sin incidencias en 34.5 s; build 39662 exit 0 en 488.1 s. APK SHA-256 `C2DC870FE33323879B5A3F3E1DC4E769B07DF76287BD87CD1173D2EB226475A1`. No repetir suites estables ni sumar este diagnóstico a los 22 casos de contrato/transporte.
- **Evidencia:** `build/fault-audit/fault_window_bgqueue1/report.json`, `fault-point.log`, `seed-native-trace.jsonl`, `recovery-native-trace.jsonl`, `recovery-flutter.log`, `runtime.log`, `receiver-final.json`; snapshots en `build/replay-audit/fault_window_bgqueue1_before`, `_after_death`, `_after_recovery`. Logs de analyze/build y `probe-fault-window-bgqueue1.log` en raíz del probe. Tres snapshots SQLite estables/quick_check ok. Caso reverse1 fallido preservado, no reinterpretado como aprobado.
- **Recursos cerrados:** receptor 50863 terminado, AVD cerrado por consola sólo tras verificar PicaleUploadProbe y cero requests activos. ADB sin dispositivos, cero listeners 8797 y cero procesos propios de AVD/receptor verificados. Regla reverse propia retirada; 8787 ajeno intacto. Daemon Gradle 14384 detenido sólo tras identidad y último build finalizado. No handles activos de esta unidad. R2 no se tocó y conserva cleanup previo.
- **Siguiente acción exacta:** comenzar unidad local de medición de memoria/disco del probe Android, con baseline, archivo/tamaño documentado, seguimiento durante transferencia nativa y cierre; conservar resultados crudos y declarar límites de debug/emulador/tamaño. Preparar/verificar un harness de recursos que mida memoria del proceso Android y bytes de almacenamiento temporal, sin repetir casos anteriores sólo para sumar verdes. No asumir iOS medido ni cargas grandes aprobadas por un fixture pequeño. Sólo imagen API 36 x86_64 instalada (inventario SDK); Android anterior a 14 requiere otra imagen/dispositivo y sigue pendiente. No reabrir diagnóstico causal ya aprobado salvo inconsistencia concreta.
- **Pendientes de Fase 1:** memoria/temporales y tamaño, duración/reintentos Android anterior a 14, Android/iPhone físicos, release iOS y temporales de rangos iOS. Usuario sin Android USB, Mac/iPhone no disponibles: no inventar pruebas ni volver a pedir USB. En etapas futuras, el dominio deberá reconciliar partes y tratar reenvío ante resultado incierto; no implementar Fase 2 ahora.

### Historial de instrumentación, sin acciones activas pendientes
- Turno anterior clasificado como progreso: ventana medida y reapertura verificadas, receptor mejorado por intento. Se sigue la pregunta causal sin repetir seed/reopen, R2, baseline ni controles estables.
- Se revalidó estado de repositorios y AGENTS (ninguno aplicable); cambios existentes preservados. Puerto 8797 libre al iniciar, se usa receptor propio `19520` con 150 ms/chunk; 8787 ajeno permanece intacto. AVD aislado reabierto, launcher `29968`; comprobar boot antes de instalar.
- Cambios actuales del probe: CommitAudit arma y persiste el marcador ANTES de encolar mediante armFault. killIfRunning queda restringido a APK debuggable y taskId fault-* de su propia sesión armada; sólo lee estado y envía SIGKILL a su propio PID si hay exactamente un WorkSpec RUNNING. Si ya es terminal no mata y el caso es ventana no alcanzada, no reproducción. No modifica DB, worker ni resultado del transporte. El primer diseño armaba en el intervalo crítico y llegó tarde; evidencia conservada abajo.
- Nuevo target `integration_test/native_commit_fault_test.dart`: manifiesto `fault-audit.json` separado del commit-audit existente, fixture único, task retries=0. Primer proceso intenta fallo tras HTTP 200; segundo sólo observa la MISMA tarea con doRescheduleKilledTasks=false, sin enqueue manual, esperando estado nativo terminal y registrando cuántas aceptaciones hubo. Primer proceso no debe terminar con test verde: su muerte es el evento deliberado; host debe acreditar PID desaparecido y estado SQLite posterior antes de reabrir.
- Receptor agrega identidad `probeReceiver=picale_upload_probe_v1` en report para no confundirlo con un servicio ajeno. Define Android y host deben usar 8797. Análisis `12973` en curso, `probe-fault-analyze.log`; build y ejecución todavía pendientes. No dar por alcanzada la ventana por la existencia de un marcador armed.
- Siguiente acción: corregir análisis si procede, compilar target, ejecutar orquestación que confirme HTTP aceptado + lectura RUNNING + muerte y snapshot posterior no terminal. Reabrir sin instalar/encolar y contrastar intentos/PUTs. Si el punto de fallo llega tarde, preservar la evidencia y reportarlo como miss, sin simular éxito.
- Reanudación de la misma unidad: handles anteriores `12973`/`85493` ya no existen; log inicial fault vacío y procesos/puertos propios ausentes al inspeccionar. No se consideró análisis aprobado. Se reanudó análisis `94558`, log `probe-fault-analyze-resumed.log`, procesos 21556/21680 y analysis_server 24820/24896 confirmados vivos; no relanzar por demora. AVD nuevo launcher `26092`, QEMU `25884`, boot completo; receptor actual `22455`, PID Dart 25832/25624, puerto 8797 e identidad de probe. 8787 ajeno intacto.
- Nuevo `bin/fault_audit.cjs` (Node syntax check aprobado) guarda checkpoints locales antes/después de cada fase: PID muerto, marcador y traza RUNNING, aceptación exacta previa, snapshot SQLite no terminal antes de reabrir, luego recuperación y número de aceptaciones/intentos. No encola tareas ni reinstala tras la muerte; exige identidad de receptor. Primer build target fault recién iniciado con define 8797, `probe-build-native-fault.log`; registrar handle y recogerlo.
- Build actual `36153` vivo, daemon Gradle `19892` confirmado trabajando sólo en upload_probe. Análisis `94558` terminó exit 1 con tres avisos de estilo en 373.6 s; corregidos sin cambiar lógica, análisis final `78577` (`probe-fault-analyze-final.log`) en curso. Host sólo tenía ~315,672 KiB libres; no había daemons propios idle que detener (los antiguos ya no viven). Para liberar RAM se cierra temporalmente SÓLO el AVD propio durante build, después de verificar receptor de identidad correcta vacío: ningún experimento de fallo había comenzado. Reabrirlo después del build; no es reinicio de un test ni cambio de estado del worker.
- Cierre temporal del AVD `46593` exit 0 mediante consola SDK; comprobación `64958` confirmó QEMU ya ausente (no se ejecutó Stop-Process). Memoria libre subió a 3,230,328 KiB. Análisis final `78577` exit 0 sin incidencias en 308.8 s. Sigue pendiente únicamente build `36153`: daemon 19892 vivo, CPU acumulada pasó de 116.9 a 134 s y dump `build/fault-gradle-threads.log` mostró transformaciones/cache en curso; no se probó deadlock ni fallo de código. Esperar el mismo handle. NO instalar el APK anterior ni iniciar orquestación antes de salida terminal exit 0 del target fault.
- Build `36153` terminó **exit 0** en 870.7 s. Se preservaron dumps que muestran cambio de cache/transformaciones a lectura JAR, no se reinició el build por demora. Análisis final ya limpio. Se inicia reapertura de AVD y `fault_audit.cjs fault_window` tras boot completo; guardar y recoger el nuevo handle. Durante el build sólo se ajustaron llaves y una aserción null innecesaria, sin cambios de comportamiento. Causalidad todavía pendiente de ejecución, no inferirla por compilación.
- APK fault 88,095,975 bytes, SHA-256 `9A744DF0EBFDECA730CC6DF725504AF9EA67947C3533DAF06E514A3CEF517919`. Se confirmó fin del último build del probe e inactividad/identidad del daemon 19892 y se detuvo sólo ese daemon propio para liberar memoria; no se usó gradlew --stop ni se tocaron otros JVMs. Handle actual `31137` espera boot del AVD reabierto y luego ejecutará Node fault_window; receptor propio `22455` activo en 8797. Recoger ese handle, no relanzar por demora. Aún no hay evidencia de un fallo alcanzado.
- `31137` terminó exit 1 por timeout de ADB después del snapshot inicial y antes de registrar PID/launch. Inspección posterior: receptor vacío, probe sin PID, PackageManager mostró instalación terminada a `17:24:35Z`; no había comenzado la carga ni se provocó muerte. Evidencia `build/fault-audit/fault_window/report.json` preservada. El límite de instalación era 30 s; se amplía a 180 s y se agrega --installed que exige SHA-256 del APK instalado igual al build antes de omitir instalación. No repetir una instalación todavía en curso ni inferir fallo del motor por ese timeout.
- Se inicia recolección/ejecución `fault_window_installed --installed`, log `probe-fault-window-installed.log`, con identidad de receptor comprobada y cero requests. El script verifica hash y abrirá el APK ya instalado, sin repetir transferencia previa (no existía). Guardar/recoger nuevo handle y checkpoint causal incremental.
- `47216` terminó exit 1 por **ventana no alcanzada**, no por fallo de transporte. Tanda `fault-1794245235569`, PID 2846 siguió vivo. Trace: fault_arm vio WorkSpec 44dee6e5... RUNNING; ~126 ms después, fault_kill_check ya vio SUCCEEDED. El I/O durable del marcador se intercaló y consumió la ventana. No hubo SIGKILL ni prueba causal aprobada. Una aceptación exacta; seed/marker/traza/receptor preservados en `build/fault-audit/fault_window_installed/`.
- Corrección basada en evidencia: mover armFault/FD sync fuera del intervalo, antes de enqueue. killIfRunning sólo valida sesión armada, lee y envía señal, sin preparar archivos. Se conserva marcador inmutable por trace y puntero por tarea; no se altera estado de WorkManager. Se agrega PROBE_FAULT_CASE y --case para otro manifiesto/tanda sin borrar el intento fallido: caso `earlyarm`, archivo `fault-audit-earlyarm.json`.
- AVD se cerró de nuevo tras confirmar cero requests activos, antes de compilar para evitar presión RAM. Nuevos handles: análisis `22232` (`probe-fault-earlyarm-analyze.log`) y build `2086` (`probe-build-native-fault-earlyarm.log`) con defines puerto8797/caso earlyarm. Receptor anterior `22455` detenido y se inicia receptor fresco; recoger sus resultados/handle antes de lanzar el nuevo caso. No repetir first-case ni reinterpretar miss como éxito.
- Variante earlyarm: análisis `22232` exit 0 sin incidencias (233.6 s), build `2086` exit 0 en 292.3 s, sin cambios de fuentes durante esa compilación. Receptor fresco `29335` activo. Se inicia AVD y orquestación `fault_window_earlyarm --case earlyarm` tras boot; guardar/recoger handle. Daemon propio 23456 sólo se detiene si el log acredita último build del probe finalizado y la identidad sigue siendo Gradle; no otros JVMs. El experimento aún no se declara alcanzado.
- APK earlyarm 88,096,396 bytes, SHA-256 `063D337349116897FABDD03C337FFFF48F3C8382859E8D8DE1BD765C8B8311BD`. Se verificó y detuvo daemon idle 23456 del probe. Handle actual `92277`: reabre AVD y ejecuta Node al publicar boot completo; log `probe-fault-window-earlyarm.log`. Receptor `29335` debe conservarse durante muerte/reapertura. No instalar variante anterior ni usar manifiesto legacy fault-audit.json para el caso earlyarm.
- Caso earlyarm `92277` terminó exit 1 por timeout de espera del fallo, SIN alcanzar el punto causal. Tanda `fault-earlyarm-1794246047913`, PID 2294: TaskRunner informó Failed to connect to /10.0.2.2:8797 y FAULT_STATUS failed http=null; receptor siguió vacío. No hubo HTTP aceptado ni SIGKILL de la prueba. Runtime/manifest/receptor preservados bajo `build/fault-audit/fault_window_earlyarm/transport-*`. Cierre posterior del AVD fue sólo preparación de build, no evidencia causal.
- Corrección de infraestructura: se agrega configuración por intent del probe (launchOptions: probeReceiver/probeFaultCase), validada como HTTP de loopback/emulator y caso alfanumérico. Así se cambia puerto/caso sin recompilar cada intento. Host admite --port y --reverse; verifica receptor, crea/reutiliza sólo la regla ADB reverse del puerto elegido y retira sólo la propia. No cambia firewall/VPN ni toca 8787 ajeno. Nuevo caso planeado `reverse1`, manifiesto separado.
- Código actualizado necesita un único build para ese mecanismo runtime: análisis `65929` (`probe-fault-runtime-analyze.log`) y build `44882` (`probe-build-native-fault-runtime.log`), todavía pendientes. AVD cerrado y receptor `29335` vacío conservado. Luego usar `fault_audit.cjs fault_window_reverse1 --case reverse1 --reverse`, sin repetir las tandas anteriores.
- Análisis runtime `65929` exit 0 (42.2 s). Se agregó preflight desde Android al receptor identificado ANTES de crear manifiesto/tarea, para no confundir alcance host con alcance device; análisis de ese código `33833` exit 0 limpio (22.7 s), `probe-fault-preflight-analyze.log`. Build runtime `44882` exit 0 en 322.3 s; se ejecuta confirmación incremental final porque preflight se agregó durante ese build. No lanzar antes de su resultado terminal. Ya no hace falta recompilar para cambiar caso/puerto: se reciben por intent sólo en el probe.
- Confirmación final `61783` exit 0 en 40.9 s, `probe-build-native-fault-final.log`. Se inicia AVD y orquestación con `--case reverse1 --reverse --port 8797`, receptor vacío `29335` conservado. La variante vieja earlyarm no se reutiliza ni se borra. Si el daemon 22860 se detiene, sólo después de confirmar identidad e idle del último build del probe. Registrar/recoger handle nuevo; el experimento sigue sin aprobación causal hasta snapshot post-muerte.
- APK runtime final 88,097,762 bytes, SHA-256 `757E52CA9A7FF07D19AA2CBD70AFFC40B40766DC924D7E605BBEAFF12C2F0CEE`. Se confirmó y detuvo sólo daemon idle 22860. Handle actual `85572` espera boot y ejecutará `fault_window_reverse1 --case reverse1 --reverse --port 8797`; log `probe-fault-window-reverse1.log`, checkpoints `build/fault-audit/fault_window_reverse1/report.json`. Receptor vacío `29335` permanece; ninguna tanda reverse1 se considera ejecutada hasta ver READY y evidencia de HTTP/punto de fallo.

## Unidad verificada: medir complete frente a persistencia nativa

- **Resultado:** `commit-1794241235508`, corte/restauración de red, una parte de 5,242,880 bytes aceptada. Trace de 4,107 muestras sin errores: en la marca nativa dart_complete el WorkSpec 14e8c88a... estaba RUNNING; la transición persistida a SUCCEEDED quedó entre **48.724405 y 61.639855 ms** después de recibir esa marca. No es una duración universal: incluye latencia y perturbación de instrumentación en Android emulado/debug. Evidencia exacta en `build/commit-audit/commit_seed/summary.json` y `native-trace.jsonl`.
- **Reapertura:** snapshot previo al cierre final SUCCEEDED y dos WorkSpecs cancelados; tras reiniciar el MISMO AVD/APK sin instalar ni enqueue, todos los WorkSpecs conservaron estados e intentos. Mismo taskId/fixture; sólo una aceptación. Seed `50617` y reopen `36353` exit 0, tests All tests passed. Observer 1,370 muestras sin errores.
- **Límite registrado:** entre cierre y reapertura apareció otro HttpException en el formato antiguo sin identidad. No se atribuye con certeza ni se lo cuenta como PUT nuevo; durante la ventana observer el receptor no cambió. La comparación global es distinta, las aceptaciones y los estados/intentos son iguales. `receiver-comparison.json` conserva ese detalle.
- **Mejora del receptor:** attemptId por request y errores atribuidos, para no sobrescribir intentos solapados de la misma parte. Nuevo test con dos sockets reales, uno abortado y el otro completado. Ocho casos locales aprobados tras ese cambio (`52583`, `probe-receiver-attempt-tests.log`); después sólo se agregaron llaves de estilo, sin repetir tests. Análisis final `49708`, `probe-commit-final-analyze.log`, sin incidencias (34.3 s).
- Archivos propios Flutter: `android/.../CommitAudit.kt`, `MainActivity.kt`, `integration_test/native_commit_test.dart`, `bin/commit_audit.cjs`, `lib/probe_receiver.dart`, `test/upload_contract_test.dart` y README. API: avance/decisiones únicamente. Build Android final `1833` exit 0; sin cambios en motor/dependencias/fuentes productivas.
- Cierre: seed `50617`, reapertura `36353`, tests `52583` y análisis final `49708` terminales; receptor propio `71369` terminado y AVD cerrado, ADB sin dispositivos. En la comprobación final 8787 ya estaba ocupado por un proceso **Node ajeno al probe**, verificado por comando/parent; se dejó intacto. No afirmar cero listeners ni detener ese servicio. Próxima unidad debe verificar puerto libre o elegir otro y propagarlo al receptor, define PROBE_RECEIVER Android y orquestador host (hoy usa 8787). No quedan procesos propios pendientes, ni cambios R2/productivos/commits/push/despliegues.
- Siguiente acción exacta: nuevo caso de fallo con manifiesto y tanda separados que termine el proceso cuando HTTP ya aceptó y el lector todavía vea RUNNING; capturar estado antes/después y observar recuperación SIN enqueue manual. No borrar `commit-audit.json` ni reejecutar seed aprobado. Determinar si el intervalo produce reemisión; no atribuir aún la causa original ni agregar sleeps como arreglo. No avanzar Fase 2 ni repetir R2/baseline/controles estables.

### Historial de implementación, verificaciones y límites

- Turno anterior clasificado como progreso: tres controles pasivos aislaron reinicio/reinstalación con trabajos terminales guardados, sin reemisiones. No se repiten ni se toma eso como resolución del replay original.
- Se revalidó git status de ambos repositorios y ausencia de AGENTS aplicables; cambios del usuario preservados. AVD aislado iniciado con launcher `7228`, proceso QEMU observado `27288`. Hubo una demora larga de observación/host (la hora pasó de madrugada a 10:10 local); handle de lanzamiento `7862` terminó exit 0, no se relanzó. Se verificó de nuevo el estado de archivos y ADB tras la demora. Android ahora publica boot completo.
- Cambios sólo en probe: `android/.../CommitAudit.kt` y MainActivity agregan un canal diagnóstico que abre SQLite WorkManager en READONLY y toma muestras cada 20 ms por taskId, en executor propio. Registra tiempos monotónicos de inicio/fin de lectura y marcas recibidas desde Dart. No cambia estados, workers ni resultados; escribe sólo trazas JSONL separadas en files/probe.
- Nuevo target `integration_test/native_commit_test.dart`: crea una única tanda controlada si no hay manifiesto; conserva taskId/fixture y en siguientes arranques sólo observa sin enqueue. La carga usa parte 2 y retries/retriesRemaining=3 para repetir la condición de red como investigación de la inconsistencia. Marca dart_complete en el lector nativo y espera estados terminales leídos antes de permitir cierre. La instrumentación agrega carga/latencia: reportar límites de muestreo, no duración exacta universal.
- Procesos: análisis `58699` (`probe-commit-analyze.log`), build `52901` (`probe-build-native-commit.log`), receptor independiente `71369` en 8787 con 500 ms/chunk. Código nuevo aún sin verificación de ejecución. Recoger handles, no reiniciar por demora. R2 y fuentes productivas intactas.
- Siguiente acción: recoger build/análisis, ejecutar corte/restauración controlado, recoger traza y snapshot previo al cierre; reiniciar sólo AVD con mismo APK y receptor, observar mismo taskId sin reinstalar ni crear tanda nueva.
- Análisis inicial `58699` detectó cuatro avisos de estilo/null-aware (sin errores de tipos); corregidos. Análisis final `8409` exit 0 sin incidencias, `probe-commit-analyze-final.log`. Build `52901` exit 0 en 192.6 s; se inicia build incremental final porque la detección explícita de errores del sampler y ajustes Dart entraron mientras el primero estaba en curso. No acreditar el APK hasta recoger ese build final.
- Se agregó `bin/commit_audit.cjs`, parser Node aprobado: orquesta sólo esta nueva carga, prueba pérdida de red real, restaura radios, recoge trazas/snapshots previos al cierre y calcula un intervalo de transición observado. En modo sin --seed reabre el mismo APK/manifiesto y no instala/encola. Es diagnóstico, no política productiva de sleeps o cambios de WorkManager.
- Build incremental final `1833` terminó exit 0 en 45.1 s (`probe-build-native-commit-final.log`), con la última instrumentación y contador de errores de muestreo. Se inició `commit_audit.cjs commit_seed --seed` (`probe-commit-seed.log`); registrar/recoger su handle antes de repetir. Host rechaza métricas ambiguas si hay más de un WorkSpec SUCCEEDED o faltan marcas/errores de muestreo. Receptor `71369` permanece activo.
- APK final 88,096,065 bytes, SHA-256 `B837D010CD43EEFC0BBF2FF6946CB6205B1B98A1E045EF317452633A7C1370D2`. Orquestador actual `50617`, tanda `commit-1794241235508`, PID 4983. READY a `16:20:49Z`, pérdida de red confirmada none y estados waitingToRetry/enqueued. Está en ejecución; no reiniciar AVD ni repetir la tanda. Resultado de persistencia todavía pendiente.
- Corrida seed `50617` terminó exit 0; test All tests passed. Se registraron 4,107 muestras sin errores. En `dart_complete`, WorkSpec final `14e8c88a-b10d-4b3e-b5a2-c2c94ef0ba15` aún state=1 (RUNNING); los dos WorkSpecs previos por retries estaban state=5. El primer SUCCEEDED llegó aproximadamente 61.64 ms después de la recepción nativa de la marca Dart; el intervalo de transición observado tiene ancho 12.92 ms. No usar como tiempo universal ni atribuir todavía causa original.
- Hubo dos interrupciones/retries: la inducida por radios y otra SocketException tras restaurar; no se infiere causa de la segunda. El receptor sólo aceptó una parte exacta de 5,242,880 bytes. Snapshot previo al cierre conservó WorkSpec final state=2 y previos state=5. Trazas, manifiesto y reporte en `build/commit-audit/commit_seed/`.
- Se inició reapertura SIN reinstalar ni encolar: `commit_reopen`, receptor `71369` conservado. Registrar/recoger handle recién iniciado; aún falta validar ese control antes de cerrar la unidad.
- Reapertura `36353` terminó exit 0: mismo APK y runId, PID nuevo 1575, modo observe sin enqueue y 1,370 muestras sin error. Dos inicializaciones del engine/COMMIT_OBSERVING en el mismo PID precedieron una finalización All tests passed; el manifiesto impidió crear otra tanda. El receptor conservó una sola aceptación y ningún request nuevo; comparar estados/intentos en summary.json antes de concluir. Recursos todavía pendientes de cierre.
- Comparación real de `build/commit-audit/summary.json`: estados/intentos de TODOS los WorkSpecs iguales antes de cerrar, tras boot y tras observación; bootId sí cambió. Aceptaciones permanecieron en una. La igualdad global del receptor seed→reopen es FALSE: apareció un HttpException genérico adicional entre cierre y reapertura. La ventana de observación antes→después de reapertura SÍ es idéntica. El error antiguo no tiene runId/attemptId y no puede atribuirse con certeza; no llamarlo request nuevo ni atribuirlo definitivamente a una conexión vieja. Detalle en `receiver-comparison.json`.
- Se detectó límite de instrumentación: active usaba runId/parte como clave, por lo que intentos solapados podían sobrescribirse; los errores del body no llevaban identidad. Se corrige sólo receptor del probe con attemptId único por request y error contextual no aceptado. Nuevo test real de dos requests solapados: abortar primero, conservar segundo activo y aceptarlo una sola vez. Suite local en `52583`, `probe-receiver-attempt-tests.log`; análisis nuevo iniciado (`probe-commit-and-receiver-analyze.log`). No repetir Android ni R2 por esta adición de metadatos; los resultados anteriores conservan su limitación explícita.

## Unidad verificada: controles pasivos para aislar reemisión

- **Resultado terminal:** los tres controles pasivos terminaron exit 0, sin eventos, tareas nativas ni requests nuevos. `passive_initial` PID 4388, `passive_after_reboot` PID 1563 y `passive_after_reinstall` PID 4332. El receptor permaneció con observations=[] y active=[]. No se encoló ni repitió una carga aprobada.
- Comparación en `build/replay-audit/controls-summary.json`: no hubo cambios de estado, intentos ni taskIds en ninguno. Inicialmente sólo tres `schedule_requested_at` pasaron a -1; los controles posteriores fueron idénticos en todos los campos leídos. BootId cambió de 073b4858... a 2810dddc... al reiniciar y se conservó al reinstalar, acreditando operaciones separadas.
- Esto limita la hipótesis: con WorkSpecs ya terminales persistidos no apareció replay en estas ventanas de observación. NO explica la reemisión original ni prueba exactamente una entrega ante cortes en el intervalo de persistencia. No se aplicó arreglo al paquete ni se aprobó adopción. Los 21 casos de transporte anteriores no aumentan por estos controles de diagnóstico.
- Archivos Flutter propios de la unidad: `integration_test/native_passive_audit_test.dart`, `bin/workmanager_snapshot.cjs`, `bin/passive_audit.cjs`, `README.md`. API: sólo avance y decisiones. Scripts Node con sintaxis validada y ejecución real; Flutter analyze limpio, APK debug compilado. WorkManager 2.11.0 efectivo confirmado por dependencyInsight y fuente publicada exacta conservada.
- Evidencia de controles y snapshots: `build/replay-audit/`, logs `probe-passive-initial.log` y `probe-passive-reinstall.log`. Handles `27005`, `19733` y `63243` terminales; cierre de AVD/receptor se registra abajo. No borrar el replay original ni interpretar controles negativos como solución.
- Cierre de recursos: receptor `88576` terminado, AVD cerrado por `adb emu kill`, ADB sin dispositivos y cero listeners en 8787 verificados. Logs y receptor final preservados en replay-audit. No hay handles pendientes, tráfico R2 nuevo, cambios productivos, commits, push ni despliegues.
- Siguiente acción exacta: medir una NUEVA carga controlada con reintentos/red, justificada por la inconsistencia, capturando estado nativo inmediatamente al llegar complete y antes de cualquier cierre. Comparar señal Dart/HTTP, resultado Worker y estado persistido WorkSpec; cerrar/reabrir con estado previo conocido. Preservar receptor y snapshots sin reinstalar en ese paso. No repetir R2 ni baseline, no avanzar Fase 2.

### Historial de preparación y controles

- Turno anterior clasificado como progreso: 403/renovación nativos aprobados y replay real identificado; se sigue su siguiente acción, sin repetir R2 ni los casos aprobados.
- Cambios propios del probe: `bin/workmanager_snapshot.cjs` copia sólo DB/WAL/SHM del sandbox debug mediante ADB exec-out binario; compara dos lecturas DB/WAL y rechaza copias cambiantes; genera quick_check, hashes, WorkSpec/taskId/estados/intentos, sin imprimir inputs ni URLs. No escribe ni elimina datos del dispositivo. Node 24.14.1/node:sqlite existentes.
- Primer control sin reinstalar ni abrir actividad: AVD reabierto con launcher `12936`, boot completo. Snapshot `build/replay-audit/boot_without_install/report.json` a `08:23:41Z`: 20 WorkSpecs state=2 y 4 state=5, mismo estado/intentos del snapshot anterior al cierre. Receptor `88576` no recibió requests. No se reprodujo replay en este reinicio con trabajos ya terminales guardados.
- Segundo control: reinstalar exactamente APK renewal con `adb install -r`, SIN abrir actividad. Handle `97122` exit 0; snapshot `reinstall_without_launch` a `08:24:54Z`, mismos estados/intentos, cero requests. No se reproduce por reinstalación sola en este estado estable.
- Se agregó `integration_test/native_passive_audit_test.dart`: arranque que sólo lee tracking, escucha eventos, llama start con doRescheduleKilledTasks=false y espera 30 s. No enqueue/cancel/delete/fixture. Permitirá abrir Flutter sin repetir renewal y contrastar eventos almacenados/cola nativa y recepción.
- Análisis de target pasivo `35166`, build `35605`, logs `probe-replay-analyze.log` / `probe-build-native-passive.log`, resultados pendientes. DependencyInsight `69221` sigue abierto para confirmar WorkManager efectivo; no confundir demora con salida terminal. Receptor `88576` sigue activo en 8787 con 150 ms/chunk; no reiniciar AVD ni limpiar DB.
- Fuente oficial consultada para hipótesis (no prueba causal): WorkerWrapper registra SUCCESS antes de setSucceeded/estado SUCCEEDED: https://android.googlesource.com/platform/frameworks/support/+/b976b3b88e7acea5c0023c93c7b2d08822d9e576/work/work-runtime/src/main/java/androidx/work/impl/WorkerWrapper.kt . Confirmar versión efectiva y no usar un log como comprobante de commit SQLite.
- Siguiente acción: recoger análisis/build, instalar target pasivo y observar apertura sin encolar. Después comparar snapshots antes/después de reinicio y reinstalación del mismo APK pasivo. Si no reproduce, conservar la incertidumbre y preparar control de ventana complete→commit con una carga nueva justificada por la inconsistencia.
- Avance de herramientas: análisis `35166` exit 0 sin incidencias (71.4 s). DependencyInsight `69221` exit 0 confirmó `androidx.work:work-runtime:2.11.0` y work-runtime-ktx:2.11.0; evidencia `probe-replay-dependencies.log`. Build `35605` sigue vivo, no reiniciar. Se agregó `bin/passive_audit.cjs`: snapshots/receiver antes y después, arranque pasivo por PID, manifiesto de eventos/cola y detección de requests nuevos sin encolar. Ambos scripts Node pasaron `node --check`.
- Build pasivo `35605` terminó exit 0 en 227.1 s. APK 69,343,865 bytes, SHA-256 `F0D3A3BC64B346016C6B50A472197A5BDF876955A319B8B9D56BF79B7C6A78F7`. Control `27005` (`passive_initial --install`) exit 0: PID 4388, 30 s de observación, cero eventos, cero tareas nativas y noNewRequests=true. Snapshot antes/después guardado en `build/replay-audit/passive_initial*.json`; no se reprodujo al abrir Flutter con start/recuperación de eventos.
- Código fuente publicado exacto 2.11.0 descargado desde Maven Google, sólo para lectura: `build/replay-audit/WorkerWrapper-2.11.0.kt`, confirma orden log SUCCESS → setSucceeded → estado SUCCEEDED. Archivo jar de fuentes conservado; no ejecución ni dependencia instalada.
- Se inició el siguiente control: cerrar AVD con cola vacía y snapshot terminal, reabrirlo SIN reinstalar, esperar boot completo y abrir el mismo target pasivo (`passive_after_reboot`). Guardar handle recién iniciado y recoger su resultado antes de repetir. Receptor `88576` se conserva entre controles; ninguna carga nueva fue creada.
- Handle del control de reinicio: `19733`, arranque observado `08:32:09Z`, serial device pero boot todavía pendiente en la primera consulta. No reiniciar por esa demora. `controls-summary-initial.json`: igualdad exacta entre boot y reinstalación aislada. El primer arranque pasivo SÍ cambió `schedule_requested_at` a -1 en tres WorkSpecs terminales (renewal partes 1/3 y network parte 2), sin cambiar estado ni intentos; no equivale a reejecución. Diff completo en `passive-initial-diff.json`. Corregida la descripción inicial que afirmaba igualdad total antes de inspeccionar el comparador.
- Control de reinicio `19733` terminó exit 0: `passive_after_reboot`, PID 1563, noNewRequests=true, noNativeTasks=true y events=[]; receptor siguió vacío. Log muestra dos inicializaciones Flutter/READY en el mismo PID a `08:33:55Z` y `08:34:15Z`, con una finalización terminal a `08:34:48Z`; no se atribuye aún causa de esa recreación de engine. El target pasivo evita que tal arranque cree nuevas tandas. Snapshots y manifiesto preservados.
- Siguiente control iniciado: `passive_after_reinstall --install`, reinstalación del MISMO APK pasivo y apertura, sin reiniciar AVD ni crear transferencias. Recoger handle nuevo y comparar estados/intentos/receptor; no dar por resuelta la causa original sólo por controles negativos.
- Control `63243` terminó exit 0 a `08:36:55Z`, PID 4332: noNewRequests=true, noNativeTasks=true, events=[] y snapshots idénticos en los campos comparados. `controls-summary.json` reúne los tres controles. No se reprodujo en un estado ya terminal y estable; la ventana de persistencia de la carga original sigue sin evidencia previa.

## Unidad verificada: 403 y renovación Android nativos

- **Resultado terminal:** `renewal-1794212078240`, PID 2034, test `All tests passed!`; recolección `-CollectExisting` exit 0 (`probe-native-renewal-collect.log`). Parte 2 failed/403, luego complete/200 con mismo taskId `renewal-1794212078240-2-initial`, metadatos y rango `bytes=5242880-10485759`. Registro final persistido complete; fixture original sin modificación entre intentos.
- Receptor verificó cuatro requests de esa tanda: partes 1/3 HTTP 200 a `08:15:01.333165Z` / `08:14:56.260484Z`; parte 2 HTTP 403 a `08:15:02.802891Z` y HTTP 200 a `08:15:16.077293Z`. Todos bytes correctos y sin Range/Authorization/Content-Disposition en red. Ninguna repetición de partes 1/3 de la tanda renewal.
- Alcance: permiso sintético del receptor HTTP, transporte Android real, tres partes del fixture y reintentos automáticos a cero. No nueva firma R2, autenticación Pícale, iOS ni background en esta corrida (ADB falló antes de Home). El caso background tiene evidencia separada y no se repite.
- Archivos Flutter propios: `integration_test/native_renewal_test.dart`, `bin/android_renewal.ps1`, `README.md`. API: sólo continuidad y decisiones; ningún endpoint/servicio productivo. Análisis/build limpios, evidencia principal `build/native-renewal-evidence.json` verified=true y `probe-native-renewal-runtime.log`. Fallo inicial de orquestación preservado.
- Hallazgo que define la próxima unidad: replay de tarea network ya completada, detalles y snapshot al final del historial. No cerrar Fase 1 ni avanzar Fase 2. El caso de renovación queda aprobado; el comportamiento del motor a través de reinicios sigue bajo revisión.
- Cierre verificado: AVD terminado mediante `adb emu kill`, ADB sin dispositivos y cero listeners en 8787; receptor `34856` y orquestador `23989` terminales. Recolección posterior exit 0, sin procesos de esta unidad pendientes. No hubo R2, commits, push ni despliegues. Dependencia declarada por `background_downloader/android/build.gradle`: WorkManager `work-runtime-ktx:2.11.0`; confirmar resolución efectiva al investigar replay.

### Historial de preparación y diagnóstico

- Turno anterior clasificado como progreso: red aprobada con captura de pérdida/restauración, contadores corregidos y tres partes exactas. No se repiten esas pruebas ni R2 real ya limpio.
- Cambios propios sólo del probe: `integration_test/native_renewal_test.dart` y `bin/android_renewal.ps1`. Entrega partes 1/3 válidas y 2 con token sintético vencido, exige failed/403, lee registro persistido, reconstruye con `renewProbeTask`, conserva ID/archivo/directorio/rango, reentrega sólo parte 2 y exige 200. Fixture no se regenera ni modifica entre intentos; receptor debe registrar exactamente un 403 y tres 200 con contenido correcto.
- Orquestador instala sólo el probe, espera PID de forma acotada, usa logcat filtrado por ese PID, envía Home y exige test terminal aprobado, reporte host y manifiesto persistido. No prueba autenticación API ni una nueva firma R2; ese transporte real se probó antes. Parser PowerShell aprobado.
- AVD aislado reabierto con launcher `18532`, ADB device pero boot completo todavía pendiente al preparar. No se abrió Pixel_8. Receptor host `34856` en puerto 8787 con demora 150 ms/chunk.
- Análisis `9370` y build debug `49930` recién ejecutados; recoger resultados terminales. Logs `probe-renewal-analyze.log` y `probe-build-native-renewal.log`. No inferir ejecución por compilación. Monitor de boot iniciado, conservar handle y esperar estado real sin reiniciar el AVD por timeout de observación.
- Siguiente acción: confirmar boot completo y build, ejecutar `bin/android_renewal.ps1`, recoger evidencia terminal y cerrar recursos. R2, app productiva y fases posteriores no se modifican.
- Checkpoint de ejecución: análisis `9370` exit 0 sin incidencias, build `49930` exit 0 en 52.7 s. APK target renewal 88,095,440 bytes, SHA-256 `AE9E804BF5E107BD9E1037603F73B32C69F728B4666FADA96B06402A97EE780C`. Monitor de boot terminó exit 0 con BOOT_COMPLETE. Orquestador actual `23989`, `probe-native-renewal-orchestrator.log`; instalación/arranque enviados correctamente. Receptor `34856` sigue activo. Recoger `23989` y no reiniciar por ausencia inicial de logs Flutter.
- Ejecución observada: `renewal-1794212078240`, PID 2034, test `All tests passed!`. Parte 2 recibió 403 y luego 200 conservando taskId/rango. Orquestador `23989` falló exit 1 por ADB `daemon still not running`, antes de enviar Home; la app siguió viva. Se preservó `build/native-renewal-adb-failure.json`, se corrigió tratamiento de stderr y reintento acotado sólo de lecturas ADB, y `-CollectExisting` recogió la MISMA corrida sin reinstalar/enviar bytes de nuevo: exit 0, `RENEWAL_HOST_VERIFIED`, manifiesto y reporte exactos. No se acredita background en esta corrida por el fallo previo a Home; ese escenario tiene evidencia separada.
- Hallazgo adicional que cambia la siguiente acción: durante esta reapertura Android reejecutó `network-1794211649103-2-initial` de la prueba de red anterior, aunque ya constaba SUCCESS. El receptor recibió otros 5,242,880 bytes HTTP 200 a `08:15:05.364076Z`. El WorkSpec original `2a6a53ab-0133-42d9-a600-0c1b93c39c79` había reportado SUCCESS a `08:09:11.759Z`; reapareció tras arrancar y acabó cancelado, seguido de WorkSpec nuevo `b6eed0fd-3b17-4a28-b4f9-031fefed97f7` SUCCESS. Evidencia `build/native-stale-work-replay.log` y `build/native-renewal-receiver-final.json`. No atribuir causa todavía a librería, cierre del AVD o reinstalación. No se invalida la recepción exacta anterior, pero NO queda probado que no haya reemisión posterior a reinicio.
- Siguiente acción corregida: investigar reproducción controlada de ese replay nativo en Fase 1 y estado persistido WorkManager, antes de adoptar motor o avanzar API. No limpiar DB ni cancelar globalmente para ocultar el hallazgo; sólo inspección del sandbox del probe. R2 no se toca.
- Snapshot diagnóstico de WorkManager (DB/WAL/SHM del sandbox del probe, copiados sin modificar el dispositivo): `build/workmanager-renewal-snapshot/report.json`, quick_check=ok. WorkSpec original 2a6a... quedó state=5/run_attempt_count=2; nuevo b6ee... state=2/run_attempt_count=1. No había requests activos al copiar. No existe snapshot anterior al cierre del AVD para comparar persistencia; no inferir aún causa. Node 24.14.1 con node:sqlite disponible se usó para lectura local; Python no está instalado, no se instaló nada.
- Próxima unidad exacta: reproducción controlada de reemisión tras una carga exitosa, con snapshot WorkManager antes y después de cerrar/reabrir el AVD y, por separado, reinstalar el APK. Comparar WorkSpec ID/taskId, estados, intentos y recepción sin encolar una nueva tanda. Determinar si fue persistencia del cierre/host, trabajo nativo pendiente o interacción retry/WorkManager. No cambiar el paquete ni limpiar evidencia antes de aislar causa.

## Unidad verificada: interrupción y restauración de red Android

- **Resultado terminal:** orquestador `20708` exit 0, `NETWORK_HOST_VERIFIED`; test `All tests passed!`. Corrida `network-1794211649103`, PID 4920. Parte 1 completó `2026-11-09T08:08:07.352032Z` antes del corte. Partes 2/3 tenían 5,840 / 1,460 bytes en vuelo antes de desactivar radios a `08:08:10.1396175Z`.
- Android reportó default network none a `08:08:14.3081200Z`, siguió none tras 15 segundos y restauró desde `08:08:29.4682147Z`; red inicial 103, final 106. Durante outage sólo parte 1 estaba aceptada. Partes 3 y 2 completaron después de restaurar, a `08:08:49.783002Z` y `08:09:12.069100Z`, respectivamente. Tres HTTP 200, bytes exactos (5,242,880 / 5,242,880 / 2,097,189), una aceptación por parte.
- Estados persistidos de cada parte interrumpida: enqueued → running → waitingToRetry → enqueued → running → complete. Mismo runId/taskId/archivo; no selección, regeneración de fixture ni reenviado manual. MainActivity quedó pausada y launcher resumido durante la recuperación. El proceso Flutter seguía vivo; no extrapolar a muerte de proceso durante el intervalo de retry.
- Evidencia ignorada: `build/native-network-evidence.json` (`verified=true`), `probe-native-network-runtime.log`, `probe-native-network-orchestrator-final.log` y manifiesto `files/probe/native-network.json` dentro del sandbox. `README.md` documenta comandos y límites. Build final y análisis limpios; fallos anteriores conservados abajo.
- Radios originales Wi-Fi/datos restaurados y comprobados ambos a 1 antes de cerrar AVD. Receptor `98762` cerrado; orquestador `20708` terminado. AVD terminado por `adb emu kill`; cierre comprobado con ADB sin dispositivos y cero listeners en 8787. No hay handles pendientes. No hubo tráfico R2 ni cambios de producción, commits o push.
- Siguiente acción exacta: prueba nativa Android de 403 por permiso sintético vencido y renovación con `renewProbeTask`, conservando taskId/rango y confirmando bytes exactos del reintento. R2 real vencido ya pasó; no repetirlo. Después revisar recursos/compatibilidad y matriz física/iOS pendiente. No avanzar Fase 2.

### Historial de preparación y corridas fallidas de esta unidad

- Turno anterior clasificado como progreso: recuperación tras SIGKILL aprobada con evidencia de dos procesos y tres partes aceptadas. No se repite esa prueba.
- Archivos nuevos sólo en el probe: `integration_test/native_network_test.dart` y `bin/android_network.ps1`. Completa parte 1, entrega partes 2/3 con hasta tres reintentos, espera recuperación sin selección nueva y contrasta tres partes exactas, una aceptación por parte. El orquestador valida bytes incompletos, envía Home, desactiva Wi-Fi/datos sólo en AVD aislado, exige `Active default network: none`, mantiene 15 segundos y restaura los radios originales incluso al fallar.
- AVD `PicaleUploadProbe` reabierto con launcher `11252`; boot completo y red default 101 verificados. Pixel_8 intacto y sin abrir. Receptor host handle `74902`, puerto 8787, demora 500 ms/chunk, sin tráfico R2.
- Análisis `61011` exit 0 sin incidencias, `probe-network-analyze.log`. Build `77066` exit 0 en 107.2 s, `probe-build-native-network.log`, target Android x64 `native_network_test.dart`. Parser PowerShell verificado. Escenario todavía no aprobado; ejecución del orquestador recién iniciada, recoger su handle/log antes de repetir nada.
- APK network: 88,093,705 bytes, SHA-256 `8F85D39CA3455FB00D13B67287F734D250A61AE3E0A5AC59DBE59B50BE91651D`. Primer orquestador `72298` terminó exit 1 por carrera de arranque: `pidof` vacío inmediatamente después de am start, pero el proceso apareció luego como PID 2642 y siguió ejecutando la prueba. No hubo corte de red en esa ejecución del orquestador. Evidencia preservada `build/native-network-startup-failure.json`.
- Se corrigió espera acotada a 30 s para PID y se agregó `-Attach` para recuperar control de la MISMA ejecución viva, sin reinstalar ni repetir bytes. Orquestador actual `52070`, `probe-native-network-orchestrator-attach.log`; tanda `network-1794211370801`, PID 2642, parte 1 en vuelo a `08:03:20Z`. Receptor `74902` sigue activo. Recoger `52070`, no reiniciar por espera.
- Siguiente acción: recoger ejecución y evidencia independiente de red ausente, restauración y partes recibidas. Si falla, diagnosticar estados/registro nativo y corregir dentro de Fase 1. No dar por aprobada red únicamente porque termina un PUT; no avanzar Fase 2 ni repetir R2.
- Primer escenario `52070` terminó exit 1 por una aserción prematura del harness: Android quedó sin red default de `08:03:44.2163147Z` a restauración `08:03:59.5208495Z`, pero el motor emitió `canceled` para ambas partes al detener los workers por constraints. El harness lo trató como final definitivo; WorkManager reejecutó las mismas tareas a `08:04:12/13` después de volver la red. No se aprobó el caso ni se oculta el fallo.
- Correcciones basadas en esa evidencia: el harness espera de forma acotada un canceled transitorio (no hay cancelación de usuario en esta prueba), exige igualmente tres completions exactas; `copyWith(retries:3)` conservaba `retriesRemaining=0` de la tarea original, ahora se establecen ambos a 3. Fuente verificada: `UploadTask.copyWith` 9.5.9 preserva explícitamente el contador si no se pasa. Orquestador ahora exige que sólo parte 1 esté aceptada durante outage y que 2/3 completen después de restaurar red.
- Evidencia fallida preservada bajo `build/native-network-first-failure*.json/log`; receptor original sigue activo hasta guardar su cierre. Nueva compilación justificada por cambios reales del harness: `22692`, `probe-build-native-network-final.log`; análisis `27182`, `probe-network-analyze-final.log`. Recoger esos handles; después preparar receptor fresco y una nueva corrida, sin repetir escenarios ya aprobados.
- Receptor inicial `74902` cerrado tras guardar la recepción final de la corrida fallida (tres partes correctas, sin convertir ese test fallido en aprobado). Receptor actual `98762`, fresco en puerto 8787. Análisis final `27182` exit 0 sin incidencias; build `22692` exit 0 en 78.3 s. APK final 88,093,892 bytes, SHA-256 `17CA672B1C15F5B3E353ADED393563640269C90B3600AAB0EDC7EE7FE28BCD8F`.
- Orquestador final actual `20708`, log `probe-native-network-orchestrator-final.log`, iniciado `08:07Z`. Instalación/arranque exitosos, espera primera parte. Recoger ese handle y el JSON terminal sin reiniciar por observación vencida; nueva prueba todavía pendiente de aprobación.

## Unidad verificada: recuperación nativa tras muerte del proceso

- **Resultado terminal:** orquestador `99614` exit 0, `RECOVERY_HOST_VERIFIED`; harness del segundo proceso `All tests passed!`. Corrida `recovery-1794210830375`: PID `4538` terminó por SIGKILL a `2026-11-09T07:54:39.9280641Z`; `pidof` vacío después de la señal, reapertura enviada `07:54:41.0571268Z`, PID nuevo `4904`. Manifiesto persistido terminó `stage=verified`, firstPid=4538, secondPid=4904 y mismo runId.
- Parte 1 fue aceptada antes de matar el proceso: 5,242,880 bytes, HTTP 200, `07:54:34.445539Z`. En la captura previa a SIGKILL, parte 2 llevaba 202,448 bytes y parte 3 135,452; ambas incompletas. Después de reabrir, parte 3 completó `07:55:22.416902Z` con 2,097,189 bytes y parte 2 `07:55:45.795433Z` con 5,242,880. Las tres partes tuvieron contenido exacto, HTTP 200 y una sola aceptación exitosa por parte. Parte 1 no se volvió a solicitar; los logs sólo reinician workers 2/3.
- Receptor registró además dos desconexiones de requests interrumpidos (`receiverError`), sin aceptación exitosa; no confundirlas con partes confirmadas. La recuperación repite desde el inicio cada PUT interrumpido, no continúa un PUT a mitad.
- Evidencia: `build/native-recovery-evidence.json` (`verified=true`), `probe-native-recovery-runtime.log`, `probe-native-recovery-orchestrator.log` y manifiesto dentro del sandbox del probe. Cierre verificado: ADB sin dispositivos, cero listeners en 8787, receptor `87824` terminado y orquestador `99614` exit 0. No hay procesos de prueba pendientes ni recursos R2 nuevos.
- Alcance: Android 36 emulado, APK debug, muerte abrupta simulada vía señal del UID de prueba y reapertura explícita. No demuestra un evento real de low-memory killer, force-stop manual, reinicio del teléfono ni recuperación iOS. No se implementó ListParts/API de sesiones; eso sigue en fases futuras.
- Archivo adicional: `README.md` del probe documenta comandos, manifest y límites. Tras el éxito se filtró logcat por PID en el orquestador para evitar que una futura corrida lea marcadores viejos: parser verificado y flag `--pid=4904` contrastado contra el log real; no se repitió la prueba completa por ese ajuste de captura.
- Siguiente acción exacta: preparar y ejecutar interrupción de red en el probe Android con receptor independiente, conservar registros y comprobar recuperación al restaurar conexión. Después, 403/renovación nativos. No repetir recuperación aprobada ni R2; no iniciar Fase 2.

### Historial de preparación y ejecución de esta unidad

- Turno anterior: la respuesta «qué sigue» fue una explicación, sin cambio de estado (no progreso técnico); se retoma ahora la siguiente acción real del checkpoint. La unidad Android Home/Sleep anterior sí produjo evidencia y queda aprobada, sin repetirla.
- Se agregó sólo `metricol_app/tool/upload_probe/integration_test/native_recovery_test.dart`: harness en dos arranques con manifiesto local durable, runId y PID. Completa parte 1, entrega partes 2/3, espera registros persistidos y emite `RECOVERY_READY` para que el host mate el proceso mientras hay bytes en vuelo. Al reabrir usa `FileDownloader.start` y su recuperación real, lee los mismos registros y contrasta tres partes aceptadas en receptor independiente.
- Fuente consultada: código publicado 9.5.9 en caché Pub, `lib/src/file_downloader.dart`: `start` activa tracking, recupera eventos y agenda `rescheduleKilledTasks` a 5 segundos. Éste compara registros enqueued/running/waitingToRetry con cola nativa. No confundir `am force-stop` con muerte por señal; se probará SIGKILL del proceso propio vía run-as del APK debug.
- AVD aislado reabierto, launcher `15396`, serial `emulator-5560`; ADB device y `sys.boot_completed=1` verificados. No se abrió Pixel_8 ni se hizo wipe.
- Análisis inicial `76369` terminó con un import innecesario; corregido. Análisis final `45620` terminó exit 0, sin incidencias, `probe-recovery-analyze-final.log`. Build debug recovery `96576` exit 0 en 212.8 s; APK 88,095,484 bytes, SHA-256 `A08E1DECCEE76AC3EB62702D60BB16DB47D29360911640C016683C28AA61F5C8`. La única edición posterior al inicio del build fue retirar el import sin uso; no cambió lógica.
- Se agregó `bin/android_recovery.ps1` del probe: orquesta instalación, bytes en vuelo, SIGKILL limitado por run-as, PID nuevo y validación terminal. Parser PowerShell sin errores. Ejecución actual `99614`, log `probe-native-recovery-orchestrator.log`; instalación exitosa y primer arranque enviado. Receptor `87824` en puerto 8787 con 500 ms/chunk. Recoger el handle sin repetir el escenario por observación vencida. Recuperación todavía no acreditada.
- Siguiente acción de esta unidad: recoger analyze/build, instalar sólo probe, ejecutar primer arranque, capturar parte 1 aceptada y 2/3 incompletas, registrar PID y SIGKILL, comprobar desaparición/cambio real de PID y reabrir. Evaluar resultado sin inferir iOS ni implementación productiva. R2 no se repite.
- Progreso observado de ejecución: corrida `recovery-1794210830375`, primer PID `4538`. Parte 1 aceptada `2026-11-09T07:54:34.445539Z`; `RECOVERY_READY` a `07:54:36`. El host envió SIGKILL vía run-as y reabrió; nuevo PID `4904`, `RECOVERY_REOPENED` a `07:54:50`. A `07:55:05/06` Android inició nuevamente workers de las mismas partes 2/3, sin iniciar parte 1. Falta resultado terminal y validación exacta del receptor. Orquestador `99614` sigue en ejecución; no reiniciarlo.

## Unidad terminada: verificación nativa Android

### Continuación del 9 de noviembre: ejecución real del harness

- Cierre verificado: AVD terminó mediante `adb emu kill`; ADB sin dispositivos, PIDs `33560`/`460` ausentes y ningún listener en puerto 8787. Receptor `70054` y lector logcat `63175` terminaron tras Ctrl+C. No hay handles pendientes de esta unidad. Análisis final sin incidencias y nuevo test dirigido del receptor aprobados; no se ejecutaron commits, push ni despliegues.

- **Resultado terminal:** corrida `native-1794208867431` aprobada con cuatro HTTP 200 y bytes exactos. Parte 3 completó `07:21:34.187587Z`, parte 1 `07:21:54.617908Z`, parte 2 `07:21:56.644305Z` y archivo completo `07:22:46.173426Z`, todos después de Home/Sleep y antes de reabrir. Logcat `All tests passed!`; los cuatro workers terminaron SUCCESS. Captura independiente `build/native-background-completed.json` conserva resultados y estado de pantalla apagada. No hubo reapertura para obtener el éxito.
- Alcance exacto: Android 36 x86_64, APK debug del probe, prioridad 5 y servicio foreground con notificación. No prueba UIDT, Doze prolongado, bloqueo con PIN, muerte de proceso, red interrumpida, archivos grandes, un Android físico ni iOS. Keyguard se observó inicialmente visible y luego `showing=false`; pantalla e interacción siguieron OFF/SLEEP al final. Se acredita pantalla apagada, no autenticación segura.
- Balance de Fase 1: **18 casos distintos aprobados** (7 locales, 6 integración Windows, 1 persistencia entre procesos Windows, 3 R2 y 1 Android nativo repetido en dos corridas). No sumar requests o repeticiones como casos distintos. Análisis final limpio; no repetir R2 ya limpio.

- El usuario confirmó «no, sigamos asi» ante la solicitud de Android físico y pidió «termina». Se continúa con el emulador; esto no sustituye los gates físicos ni autoriza inferir resultados iOS.
- El AVD aislado `PicaleUploadProbe` alcanzó `sys.boot_completed=1`; instalación del APK debug terminó exit 0. Los handles de boot `13581`, espera `64047`, build `62320` e instalación `31960` ya terminaron. No volver a esperar esos procesos.
- Primera corrida nativa `native-1794208723442`: cuatro PUT aprobados, incluyendo archivo completo de 12,582,949 bytes y partes de 5,242,880 / 5,242,880 / 2,097,189 bytes. Todos HTTP 200, contenido exacto, sin Range/Authorization/Content-Disposition filtrados. Logcat: `All tests passed!`. Esta corrida inicial acredita contrato Android; no se usa como evidencia de bloqueo.
- Segunda corrida `native-1794208867431`: se envió Home y KEYCODE_SLEEP entre `2026-11-09T07:21:12.0445043Z` y `07:21:12.9182988Z`, con las cuatro cargas incompletas. El receptor tenía 266,524 bytes del archivo completo, 200,988 de parte 1 y 4,380 de cada parte restante.
- A las `07:21:29Z`, antes de reabrir, el receptor ya tenía 2,285,984 / 1,761,608 / 1,545,936 / 1,466,368 bytes respectivamente. Android reportó launcher como actividad resumida, MainActivity pausada, keyguard `showing=true`, pantalla `SCREEN_STATE_OFF` e interacción `INTERACTIVE_STATE_SLEEP`. Se demuestra avance remoto con app fuera de primer plano y pantalla apagada.
- Los workers ejecutados son `com.bbflight.background_downloader.UploadTaskWorker`, con servicio foreground nativo; “foreground” en estos logs describe el servicio Android con notificación, no una pantalla abierta de la app.
- Evidencia local ignorada: `probe-native-runtime.log`, `build/native-background-transition.json`, `build/native-background-progress.json` y `build/native-background-completed.json`. Tras recoger el resultado se ordenó cierre del AVD aislado y de receptor/logcat; comprobar el estado real antes de reabrir cualquier recurso.
- El total previo de 16 tests se amplió con 1 test dirigido del receptor en vuelo aprobado: 17 casos distintos previos a Android. Las dos ejecuciones del mismo caso Android cuentan como un caso adicional, no como ocho tests nuevos por sus cuatro requests.

### Historial de preparación del 8 de noviembre

- Inicio de continuación: 8 de noviembre de 2026, aproximadamente 19:12 America/Mexico_City.
- Estado verificado de partida: ADB sin dispositivos, ningún handle previo pendiente; cambios preexistentes de ambos repositorios preservados.
- Se inició AVD `Pixel_8` (Android 36 x86_64, 2 GiB RAM) mediante arranque en frío sin wipe ni carga/guardado de snapshot. Launcher PID `22476`, proceso QEMU observado `25036`, inicio 19:12:53.
- A las 19:17 el proceso seguía vivo, console reportaba `virtual device is running`, ADB aún `offline`. CPU acumulada aumentó de 24 a 124 segundos. No declarar boot fallido sólo por la demora; verificar `sys.boot_completed` antes de instalar/ejecutar.
- Receptor independiente iniciado en host `127.0.0.1:8787`, demora de 150 ms por chunk, handle `59353`. Para el emulador se usa `http://10.0.2.2:8787`.
- Cambio propio móvil: `lib/probe_receiver.dart` del probe agrega inicio de transferencia, bytes en curso y listado `active` en `/report`. Limpia el estado activo incluso al desconectarse un request. Es instrumentación para comparar progreso remoto con app pausada; no modifica Pícale.
- Build debug x64 del target `integration_test/native_contract_test.dart` en curso, handle `75044`, log `tool/upload_probe/probe-build-native-debug.log`. No repetir el build ni tratarlo como terminado sin recoger resultado.
- Siguiente acción: esperar boot utilizable y resultado de build; instalar sólo el paquete `com.rodtech.picale_upload_probe`, conceder notificaciones en el emulador y ejecutar harness nativo. Capturar recepción desde host con app Home/bloqueada antes de reabrir.
- No hubo nuevo tráfico R2. La verificación móvil física/iOS permanece pendiente.
- Para comandos de diagnóstico usar `login:false` cuando sea apropiado: algunos shells con inicialización demoraron y retornaron handles sin salida inmediata. Imprimir el objeto completo de exec, no sólo `.output`, para conservar identificadores de procesos activos.

### Diagnóstico y correcciones de entorno (19:28)

- Build debug `75044` terminó con exit 1: daemon Gradle desaparecido. El archivo local ignorado `tool/upload_probe/android/hs_err_pid23616.log` acredita **Out of Memory Error / fallo de malloc nativo**, no un fallo Dart/Kotlin. Host informa 15 GiB de RAM; el scaffold reservaba heap máximo 8 GiB y metaspace 4 GiB.
- Se ajusta sólo `tool/upload_probe/android/gradle.properties`: heap 2 GiB, metaspace 768 MiB, code cache 256 MiB, dos workers y compilador Kotlin en proceso. No se cambian preferencias globales ni se detienen procesos Java del usuario.
- El AVD anterior `25036` se cerró mediante consola. Se inicia variante diagnóstica sin Vulkan, con SwiftShader indirecto y `-show-kernel`; launcher `11664`, QEMU `13440`, inicio 19:24:58. Es una prueba de configuración ante warning gráfico; no se afirma que Vulkan sea la causa.
- El nuevo kernel avanzó de inicialización a servicios Android (`init`, apexd, módulos vendor); a las 19:28 ADB seguía offline. Esperar estado utilizable; no confundir demora con terminación del proceso.
- Fuentes oficiales consultadas para flags: https://developer.android.com/studio/run/emulator-commandline y https://developer.android.com/studio/run/emulator-troubleshooting.
- Seis tests de contrato local volvieron a pasar tras instrumentar el receptor (cambio real que justifica repetición): `probe-tests-receiver-instrumentation.log`, exit 0. No se repitió R2 ni las suites Windows ya aprobadas.
- Se preguntó al usuario si puede conectar un Android por USB; respuesta pendiente. Se continúa trabajo independiente mientras tanto.

### Aislamiento del AVD por fallo comprobado (19:31)

- La captura kernel de la variante diagnóstica mostró una causa concreta: `verity file corrupted; can't find descriptor` en dm-55 durante `odrefresh`, seguida de `Kernel panic - not syncing` y otro arranque del kernel. Evidencia ignorada `build/emulator-native-no-vulkan.log`, alrededor de líneas 1866–1923. No era sólo un timeout de ADB.
- Se cerró la instancia diagnóstica de `Pixel_8`; no se hizo wipe, reparación de filesystem ni eliminación de sus datos.
- Se creó AVD independiente `PicaleUploadProbe` usando la imagen Android 36 ya instalada. `ANDROID_AVD_HOME` y su carpeta apuntan exclusivamente a `tool/upload_probe/build/avd`, ignorada por Git. Creación mediante avdmanager exit 0, evidencia `build/avd-create.log`.
- Launcher del nuevo AVD `460`, inicio 19:31:40, puerto 5560 (serial previsto `emulator-5560`), dos núcleos y 2048 MiB; sin snapshot, sin Vulkan, renderer SwiftShader indirecto y kernel log habilitado. Evidencia `build/emulator-isolated.log` y `build/emulator-isolated-error.log`.
- Build debug con memoria reducida sigue en curso: handle `62320`, `probe-build-native-debug-low-memory.log`; el handle anterior `75044` ya terminó con error de memoria. Receptor `59353` sigue activo. No reiniciar estos procesos por demoras de observación.

### Checkpoint de build y espera de Android (19:33)

- Build debug reducido `62320` terminó con **exit 0** en 225.4 s. Target `integration_test/native_contract_test.dart`, Android x64. APK `build/app/outputs/flutter-apk/app-debug.apk`, 69,343,865 bytes, generado a las 19:32:13. No repetir el build mientras este código siga siendo el mismo.
- AVD aislado: QEMU `33560`, launcher `460`, serial `emulator-5560`. Kernel muestra montaje limpio de metadata y creación de particiones lógicas; no se observó el panic anterior. Boot utilizable aún pendiente.
- Espera explícita SDK `adb -s emulator-5560 wait-for-device` en handle `64047`. Recoger ese handle y luego comprobar `sys.boot_completed`; no relanzar el AVD si sólo expira una observación.
- Receptor `59353` confirmado escuchando en `127.0.0.1:8787` y `/report` responde con listas vacías de observaciones/active antes de la prueba. Su proceso Dart observado fue `31008`.
- Android del usuario solicitado por la herramienta de preguntas; sin respuesta todavía. Esta espera no acredita la matriz física ni desbloquea iOS.

### Checkpoint de instrumentación y arranque avanzado (19:44)

- Nuevo test dirigido `receiver reports in-flight bytes before accepting a complete part`: **1 aprobado**, exit 0, `probe-receiver-inflight-test.log`. Comprueba que `/report.active` ve bytes mientras la parte sigue incompleta, que no hay observación de éxito antes del cierre y que active desaparece al completarse.
- Análisis final `83005`: sin incidencias, exit 0, `probe-native-preflight-analyze.log`. Se corrigió un lint de llaves en el nuevo test; no cambió su comportamiento ni se repitieron suites ya aprobadas.
- Receptor anterior `59353` detenido para cargar el código final; receptor actual `70054` en puerto 8787 con 500 ms por chunk. Confirmó `/report` con cero cargas antes de la prueba.
- El AVD aislado alcanzó ADB utilizable a las 19:39:51; `64047` terminó exit 0, pero `sys.boot_completed` aún estaba vacío. A las 19:42:59 el servicio package estaba presente y luego activity también.
- Instalación anticipada del APK debug fue rechazada con `Error: device is still booting`, exit 1; no se acredita ejecución de la app. No repetir instalación hasta que Android publique `sys.boot_completed=1`.
- Monitor activo de boot/package: handle `13581`, iniciado 19:42:26; sigue el PID `33560` y serial `emulator-5560`. Recogerlo, no relanzar el AVD por una observación vencida.
- APK debug x64 SHA-256: `47A69E91DED947FE3715FC53E717800415320911F73E04E399CC964A85556D6A`.
- Ninguna carga nativa ha comenzado todavía. Los resultados de esta unidad no se confunden con background Android ya verificado.

## Checkpoint Fase 1: prueba aislada creada

- Proyecto independiente `../metricol_app/tool/upload_probe`, generado con Flutter para Android/iOS/Windows. No modifica el pubspec ni runtime de la app principal.
- Dependencia exacta `background_downloader: 9.5.9`, instalada mediante `flutter pub get` en el proyecto de prueba.
- La versión publicada más reciente observada, 9.6.6, exige Dart ^3.13.0 y Flutter >=3.47.0; no es compatible con el SDK 3.41.6/3.11.4 instalado. La 9.5.9 exige Dart ^3.7.0 y Flutter >=3.29.0.
- Código/documentación consultados directamente del archivo publicado en caché Pub: `C:/Users/bicos/AppData/Local/Pub/Cache/hosted/pub.dev/background_downloader-9.5.9`.
- Licencia BSD de tres cláusulas, con atribuciones adicionales de localstore. Revisar inventario de licencias al integrar.
- Podspec y Package.swift de 9.5.9 exigen iOS 14.0. Se ajustó sólo el proyecto de prueba a 14.0; la app principal permanece en 13.0.
- UploadTask impone `allowPause: false`. Usar multipart para recuperación, nunca asumir pausa de un PUT.
- Código Swift de rango crea archivo temporal y lee/descarta el prefijo antes de copiar la parte. Requiere medir disco y costo de preparar muchas partes; no está optimizado como versiones posteriores.
- Tanto Android como desktop agregan Content-Type a binarios; verificar compatibilidad real con firmas R2 de partes antes de adoptar. El receptor local verifica application/octet-stream.
- Prueba incluye fixture sintética de 12 MiB + 37 bytes, tres partes (5 MiB, 5 MiB y cola), receptor estricto, tests y app ejecutable con registros de tareas.
- Primera ejecución de tests encontró error del runtime Flutter Windows durante setUpAll (`Callbacks into the Dart VM are currently prohibited`) y no progresó. Se detuvo esa ejecución y se instrumentó con hitos para localizar la causa; no se consideró aprobada.
- Pendientes inmediatos: resolver/identificar fallo del harness, ejecutar pruebas, compilar release Android, documentar protocolo físico/R2. No iniciar Fase 2.

### Checkpoint Fase 1: contrato local y prueba real autorizada

- El usuario autorizó usar archivos locales de Downloads y limpiarlos del bucket después. Se eligió el video existente `../media-prueba/reel-vertical-30s.mp4`, de 15,504,484 bytes; original sin modificar.
- Se agregó `tools/SubidasR2Probe.java` en API: helper invocado explícitamente, no servicio de producción. Abre una corrida con prefijo UUID, firma URLs, verifica partes/hash completo y limpia únicamente dos keys de esa corrida.
- Corrida R2 abierta: `84aca06f-f5b6-4f5e-9b73-16b6b1575969`; prefijo `pruebas/subidas-background/84aca06f-f5b6-4f5e-9b73-16b6b1575969/`. Manifest sensible local: `../metricol_app/tool/upload_probe/build/r2-probe-manifest.json` (ignorado); no imprimir su contenido.
- Orquestación de R2 deja `cleanup` en finally incluso si el test falla. Revisar resultado terminal y evidencia de limpieza antes de cerrar la unidad. No atribuir éxito a la mera apertura de multipart.
- Seis tests de contrato local aprobados después de corregir el harness. Evidencia: `../metricol_app/tool/upload_probe/probe-tests.log`. Transporte desktop real y receptor estricto; persistencia de unit test en memoria explícita.
- Hallazgo: el worker desktop elimina `Range` de la tarea devuelta. Recuperación se reconstruye desde metadatos JSON (`runId`, `partNumber`) y conserva ID. No usar headers retornados como manifiesto de reanudación.
- Hallazgo: HTTP 403 puede venir en `TaskHttpException.httpResponseCode` con `responseStatusCode` nulo. El adaptador del probe contempla ambos.
- Primer harness Windows con disco real ejecutó transferencias pero detectó registros viejos al repetir IDs. Se corrige borrando sólo el registro de la tarea del test antes de reenviarla; falta confirmar la nueva corrida.
- Primera compilación Android falló porque `GeneratedPluginRegistrant` incluía integration_test sin enlazarlo en release. Se declara el plugin como dependencia del proyecto de prueba y se recompila; no se cambian dependencias de Pícale.
- Se escribió protocolo reproducible en `../metricol_app/tool/upload_probe/README.md`, incluidas pruebas físicas y R2, límites y comandos de recuperación.

### Checkpoint Fase 1: R2 real verificado y limpio

- Tres tests R2 ejecutados mediante FileDownloader real en Windows: PUT completo 200, firma real vencida 403 y tres rangos aceptados por multipart. `probe-r2-tests.log`: **3 aprobados**.
- Helper `verify`: tamaños individuales correctos, unión exitosa, SHA-256 completo de ambos objetos igual al video local. Hash `8743f28366fbb11b9ff7f5ed42c2b576357d91cbce12d9d6c1a2caa0f9987c68`.
- Helper `cleanup`: ambos objetos ausentes y cero multipart bajo el prefijo exacto de la corrida. Orquestación terminó con código 0; el manifest quedó `cleaned=true` y sin URLs bearer.
- Evidencia API: `target/subidas-phase1-r2-open.log`, `target/subidas-phase1-r2-verify.log`, `target/subidas-phase1-r2-cleanup.log`. No repetir tráfico R2 sin un motivo nuevo.
- Fuente local intacta y hash comprobado después. No se modificaron cuota ni entidades de Pícale; el helper prueba transporte directamente al bucket.
- Suite corregida de integración desktop con almacenamiento por defecto en disco: **6 aprobadas** en `probe-windows-integration.log`. Lectura de registro desde otro proceso aún en ejecución como segunda parte del handle de tests.
- Análisis estático final del proyecto de prueba: sin incidencias, `probe-analyze.log`.
- AVD Pixel_8 disponible: se intentó arranque headless; snapshot apareció offline en ADB. Se probó reconexión y arranque en frío sin wipe. Se cerró el emulador mediante `adb emu kill`; no se ejecutó suite nativa ni se acredita Android físico.
- `integration_test/native_contract_test.dart` quedó preparado para un receptor independiente; no ejecutado todavía.
- Receptor autónomo `dart run bin/receiver.dart 8787 0`: inició y respondió `/report` correctamente; se detuvo después de esa comprobación.

## Unidad realizada: inventario y herramientas

### Cierre de unidad Fase 1 (19:10, 8 de noviembre)

| Verificación | Resultado actual | Evidencia local |
|---|---|---|
| Contrato HTTP local con motor desktop y storage de test | 6 aprobadas | Probe: `probe-tests.log` |
| Contrato desktop con motor Flutter y disco por defecto | 6 aprobadas | Probe: `probe-windows-integration.log` |
| Lectura desde otro proceso y reconstrucción del rango | 1 aprobada | Probe: `probe-persistence-read.log` |
| Transporte real R2 y firma vencida | 3 aprobadas | Probe: `probe-r2-tests.log` |
| Partes, unión y SHA-256 de objetos reales | Verificado | API: `target/subidas-phase1-r2-verify.log` |
| Limpieza R2 de la corrida | Verificada | API: `target/subidas-phase1-r2-cleanup.log` |
| Análisis estático del probe | Sin incidencias | Probe: `probe-analyze.log` |
| Android release con R8 y shrinkResources | Exit 0; APK generado | Probe: `probe-build-android.log` y mapping R8 |
| Helper Java actualizado | Compila, exit 0 | API: `target/subidas-phase1-helper-compile.log` y clase en `target/subidas-probe-classes/` |
| Android nativo en ejecución/fondo | Pendiente | Harness `integration_test/native_contract_test.dart` preparado, no ejecutado |
| iOS release y iPhone físico | Pendiente | Sin Mac/Xcode/iPhone disponible confirmado |

Total de esta unidad: **16 tests aprobados**, además de la línea base previa. No equivalen a 16 escenarios móviles físicos.

APK de prueba: `../metricol_app/tool/upload_probe/build/app/outputs/flutter-apk/app-release.apk`, 50,441,950 bytes; SHA-256 `3E4A7F71BA192AA92D198A136C19909DAC303F3863918BC36B87C741434693F8`.
Inspección con aapt: paquete `com.rodtech.picale_upload_probe`, versión `0.1.0+1`, minSdk 24, target/compile SDK 36; sin marca debuggable. Mapping R8 presente (11,039,552 bytes). Firma debug intencional en app de prueba; no es build publicable de Pícale.

La lectura entre procesos comprobó disco y metadatos, no supervivencia de un upload nativo al cierre del sistema. La prueba real R2 usó desktop y un helper que completa el objeto; no acredita el futuro worker autónomo de la API.

Todos los handles de esta unidad tienen resultado terminal recogido: Android `25740` exit 0, integración/lectura `51951` exit 0, helper `64833` exit 0 y R2 `56913` exit 0. No repetirlos ni intentar tratarlos como procesos vivos. Receptor/emulador de prueba cerrados; ADB vacío al cierre.

Se leyó la especificación `docs/goal-subidas-moviles-segundo-plano.md`. No se encontraron `AGENTS.md` en los ancestros comprobados (`C:/`, `C:/Users`, usuario, Downloads, raíz compartida) ni dentro de API/móvil mediante `rg --files -g AGENTS.md`.

### Puntos de integración móvil

| Punto | Archivo y método | Comportamiento observado / acción futura |
|---|---|---|
| Contenido: galería mixta | `lib/pages/contenido/contenido_page.dart`, selección alrededor de línea 288 | `_cola.agregar`; motor secuencial en memoria |
| Cola de Contenido | `lib/services/cola_de_subidas.dart`, `_subir` | Llama `media.preparar` y `media.enviar`; no pasa `batchId`/índice; genera clave local sin persistencia |
| Recuperar selector Android | `lib/widgets/subir_contenido.dart`, `recuperarContenidoInterrumpido` | Pregunta por negocio abierto y usa diálogo compartido; no recupera transferencias |
| Selección para asistente | `lib/widgets/mandar_al_asistente.dart`, `MandarAlAsistente` | Filtra selección (20 archivos/5 videos) y usa `subirContenido` |
| Diálogo compartido | `lib/widgets/subir_contenido.dart` | Reserva la tanda completa con UUID e índices antes de transferir; estado en memoria, cancelación al destruir widget |
| Crear contenido | `lib/pages/crear/crear_page.dart`, `_subir` | `media.upload` secuencial; luego `_empezar(ids)` depende del resultado; conservar intención posterior sin repetirla |
| Captura optimista | `lib/bloc/capture/capture_bloc.dart`, `_subirEnSegundoPlano` | `media.upload` no esperado; al cerrar BLoC deja de registrar el resultado en estado |
| Captura al publicar | Mismo BLoC, alrededor de línea 616 | Reutiliza URL si está disponible; de lo contrario otro `upload`; auditar posible solapamiento con carga optimista |
| Foto de negocio al registrarse | `lib/pages/auth/register_page.dart`, `uploadXFile` | Foto pequeña, resultado necesario para registro; mantener camino actual inicialmente |
| Foto de referencia de imagen IA | `lib/pages/imagenes/asistente_imagenes_page.dart`, `uploadXFile` | Imagen de referencia, no carga general de videos; mantener inicialmente y documentar excepción |
| Transporte común | `lib/services/media_service.dart` | Presign + PUT Dart streaming + confirm, renovación simple y reintentos de confirm; también descarga/recorte ajenos al reemplazo |
| Sesión | `lib/services/session_service.dart` | Token en preferencias y `ApiHelper`; `vaciar` en logout/cambio workspace; adaptar para conservar trabajos y detener tareas según política |
| Capacidad backend | `lib/models/storage_usage.dart` | Aún no lee `multipartFromBytes` |
| Composición de servicios | `lib/services/services.dart` | Construye `MediaService(api)`; punto para inyectar adaptador en etapas futuras |

Las referencias a líneas son orientativas. Revalidar con búsquedas antes de editar.

### Invariantes y riesgos comprobados API

- `MediaController` exige `POST_CREATE` en presign, renovar, confirm y multipart.
- `TenantIdentifierResolver` usa workspace del principal o tenant impuesto por worker. Imponer tenant antes de abrir transacción.
- `MediaService.presign` e `iniciarPorPartes` validan MIME, tope diario, repetidos y cuota antes de crear el `PENDING`; conservan lote/índice/huella.
- `MediaPresignRequest` no tiene todavía clave de idempotencia de apertura ni datos de sesión recuperable.
- Dedupe: huella cuando existe; fallback por tamaño/tipo para datos antiguos, con opción explícita `aunqueRepetido`.
- La huella Flutter es SHA-256 del tamaño y muestras de inicio/medio/final (hasta 1 MiB por posición), no hash completo del video.
- Multipart: firma hasta 50 partes por petición; parte base 16 MiB; umbral local 100 MiB; modo por defecto `APAGADA`. Son defaults de código, no estado de producción.
- `R2StorageService.partesSubidas` ya pagina; convierte `NoSuchUpload` en lista vacía. Revisar esta ambigüedad al recuperar un objeto ya unido.
- `completarPorPartes` verifica faltantes y suma, pero filtra partes mayores al total; falta validar conjunto exacto y tamaños individuales como exige el plan.
- No se observó lease compartido entre unión, cancelación y limpieza.
- El rescate actual llama `confirm`, no une multipart que sólo tiene partes en R2.
- Limpieza usa antigüedad `coalesce(upload_renewed_at, created_at)`; el reconciliador puede abortar multipart sin objeto final. Coordinar antes de activar el motor.
- El agente espera hermanos del lote mediante `activeUploadsInBatch(..., ahora.minusHours(1))`. Una sesión de recuperación mayor necesita revisar esa ventana y preservar lote desde móvil.
- Consultas de sesión terminal y expiración explícita siguen pendientes.

### Herramientas observadas

| Componente | Evidencia local |
|---|---|
| Flutter | 3.41.6 stable, revisión `db50e20168` |
| Dart | 3.11.4 |
| App | `1.0.13+14`; HEAD móvil `74d8aa6` |
| Kotlin / Android Gradle Plugin | 2.2.20 / 8.11.1 |
| Gradle wrapper | 8.14 |
| Android SDK | SDK local con plataformas hasta android-36 |
| Valores Flutter SDK de Android | minSdk 24; compileSdk y targetSdk 36, heredados por app |
| iOS mínimo declarado | `IPHONEOS_DEPLOYMENT_TARGET = 13.0` en proyecto; contrastar con versión del paquete antes de elevarlo |
| Java por PATH | JDK 11; no sirve para compilar este proyecto API Java 17 |
| Java alterno API | `C:/Program Files/Java/jdk-17`, Java 17.0.12 |
| Java Android disponible | Android Studio JBR 21.0.6 |
| Maven | Ejecutable `C:/apache-maven-3.9.10/bin/mvn.cmd` |
| Dispositivos | `flutter devices --machine`: Windows, Chrome, Edge. `adb devices -l`: sin Android conectado |
| Mac/iPhone | No hay Xcode local en Windows ni evidencia de Mac/iPhone remoto; disponibilidad externa por confirmar |

### Prueba técnica definida para Parte 2

1. Consultar versión publicada concreta de `background_downloader`, requisitos y documentación de esa versión. No instalar todavía en esta etapa.
2. Crear prueba aislada con archivo determinista y receptor HTTP local que compruebe método PUT, bytes, límites de rango y encabezados, sin JWT.
3. Probar primera/intermedia/última parte y archivo simple, además de 403 controlado y recuperación con nueva URL.
4. Verificar firma de R2 usando cuenta y bucket de prueba identificados explícitamente. No reutilizar silenciosamente `.env` local: la documentación indica que apunta a almacenamiento de producción.
5. Prefijo propuesto, no creado: `pruebas/subidas-background/<runId>/`; cuenta/workspace aislado: pendiente de designación antes de prueba externa.
6. Compilar Android release local y ejecutar en teléfono cuando esté conectado; medir fondo, bloqueo, proceso muerto, duración y recuperación.
7. Compilar iOS en Mac y ejecutar prueba equivalente en iPhone; mínimo iOS y espacio temporal son puertas de decisión.
8. Registrar aprobación, rechazo o viabilidad parcial con evidencia; no integrar el flujo completo si falta demostrar lo crítico de ambas plataformas.

## Verificaciones

### Móvil

Comando ejecutado desde `metricol_app`:

```powershell
flutter test --no-pub test/media_upload_test.dart test/cola_de_subidas_test.dart test/subir_contenido_test.dart test/mandar_al_asistente_test.dart test/contenido_campana_test.dart --reporter expanded
```

Resultado: 17 pruebas aprobadas, código de salida 0. Evidencia local: `../metricol_app/mobile-upload-phase0-baseline.log` (ignorado por Git).

Cobertura de línea base: renovar misma reserva, reintentar confirmación sin repetir PUT, cancelación, cambio de sesión/workspace, vacío, dedupe local, lote mixto del diálogo, límites del selector y tarjeta de Contenido. No demuestra persistencia ni ejecución nativa.

Análisis estático de siete archivos del flujo: aprobado, `No issues found`, código de salida 0. Evidencia: `../metricol_app/mobile-upload-phase0-analyze.log`.

`flutter doctor -v`: terminó con advertencia de licencias Android pendientes. No se aceptaron licencias en nombre del usuario. SDK 36/build-tools 36.0.0 y JBR 21 disponibles; no se compiló release en esta fase. Evidencia: `../metricol_app/mobile-upload-phase0-doctor.log`.

### API

Resultado terminal: **33 pruebas aprobadas, 0 fallos, 0 errores, 0 omitidas; BUILD SUCCESS**, código de salida 0.

| Suite | Pruebas |
|---|---:|
| `SubidaPorPartesTest` | 17 |
| `MediaTopeDiarioTest` | 5 |
| `OrganizadorDeContenidoTest` | 8 |
| `AvisoDeRepetidoTest` | 2 |
| `RescateDeSubidasTest` | 1 |

Se impuso JDK 17, H2 `subidas_phase0`, `create-drop` sólo sobre esa base en memoria y una importación de configuración inexistente opcional para evitar cargar `.env`. Se dejaron vacías las llaves de integraciones en propiedades de la ejecución. Los tests de almacenamiento usan mocks. No se ejecutó la API contra base ni bucket productivos.

Evidencia: `target/subidas-phase0-baseline.log` y reportes correspondientes en `target/surefire-reports`. La ejecución finalizó el 8 de noviembre de 2026 a las 18:33:39 (America/Mexico_City). Los reportes Surefire pueden sobrescribirse en ejecuciones posteriores; el log conserva el resumen de esta corrida.

Comando reproducible desde API (no repetir sin cambios o necesidad de investigar):

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
mvn '-Dtest=SubidaPorPartesTest,MediaTopeDiarioTest,OrganizadorDeContenidoTest,AvisoDeRepetidoTest,RescateDeSubidasTest' '-Dspring.config.import=optional:file:./target/subidas-phase0-no-env.properties' '-Dspring.datasource.url=jdbc:h2:mem:subidas_phase0;DB_CLOSE_DELAY=-1' '-Dspring.datasource.username=sa' '-Dspring.datasource.password=' '-Dspring.datasource.driver-class-name=org.h2.Driver' '-Dspring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect' '-Dspring.jpa.hibernate.ddl-auto=create-drop' '-Dopenai.api-key=' '-Duploadpost.api-key=' '-Dapp.stripe.secret-key=' '-Dapp.demo.email=' '-Dapp.comentarios.enabled=false' '-Dapp.metricas.enabled=false' test 2>&1 | Tee-Object -FilePath target/subidas-phase0-baseline.log | Out-Null
$LASTEXITCODE
```

Antes de reutilizarlo, asegurar que el archivo opcional de importación indicado siga sin existir y que no haya variables de entorno con integraciones reales que alteren el aislamiento. El `JAVA_HOME` anterior aplica al proceso del comando, sin modificar configuración global.

## Cambios propios por repositorio

API: `docs/subidas-moviles-avance.md`, `docs/subidas-moviles-decisiones.md` y, en Fase 1, `tools/SubidasR2Probe.java`. La especificación ya existía al iniciar. No se modificaron servicios, entidades ni endpoints de producción. Logs y reportes son artefactos locales ignorados.

Móvil: proyecto aislado `tool/upload_probe/` con scaffolding Flutter Android/iOS/Windows, pubspec/lockfile propios, app, fixture, receptor, tests, suites de integración y README. No se modificaron fuentes/dependencias de la app principal por este goal. Artefactos de build y logs del probe ignorados por su `.gitignore`.

### Cambios preexistentes que se preservan

API modificados: `Dockerfile`, archivos de catálogo `LICENSE.txt`, `README.md`, `catalogo.json`, `taxonomia.json`, `ReelMusicCatalog.java`, `ReelMusicCatalogTest.java`.

API no rastreados: evidencia HTML de Kaino del 31 de octubre, especificación de este goal, presentación HTML ROI y propuesta comercial Markdown. No atribuirlos a esta etapa.

Móvil modificados: `lib/pages/account/perfil_page.dart`, `lib/pages/contenido/contenido_page.dart`, `lib/router/routes.dart`, `lib/services/api_helper.dart`, `lib/services/services.dart`, `pubspec.yaml`.

Móvil no rastreados: `lib/pages/account/ajustes_videos_page.dart`, `lib/services/ajustes_videos_service.dart`, `lib/widgets/ajustes_videos.dart`, `test/ajustes_videos_test.dart`.

Estos archivos pueden coincidir con integraciones futuras; revisar sus diffs antes de editar, sin revertirlos.

## Pendientes y siguiente acción

- Línea base terminada: 50 pruebas seleccionadas aprobadas entre móvil/API y análisis de siete archivos sin incidencias.
- Fase 1 en curso: candidato exacto 9.5.9 fijado en proyecto aislado. Se verificaron 8 casos de contrato local, 6 de integración Windows/disco, 1 de persistencia entre procesos, 3 de transporte R2 real con SHA-256/limpieza y 4 casos Android: 22, además de diagnósticos separados.
- Siguiente acción exacta: unidad Android anterior a14 descrita en checkpoint actual. Recursos Android y corte causal ya pasaron con límites documentados; no repetirlos. iOS/físicos y recursos iOS pendientes. No avanzar Fase 2.
- No iniciar Fase 2: hay cuatro casos Android aprobados, pero recursos, Android anterior a 14 y gates físicos/iOS pendientes. El corte causal confirma reemisión ante éxito no persistido. Build Android y lectura entre procesos Windows ya están aprobados.
- Android doctor había reportado licencias pendientes; no se aceptaron términos. La compilación efectuada debe informar si eso es impedimento real.
- R2 ya no es bloqueo: autorización recibida, prueba real aprobada y objetos/multipart de la corrida eliminados y verificados. No repetirla al reanudar salvo cambio de transporte que lo justifique.
- La app productiva conserva su motor Dart actual. Sesiones nuevas, idempotencia y worker de finalización son etapas posteriores todavía no implementadas.

### Historial de procesos locales (ya terminados)

Observación intermedia a las 19:08 y resultados terminales recogidos aproximadamente a las 19:10, America/Mexico_City.

| Handle exec | Trabajo | Fuente de evidencia |
|---|---|---|
| `25740` | Release APK del probe, terminó exit 0 | `../metricol_app/tool/upload_probe/probe-build-android.log` |
| `51951` | Suite de contrato y lectura desde proceso nuevo, ambas aprobadas; exit 0 | `probe-windows-integration.log` y `probe-persistence-read.log` dentro del probe |
| `64833` | Helper Java actualizado, terminó exit 0 | `target/subidas-phase1-helper-compile.log` y `target/subidas-probe-classes/SubidasR2Probe.class` |

Los resultados ya fueron obtenidos mediante `write_stdin` y registrados arriba; esta tabla conserva trazabilidad. La existencia de logs no demuestra actividad. Al iniciar nuevas unidades, registrar sus propios handles si quedan corriendo.

Gradle daemon observado: PID `23616`, con tareas Kotlin en ejecución al inspeccionar threads; evidencia diagnóstica ignorada `target/subidas-phase1-gradle-threads.log`. No detener otros procesos Java del usuario. Las primeras corridas fallidas quedan explicadas arriba; sólo una salida terminal exitosa del código actual acredita build.

No hay mutaciones remotas pendientes: la cadena R2 `56913` terminó con exit 0 y cleanup verificado. Receptor local detenido (`13928`), AVD detenido con `adb emu kill`; ADB volvió a mostrar lista vacía. No dejar ni reabrir emuladores sin necesidad de la prueba nativa.

### Prompt histórico para siguiente conversación (sustituido por D30)

```text
Reanuda Parte2/Fase1 del goal Pícale tras bloqueo por entornoMac. Primero incorpora acceso/reporteMac nuevo; sin él la ejecución Apple sigue pendiente. El conteo de bloqueo anterior es histórico: reinicia auditoría al reanudar, no bloqueo inmediato. Lee especificación, avance, decisionesD28/D29,
evaluación E14/E15 y tool/upload_probe/IOS_FASE1_PROTOCOL.md. Revisa AGENTS/gitstatus
API/Flutter; preserva cambios ajenos. Sin delegar, commit/push/deploy ni Fase2.
AndroidR2real native_r2_android1 ya pasó/limpió (UUID3e5231c5...); no repetir ni
canary7/desktop. D28autoriza iOS14 mínimo y candidato9.5.9 sujeto a pruebas;
no preguntar otra vez. Mainapp aún13, aplicar14 en etapa integración autorizada.
Unidad preparada: bin/ios_preflight.cjs, test/ios_preflight.test.cjs,
integration_test/ios_bootstrap_test.dart y protocolo.4testsfixture exit0,
pubgetoffline enforce-lockfile y analyze4.4s limpios; WindowsCLIgate2 exit2 esperado,
macOS-required, cero comandosApple y projectSpmDisabled=true. Pubspec PROBE fija
flutter.config.enable-swift-package-manager=false: global/proyecto preceden env,
no confiar sólo en FLUTTER_SWIFT_PACKAGE_MANAGER=false. No cambios de mainapp.
E15/preparation-validation son estáticos, NO build/runtime/transfers iOS.
Usuario confirmó sin accesoMac por ahora; no volver a preguntar esa disponibilidad. Incorporar
respuesta nueva si existe. No repetir pregunta ni asumir disponibilidad/ausencia.
Entorno actualWindows sin xcodebuild/xcrun, ningún handleMac vivo. Siguienteacción
requiere acceso real a Mac: node bin/ios_preflight.cjs ios_host_1 UDID_REAL,
seguir comandos bootstrapdebug/buildrelease/bootstraprelease del protocolo y
recoger artifacts antes de uninstall. Fluttertest3.41.6 fuerza debug, no --release.
Sólo luego implementar transporte/staging/auditoríaURLSession/canario iOS; esos
componentes todavía NO existen. allTasks mezcla holdingQueue/URLSession y no
prueba handoff. No simular platform ni acumular Swift sin compilar por bloqueo.
Mantener mínimo14 autorizado, comprobar minOS/frameworks reales y runtime14 aparte.
Sin debugger/iPhone para background; forcequit distinto de terminación por sistema.
Conservar cuota/workspace/lotes/dedupe/API-finalización como requisitos posteriores,
no capacidades del probe. No avanzar Fase2 por faltarMac. Si no hay acceso nuevo,
registrar dependencia real y seguir reglas de auditoría de bloqueo del goal;
no repetir tests/docs sólo para aparentar progreso. Usuario sinUSBAndroid, noinsistir.
Goal/Fase1 parciales; no publicar, tocar8787/Pixel_8/DBbase ni generar nuevospresigns
para redactar protocolo. Usa autorización previa sólo al ejecutar futura corrida
real trazable con stop nativo antes de cleanupUUID. Guarda checkpoint preciso.
```
