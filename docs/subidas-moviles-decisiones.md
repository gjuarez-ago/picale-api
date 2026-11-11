# Decisiones: subidas móviles persistentes

Fecha inicial: 8 de noviembre de 2026, America/Mexico_City.

## D01. Comenzar por Fase 0 al no existir checkpoint

- Problema: la instrucción pide continuar la etapa registrada, pero no existían archivos de avance o decisiones.
- Evidencia: búsquedas de `docs/subidas-moviles*` sin resultados al inicio; especificación presente y sin implementación de este goal.
- Decisión: ejecutar exclusivamente inspección y línea base (Parte 1 / Fase 0), crear continuidad y entregar la siguiente instrucción.
- Motivo: establecer estado real y respetar el límite de una etapa por conversación.
- Alternativa descartada: asumir que el plan previo equivale a implementación o empezar directamente la librería.
- Consecuencia: no cambiar motor/dependencias ni lógica de producción durante Fase 0.
- Reconsiderar cuando: el usuario indique la siguiente etapa o aparezca evidencia de un checkpoint posterior.

## D02. Mantener un gestor común con excepciones pequeñas explícitas

- Problema: hay varios caminos de subida con dependencias distintas sobre su resultado.
- Evidencia: Contenido usa cola; asistente usa diálogo; Crear espera IDs y arranca creación; captura mantiene URLs en BLoC; registro e imagen IA usan `uploadXFile`.
- Decisión: planificar migración del contenido general al gestor compartido; mantener inicialmente foto de registro y referencia IA como cargas pequeñas, documentadas.
- Motivo: cubrir videos sin cambiar innecesariamente flujos especiales y sin perder las acciones que continúan después del upload.
- Alternativa descartada: sustituir únicamente la cola de Contenido y dar por cubiertos todos los videos.
- Consecuencia: la futura integración debe revisar intención posterior de Crear, publicación de captura y recuperación de sus IDs/URLs.
- Reconsiderar cuando: inventario adicional encuentre video o requisitos de persistencia en una excepción.

## D03. No confundir huella de dedupe con integridad del objeto

- Problema: el nombre SHA-256 puede dar a entender que se comprueba todo el video.
- Evidencia: `huellaDeArchivo` calcula SHA-256 sobre tamaño y muestras de inicio, medio y final; cada muestra hasta 1 MiB.
- Decisión: conservar compatibilidad de esa huella para deduplicación; verificar partes/tamaños en API y describir por separado cualquier checksum completo futuro.
- Motivo: no invalidar dedupe existente ni afirmar integridad criptográfica no implementada.
- Alternativa descartada: llamar hash completo a la huella actual o al ETag multipart.
- Consecuencia: los tests de integridad deben comprobar bytes/rangos en la prueba técnica, además de tamaños remotos.
- Reconsiderar cuando: se requiera checksum de objeto y R2/SDK/cliente soporten un contrato verificable acordado.

## D04. El candidato nativo sigue sujeto a prueba y versión publicada

- Problema: elegir una librería por README no demuestra que sus uploads funcionen con R2 ni que sobrevivan todas las condiciones requeridas.
- Evidencia: no está instalada; el proyecto tiene Flutter 3.41.6/Dart 3.11.4, Kotlin 2.2.20, AGP 8.11.1 y mínimo iOS declarado 13.0.
- Decisión: `background_downloader` sigue como candidato de la especificación, sin versión seleccionada todavía. La selección se realiza en Fase 1 con documentación y prueba de versión publicada.
- Motivo: evitar depender de APIs de `main`, capacidades de descarga o elevar mínimo iOS sin evaluar impacto.
- Consecuencia: Fase 0 no modifica `pubspec.yaml`; no prometer pausa de PUT ni compatibilidad iOS todavía.
- Reconsiderar cuando: las pruebas de binario/rangos, releases y teléfonos revelen un impedimento concreto.

## D05. Línea base aislada del entorno local real

- Problema: la configuración importa `.env` y la documentación indica almacenamiento compartido con producción.
- Evidencia: `spring.config.import=optional:file:./.env[.properties]`; tests multipart advierten explícitamente no tocar el bucket real.
- Decisión: ejecutar baseline API con H2 efímero explícito, import opcional inexistente, JDK 17 y llaves de integraciones vacías; almacenamiento mockeado por los tests seleccionados.
- Motivo: comprobar regresiones locales sin mutar servicios externos.
- Alternativa descartada: arrancar API con `.env` real para obtener una supuesta verificación de fondo.
- Consecuencia: resultados son de tests aislados; no acreditan R2 real. JDK 11 en PATH se evita mediante JAVA_HOME de proceso apuntando al JDK 17 instalado.
- Reconsiderar cuando: se identifiquen bucket, prefijo y workspace de prueba autorizados para la prueba técnica.

## D06. Probar 9.5.9 en una app separada

- Problema: 9.6.6 publicada requiere Dart ^3.13.0 / Flutter >=3.47.0, superiores al SDK instalado.
- Evidencia: metadatos de versiones desde `https://pub.dev/api/packages/background_downloader`; pubspec/código del archivo publicado 9.5.9 consultados en caché Pub.
- Decisión: fijar exactamente 9.5.9 en `metricol_app/tool/upload_probe`, con lockfile propio. Mantener intactas dependencias y rutas de producción durante viabilidad.
- Motivo: probar una versión compatible (Dart ^3.7.0 / Flutter >=3.29.0) sin ampliar el trabajo a actualizar todo Flutter.
- Consecuencias: no usar TransferHint ni otras APIs nuevas de documentación de main. Mínimo iOS del candidato: 14.0, frente a 13.0 actual. Adaptar sólo el probe mientras se decide integración.
- Limitaciones: UploadTask no tiene pausa; preparación de rango en iOS copia a temporal y lee prefijos. Validar costo y R2 antes de adoptar.
- Alternativa descartada por ahora: actualizar globalmente SDK o instalar latest incompatible.
- Reconsiderar cuando: prueba móvil/R2 falle o el costo iOS de rangos haga inviable la versión.

## D07. Reconstruir partes desde metadatos independientes

- Problema: un registro devuelto por el motor no conserva necesariamente los encabezados originales.
- Evidencia: pruebas reales desktop con 9.5.9 y lectura de `upload_isolate.dart`: elimina `Range` antes de enviar y devuelve la tarea modificada. Los primeros tests detectaron `Range=null` en tracking.
- Decisión: guardar número de parte y sesión fuera de los headers mutables; en el probe se usa JSON en `metaData`. Renovación reconstruye el UploadTask/rango desde esa información.
- Motivo: copiar sólo una tarea fallida con otra URL podría subir el archivo entero en lugar de la parte.
- Consecuencia: futuras etapas deben conservar manifiesto de dominio y validar rangos independientemente de los registros del motor.
- Reconsiderar: sólo con evidencia de una nueva versión; conservar redundancia de dominio aunque el paquete mejore.

## D08. Prueba real R2 con limpieza limitada a la corrida

- Problema: no existía almacenamiento aislado designado; el usuario autorizó expresamente archivos locales de Downloads y limpieza posterior del bucket.
- Evidencia de autorización: respuesta del usuario «Usa archivos locales de download y luego limpialos del bucket» durante Fase 1.
- Decisión: usar el video local de prueba `media-prueba/reel-vertical-30s.mp4`; crear sólo `full.mp4` y `multipart.mp4` bajo prefijo UUID nuevo de `pruebas/subidas-background/` en el bucket configurado. No modificar el original ni otros objetos.
- Motivo: verificar firmas/rangos con FileDownloader real y comparar SHA-256 de los objetos recibidos.
- Consecuencia: cleanup obligatorio en finally y comprobación de ausencia de objetos/multipart. URLs sólo en manifest ignorado; credenciales no se imprimen. Helper Java usa el AWS SDK 2.28.11 ya disponible en API, sin arrancar Spring ni reservar cuota del producto.
- Límite: esto acredita transporte a R2, no permisos/cuota de los endpoints ni ejecución nativa móvil.

## D09. Viabilidad parcial demostrada, adopción móvil pendiente

- Problema: pruebas desktop y build Android no demuestran la experiencia móvil requerida.
- Evidencia: 16 tests de esta unidad aprobados (6 contrato local, 6 integración desktop, 1 lectura de otro proceso, 3 R2); objetos comparados mediante SHA-256 y eliminados. Android release compila con R8, minSdk 24/target 36. Sin ejecución física Android/iPhone ni build iOS.
- Decisión: conservar 9.5.9 como candidato viable para el transporte probado, sin aprobar aún adopción productiva. Continuar Fase 1 por unidad nativa/física; no iniciar Fase 2 automáticamente.
- Consecuencias: app principal permanece igual. APK del probe disponible, reproducible y diferenciado por applicationId. Falta verificar costo de rangos iOS y funcionamiento real bajo suspensión/proceso muerto.
- Reconsiderar cuando: se complete matriz móvil o aparezca impedimento demostrado. La falta de equipos no se convierte en aprobación por inferencia.

## D10. Acotar memoria del build del probe

- Problema: la compilación debug del harness terminó porque desapareció el daemon Gradle.
- Evidencia: `android/hs_err_pid23616.log` del probe identifica fallo de asignación nativa / Out of Memory Error. Scaffold configuraba heap 8 GiB y metaspace 4 GiB en host con 15 GiB, simultáneo a AVD y herramientas.
- Decisión: en el proyecto de prueba, limitar heap a 2 GiB, metaspace 768 MiB, code cache 256 MiB, workers a 2 y Kotlin en proceso.
- Motivo: reducir compromiso de memoria y concurrencia sin modificar la máquina ni procesos ajenos.
- Consecuencia: reintentar el build debug fallido con este perfil; release previamente aprobado sigue siendo evidencia del transporte compilable, no del nuevo harness.
- Reconsiderar cuando: aparezca una necesidad medida de memoria mayor en el probe. No trasladar automáticamente estos límites al proyecto principal.

## D11. AVD de prueba independiente, sin borrar Pixel_8

- Problema: Pixel_8 permanecía offline en ADB; una espera o reinicio repetido no explicaban la causa.
- Evidencia: captura con `-show-kernel` mostró error EXT4/fs-verity durante odrefresh, kernel panic y un nuevo banner del kernel. Desactivar Vulkan se usó para diagnóstico, no se acredita como solución del filesystem.
- Decisión: cerrar esa instancia y crear `PicaleUploadProbe` con imagen ya instalada, AVD_HOME dentro de `tool/upload_probe/build/avd` y puerto 5560. No reparar ni borrar datos del AVD original.
- Motivo: probar el motor nativo en un entorno nuevo bajo control del proyecto sin afectar datos del usuario ni instalar otro SDK.
- Consecuencia: resultados del AVD nuevo seguirán siendo de emulador, no de teléfono físico. Dejar registros y cerrar el recurso al terminar la unidad; conservar su estado sólo si ayuda a reanudar pruebas.
- Reconsiderar cuando: el AVD aislado también muestre un fallo de imagen/host; entonces se necesita un dispositivo utilizable o resolver ese impedimento con evidencia.

## D12. Aprobar sólo el escenario Android demostrado

- Evidencia: el 9 de noviembre el harness nativo pasó dos veces en Android 36 x86_64. En `native-1794208867431`, Home/Sleep se enviaron con cuatro PUT incompletos; el receptor independiente verificó avance y recepción completa antes de reabrir. Cuatro HTTP 200, bytes exactos y workers SUCCESS; pantalla OFF/SLEEP al terminar.
- Decisión: dar por aprobado el escenario de cambio de app y pantalla apagada en ese emulador con prioridad 5 y servicio foreground. Mantener Fase 1 parcial y la app productiva intacta.
- Límites: no se probó bloqueo con PIN, UIDT, Doze prolongado, recuperación tras muerte del proceso, red interrumpida ni matriz física/iOS. El usuario indicó que no tiene Android USB disponible; esa ausencia no es un resultado aprobado.
- Consecuencia: conservar evidencia y cerrar recursos de prueba. La siguiente unidad cubre escenarios faltantes sin repetir R2 ni avanzar automáticamente a Fase 2.

## D13. Recuperación Android acreditada por dos procesos y receptor independiente

- Problema: sobrevivir al cambio de pantalla no demuestra recuperar un proceso terminado; tampoco basta encontrar registros en disco.
- Evidencia: `native_recovery_test.dart` y orquestador host completaron `recovery-1794210830375`. Parte 1 aceptada antes de SIGKILL; partes 2/3 incompletas en receptor. PID 4538 desapareció y PID 4904 leyó el mismo manifiesto/registros. `FileDownloader.start` de 9.5.9 recuperó el trabajo; workers 2/3 completaron bytes correctos y la parte 1 sólo se recibió una vez. Test y orquestador exit 0.
- Decisión: aprobar recuperación tras muerte abrupta simulada y reapertura en Android 36 emulado. Conservar tareas/manifiesto y usar el receptor como fuente de aceptación; no inferir que una tarea completa en disco equivale a un medio listo en la API.
- Motivo: el contraste entre PID, bytes previos, estados persistidos y recepción posterior cubre el comportamiento reclamado sin volver a seleccionar ni regenerar el fixture en el segundo proceso.
- Alternativa descartada: usar `am force-stop` como si fuera muerte por el sistema. Se usó SIGKILL vía run-as limitado al UID del probe; no se indujo un evento real de low-memory killer.
- Consecuencias: una parte interrumpida reinicia su PUT; una parte ya aceptada no se repite en este caso. No extrapolar a la carrera R2 aceptó/cliente no recibió respuesta: se resolverá con ListParts y sesiones API en etapas posteriores. Red, renovación nativa, recursos e iOS siguen pendientes.
- Versiones: background_downloader 9.5.9, Flutter 3.41.6/Dart 3.11.4, Android 36 x86_64, prioridad 5/servicio foreground. Reconsiderar ante cambios del motor, plataforma o escenarios fallidos; no adoptar producción sólo por esta corrida.

## D14. Separar cancelación de usuario y parada por pérdida de red

- Problema: al cortar Wi-Fi/datos en Android 36, el motor 9.5.9 emitió `TaskStatus.canceled` aunque el usuario no había cancelado. El harness falló al tratarlo como terminal; WorkManager reejecutó las mismas partes al volver la red y las recibió correctamente.
- Evidencia: corrida `network-1794211370801`, default network none durante más de 15 segundos, workers detenidos por constraints y estados canceled a `08:03:43/44Z`. Workers 2/3 reiniciados `08:04:12/13Z`, partes aceptadas una vez después de restaurar. La corrida del test falló y se conserva como evidencia, no se cuenta como aprobada.
- Decisión: el probe de red espera acotadamente la recuperación de canceled cuando no existe intención de cancelación de usuario; continúa exigiendo completion real y recepción exacta. Para integración futura, persistir intención de cancelación por separado y reconciliar con tareas nativas/R2; no convertir automáticamente este evento en borrado o cancelación de sesión.
- Motivo: el estado del transporte puede representar una parada del planificador; no basta para determinar el estado del medio o la intención del usuario.
- Hallazgo adicional: `UploadTask.copyWith(retries:3)` conserva retriesRemaining=0 si la tarea original lo tenía a cero. Código publicado de 9.5.9 confirma `..retriesRemaining = retriesRemaining ?? this.retriesRemaining`. El harness configura ambos contadores a 3; no cambiar dependencias productivas por este hallazgo.
- Consecuencia: repetir sólo el escenario fallido con harness corregido y evidencia de red antes/durante/después. No ignorar errores definitivos ni canceled de usuario; el probe no expone cancelación de usuario. Reevaluar en otras versiones/plataformas y con los contratos API futuros.
- Versiones: background_downloader 9.5.9, Flutter 3.41.6, Android 36 x86_64, prioridad 5 con servicio foreground. La aprobación terminal de la nueva corrida se registra en avance, no se presupone aquí.

### Resultado posterior de D14

- Corrida corregida `network-1794211649103` aprobada: red default none durante más de 15 segundos, estados waitingToRetry/enqueued/running/complete en ambas partes interrumpidas, bytes exactos y una aceptación por parte. La parte 1 aceptada previamente no se reenviaba. Orquestador y test exit 0.
- Código publicado `base_downloader.dart` de 9.5.9 confirma que el retry de un failed con contador positivo usa temporizador Dart y luego entrega de nuevo la tarea al motor. El transporte y la espera de conectividad de la tarea entregada son nativos; esta corrida conserva el proceso Flutter vivo en background. No usar este éxito para afirmar retry totalmente independiente de Dart ni para probar muerte en el intervalo del temporizador.
- Consecuencia para las siguientes fases: el registro de dominio durable y reconciliación al abrir siguen siendo necesarios. Mantener pendientes físicos/iOS, recursos y escenarios combinados; no sustituirlos por este caso aprobado.

## D15. Renovación 403 verificada a partir del registro persistido

- Problema: un 403 requiere reconstruir la misma parte con permiso nuevo; repetir a ciegas la URL vencida o confiar en headers mutables puede enviar bytes equivocados.
- Evidencia: `renewal-1794212078240` en Android 36 x86_64: partes 1/3 aprobadas, parte 2 failed/403; registro leído de disco y renovado por `renewProbeTask`, mismo taskId/ruta y rango 5,242,880–10,485,759. Reintento 200, bytes exactos y fixture intacto. Cuatro requests de la tanda (uno 403 y tres 200 únicos), test aprobado y manifiesto final verified=true.
- Decisión: aprobar ese contrato del adaptador/transportador nativo. Mantener renovación basada en metadatos inmutables y evidencia de recepción, con reintentos automáticos a cero en este test.
- Límites: token sintético, no nueva firma criptográfica R2 ni autenticación del producto. R2 real vencido tiene evidencia previa separada; no repetirlo. No inferir background de esta corrida: ADB falló antes de enviar Home.
- Consecuencia: el orquestador permite `-CollectExisting` y reintenta sólo lecturas ADB ante fallo transitorio, sin volver a instalar/encolar. La misma corrida pudo recogerse y aprobarse sin repetir la prueba. Cualquier renovación productiva requerirá API y autorización de etapas futuras.
- Versiones: background_downloader 9.5.9, Flutter 3.41.6/Dart 3.11.4, prioridad 5/servicio foreground, Android 36 emulado. Reconsiderar al cambiar transporte/metadatos/plataforma.

## D16. Investigar reemisión nativa antes de adoptar el motor

- Problema: durante el siguiente arranque del probe volvió a enviarse una parte previamente completada de la prueba de red.
- Evidencia: `network-1794211649103-2-initial`, WorkSpec `2a6a53ab-0133-42d9-a600-0c1b93c39c79`, reportó SUCCESS a `08:09:11.759Z`; reapareció a `08:14:35Z`, terminó cancelado y se creó nuevo WorkSpec `b6eed0fd-3b17-4a28-b4f9-031fefed97f7`, aceptado a `08:15:05.364076Z`. El receptor guardó otro PUT correcto de 5,242,880 bytes. No se había encolado de nuevo esa tanda desde el harness renewal; `start` tenía doRescheduleKilledTasks=false.
- Evidencia adicional: snapshot DB/WAL/SHM de WorkManager del probe con quick_check=ok; IDs anterior/nuevo tienen estados numéricos 5/2 e intentos 2/1 al recoger. Logs y reporte preservados. Falta snapshot antes del cierre anterior: no determina cuándo se persistió el estado.
- Decisión: no inferir no reemisión tras reinicio ni aprobar adopción por los tests acotados. Priorizar reproducción controlada separando cierre/reapertura de AVD y reinstalación, con snapshots y receptor independiente.
- Alternativas descartadas: borrar DB, cancelar globalmente o ignorar otra tanda en los logs para presentar un resultado completamente verde. La aprobación del caso 403 sólo cubre esa tanda, no elimina este hallazgo.
- Consecuencias: Fase 1 sigue parcial. Investigar si el cierre de emulador/host dejó WorkSpec no persistido o si hay interacción retry/WorkManager; no atribuir causa aún ni mantener un fork importante sin necesidad. Futuras sesiones API/ListParts siguen siendo necesarias, pero no sustituyen resolver duplicación nativa no controlada.
- Versiones: background_downloader 9.5.9 declara `androidx.work:work-runtime-ktx:2.11.0` en su build.gradle; resolución efectiva pendiente de confirmar en auditoría. Flutter 3.41.6, AVD Android 36 x86_64. Node 24.14.1/node:sqlite sólo para diagnóstico local sin cambios en el dispositivo. Reconsiderar al aislar causa con evidencia reproducible.

## D17. Medir persistencia y recepción; no equiparar log SUCCESS a commit

- Problema: el replay observado no tiene snapshot de WorkManager anterior al cierre del AVD. Por ello no sabemos si se reejecutó un trabajo que ya estaba persistido como SUCCEEDED o un trabajo que todavía figuraba RUNNING pese a que sus bytes habían llegado.
- Evidencia de versión: `app:dependencyInsight` confirmó WorkManager/work-runtime-ktx **2.11.0** efectivos. Fuente publicada exacta: https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/2.11.0/work-runtime-2.11.0-sources.jar . WorkerWrapper imprime SUCCESS antes de llamar setSucceeded, que cambia el estado dentro de la persistencia WorkManager. Esa secuencia abre una hipótesis de medición; no demuestra por sí sola la causa ni la duración del intervalo.
- Decisión: agregar diagnóstico de sólo lectura y un target pasivo sin enqueue/cancel/delete. Comparar DB/WAL estables, estados/intentos por WorkSpec/taskId, bootId, eventos de Flutter y receptor independiente antes/después de operaciones separadas. No cambiar versión ni parchear WorkManager sin reproducción causal.
- Evidencia inicial: reinicio sin instalación y reinstalación sin abrir actividad conservaron todos los WorkSpecs iguales y cero requests. Arranque pasivo inicial también tuvo cero eventos/tareas/requests; cambió sólo schedule_requested_at a -1 de tres trabajos terminales, manteniendo estado/intentos. Reinicio posterior con mismo APK pasivo tampoco reemitió; datos en `build/replay-audit/controls-summary.json`.
- Alternativa descartada: declarar eliminado el replay porque no aparece en un estado estable o aumentar sleeps como arreglo del producto. La ventana inmediatamente posterior a completar/reintentar una carga sigue sin medirse; la siguiente unidad debe capturarla y distinguir señal de transporte de confirmación persistida.
- Consecuencia: el motor permanece candidato, Fase 1 parcial. No sumar controles pasivos a los 21 casos de transporte aprobados ni usarlos para aprobar iOS/físicos. Conservar el replay real original y los controles negativos, incluso si no convergen todavía en una causa.
- Versiones: background_downloader 9.5.9, WorkManager 2.11.0, Flutter 3.41.6/Dart 3.11.4, AVD Android 36 x86_64, Node 24.14.1/node:sqlite sólo en host. Reconsiderar al obtener snapshots de la ventana crítica y una reproducción controlada.

### Cierre de los controles de D17

- Tres controles completados sin requests, tareas nativas ni eventos: instalación/apertura inicial del target pasivo, cierre/reapertura del mismo AVD sin instalar, e instalación/apertura del mismo APK sin reiniciar. No cambió estado ni número de intentos; sólo metadatos schedule_requested_at se normalizaron en el primero. BootIds y snapshots confirman la separación.
- La reemisión original permanece sin causa demostrada. La siguiente unidad medirá la ventana complete→persistencia con un transporte nuevo y capturas previas al cierre. Ésta es una repetición justificada por una inconsistencia, no una repetición de baseline para sumar pruebas verdes.

## D18. Separación entre callback complete y estado nativo persistido observada

- Problema: los controles con trabajos ya terminales no capturaban el intervalo entre señal de éxito y persistencia. Una copia por ADB tarda demasiado para medir ese intervalo.
- Decisión: lector SQLite READONLY dentro del probe, executor separado, muestras nominales de 20 ms y marcas del canal Dart. Captura tiempos monotónicos de inicio/fin de lectura; escribe JSONL fuera de WorkManager, sin pausar ni cambiar workers. Un error de lectura o WorkSpecs exitosos ambiguos invalida la medición.
- Evidencia: `commit-1794241235508`, parte 2 con corte de red y retries, 4,107 muestras sin errores. Al recibir marca dart_complete el WorkSpec final 14e8c88a... seguía RUNNING. La última lectura RUNNING y primera SUCCEEDED acotan la transición entre 48.724405 y 61.639855 ms después de recibir la marca en el lector nativo. Ancho del intervalo: 12.91545 ms. Snapshot posterior y previo al cierre: final state=2, dos intentos nativos previos state=5; sólo un PUT exacto aceptado.
- Límites: observación instrumentada en emulador/debug, con latencia de consulta/canal y posible perturbación de CPU/IO. No es una duración fija, no prueba fsync físico del host ni demuestra que ésa fuera la causa del replay original. Se observó además un segundo SocketException tras restaurar red, recuperado sin otra aceptación exitosa.
- Consecuencia: no equiparar complete de transporte con commit nativo ni con medio listo del producto. No agregar sleeps fijos como arreglo; mantener reconciliación/estado de dominio y finalización servidor previstos en las fases futuras. Falta probar muerte abrupta dentro del intervalo para demostrar su efecto causal sobre reemisión; la reapertura con estado terminal conocido se registra separadamente en avance.
- Versiones: background_downloader 9.5.9, WorkManager 2.11.0, Flutter 3.41.6/Dart 3.11.4, Android 36 x86_64 debug, lector SQLite Android y Node 24.14.1 para análisis. No dependencia nueva ni modificación del paquete publicado.
- Reconsiderar: ante otra versión/plataforma o medición sin instrumento. Mantener Fase 1 parcial y no adoptar el motor basándose en esta medición aislada.

### Reapertura controlada de D18

- Misma tanda/APK, sin instalación ni enqueue después del cierre con estado final capturado. Todos los WorkSpecs mantuvieron ID, estado e intentos antes de cerrar, después del boot y después de observar; sólo una aceptación de 5,242,880 bytes. Test y host exit 0, traza observer sin errores.
- La comparación global del receptor añadió un HttpException genérico entre el cierre y el inicio del control; el formato anterior no permite atribuirlo a un intento. No hubo cambios del receptor DURANTE la ventana de observación ni otra aceptación, pero no se afirma que cada error previo esté explicado.
- Próxima pregunta causal: qué ocurre al terminar el proceso dentro de la ventana observada, con una marca/snapshot que acredite RUNNING y bytes ya aceptados. Conservar esta corrida, usar otro manifiesto/tanda explícita y no introducir esperas productivas ni modificar resultados del worker para aparentar una solución.

## D19. Trazar cada intento HTTP, incluyendo desconexiones

- Problema: el receptor indexaba active por runId/parte, por lo que reintentos solapados podían sobrescribirse y el cierre de uno podía ocultar otro. Además sus errores genéricos no se podían asociar a un intento, como ocurrió en la comparación de D18.
- Decisión: attemptId único por request dentro del receptor de prueba; entradas activas independientes y errores de lectura con attemptId, runId, parte, bytes parciales e inicio/fin. No registrar query strings, headers de autenticación ni contenido. Un intento abortado no se acepta ni se confunde con HTTP 200.
- Evidencia: nuevo test HTTP real con dos requests de la misma parte solapados; aborta el primero, comprueba error atribuido y segundo aún activo, y completa el segundo exactamente una vez. Suite local actualizada: ocho casos aprobados; no se repitió Android ni R2 por metadatos de diagnóstico.
- Consecuencias: mejora la siguiente prueba de fallo causal. Los errores de trazas antiguas siguen sin identidad y no pueden reconstruirse retroactivamente. No cambia el protocolo de bytes/firmas ni el motor productivo.
- Versiones: Dart 3.11.4/Flutter 3.41.6, receptor local del probe. Reconsiderar si se incorpora receptor persistente o varias instancias: el attemptId actual es único sólo por proceso del receptor.

## D20. Preparar la inyección de fallo antes del intervalo crítico

- Problema: el primer punto de fallo ejecutaba I/O durable del marcador después del callback HTTP, consumiendo la ventana que pretendía probar.
- Evidencia: `fault-1794245235569`, PID 2846: fault_arm leyó RUNNING; fault_kill_check leyó SUCCEEDED aproximadamente 126 ms después. El proceso siguió vivo y el receptor registró una aceptación. Host terminó como ventana no alcanzada; no se cuenta como reproducción ni fallo del motor. Trace/marker/manifiesto se preservan en fault_window_installed.
- Decisión: armFault guarda marcador y sincroniza archivo ANTES de enqueue. killIfRunning valida APK debuggable, sesión fault-* ya armada y único WorkSpec RUNNING; después sólo lee y envía SIGKILL a su propio PID. No se agregan sleeps, pausas de worker ni cambios en WorkManager. El host además debe comprobar estado no terminal después de la muerte antes de recuperar.
- Motivo: separar preparación del experimento de la condición medida. Un marcador armed por sí solo nunca demuestra que se alcanzó el corte.
- Consecuencias: nuevo PROBE_FAULT_CASE=earlyarm/manifiesto fault-audit-earlyarm.json y --case earlyarm del host, sin borrar el intento legacy. Se conservan marcadores por trace ante recreación de engine. Si vuelve a llegar tarde, informar miss; no fabricar un resultado positivo.
- Versiones: background_downloader 9.5.9, WorkManager 2.11.0, Flutter 3.41.6/Dart 3.11.4, Android 36 x86_64 debug. Análisis/build de variante earlyarm aprobados; resultado causal pendiente de ejecución en avance. Revisar la política del motor sólo con evidencia del experimento y no con la compilación.

## D22. Corte antes del éxito persistido reproduce reenvío nativo

- Problema: había replay observado sin snapshot previo al cierre. Los controles con trabajos ya terminales no permitían distinguir una tarea exitosamente persistida de bytes aceptados con resultado aún RUNNING.
- Evidencia nueva: `fault-bgqueue1-1794248186106`, WorkSpec `ec8dc952-18ea-4cb0-bd16-30d1fbe6d24f`. PID 2466 recibió HTTP 200; logd registró lectura RUNNING/intento 1, cero errores y PID/taskId propios. El PID desapareció; snapshot posterior seguía RUNNING/intento 1 y receptor tenía una aceptación exacta de 5,242,880 bytes. No se modificó WorkManager ni el worker.
- Recuperación: mismo APK/AVD, sin instalación ni enqueue manual; PID 3496 observó running→complete. Mismo WorkSpec terminó SUCCEEDED/intento 2 y receptor aceptó dos PUTs exactos de la misma parte. Host exit 0, verified/causalCut/replayed=true; Flutter All tests passed. Source `native_commit_fault_test.dart` sólo encola cuando no existe manifiesto, que aquí persistió; retries=0, doRescheduleKilledTasks=false. La segunda ejecución fue del trabajo nativo ya existente.
- Decisión: cerrar la pregunta causal LOCAL para esta condición. El motor recupera la tarea, pero puede reenviar bytes ya aceptados si el proceso muere antes de persistir éxito. No exigir ni presentar exactamente una entrega del transporte. Los futuros contratos de dominio deben conservar sesión/parte, reconciliar con R2 y evitar finalización/reservas duplicadas, como ya pide la especificación; se implementarán en sus fases autorizadas, no dentro del probe.
- Límite: esto demuestra una causa suficiente, no la causa histórica del replay original, que no tenía snapshot anterior. No demuestra cómo se comportan todas las interrupciones/plataformas ni resuelve gates físicos/iOS/recursos. El motor continúa candidato, Fase 1 parcial. No atribuir al producto actual protección que aún no implementa.
- Instrumentación: orden nativa→inicio de lectura 0.039428 ms; consulta 0.262967 ms en esta corrida instrumentada. No valores universales. SIGKILL dejó último fragmento JSONL incompleto, preservado; verificación usó logd independiente más snapshot posterior no terminal. Sin logs/snapshot coincidentes no habría reproducción acreditada.
- Alternativas descartadas: parchear el paquete para esconder reenvíos, retrasar artificialmente el worker, o repetir controles negativos para declarar resuelto el riesgo. No se necesita otro ciclo de la misma prueba salvo inconsistencia nueva.
- Evidencia: `tool/upload_probe/build/fault-audit/fault_window_bgqueue1/` y snapshots `build/replay-audit/fault_window_bgqueue1_*`. Analizador limpio; build debug exit 0 en 488.1 s, SHA-256 C2DC870FE33323879B5A3F3E1DC4E769B07DF76287BD87CD1173D2EB226475A1. Receptor y AVD cerrados, reverse propio retirado; R2 sin tocar.
- Versiones: background_downloader 9.5.9, WorkManager 2.11.0, Flutter 3.41.6/Dart 3.11.4, Android 36 x86_64 debug. Reconsiderar adopción final con recursos, Android anterior a 14 y pruebas iOS/físicas; no volver a abrir esta pregunta sólo por cambiar de conversación.
## Versiones de línea base, sin cambios en app principal

- App `1.0.13+14`.
- Flutter `3.41.6`, Dart `3.11.4`.
- Kotlin `2.2.20`, AGP `8.11.1`, Gradle `8.14`.
- Android minSdk `24`, compileSdk/targetSdk `36` heredados del Flutter SDK instalado.
- Java API `17.0.12`; JBR Android disponible `21.0.6`.
- Spring Boot `3.5.15`, AWS SDK declarado `2.28.11`.
- Motor background: no instalado/no fijado todavía.

Estas son observaciones locales al inicio; futuras etapas deben registrar sus modificaciones reales y no reinterpretarlas como versiones desplegadas.

## D21. Evitar la cola del muestreo en el corte causal

- Problema: preparar el marcador antes de enqueue no eliminó la demora del diagnóstico. En reverse1 HTTP llegó correctamente, pero no se alcanzó la ventana.
- Evidencia: fault-reverse1-1794246947291, PID 2131, una aceptación exacta de 5 MiB. La consulta empezó 278.623011 ms después de recibir killIfRunning y leyó SUCCEEDED. No hubo SIGKILL ni evidencia causal. El experimento no suma una prueba aprobada.
- Decisión: canal de diagnóstico en background task queue de Flutter; lectura crítica directa fuera del executor de muestreo. Separar readState de capture; sólo el muestreo sigue escribiendo su BufferedWriter. Registrar el punto crítico por Android logd con PID/taskId antes de matar el propio proceso debug. Mantener marcador durable anterior a enqueue y snapshot SQLite posterior a la muerte como requisitos independientes del host.
- Motivo: reducir perturbación de instrumentación sin retrasar ni modificar el worker real. No se garantiza alcanzar la ventana; si logd falta, hay errores de muestreo o el snapshot posterior ya es terminal, el caso no acredita corte causal.
- Consecuencia: un SIGKILL puede dejar la última línea JSONL truncada; el host conserva el archivo binario y sólo tolera ese fragmento final, exigiendo marca crítica independiente válida y SQLite posterior no terminal. Nunca tolera corrupción de líneas intermedias. Se conserva la corrida reverse1 y se usa caso nuevo bgqueue1.
- Alternativas descartadas: pausar WorkManager, modificar su resultado, añadir esperas productivas o presentar la recepción HTTP como prueba de estado persistido. No se atribuye todavía causa al replay original.
- Versiones sin cambio: background_downloader 9.5.9, WorkManager 2.11.0, Flutter 3.41.6/Dart 3.11.4, Android 36 x86_64 debug. Node syntax check y Flutter analyze limpios; build/ejecución pendientes en avance. Reconsiderar con evidencia del corte y recuperación reales.
## D23. Medición de recursos separada de contrato y generación

- Problema: el fixture anterior de 12 MiB y los casos funcionales no medían consumo durante transporte ni rangos de archivos mayores. No se puede inferir memoria/disco por tests verdes.
- Decisión: target y receptor de recursos separados, sin alterar casos aprobados. Preparar archivos sintéticos de 16/128 MiB+37 en chunks de 64 KiB; medir después de preparación y antes de cada enqueue. Tres casos secuenciales: ambos PUT completos y un rango 8 MiB+37 a offset 64 MiB. Receptor verifica cada byte sin almacenar cuerpo y limita 1 MiB/s para obtener ventanas observables.
- Instrumentación: PSS dumpsys meminfo y RSS de /proc del PID real; du -ak del sandbox propio con artefactos crudos y tiempos por muestra. Requiere al menos dos muestras con request activo antes y después de medir por caso, una aceptación exacta por caso y eliminación de los dos fixtures al final. Gates de archivos coordinan baseline/transferencia/cierre sin cambiar el worker.
- Límites: debug/emulador/API36, PSS/RSS incluyen Flutter/VM/probe; sin GC forzado y máximo observado no es pico absoluto. du informa bloques asignados e incluye DB/metadatos y archivos anteriores; crecimiento fuera de fixtures no equivale exclusivamente a temporales. No afirmar complejidad de memoria universal con dos tamaños ni extrapolar a iOS, mayores tamaños o teléfonos físicos.
- Alternativas descartadas: cargar el fixture completo en memoria, usar sólo longitud lógica para medir espacio asignado, observar sólo después del PUT, volver a ejecutar contratos sin instrumentarlos o usar el target fault que provoca SIGKILL.
- Evidencia inicial: harness implementado y Node syntax check aprobado; pruebas/análisis/build/ejecución pendientes en avance. Decisión de uso del motor no cambia hasta resultados reales.
- Versiones sin cambio: background_downloader 9.5.9, WorkManager 2.11.0, Flutter 3.41.6/Dart 3.11.4, Android36 x86_64; Node24.14.1 para host. No dependencia nueva. Reconsiderar ante falta de muestras activas, errores del contador o necesidad de evaluar más tamaños/plataformas.
### Resultado verificado de D23

- Corrida resources_android36_2 / rs-1794249201195, PID 3506/API 36: host 46561 exit 0 y Flutter All tests passed. Tres HTTP 200 exactos, 159 muestras PSS/RSS/du con validación de los 477 archivos crudos; 9/125/6 muestras activas para completo 16 MiB+37, completo 128 MiB+37 y rango 8 MiB+37.
- Máximos PSS del proceso: 169893 / 161402 / 156046 KiB; RSS: 174152 / 173180 / 158664 KiB. Incremento PSS respecto a última baseline de cada caso: 7356 / 399 / 591 KiB. Crecimiento asignado fuera de fixtures: 12 / 12 / 16 KiB. Baselines y tiempos completos en report.json; no convertir comparación frío/caliente en una ley de crecimiento o garantía de pico absoluto.
- No se observó una copia visible comparable al tamaño del archivo. du no mide archivos abiertos ya eliminados ni picos entre muestras; incluye DB/metadatos/cachés. Estas limitaciones impiden afirmar ausencia universal de temporales. Recursos iOS siguen pendientes, especialmente copias de rangos.
- Cleanup: fixtures propios 147476 KiB asignados antes, cero después. Sandbox 293356→145884 KiB, diferencia 147472 KiB por 4 KiB adicionales de metadatos. Receptor sin requests activos, cerrado por SIGINT; AVD cerrado y ADB sin dispositivos. Reverse propio retirado y 8797 libre. No tocar R2 ni recursos ajenos.
- Falla inicial resources_android36_1: exec-out devolvió cat missing con exit 0 antes del manifiesto; host abortó y retiró reverse, fallando luego preflight. Cero tareas/fixtures/requests de ese intento. Corrección host state() espera ausencia explícita; no se relajó verificación de JSON válido ni aceptación. Evidencias fallidas preservadas; no es fallo del motor.
- Verificación de implementación: dos tests reales del receptor aprobados, analyzer limpio, Node syntax check, APK resources x64 final exit 0 (build 222.3 s + confirmación 21.7 s), SHA-256 D9C024494096166A4F553A95C7661961F746CB9A5DCF4A42B30D82A4BEECACEB. El receptor pasó a quitar active al terminar body, antes de response.close, tras detectarlo sus tests.
- Consecuencia: cerrar medición Android para este entorno/tamaños. La siguiente unidad evalúa duración y reintentos en Android anterior a 14; no repetir recursos Android sin inconsistencia ni declarar iOS/físicos/producción aprobados. Sin cambio de dependencias o motor productivo.
## D24. Probar duración continua y reintento en Android 13 aislado

- Problema: pruebas en API36 no acreditan el comportamiento anterior a Android14 ni superar el límite habitual de diez minutos. El ensayo de recursos tenía timeouts demasiado cortos para ello.
- Evidencia de plataforma: SDK ofreció system-images;android-33;default;x86_64 rev2, instalado con exit0; AVD nuevo separado, sin sobrescribir Pixel_8 ni PicaleUploadProbe. Inventario inicial21.15GiB libres, licencias SDK existentes; ningún término nuevo aceptado automáticamente. Código exacto9.5.9 TaskRunner determina runInForeground por configuración/tamaño y excluye esa ruta de ramas isTimedOut&&!runInForeground.
- Fuente oficial revisada: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running (actualizada2026-10-28). WorkManager admite trabajos que superen diez minutos con foreground service y notificación; no implica duración ilimitada y Android16 añade cuotas. Esta referencia motiva el ensayo, no sustituye evidencia de ejecución en API33.
- Decisión: fixture sintético128MiB+37, un único enqueue manual con retries=1; primer cuerpo exacto recibe503 local, reintento automático recibe200 y se limita192KiB/s (~683s). Host verifica waitingToRetry, ambos cuerpos, mismo taskId y mismo WorkSpec RUNNING al inicio/después de610s, mismo run_attempt_count en esa ventana, servicio foreground activo, app en Home y pantallaOFF. Finalmente mismo WorkSpec SUCCEEDED y cleanup sólo del fixture propio. Timeouts host18min/target20min/test25min, más preparación.
- No se combina con corte real de radio en este ensayo: el503 es una falla HTTP transitoria sintética. No confundirla con firma vencida ni nueva pruebaR2. Retry del paquete se programa en Dart antes de entregar otra tarea nativa, límite ya documentado; sólo la transferencia larga queda entregada al worker nativo. No afirmar retry independiente de Dart.
- Instrumentación: snapshots READONLY de SQLite/WAL estables con quick_check, selector --serial para5562 manteniendo default5560. El host sólo admite AVD API33 de nombre exacto; logs, servicios/actividad/power y heartbeat monotónico independientes. Una corrida de once minutos acredita ese intervalo, no un máximo universal ni dispositivos físicos. El servicio puede detenerse normalmente al completar.
- Versiones sin cambios: background_downloader9.5.9, WorkManager2.11.0, Flutter3.41.6/Dart3.11.4, Android13/API33 x86_64 default rev2, targetSDK36. Resultado runtime pendiente en avance. No adoptar motor ni avanzar Fase2 hasta gates restantes.
### D24: recuperar la observación sin repetir la transferencia

- El primer host falló al exigir en dumpsys power un campo Display Power: state=OFF que API33 no expone ahí. Su dump sí registró mWakefulness=Asleep; dumpsys display confirmó Display State=OFF y mScreenState/mActualState=OFF. La comprobación corregida exige ambos, no deduce pantalla apagada sólo por Home.
- El mismo proceso2265, task lr-1794251740168-upload y segundo request siguieron en ejecución, incluso tras retirarse el reverse de escucha. Se preservó snapshot SQL inicial estable RUNNING/attempt1 del WorkSpec c2d342ce-c77f-4082-89d4-3af2f786acb8 y el reporte fallido original. No instalar, relanzar ni encolar para salvar un error del observador.
- Se añadió colector de sólo observación. Verifica al cruzar610000ms del Stopwatch del MISMO request que éste aún recibe datos y el mismo WorkSpec sigue RUNNING/attempt1, con FGS y pantallaOFF/appSTOPPED; después exige650000ms de duración final como mínimo, HTTP200 exacto y SUCCEEDED. No afirma610s entre snapshots: el primer snapshot conservado es posterior al inicio. Medir duración real del cuerpo con reloj monotónico del receptor acredita la duración del transporte, sin sumar latencia del controlador ni fingir un intervalo host no capturado.
- Artefactos separados: report.json conserva verified=false/error de primer observador; collection-report.json/collection-heartbeat y collection-runtime registran la recolección actual. Verificación final pendiente en avance. No modificar velocidad/worker/SDK para alcanzar la duración ni repetir una transferencia sana por un error de formato del host.
### Resultado verificado de D24

- Recolección35748 exit 0, Android13/API33 default x86_64 rev2, PID2265, run lr-1794251740168. El segundo PUT de 134217765 bytes duró682672ms (11min22.672s), medidos por Stopwatch del receptor. Dos cuerpos exactos, primero503 y segundo200; callback waitingToRetry; sólo un enqueue manual. Flutter All tests passed y fixture propio eliminado.
- Misma tarea y WorkSpec c2d342ce-c77f-4082-89d4-3af2f786acb8: snapshots inicial/frontera/final [RUNNING,RUNNING,SUCCEEDED], contador nativo [1,1,1]. Request2 aún activo a611008→612010ms, con bytes crecientes antes/después del snapshot. Lecturas estables/quick_check ok. La duración es del cuerpo real, no610s entre snapshots SQL. Se conserva ese límite explícito.
- En frontera: SystemForegroundService isForeground=true, power Asleep, displayOFF y actividadSTOPPED. Al finalizar servicioforeground=false normalmente; no exigir que un servicio terminado permanezca activo. Emulador alimentado (mIsPowered=true): no generalizar a deepDoze, OEM ni teléfono físico.
- Fallo inicial y recuperación observacional quedaron separados: report.json/error del primer host intactos, collection-report.json verified=true y validation.json con comprobación independiente de SQL/tiempo/bytes/Flutter/cleanup. No se reinició ni duplicó la transferencia por el error del controlador. El host original se corrigió, sin repetir una corrida sana sólo para cubrir ese camino.
- Se cierra este caso de Android13; no implica duración máxima universal, todas las versiones anteriores a14, ni iOS. El motor sigue candidato por gates de Fase1 restantes. Siguiente entregable: matriz completa de requisitos/evidencia y recomendación de adopción condicionada, sin avanzar Fase2.
- Versiones: paquete9.5.9, WorkManager2.11.0, Flutter3.41.6/Dart3.11.4, targetSDK36, Android13/API33 rev2. APK669CAC1B57EC379C70601DC9463CB38DE168C2B03BA9ABFECF5F7D92D4DC2B6F; build449.4s+confirmación24.4s y analyzer limpio. Receptor/AVD cerrados, reverse retirado, sin listeners8797/5562 ni handles activos; imagen/AVD aislados se conservan. R2 no tocado.
## D25. Evaluación de Fase 1 por requisito y plataforma, sin adopción anticipada

- Problema: la acumulación de tests Android locales y R2 desktop podía interpretarse como prueba firmada del motor móvil, y los cierres históricos no eran una matriz contra cada requisito del apartado6.
- Evidencia: r2_contract_test.dart fija TargetPlatform.windows. R2 real tiene tres tests, hashes de ambos objetos y cleanup verificado, pero no fue UploadTaskWorker/URLSession. Android usa receptor local sin SigV4. iOS no compila/ejecuta en evidencia actual; app13 frente a candidato14. Release existente es probe con firma debug, no Pícale. Retry de9.5.9 pasa por Dart; rangos Swift crean temporales y leen prefijos. Documentación pública menciona AndroidAPI21, fuente Gradle declara23 y probe efectivo24.
- Publicación contrastada: archivo oficial9.5.9 SHA-256 ae55e127f87397b372578fc9474568b879fa78326103c342e00160558d08e527 coincide con API/lockfile. LICENSE y seis fuentes auditadas coinciden con caché. Texto principal BSD de tres condiciones y licencia MIT/localstore; pub.dev badge unknown no reemplaza el archivo. Metadatos9.6.6 confirman SDK incompatible con Flutter3.41.6/Dart3.11.4. Mantenimiento observado por publicaciones recientes, sin promesa de soporte futuro.
- Decisión: mantener9.5.9 sólo como candidato aislado y declarar NO-GO de integración/liberación. Informe docs/subidas-moviles-evaluacion-motor.md cubre M01–M16, catálogo E00–E12, implicaciones y brechas. No cambiar SDK global, mínimo iOS ni proveedor ni mantener fork sin evidencia que lo justifique.
- Motivo: respetar el criterio de avance de ambas plataformas y distinguir transporte firmado/contrato local, emulación/físicos, estático/runtime y probe/producto. No invalidar pruebas desktop útiles ni convertirlas en prueba móvil indirecta suficiente.
- Siguiente unidad: preparar localmente el target Android firmado y protocolo de manifiesto/archivo/headers/logging/cleanup con helper existente. Es nueva cobertura de plataforma. No repetir desktop ni escribir R2 para producir el informe o preparar código. Reconsiderar ejecución usando la autorización de pruebas/cleanup aplicable, sin un flujo de aprobación inventado.
- Alternativas descartadas: dar Fase1 por terminada por conteos verdes, subir iOS13 a14 sin decisión del producto, instalar latest incompatible, o repetir Windows para cubrir una brecha nativa. Cuota/workspace/lotes/dedupe y finalización autónoma siguen siendo requisitos de etapas posteriores, no capacidades verificadas del probe.
- Consecuencias: Fase1 parcial y goal activo; se necesita prueba firmada móvil, Mac/iPhone/físicos y decisión de compatibilidad. Usuario sin USB, no repetir esa solicitud. Reconsiderar selección si iOS13 debe mantenerse o costo/ejecución iOS requieren un fork importante.
- Versiones/evidencia:9.5.9 publicada2026-08-29;9.6.6 publicada2026-11-05 requiere Dart^3.13/Flutter>=3.47. WorkManager2.11.0, Kotlin2.2.20, AGP8.11.1, Gradle8.14, probeAndroid24/36, iOS14. validación16 filas/41 enlaces, archivos/JSON/logs revisados sin repetir tests. Índice en build/evaluation-audit/validation.json. Sin cambios productivos/remotos ni procesos pendientes.
## D26. Sandbox separado y canario obligatorio antes de firmas reales

- Problema: Android local y R2 desktop no prueban transporte Android firmado. El historial nativo guarda URLs y un403 puede reflejarlas en logs; no borrar evidencia del probe anterior.
- Decisión: target Android separado, manifiesto runtime privado, paquete opt-in .r2 instalado fresco y retirado en finally. Nunca URLs en dart-defines/argumentos, temporales públicos, DB exportada ni logs crudos. Kotlin lee sólo metadata WorkSpec y espera terminal nativo tras callback. Renovar parte1 conserva taskId y SHA del origen.
- Gate local: fixture12582949 bytes, partes5242880/5242880/2097189, body403 refleja canario falso. Runner sólo loopback8797, sin helper/.env/R2. Exigir bytes/status/WorkManager/cleanup y canario ausente de logcat. Resultado runtime pendiente al registrar decisión; tests4/4 de contrato pasan.
- Staging observado: exec-in binario dejó4096/4133 bytes y shell-v2 dejó26/4133. ASCII Base64→decode privado dio4133/4133 y source12582949 con SHA completo en canary4. Base64 es transporte, no cifrado. Canary1–4 fallaron antes de HTTP; instalación propia retirada. No son éxitos nativos ni fallos R2.
- Release+debuggable produjo VM fatal por snapshot debug/runtime precompilado con Flutter3.41.6. Conservar APK fallido f9c044..., no reinstalar. Baseline release3e4a... preservado separado. Debug coherente con R8/shrink privado es el siguiente candidato. Primer minifyDebug falló por kxml2:2.3.0, traído por AndroidX test runner1.2.0: excluir sólo esa dependencia en opt-in por interfaz duplicada frente a Android. No modificar SDK/caché ni configuración normal.
- R8 avisa optimización/obfuscación deshabilitada en debug: reglas Log no demuestran redacción. Si el canario filtra, bloquear firmas reales y resolver logging. Filtrar sólo evidencia después de emitir secretos no es solución aceptable.
- Consecuencias: orquestador real aún pendiente; protocolo NATIVE_R2_PROTOCOL.md distingue preparación y ejecución posterior. Antes de cleanup remoto detener instalación propia para evitar escrituras tardías. Helper existente verifica hashes/partes y ausencia de ambas keys/multipart del UUID; nunca limpieza amplia. Reconsiderar build/logging según canario, sin elevar local a R2 real.
- Versiones: background_downloader9.5.9, Flutter3.41.6/Dart3.11.4, AGP8.11.1/Gradle8.14, AndroidX runner1.2.0. Variante nueva Android33 runtime pendiente; producto, iOS y gate Fase1 incompletos.

- Actualización D26,18:12: debug+R8 falló canario real local: TaskRunner nivelI emitió body403 con URL falsa. Full200 y expired403 verificaron bytes, renovación se interrumpió por stop del host; instalación/túnel retirados, sin activos. Evidencia redactada leak-metadata.json. Se descarta debug para firmas.
- Corrección: release NO debuggable coherente AOT+R8optimizado, staging en emulador allowlisted con su bajo UID app (su0 disponible comprobado), sin adbroot ni configuración de teléfono/producto. NativeR2Proof mantiene paquete.r2 y SQLmetadataonly, retira checkDEBUGGABLE que impedía evaluar release. Runner valida modo/identidad/UID y sandbox absoluto; debug queda reproducer explícito. Build40899 en curso, runtime nuevo pendiente. Reconsiderar sólo si canario aprobado; no esconder filtración mediante propiedades de logcat ni editar SDK.

- Cierre D26,18:23: canary7 release pasó, host68925exit0, UUIDfbcb5200-6297-469a-8b2b-f932874ef2f9, PID2253/UID10127, APK016d2336edd18f4daec5466ac6bb9426cb91e30b538f3589051a85743e094a66,17245950bytes. Build414.6s, AOTlibapp presente/kernelblob ausente, NOdebuggable. EstadosHTTP200/403/200/200/200, bytes12582949/5242880/5242880/5242880/2097189; cinco WorkSpecs/cuatro IDs lógicos/cinco intentos, estados2/5/2/2/2. Renovación conserva taskId, no WorkSpecID. No llamar FAILED al5 ni inferir pausa/resume por bytes.
- Canario ausente en logs y All tests passed observado. SHA fuente e9abcdfffde5c65f75e8211b8be6606a6af0516702568517623d8fde1277232a inmutable; fixture/manifiesto privados retirados, paquete y reverse ausentes revalidados independientemente. validation.json del caso comprobó contratos/estados/hashes/cleanup y endpoints sólo loopback. Emulador/receptor cerrados, puertos8797/5562 sin listeners. No DB cruda ni logs sensibles exportados, ni R2/.env/helper usados. Cleanup de historial nativo por uninstall de instalación nueva propia.
- Alcance: preparación local verificada, no expiración SigV4 ni transporte R2 nativo, background firmado, API/finalización o iOS. Reglas globales AndroidLog del probe privado no equivalen a política de logging de producto. Fase1 parcial/NO-GO y goal activo; siguiente unidad orquestador real y prueba firmada con autorización previa aplicable, sin repetir desktop ni integraciónFase2.

## D27. Orquestar R2 nativo con cleanup recuperable y evidencia separada

- Problema: el target real existía sin controlador host y el helper podía dejar una sesión huérfana si R2 creaba multipart antes de persistir uploadId. No reutilizar runner Windows ni retirar allowlist del runner local aprobado.
- Decisión: native_r2_real.cjs separado, con guardas puras en native_r2_real_guards.cjs, APK release016d2336... aprobado por canary7, preflightAVDsu/packageausente, helper compilado fresco en carpeta de corrida y manifiesto runtime privado. Node no lee.env; sólo helper autorizado abre/verifica/limpia R2. Capturar procesos Java/adb en pipes y emitir sólo metadata segura. No rawlogcat/WorkDB/URLs en archivos de evidencia ni argumentos.
- Guardas: UUID/prefijo/doskeys exactos, mismo bucket/origin y fuente/SHA/rangos, firmas1s/1800s, ventanas UTC y relojAndroid antes de launch, resultado realAndroid con cinco pasos/WorkSpecs y renovaciónID. URLs virtualhost/pathstyle válidas sólo para el bucket del manifiesto. Errores genéricos no reflejan entrada. 3tests puros aprobaron alcances/errores/expiración/identidad, sin HTTP.
- Finally: detener/desinstalar sólo instalación nueva.r2, comprobar ausencia antes de abort/delete; si no se confirma stop, dejar cleanupPending y conservar identidad. Helpercleanup usa UUID validado, aborta uploadId conocido y recupera sesiones sólo de multipart.mp4 bajo ese prefijo. Listado truncado o key inesperada hace fallar antes de deletes amplios. Nunca bucketwide. Sanea manifiesto sólo después de comprobar keys ausentes/cero multipart.
- Evidencia local de recuperación: test SDKJava contra servidorloopback y.envFAKE recupera sesión con uploadId ausente y rehúsa key inesperada sin ningún DELETE. Compilación y1test/2escenarios exit0/26.9s, build/native-r2-cleanup-contract/1794274684161/report.json. Configuración real/R2 no usados en ese test.
- Autorización: usuario pidió usar video local de Downloads y limpiarlo del bucket; se aplica a la corrida exacta, sin pedirla de nuevo. Video15504484bytes. Prueba real native_r2_android1 en curso, UUID3e5231c5-0836-4cc9-a4e2-a5878d58d834, host6422. No darla por aprobada hasta integridad/cleanup y revalidación independiente. Origen SHA8743f28366fbb11b9ff7f5ed42c2b576357d91cbce12d9d6c1a2caa0f9987c68.
- Alternativas descartadas: duplicar PUTs en Dart/Windows como evidencia Android, reutilizar manifiesto firmado anterior, esconder filtración de logs o borrar DB base, limpiar bucket completo, o llamar terminado a callback sin WorkSpecterminal. Sin fork ni rebuild nuevo del motor.
- Consecuencias: nueva cobertura R2Android; no demuestra iOS/físicos, background firmado, finalización automática de API ni invariantes de dominio del producto. Mantener NO-GO mientras gates restantes sigan pendientes. Versiones9.5.9/WorkManager2.11.0, Flutter3.41.6/Dart3.11.4, APKreleaseR8/AOT target36 en Android13API33; helperJavaJBR21/SDK existente en classpath del proyecto.

- Cierre D27: native_r2_android1/6422exit0, UUID3e5231c5-0836-4cc9-a4e2-a5878d58d834, PID2274. HTTP200/403/200/200/200, cinco WorkSpecs y cuatroIDs/cinco intentos, fuenteSHA8743f283... inmutable. Helper verificó partes y SHA de ambosobjetos, luego cleanup exacto tras uninstall, manifiesto saneado y ambas keys/cero multipart comprobados. Independiente validation.json confirma artefactos, firma vencida anteslaunch/reloj y ausencia paquete. AVD cerrado, sin listeners5562/8797. No repetir ni confundir con background firmado/iOS o producto. E14 documenta alcance; D28resuelve mínimoiOS14. Goal/Fase1 siguen parciales, siguiente protocolo/gates iOS.

## D28. Mínimo iOS14 autorizado por el usuario

- Problema: producto declara13 y candidato/probe9.5.9 requiere14; no correspondía elevar el mínimo sin decisión del producto.
- Decisión explícita del usuario: «Permitir iOS 14: conservar este candidato, sujeto a pruebas.» Se autoriza14 como mínimo de producto para esta implementación; no volver a pedir esa decisión en continuidad.
- Motivo: aclaración directa de alcance, no inferencia por silencio ni preferencia del agente. Descarta conservar13 como requisito del motor en esta etapa; no exige un fork para13 ni justifica instalar latest incompatible con Flutter.
- Consecuencias: compatibilidad mínima resuelta a nivel de decisión; app principal aún declara13 en sus archivos y no se modifica como parte de la corrida R2 aislada. Aplicar14 consistentemente en configuración real cuando corresponda a la etapa de integración, usando esta autorización. Probe ya14. No confundir autorización con build/runtime iOS verificado ni aprobación de adopción/producción.
- Gates restantes: Mac/Xcode/iPhone, release y URLSession/rangos/recuperación/recursos/iOSfísico, más Androidfísico. Continuar Fase1/protocolo reproducible, sin avanzar automáticamente Fase2. Versiones candidato9.5.9 y Flutter3.41.6/Dart3.11.4 se conservan.



## D29. Separar bootstrap iOS de transferencia y exigir entorno Apple real

- Problema: preparar comandos desde Windows podía confundirse con verificación iOS o reutilizar pruebas Android/desktop. Swift9.5.9 allTasks agrega holdingQueue y URLSession, por lo que no demuestra por sí solo entrega al sistema. Flutter3.41.6 test fuerza debug; no sirve añadir --release por analogía.
- Evidencia: pbxproj probe declara14 y bundlecom.rodtech.picaleUploadProbe; AppDelegate usa FlutterImplicitEngineDelegate y SceneDelegate. Podfile ausente en Windows es compatible con CocoaPods.setupPodfile del SDK, que omite generación sin Xcode. FeatureSPM3.41.6 disponible/no habilitada por defecto; documentación actualFlutter3.47 cambia baseline. Fuente Apple DTS14855 explica efectos del debugger/simulador y distingue forcequit de relanzamiento.
- Decisión: preflight Node macOS-only, Flutter3.41.6/Dart3.11.4 fijados, Xcode completo/SDKiphoneos/CocoaPods y dispositivo explícito. SPMfalse fijado en flutter.config del pubspec del probe, sin cambiar configuración global ni SDK. La fuente FlutterFeaturesConfig prioriza proyecto/global sobre ambiente: variable sola no garantiza baseline. Simulador nunca cuenta como físico/release y iOS18 no verifica runtime14. Firma siempre no-verificada hasta evidencia propia.
- Target ios_bootstrap_test.dart separado exige Platform.isIOS/defaultTargetPlatformiOS sin overrides, UUID no sensible, grupo vacío y escritura/lectura privada. No enqueue ni firmas, redR2 o traslación de WorkSpec a iOS. Su objetivo es plugin/canal/sandbox, no URLSession handoff. Protocolo IOS_FASE1_PROTOCOL.md separa comandos ejecutables bootstrap/build y gates I01–I07 todavía no implementados.
- Verificación Windows:4tests lógicos finales con fixtures exit0/160ms; preflightCLI final windows_ios_gate2 exit2/macOS-required/projectSpmDisabled=true sin invocar Apple; no xcrun/xcodebuild/swift en PATH. Primer analyze mostró3lints de llaves, corregidos; pubgetoffline --enforce-lockfile exit0 y analyze targetfinal2 exit0/4.4s. No build/runtime iOS, firma ni nuevo R2. Disponibilidad Mac/Xcode/iPhone preguntada al usuario, respuesta pendiente; no asumir falta/disponibilidad.
- Alternativas descartadas: allTasks como evidencia native-only, fluttertest--release inexistente en SDKfijado, simulariOS con override de plataforma, editar Swift/cache sin compilar, seguir migración latestSPM o borrar simulador/app base para preparar estado. Bootstrap requiere instalación propia nueva o identidad recuperada, no wipe amplio.
- Consecuencias: preparación acotada validada estáticamente; siguiente ejecutar preflight/bootstrap en Mac y recoger artifacts antes de implementar transporte/staging/auditoríaURLSession/canario. M01–M16 siguen pendientes donde requieren iOS. D28mínimo14 autorizado se conserva, no preguntar otra vez. No Fase2/producción ni mainapp cambiadas.
- Versiones: background_downloader9.5.9/publicación verificada previa, Flutter3.41.6/Dart3.11.4, mínimoiOS14 aprobado; Xcode/iOSfirmware/CocoaPods del Mac por determinar mediante preflight real, no los fixtures del test. Fuentes Apple/Flutter enlazadas en protocolo.

- Auditoría20:14 de D29: condición externa repetida(2/3 contando cierre de preparación). Windows sin herramientasApple ni conexiónMac/SSH/Xcode disponible y sin reporteMac recibido; pregunta pendiente. Fuentes preparadas intactas. Este turno sólo revalidó estado y no aporta progreso funcional ni espera de proceso vivo. No marcar completo/avanzarFase2; aplicar bloqueo del goal sólo si umbral se confirma en siguiente continuación sin cambio externo.

- Cierre auditoríaD29,20:15: tercera observación consecutiva de misma dependencia (preparación20:13/auditoría20:14/actual20:15). Sin accesoMac confirmado/proporcionado, reporteMac, comandosApple en Windows ni herramienta remota/handleApple. Turno anterior y actual sin progreso funcional; no repetir tests/docs ni saltarFase2. Se marca goal blocked por dependencia externa. Preparación y AndroidR2 verificados se conservan, iOS no acreditado. Reanudar con acceso/reporteMac nuevo; si usuario reanuda sin cambio externo, nueva auditoría desde cero conforme regla del goal. No es pausa solicitada ni objetivo cumplido.

- Actualización explícita del usuario en D29: «Por ahora no tengo acceso a Mac.» Se confirma dependencia externa; goal consultado sigue blocked. No solicitar de nuevo la misma disponibilidad ni interpretar respuesta como permiso para saltarFase1. iPhone no especificado. No reanudación/pausa ni nuevos tests/builds/R2, sólo continuidad. Retomar preflight/bootstrap cuando exista accesoMac real; mínimo14 autorizado permanece.

## D30. Android primero, sin emulador; iPhone sigue con la subida de siempre

10 nov 2026. El usuario eligió seguir con Android mientras no haya Mac, y pidió no usar emulador. Se pasa de la prueba aislada a la app real solo en Android; iPhone no cambia.

- **Servidor (Fase 2/3 mínima):** `clientUploadId` en `MediaAsset` con unicidad `(tenant_id, client_upload_id)`; `presign` y `multipart` con la misma clave devuelven la misma subida (sin otra reserva ni otro conteo diario), y con otro archivo contestan `UPLOAD_ID_CONFLICT`. Firma de 12 h para subidas con clave (la app vieja sigue con 30 min). `GET /media/subidas/{clave}` contesta READY / PENDING / NO_EXISTE y confirma (o une partes) si el archivo ya está entero. La limpieza espera 24 h a las subidas con clave (1 h a las demás). El rescate cada 2 min ahora también une las subidas por partes completas, y una unión concurrente que recibe NoSuchUpload se da por buena si el objeto existe. `/media/usage` anuncia `subidaEnFondo: 1`.
- **App (Android):** `SubidasEnFondo` envuelve background_downloader 9.5.9 (la versión probada): copia el archivo a soporte de la app, guarda un registro propio con escritura atómica, pide permiso con la clave y entrega un PUT nativo; confirma al terminar si la app vive, y si no, lo confirma el servidor. Al abrir Contenido se reconstruyen las tarjetas y se pregunta al servidor por cada subida. Cambiar de negocio no cancela tareas, solo las oculta.
- **Por qué PUT entero y no por partes en el teléfono:** la subida por partes está APAGADA en producción y el PUT nativo es el camino ya probado contra R2 en Android. Límite aceptado: un PUT cortado vuelve a empezar ese archivo (Android lo reintenta hasta 3 veces). Las partes en el teléfono quedan para después.
- **Sin emulador:** se verifica con pruebas automáticas (API y Flutter), análisis y compilación release. La prueba en un teléfono real la hace el usuario con la app instalada; no hay corrida nativa nueva en esta etapa.
