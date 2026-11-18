# Coordinador multipart Android — D49

Revisión posterior: ver sección **D49-R1** al final. La implementación inicial
y sus465 pruebas se conservan como historial; las correcciones y la evidencia
vigentes se registran en `target/subidas-d49-review/report.json`.

13 de noviembre de 2026, America/Mexico_City. Autorizado por «Okis va ayudame
hacerlo» después de proponer unir registro, API y motor nativo. Se implementa el
coordinador y su entrada Android opt-in. **La selección/cola/pantalla principal
todavía no lo invocan y la capacidad productiva sigue apagada.** Esta entrega no
equivale a una prueba de Android real ni a un APK nuevo.

## Componentes

`metricol_app/lib/services/coordinador_de_subidas_multipart.dart` conecta:

- Registro v4, con inserción y modificaciones serializadas, sin borrar intentos.
- Cliente API v2: recuperar/abrir, ListParts, firmar, complete y cancelación.
- Adaptador `MotorNativoDeSubidas` real, con un grupo multipart independiente.

`services.dart` exporta el coordinador. La entrada
`CoordinadorDeSubidasMultipart.crearAndroid(cliente:, capacidades:)` comprueba
Android, crea la carpeta privada `subidas-multipart` dentro de ApplicationSupport,
lee el registro v4 de esa carpeta y arranca el grupo `picale-partes`. La ruta
actual conserva carpeta `subidas`, registro3 y grupo `picale-subidas`.

No deben compartir ni escribir el mismo registro dos propietarios. La separación
impide que el motor simple interprete tareas multipart o que un lector3 intente
modificar el manifiesto4. No sustituye un futuro protocolo de migración de trabajos
ya admitidos por la cola simple.

## Uso del componente

1. Preparar y validar la copia privada inmutable **en la carpeta del coordinador**.
   Conservar usuario, negocio, clave, lote, MIME, tamaño, huella y SHA256 local.
2. `registrar(trabajo)` comprueba capacidad móvil y umbral, propietario, copia y
   SHA256 en streaming. Inserta el trabajo sin sobrescribir selecciones existentes.
   No abre sesiones simples ni convierte un asset simple anterior.
3. `continuar(id)` recupera primero la misma clave. Si no existió y es una
   intención sin asset, guarda `verificando` antes de abrir con esa misma clave.
   Aperturas ya aceptadas se recuperan aunque se apague la capacidad de admisión.
4. Guardar asset y sesión juntos con `TrabajoDeSubida.conSesion`. OPENING espera;
   no emite permisos ni entrega partes. NO_EXISTE de una sesión conocida detiene
   sus tareas y conserva el registro sin abrir otra.
5. Consultar tareas nativas registradas y después las partes remotas. Una consulta
   fallida no significa ausencia. Validar archivo/grupo/metadatos/rango de tareas
   encontradas; no reasignar un taskId a otros bytes.
6. Pedir firmas en tandas de hasta50 partes faltantes. Antes de cada entrega,
   volver a comprobar contexto/plazo y guardar el intento con su ID determinista.
   La firma queda fuera del manifiesto. Un intento planificado/entregado ausente
   se recupera con el mismo ID; una generación terminal sólo se reemplaza después
   de otra consulta remota. Las tareas presentes no se duplican.
7. Si todas las partes están confirmadas, detener posibles tareas restantes,
   guardar `verificando`, pedir complete y exigir medio READY. El worker del
   servidor también puede completar sin Flutter. Los eventos nativos no declaran
   que un medio está listo.

`continuar` simultáneo por ID comparte un ciclo. Los eventos se serializan por
trabajo y no esperan otro ciclo dentro de su propia cola. Se conserva el historial
de generaciones para descartar eventos atrasados. El progreso publica bytes
confirmados y una estimación ponderada por tamaño; no suma dos veces una parte
que el servidor ya confirmó.

## Entrega por rangos

Cada `UploadTask` usa PUT binario, el mismo archivo privado y rango inclusivo
`bytes=inicio-finExclusivoMenosUno`. Su grupo es `picale-partes`, metaData identifica
el trabajo y el ID identifica parte/generación. No se agregan Authorization ni
cookies de la API. No se crean copias locales por parte. El motor compara también
Range/metaData al comprobar reutilización de IDs.

Se inspeccionó el código instalado **background_downloader9.5.9**:
`UploadTaskRunner.kt` aplica el rango con FileInputStream/LimitedInputStream y
Content-Length específico; `TaskRunner.kt` excluye Range y Content-Disposition
de la copia genérica de cabeceras. El runner binario agrega Content-Type según
mimeType; el candidato usa application/octet-stream y Content-Disposition vacío.
Por tanto no se afirma que la petición nativa carezca de Content-Type.

El soporte de rangos está documentado por el
[mantenedor del motor](https://github.com/781flyingdutchman/background_downloader/blob/main/CHANGELOG.md).
Los límites y tamaños uniformes, salvo la última parte, se describen en
[Cloudflare R2](https://developers.cloudflare.com/r2/objects/upload-objects/).
Estas referencias y la inspección estática **no prueban la petición efectiva del
SDK9.5.9 contra R2**: esa compatibilidad de bytes/cabeceras sigue pendiente del
canario nativo antes de habilitar la capacidad.

Los reintentos automáticos del plugin son0 para estas tareas. Al fallar una parte
se conserva el trabajo y se informa que necesita reintento. Los eventos completos
y la revisión periódica pueden consultar/finalizar, pero no generan un bucle de
reenvío de partes fallidas. El reintento explícito reconcilia y renueva permisos.

## Cancelación y cierre

`cancelar(id)` frena nuevos handoffs desde el instante de solicitud y persiste
`cancelando` antes de actuar. Debe comprobar que las tareas dejaron de estar
presentes; un ACK de cancelación no basta. Luego pide cancelación API y recupera
su estado, sin interpretar un ACK HTTP como CANCELLED. Un fallo conserva la
decisión, la sesión y la copia. Si READY ganó, conserva el medio listo y nunca
llama a eliminar un objeto final.

Una selección cancelada antes de intentar apertura termina localmente sin
reservar. Si hubo apertura incierta y NO_EXISTE, queda `cancelando` sin crear otro
ID ni liberar una reserva por conjetura. No hay todavía un reparador automático
de esa incertidumbre persistente. Los estados remotos EXPIRED/CANCELLED son
terminales; no se convierten automáticamente en otra sesión.

`cerrar()` impide operaciones nuevas, retira la revisión periódica y espera sólo
los ACK nativos pendientes y escrituras locales antes de desvincular eventos.
No espera a que termine la transferencia de bytes, no cancela uploads aceptados
y no borra copias o historial. Una petición HTTP de control pendiente no puede
entregar ni aplicar resultados después del cierre. Otro propietario debe esperar
ese cierre antes de tomar la carpeta/grupo.

## Revisión y validación

La primera ejecución reprodujo un bloqueo causado por devolver desde
whenComplete el mismo Future que se estaba limpiando de un mapa. Se corrigió con
callbacks de limpieza void. También se corrigió la sincronización de colas de
eventos en las pruebas: debían esperar tanto el adaptador como el dominio antes
de verificar o limpiar archivos. No se ocultaron los fallos aumentando timeouts.

Las pruebas usan disco temporal, ApiHelper simulado y **MotorNativoDeSubidas
real sobre PuertoDeSubidasFalso**. No usan FileDownloader/WorkManager en Android.
Cubren persistencia antes de handoff, fallo de escritura, ACK perdido antes y
después de aceptación, ausencia incierta, cambio de sesión, cancelación durante
firma/entrega, ACK sin retirada, aborto pendiente/fallido, READY ganador,
finalización, eventos viejos, rango incompatible, fuente alterada, flag apagado,
cierre, apertura perdida y progreso. Incluyen51 partes en tandas50+1 sobre una
única copia privada.

Resultado final: **465 pruebas de la app aprobadas**, incluidas29 nuevas del coordinador, sin fallos ni errores; exit0. Análisis de siete archivos con --fatal-infos sin incidencias, exit0. Siete hashes de fuentes coinciden antes/después de la corrida final. La evidencia y hashes del SDK inspeccionado están en `target/subidas-d49/report.json`. Los seis directorios temporales conocidos del intento anterior se limpiaron mediante borrado individual de JSON y directorios vacíos; la prueba final limpió sus propias carpetas.
No se modificaron API productiva, bucket, credenciales, dependencias o código
Kotlin. No se publicó app ni se construyó APK en esta unidad.

## Pendientes para entregar la función al usuario

- Conectar selección/preparación/cola y tarjetas de Contenido con esta entrada,
  sin migrar silenciosamente sesiones simples admitidas ni duplicar registros.
- Definir retención/purga sólo con estado remoto terminal y sin tareas activas.
  El coordinador conserva copias y registros; un manifiesto corrupto4 permanece
  protegido por el comportamiento conservador de D47.
- Ejecutar el canario Android real para rangos, cabeceras, foreground, pantalla
  bloqueada, proceso interrumpido, red y recuperación de URLs vencidas; comprobar
  bytes/SHA256 y limpiar sólo el prefijo de prueba autorizado.
- Construir/verificar el APK de la integración y habilitar capacidad móvil sólo
  tras validar. La bandera productiva sigue false. iOS requiere Mac y continúa
  fuera de este coordinador Android.

## D49-R1 — Segunda revisión del coordinador

Solicitud: «revisa que haya quedado por favor». La primera reproducción de esta
revisión ejecutó9 casos:8 fallaron por comportamientos incorrectos y un control
READY pasó. No fue un fallo de compilación ni una prueba contra Android/R2.

### Cancelación que sobrevive a errores e interrupciones

Integrar OPENING o EXPIRING podía cambiar un trabajo `cancelando` a
`verificando`. Si fallaba la escritura siguiente, al reiniciar se perdía la
decisión local y el coordinador podía volver a la ruta de envío. La intención
ahora permanece pegada a cualquier estado no terminal hasta que el servidor
responda READY/CANCELLED/EXPIRED. Se cubre con errores de disco y reapertura del
coordinador sobre el mismo registro.

Una comprobación adicional reprodujo que persistir una cancelación esperaba a
que terminara una petición HTTP del ciclo anterior. Se corrigió: la intención se
escribe primero mediante la cola corta de IO y después se espera la cola de
operaciones remotas. La protección se aplica a todas las modificaciones del
trabajo, no sólo a las respuestas de sesión. Se comprueba el registro en disco
mientras HTTP sigue retenido y también la recuperación de ese registro con un
coordinador nuevo antes de que regrese la respuesta vieja.

Al insertar ese commit anticipado, la inyección de fallo OPENING/EXPIRING se
ajustó al tercer commit de cancelación, para seguir probando la integración
remota y no detenerse en la nueva escritura previa.

### Eventos, reintentos y permisos

- Un evento fallido atrasado podía cambiar la fase del trabajo aunque su intento
  ya estuviera `enviado`. Los intentos terminales ya no aceptan esos eventos.
- Un reintento explícito que coincidiera con una consulta automática heredaba el
  modo «no reintentar» y quedaba sin ejecutar. Ahora se agrupa una vuelta explícita
  posterior, tanto si la consulta automática termina bien como si falla, sin
  duplicar generaciones por varios clics.
- Un permiso válido al iniciar la vuelta podía vencer durante la lectura o el
  commit previo. Se vuelve a comprobar justo antes de llamar al motor. Esto evita
  entregar una firma que ya se sabe vencida; no garantiza cuándo Android empezará
  una tarea que el sistema mantenga en cola.

### Propiedad y cierre

Se añadió exclusión entre coordinadores del mismo isolate mediante la ruta
canónica de la carpeta. Un segundo propietario no puede arrancar hasta que el
anterior libere sus entregas pendientes e IO local. Una inicialización fallida
libera la propiedad tras su limpieza. Sigue sin ser un candado entre procesos o
isolates: el contrato exige un único escritor Dart.

El cierre se comparte entre llamadas concurrentes y espera también inicialización
y lecturas locales. Un consumidor de progreso pausado ya no bloquea el cierre ni
retiene la propiedad. El stream termina para ese consumidor cuando se reanuda o
cancela. Los controles de sesión/cierre se repiten tras las esperas de cancelación
para impedir que arranquen nuevas consultas HTTP después de cerrar.

La revisión agregó14 pruebas específicas, con archivos de un byte para examinar
las intercalaciones sin depender de transferencias grandes. Incluye el fallo de
arranque y la liberación de propiedad, lectura pendiente durante cierre y un
reintento explícito después de error en una consulta automática. La suite general
conserva además el caso de51 partes de la implementación anterior. Los conteos,
logs y hashes finales están en `target/subidas-d49-review/report.json`.

La revisión no conecta el coordinador con la cola/pantalla principal, no cambia
capacidad móvil ni entrega un APK. Las pruebas siguen usando adaptador real con
puerto nativo simulado, API simulada y archivos temporales. Se mantienen las
validaciones físicas y de bucket pendientes descritas arriba.


### Resultado final D49-R1

**479 pruebas de la app y 14 pruebas de revisión aprobadas**, cero fallos/errores, exit0. Análisis de los dos archivos modificados con --fatal-infos sin incidencias. Ocho hashes de fuentes/pruebas seleccionadas se conservaron durante la corrida final. La corrida intermedia477 precede al último ajuste; la corrida final incluye persistencia anticipada de cancelación y todos sus casos. Reporte: target/subidas-d49-review/report.json. Sin cambios de código después de esa verificación.
