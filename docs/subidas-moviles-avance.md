# Avance: subidas móviles persistentes

Última actualización: 10 de noviembre de 2026, America/Mexico_City.

## Checkpoint actual (10 nov 2026): Android integrado, sin emulador

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

### Prompt exacto para siguiente conversación

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
