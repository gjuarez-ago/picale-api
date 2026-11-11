# Evaluación del motor de subidas móviles — Fase 1

Fecha de revisión: 9 de noviembre de 2026. Producto: Pícale. Alcance autorizado: Parte 2 / Fase 1 de [la especificación](goal-subidas-moviles-segundo-plano.md#6-fase-1-prueba-técnica-del-motor).

## Decisión

**No aprobar todavía la integración ni la liberación del motor. Mantener `background_downloader 9.5.9` como candidato en el probe aislado.** Android emulado ofrece evidencia de transporte R2 firmado real, persistencia, recuperación y duración. El usuario autorizó iOS14 como mínimo, sujeto a pruebas (D28). Faltan iOS compilado/probado y teléfonos físicos.

E03 conserva la prueba original **Windows/desktop**, cuyo target fuerza `TargetPlatform.windows`. E14 añade una corrida independiente real con `UploadTaskWorker` Android, APK release y manifiesto runtime; no hay override Windows. Ambas verificaron integridad R2 y cleanup por UUID. No representan `URLSession` iOS ni demuestran cambio de app/bloqueo durante una transferencia firmada: esas condiciones se probaron por separado en Android local.

No se requiere cambiar de almacenamiento ni mantener un fork a partir de los resultados actuales. Tampoco hay evidencia suficiente para descartar el candidato por completo. La decisión debe reconsiderarse al cerrar los pendientes de esta matriz, especialmente el costo de rangos en iOS y la compatibilidad mínima del producto.

La app principal conserva su flujo Dart. Sesiones persistentes de dominio, cuota idempotente, aislamiento por workspace, lotes, deduplicación, reconciliación con R2 y finalización autónoma de la API **no están implementados por este probe**; siguen dentro del objetivo completo y sus etapas posteriores. No se inicia Fase 2 con este informe.

## Versión y procedencia comprobadas

| Componente | Valor observado | Alcance |
|---|---|---|
| Paquete seleccionado | `background_downloader 9.5.9`, pin exacto y lockfile propio | Sólo `metricol_app/tool/upload_probe` |
| Publicación seleccionada | 2026-08-29T15:37:19.988407Z | Metadatos oficiales consultados el 2026-11-09 |
| SHA-256 del archivo publicado | `ae55e127f87397b372578fc9474568b879fa78326103c342e00160558d08e527` | Descarga, API de pub.dev y lockfile coinciden |
| SDK requerido por 9.5.9 | Dart `^3.7.0`, Flutter `>=3.29.0` | `pubspec.yaml` publicado y caché coincidentes |
| SDK local | Flutter `3.41.6`, Dart `3.11.4` | Entorno usado por las pruebas |
| Versión posterior observada | `9.6.6`, publicada 2026-11-05; Dart `^3.13.0`, Flutter `>=3.47.0` | No compatible con el SDK local; no se actualizó |
| Android del probe | minSdk 24, target/compile SDK 36 | APK inspeccionado; no se infiere del README del proveedor |
| Android del paquete | minSdk 23 en su `android/build.gradle`; WorkManager `2.11.0` | El README publicado menciona API 21, pero el build y sus dependencias exigen revisar el mínimo efectivo |
| Herramientas Android | Kotlin `2.2.20`, AGP `8.11.1`, Gradle `8.14`; compatibilidad Java 17 | Configuración del probe/builds existentes |
| iOS requerido por paquete/probe | 14.0 | Podspec publicado y proyecto del probe |
| iOS declarado por app principal | 13.0 en archivos actuales | Usuario autorizó14 en D28; aplicar consistentemente en configuración real durante integración. Probe ya14, sin build/runtime iOS acreditado. |

Fuentes públicas: [versión publicada](https://pub.dev/packages/background_downloader/versions/9.5.9), [metadatos inmutables de 9.5.9](https://pub.dev/api/packages/background_downloader/versions/9.5.9), [metadatos de 9.6.6](https://pub.dev/api/packages/background_downloader/versions/9.6.6) y [actividad del repositorio del proveedor](https://github.com/781flyingdutchman/background_downloader/activity). Las publicaciones recientes constituyen evidencia de mantenimiento en esta fecha, sin garantizar soporte futuro ni justificar una actualización del SDK del producto.

La revisión del `LICENSE` publicado encontró texto principal de tres condiciones BSD y una licencia MIT separada para la copia modificada de `localstore`. El badge de pub.dev figura como `unknown`: no debe sustituir la lectura del archivo completo. Conservar el texto y los avisos al distribuir. SHA-256 del `LICENSE`: `1436a440db343fe8af42524d3201b694748f2ae4c32487513b6858c85e219a4b`. No se modificaron licencias de terceros.

Se compararon contra el archivo publicado seis fuentes usadas en la evaluación: modelos Dart, reintentos Dart, podspec, preparación de rangos Swift, configuración de requests Swift y Gradle Android. Todas coinciden con la caché. Procedencia y resultados: **E00**.

## Matriz de las 16 pruebas obligatorias

Las filas siguen exactamente el orden del apartado 6 de la especificación. **Verificado** cubre la afirmación concreta de la fila; **parcial** tiene evidencia acotada y pendientes; **pendiente** carece de la ejecución requerida. No son porcentajes de preparación para producción.

| ID | Requisito | Estado | Evidencia inspeccionada y límite | Falta para cerrar |
|---|---|---|---|---|
| M01 | Seleccionar versión publicada; revisar licencia, mantenimiento, requisitos y límites | Verificado: revisión | E00; pin9.5.9/publicación/lockfile/licencias/fuentes contrastados. D28 autoriza mínimoiOS14. | La revisión y decisión de mínimo no aprueban adopción: completar gates de ejecución iOS/físicos. |
| M02 | PUT binario a URL firmada real | Parcial | E03 desktop y E14 Android nativo release: PUT200 y SHA del objeto completo igual al video original. | Transporte firmado iOS; no repetir desktop ni Android aprobados. |
| M03 | Método, cuerpo, Content-Length y headers efectivos | Parcial | E01/E04/E13 observan requests locales Android; E14 usa el mismo APK y comprueba longitudes de partes e integridad de ambos objetos R2. No hay captura independiente de headers en el servidor R2. | Contrato efectivo iOS; conservar el límite entre headers observados localmente e integridad remota. |
| M04 | Headers agregados por el paquete compatibles con firma | Parcial | E03 desktop y E14 Android aceptan requests firmados. E13 comprueba ausencia de Range/Authorization/Content-Disposition en requests Android locales del mismo APK. | Compatibilidad efectiva iOS contra R2; no extrapolar una pila HTTP a otra. |
| M05 | Parte multipart mediante rango del original | Parcial | E03 desktop y E14 Android nativo: tres partes R2 reales desde el archivo privado, listadas/unidas/verificadas por helper. | La misma operación con iOS. |
| M06 | Límites del rango y primera/intermedia/última parte exactas | Parcial | E13 compara cada byte del fixture12582949; E14 Android/R2 usa video15504484, partes5242880/5242880/5018724, longitudes remotas y SHA del objeto unido iguales al original. E10 añade rango8MiB+37 a offset64MiB. | Runtime iOS; no asumir límites Swift por Android. |
| M07 | Range local no enviado indebidamente a R2 | Parcial | E13 observa ausencia de Range en requests Android; E14 demuestra que el mismo APK envía partes compatibles e íntegras a R2. No captura headers del servidor R2. | Observar requests iOS; distinguir evidencia combinada local/remota de captura directa en R2. |
| M08 | Manejo de Content-Type y firmas de partes | Parcial | E13 Android local usa video/mp4; E14 usa ese mismo APK/headers contra firmas reales. Helper firma partes sin exigir Content-Type; R2 acepta las tres y ambos hashes coinciden. | Combinación efectiva MIME/firma iOS. |
| M09 | Binario sin multipart/form-data | Parcial | E13/E14: postbinary, tamaños/rangos exactos y SHA remota igual al video; un PUT por parte. El multipart de R2 se abre/une en helper, sin envoltura form-data. | iOS; no equiparar multipart del proveedor con body form-data. |
| M10 | Cambiar de app y bloquear Android/iOS físicos | Pendiente: físicos | E04 y E11 cubren emuladores, pantalla OFF y app fuera del frente. No hay evidencia física Android o iPhone. | Prueba física en ambas plataformas. El usuario indicó continuar sin USB; no volver a solicitarlo para repetir este cierre. |
| M11 | Muerte del proceso y reconstrucción al abrir | Parcial | E05: SIGKILL controlado, PID anterior ausente; nueva ejecución exige PID distinto, reutiliza manifiesto y no encola de nuevo; tres partes exactas y parte 1 no repetida. E09 caracteriza replay en un corte distinto. | iOS y condiciones físicas del sistema. SIGKILL no equivale a force-stop manual del usuario ni a un archivo terminado del producto. |
| M12 | 403 vencido y recuperación con URL nueva | Parcial | E14 Android/R2: firma1s vencida antes de launch, relojAndroid comprobado,403→200 con mismo taskId/parte y SHA fuente intacto. Cinco WorkSpecs terminales; renovación crea otro WorkSpec. No se guarda bodyXML sensible. | iOS; no inferir expiración sólo de un403 sin contexto de firma/reloj/renovación. |
| M13 | Reintentos y duración en Android anterior a 14 | Parcial: intervalo probado | E11: API 33, 503→waitingToRetry→200; cuerpo nativo de 682672 ms, mismo WorkSpec/intento en frontera y FGS activo. | No demuestra máximo universal, otras versiones/OEM, batería real o deep Doze. El retry usa Dart antes de entregar la nueva tarea. |
| M14 | Separar pausa de descarga y recuperación de upload | Verificado: capacidad | E00/E01: constructores UploadTask fijan allowPause=false. El probe reconstruye rangos desde metadatos y no presenta pausa de descarga como upload reanudable. | La recuperación de sesión/partes confirmadas por R2 pertenece al dominio de las fases siguientes; no está implementada en la app. |
| M15 | Release Android con reducción e iOS con configuración real | Parcial | E08 inicial y E13/E14 releaseR8/AOT NOdebuggable ejecutado en Android13, mapping presente y APKhash016d2336... fijado. | Release iOS y configuración real de Pícale; probe con certificado debug, no build publicable. |
| M16 | Memoria y temporales, especialmente rangos iOS | Parcial | E10: 159 muestras Android, archivos 16/128 MiB+37 y rango 8 MiB+37. E12: Swift crea temporal del rango y lee el prefijo. | Compilar/medir en iPhone: preparación, pico de disco, RAM, duración y limpieza, incluyendo varias partes y recuperación. |

## Catálogo de evidencia inspeccionada

Los enlaces son archivos locales de los dos repositorios. Muchos artefactos están en `build/` o `target/`, ignorados por Git: no ejecutar `clean` sin conservarlos. Este informe incluye los hallazgos esenciales para que no dependan sólo de un log. No incorporar manifests con URLs firmadas, credenciales o datos privados al documento ni a un commit.

| ID | Evidencia | Qué acredita |
|---|---|---|
| E00 | [Metadatos públicos guardados](../../metricol_app/tool/upload_probe/build/evaluation-audit/published-metadata.json), [integridad de seis fuentes](../../metricol_app/tool/upload_probe/build/evaluation-audit/source-integrity.json), [LICENSE publicado](../../metricol_app/tool/upload_probe/build/evaluation-audit/published-9.5.9/LICENSE), [pin](../../metricol_app/tool/upload_probe/pubspec.yaml), [lockfile](../../metricol_app/tool/upload_probe/pubspec.lock) | Publicación, requisitos, procedencia de código y licencia. El archivo publicado se descargó en esta revisión y coincide con la API/lockfile. |
| E01 | [Contrato y receptor](../../metricol_app/tool/upload_probe/test/upload_contract_test.dart), [log de ocho casos aprobados](../../metricol_app/tool/upload_probe/probe-receiver-attempt-tests.log), [contrato de rangos](../../metricol_app/tool/upload_probe/lib/probe_contract.dart) | Bytes/headers, rangos, error/renovación local, metadatos e intentos solapados. No es firma S3. |
| E02 | [Integración Windows: seis casos](../../metricol_app/tool/upload_probe/probe-windows-integration.log), [lectura desde otro proceso](../../metricol_app/tool/upload_probe/probe-persistence-read.log) | Transporte y persistencia desktop. No prueba un worker móvil. |
| E03 | [Target R2 que fuerza Windows](../../metricol_app/tool/upload_probe/integration_test/r2_contract_test.dart), [tres tests aprobados](../../metricol_app/tool/upload_probe/probe-r2-tests.log), [integridad R2](../target/subidas-phase1-r2-verify.log), [cleanup](../target/subidas-phase1-r2-cleanup.log), [helper](../tools/SubidasR2Probe.java) | Video local de 15504484 bytes; PUT completo, firma real vencida y tres partes; SHA-256 de ambos objetos igual al original. Dos objetos ausentes y cero multipart del prefijo exacto al limpiar. Sin nueva consulta al bucket en esta revisión. |
| E04 | [Recepción completada en segundo plano](../../metricol_app/tool/upload_probe/build/native-background-completed.json), [transición observada](../../metricol_app/tool/upload_probe/build/native-background-transition.json), [target Android](../../metricol_app/tool/upload_probe/integration_test/native_contract_test.dart) | Cuatro requests exactos: archivo completo y tres partes. `SCREEN_STATE_OFF`, `INTERACTIVE_STATE_SLEEP`, MainActivity en historial de pausa y launcher resumido en captura host. No es teléfono físico ni R2. |
| E05 | [Evidencia de recuperación](../../metricol_app/tool/upload_probe/build/native-recovery-evidence.json), [target con rama de reapertura](../../metricol_app/tool/upload_probe/integration_test/native_recovery_test.dart), [SIGKILL externo](../../metricol_app/tool/upload_probe/bin/android_recovery.ps1) | `verified=true`, PID anterior 4538 desaparecido, tres partes correctas; nuevo proceso no repite enqueue y exige identidad distinta. |
| E06 | [Corte/restauración de red](../../metricol_app/tool/upload_probe/build/native-network-evidence.json) | `Active default network: none` durante el corte, tres partes recuperadas, `verified=true`. `canceled` nativo sin intención de usuario no debe convertirse automáticamente en cancelación de dominio. |
| E07 | [Renovación Android](../../metricol_app/tool/upload_probe/build/native-renewal-evidence.json) | Parte 2: 403→200; mismo taskId, rango y archivo; partes 1/3 sin repetición de esa corrida. Es receptor sintético. |
| E08 | [Log release](../../metricol_app/tool/upload_probe/probe-build-android.log), [APK preservado](../../metricol_app/tool/upload_probe/build/evaluation-audit/baseline-release/app-release.apk), [mapping R8 preservado](../../metricol_app/tool/upload_probe/build/evaluation-audit/baseline-release/mapping.txt), [badging revalidado](../../metricol_app/tool/upload_probe/build/evaluation-audit/release-badging.txt) | APK de 50441950 bytes, SHA-256 `3e4a7f71ba192aa92d198a136c19909dac303f3863918bc36b87c741434693f8`; mapping de 11039552 bytes. Copia íntegra conservada antes de reemplazar salidas de builds posteriores. |
| E09 | [Corte causal y recuperación](../../metricol_app/tool/upload_probe/build/fault-audit/fault_window_bgqueue1/report.json), [medición de commit](../../metricol_app/tool/upload_probe/build/commit-audit/commit_seed/summary.json) | HTTP ya aceptado + SQLite aún RUNNING + muerte: mismo WorkSpec termina tras reenvío, dos aceptaciones. La marca complete no equivale a persistencia nativa ni a medio READY. La causa histórica original no se reconstruye retroactivamente. |
| E10 | [Perfil Android](../../metricol_app/tool/upload_probe/build/resource-audit/resources_android36_2/report.json), [validación de crudos](../../metricol_app/tool/upload_probe/build/resource-audit/resources_android36_2/validation.json) | 159 muestras/477 archivos crudos. PSS máximo observado del proceso 169893/161402/156046 KiB; incrementos 7356/399/591 KiB frente a baseline por caso. Crecimiento fuera de fixtures 12/12/16 KiB. Fixtures propios eliminados. |
| E11 | [Resultado Android 13](../../metricol_app/tool/upload_probe/build/long-run-audit/android13_long1/collection-report.json), [validación](../../metricol_app/tool/upload_probe/build/long-run-audit/android13_long1/validation.json), [log final](../../metricol_app/tool/upload_probe/build/long-run-audit/android13_long1/collection-runtime.log) | 503→200, 134217765 bytes por cuerpo, duración final 682672 ms; WorkSpec `[RUNNING,RUNNING,SUCCEEDED]`, intentos `[1,1,1]`; pantalla OFF/actividad STOPPED/FGS activo en frontera. El report.json original fallido se conserva, pero no es el resultado final de la recolección. |
| E12 | [Helpers Swift publicado](../../metricol_app/tool/upload_probe/build/evaluation-audit/published-9.5.9/ios/background_downloader/Sources/background_downloader/Helpers.swift), [requests Swift](../../metricol_app/tool/upload_probe/build/evaluation-audit/published-9.5.9/ios/background_downloader/Sources/background_downloader/BDPlugin.swift), [podspec](../../metricol_app/tool/upload_probe/build/evaluation-audit/published-9.5.9/ios/background_downloader.podspec), [reintentos Dart](../../metricol_app/tool/upload_probe/build/evaluation-audit/published-9.5.9/lib/src/base_downloader.dart) | Sólo inspección estática de fuentes publicadas y contrastadas. No build, ejecución, perfilado ni aprobación iOS. |

### E14 — Android nativo contra R2 real, 9 de noviembre

[Resultado](../../metricol_app/tool/upload_probe/build/native-r2-real/native_r2_android1/report.json),
[validación independiente](../../metricol_app/tool/upload_probe/build/native-r2-real/native_r2_android1/validation.json),
[integridad remota](../../metricol_app/tool/upload_probe/build/native-r2-real/native_r2_android1/r2-verify.log),
[cleanup remoto](../../metricol_app/tool/upload_probe/build/native-r2-real/native_r2_android1/r2-cleanup.log),
[orquestador](../../metricol_app/tool/upload_probe/bin/native_r2_real.cjs),
[guardas aprobadas](../../metricol_app/tool/upload_probe/probe-native-r2-real-guards.log),
[contrato SDK de cleanup recuperable](../../metricol_app/tool/upload_probe/build/native-r2-cleanup-contract/1794274684161/report.json).

Corrida native_r2_android1, UUID3e5231c5-0836-4cc9-a4e2-a5878d58d834,
PID2274/Android13API33, hostexit0. APK release016d2336... ya verificado enE13,
sin overrideWindows ni rebuild. Video15504484bytes, SHA-256
8743f28366fbb11b9ff7f5ed42c2b576357d91cbce12d9d6c1a2caa0f9987c68.
Firma1s vencida antes de launch, relojAndroid comprobado; HTTP200/403/200/200/200.
Cinco WorkSpecs/cuatro taskIds lógicos/cinco intentos, estados2/5/2/2/2;
renovación conserva taskId, no WorkSpecID. Partes5242880/5242880/5018724
listadas en R2 y SHA de ambos objetos igual al video. Fuente inmutable.

El helper recompilado confirmó ambas keys ausentes y cero multipart bajo el
UUID después de detener/desinstalar app. Manifiesto host saneado, fuente y
manifiesto privados borrados, logs sin firmas y tests terminados. AVD cerrado;
sin receptor local ni túnel en esta corrida. No demuestra iOS, físicos,
background firmado, muerte de proceso con R2 ni finalización autónoma de API.
El test SDK local adicional cubre recuperación de uploadId ausente y rechazo
de key inesperada sin deletes; no abre sesiones reales adicionales.

### E15 — Preparación de bootstrap iOS, sin build/runtime acreditado

[Preflight](../../metricol_app/tool/upload_probe/bin/ios_preflight.cjs),
[cuatro tests de lógica](../../metricol_app/tool/upload_probe/probe-ios-preflight-tests-final2.log),
[rechazo Windows](../../metricol_app/tool/upload_probe/build/ios-preflight/windows_ios_gate2/report.json),
[target bootstrap](../../metricol_app/tool/upload_probe/integration_test/ios_bootstrap_test.dart),
[análisis limpio](../../metricol_app/tool/upload_probe/probe-ios-bootstrap-analyze-final2.log),
[protocolo iOS](../../metricol_app/tool/upload_probe/IOS_FASE1_PROTOCOL.md).

Preflight se negó a invocar herramientas Apple desde Windows (exit2,
macOS-required). TestsNode usan fixtures, no Mac real. El target exige iOS
real y no encola transferencias; su análisis estático final exit0/4.4s no
acredita compilación, registro runtime, firma, mínimos efectivos ni URLSession.
Los comandos bootstrap/build están preparados, aún no ejecutados en Mac.
M01–M16 y NO-GO conservan alcance; ningún check iOS se vuelve verde por E15.

El usuario confirmó que por ahora no tiene acceso a Mac; la ejecución iOS
queda bloqueada por esa dependencia. No volver a preguntar sin cambio externo.
Disponibilidad de iPhone no especificada. D28 ya autoriza mínimo14.
Para evitar falsos positivos, el protocolo distingue holdingQueue de URLSession,
testdebug de release, debugger adjunto de suspensión y simulador de iPhone.

## Implicaciones para la implementación futura

### Actualización local: logging y preparación Android→R2, 9 de noviembre

La preparación posterior al informe inicial encontró una filtración de URL
falsa en body403, nivelI/tagTaskRunner, con debug+R8. El canario6 quedó
rechazado. La corrección usa release NO debuggable, AOT coherente y reglas
R8 para eliminar llamadas AndroidLog en el APK privado; no se modificó el
SDK ni el producto. El canario7 pasó con cinco contratos200/403/200/200/200,
12582949bytes de origen inmutable, cinco WorkSpecs terminales/cuatro taskIds
lógicos y cinco intentos. El taskId de parte1 se conserva al renovar;
los WorkSpec IDs son distintos. No inferir que se muta la URL de un WorkSpec
ya persistido. Estados observados2/5/2/2/2; no etiquetar el5 como FAILED.

E13: [filtración redactada](../../metricol_app/tool/upload_probe/build/native-r2-local/native_r2_canary6/leak-metadata.json),
[resultado local release](../../metricol_app/tool/upload_probe/build/native-r2-local/native_r2_canary7/report.json),
[validación independiente](../../metricol_app/tool/upload_probe/build/native-r2-local/native_r2_canary7/validation.json),
[protocolo](../../metricol_app/tool/upload_probe/NATIVE_R2_PROTOCOL.md).
APK SHA-256016d2336edd18f4daec5466ac6bb9426cb91e30b538f3589051a85743e094a66,
17245950bytes; canario ausente en logcat, tests terminados y limpieza de
fuente/manifiesto/instalación/túnel comprobada. Receptor/AVD propios cerrados.

El staging release usa su bajo UID app únicamente en el emulador aislado;
no es una dependencia del producto. El fixture sintético usa MIMEvideo/mp4,
pero no es un contenedor MP4 válido ni prueba validación de medios de API.
El403 de E13 es local/sintético: no acredita expiración SigV4. En esa unidad
todavía faltaba el orquestador real y no se contactó R2; E14 cubre esa brecha
posteriormente, con una corrida firmada distinta y limpieza propia.
M01–M16 conservan su alcance; sigue NO-GO y no hay adopción del motor.
La política de logging del producto requerirá solución explícita; eliminar
todos los Log en el probe privado no constituye por sí sola esa política.

### Transporte y estado de dominio

- Tratar el transporte como capaz de reenviar ante un resultado incierto. No prometer entrega exactamente una vez. En el corte controlado hubo dos aceptaciones de la misma parte después de HTTP 200 y antes de persistir el éxito nativo.
- R2 determina las partes existentes; la API determina READY. No sustituir reconciliación/finalización de servidor por un callback Flutter ni por `WorkSpec.SUCCEEDED`.
- Conservar sesión, propietario/workspace, parte, tamaño y localizador relativo del archivo fuera de headers mutables. Reconstruir Range desde metadatos; el worker puede retirarlo del Task devuelto.
- Usar un único consumidor central de `FileDownloader.updates` en la futura integración: el stream del proveedor es de suscripción única. Este diseño todavía no se aplicó a la app principal.
- Un PUT simple interrumpido puede requerir reenviar el cuerpo completo. La reanudación útil por partes proviene de la sesión multipart y de consultar R2, no de `allowPause`.

### Reintentos y cancelación

En 9.5.9, `BaseDownloader.processStatusUpdate` intercepta failed con reintentos restantes, guarda la espera en Dart y usa `Future.delayed` antes de encolar otra tarea. Android 13 confirmó esa ruta mientras el proceso permanecía vivo. No demuestra que la espera sobreviva sola a la muerte de Dart; el registro de dominio deberá permitir reconstruir trabajo pendiente al abrir.

Configurar explícitamente tanto `retries` como `retriesRemaining` si se cambia el presupuesto mediante `copyWith`: conservar accidentalmente cero reintentos restantes ya causó un fallo del probe. Separar la intención de cancelar del usuario de cancelaciones/paradas emitidas por el planificador al perder red o reemplazar un intento.

### Recursos y compatibilidad iOS

El helper Swift prepara un archivo temporal para el rango. Lee y descarta el prefijo antes de copiar la porción, con buffer explícito de 1 MiB. Ese tamaño de buffer **no es** el consumo total del proceso ni un límite de disco. Deben medirse el original retenido, temporales preparados y sobrecargas del sistema, además de preparación de varias partes, fallos y limpieza.

El perfil Android de E10 no mide ese camino. Sus máximos son muestras del proceso completo en debug; `du` incluye bases de datos/metadatos y no cubre archivos abiertos ya eliminados ni picos entre muestras. Tampoco se infiere una ley de crecimiento por comparar dos tamaños y procesos ya calentados.

La app declara iOS13 y el candidato exige14. El usuario autorizó explícitamente14 (D28), conservando el candidato sujeto a pruebas. La decisión ya no está pendiente; queda aplicar el mínimo en configuración real durante integración y verificar build/runtime iOS. El probe ya declara14. No se requiere fork para mantener13.

### Configuración de ejecución Android

El ensayo API 33 utilizó `Config.runInForeground/Config.always` y notificación. La actividad quedó fuera del frente y la pantalla apagada; el servicio nativo mantuvo el trabajo durante el intervalo comprobado. El emulador estaba alimentado: no acredita restricciones OEM, descarga de batería, deep Doze ni un máximo universal de duración. La [documentación oficial de trabajos largos de WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running) explica el modo de ejecución; no sustituye pruebas del paquete en cada plataforma.

## Brechas y siguiente unidad ejecutable

| Brecha | Evidencia necesaria | Acción autorizada siguiente |
|---|---|---|
| R2 firmado con motor iOS | URLSession, PUT/rangos/expiración e integridad remota con cleanup limitado | Android nativo ya verificado E14. Preparar protocolo iOS y ejecutar cuando exista Mac/Xcode/iPhone y compatibilidad resuelta. No repetir desktop/Android aprobados. |
| Configuración real de mínimo iOS14 | D28 autoriza14, probe ya14; app aún13 | Aplicar14 consistentemente durante integración y compilar configuración real. No volver a pedir autorización ni dar build por verificado. |
| Build/release/runtime y temporales iOS | Mac con Xcode, configuración real y iPhone; evidencia de background, bloqueo, recuperación y recursos | Protocolo reproducible y ejecución cuando estén disponibles. No declarar verificación por Windows o lectura de Swift. |
| Android físico | Evidencia de cambio de app/pantalla bloqueada y restricciones del equipo | Dependencia externa pendiente. No reiterar solicitud USB ya descartada por el usuario. |
| Contrato y finalización de Pícale | Cuota, workspace, lotes, dedupe, sesiones y finalización sin Flutter | Fases posteriores; no implementarlas ni darlas por verificadas dentro de esta evaluación. |

E13 y E14 cierran preparación local y transporte Android→R2 real con integridad/cleanup. D28 resuelve mínimoiOS14 autorizado. La siguiente unidad de Fase1 es preparar el protocolo reproducible iOS contra M01–M16 y ejecutar cuando estén disponibles Mac/Xcode/iPhone. Androidfísico sigue pendiente; el usuario ya indicó continuar sinUSB. No repetir corridas aprobadas ni avanzar Fase2.

## Verificación del informe inicial, previa al canario local

Se revisaron los 16 requisitos, fuentes del target que fuerza Windows, manifiestos/JSON de resultados, logs terminales, integridad de publicación y artefacto release existente. No se ejecutaron nuevas subidas, builds ni suites ya aprobadas. No se modificaron app/API productivas, recursos R2 ni AVDs, y no hay aprobación de Fase 1 o del goal completo.

El índice de comprobaciones y enlaces de esta revisión queda en `tool/upload_probe/build/evaluation-audit/validation.json`. [Avance](subidas-moviles-avance.md) y [decisiones](subidas-moviles-decisiones.md) contienen la continuidad y las condiciones para reconsiderar la recomendación.
