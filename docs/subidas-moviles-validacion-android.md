# Validación pendiente de la integración Android

## Extensión D41: revisión de concurrencia

APK D41 disponible en `../metricol_app/build/mobile-upload-motor-review/app-release.apk`, firma válida y29fuentes verificadas; SHA256 `a05687481cf5162f63d10e24622aabd6ea7687e5fb2126c2cf7bc67115b286a0`. Reporte en esa carpeta y primer checkpoint de avance. D40 anterior no incluye estas correcciones. Los133tests no acreditan estas pruebas en un teléfono:

| Caso físico/controlado adicional | Resultado esperado | Estado |
|---|---|---|
| Activación/reintento del sistema mientras se consulta tarea/historial | Reconsultar antes de asumir ausencia; sin otro envío ni limpieza de una tarea activa. | Pendiente |
| Cancelación mientras el planificador reactiva una tarea | Conservar copia si vuelve a observarse actividad, sin confirmar ausencia usando lectura anterior. | Pendiente |
| Operaciones simultáneas con ID incompatible | Rechazo sin adoptar otro archivo/grupo ni compartir aceptación. | Pendiente |
| Cerrar/recrear mientras hay solicitud en vuelo | Operación anterior puede terminar; llamadas nuevas al motor cerrado se rechazan sin esperar ni cancelar implícitamente. | Pendiente |

La consistencia observada entre consultas no equivale a una operación atómica entre el registro del SDK y WorkManager. No publicar ni declarar completada la validación nativa basándose en un puerto simulado.

## Extensión D40: adaptador del motor

APK D40 en `../metricol_app/build/mobile-upload-motor/app-release.apk`, firma válida y28fuentes verificadas; SHA256 `da00d290b3ff5931babceb58885d7f10d4247d421d16ca393fa244414ac0ae77`. Reporte en esa carpeta y primer checkpoint de avance.121tests locales usan adaptador/gestor reales y puerto/API simulados; no acreditan las siguientes comprobaciones físicas:

| Caso Android real | Resultado esperado | Estado |
|---|---|---|
| Abrir con tarea esperando conexión/retry o recursos del sistema | Recuperar tarea existente, sin otra firma ni entrega por confundirla con ausente. | Pendiente |
| Abrir con registro running pero tarea realmente perdida | Una recuperación con misma identidad/lote y firma nueva; sin competir con timer Dart tardío. | Pendiente |
| Tarea termina con la app cerrada y falta copia local | Confirmar con API, sin reenviar ni considerar lista sólo por estado nativo. | Pendiente |
| Dos solicitudes de entregar/recuperar el mismo ID | Una entrega activa, sin sustituir otro archivo/grupo. | Pendiente |
| SDK acepta cancelación pero aún reporta actividad | Copia conservada hasta ausencia; no reactivar cancelación persistida al abrir. | Pendiente |
| Reabrir con porcentaje conocido y luego reintentar una fallida | Recuperar porcentaje válido; otro intento empieza desde cero. | Pendiente |
| Cerrar/recrear adaptador o fallar su arranque | Sin doble listener ni pérdida de pendientes; cerrar no cancela las transferencias del sistema. | Pendiente |

No se implementa resume por bytes de PUT simple: requiere multipart posterior. No habilitar iOS ni publicar basándose sólo en estas pruebas locales.

## Extensión D39: segunda revisión

APK revisado disponible en `../metricol_app/build/mobile-upload-d38-review/app-release.apk`, firma válida y fuentes verificadas; SHA256 `5696654671e282aff2dda0489bbe7eaf1b4e75f985cb4916cd806e523e63a3a6`. Reporte en esa carpeta y primer checkpoint de avance. El APK D38 anterior no incluye estos arreglos. Los87tests locales no acreditan las siguientes pruebas físicas:

| Caso físico adicional | Resultado esperado | Estado |
|---|---|---|
| Reabrir Contenido con una tarea en curso | Tarjeta recibe avance y luego READY de API, sin quedarse congelada. | Pendiente |
| Reintentar una vencida y accionar una referencia anterior | La referencia anterior no cancela el intento nuevo ni elimina su copia. | Pendiente |
| Volver con tarjeta fallida/copia ausente y medio READY en API | Recuperar medio existente sin pedir otro permiso ni reenviar bytes. | Pendiente |
| Cancelar durante guardado de permiso/antes de confirmación | Conservar cancelación local y registro; no iniciar una confirmación que aún no se había enviado. Coordinar aparte la que ya estaba en vuelo. | Pendiente |
| Cambiar de cuenta mientras se restaura o consulta estado | Respuesta antigua no crea tarjetas de la otra cuenta ni entrega con su sesión. | Pendiente |
| Recibir eventos repetidos/tardíos mientras se confirma | Una confirmación activa, sin retroceder a otra transferencia por eventos anteriores. | Pendiente |

## Extensión D38: preparación y estados

El candidato D38 incorpora registro v3 y preparación recuperable. Su estado de build/firma está en el primer checkpoint de `subidas-moviles-avance.md`; el APK D37 anterior no contiene estos cambios. La API cb483b7 ya se desplegó según D33: no repetir ese despliegue como requisito de esta unidad. Ningún caso de esta tabla se acredita como prueba física por los tests locales.

| Caso por ejecutar en Android real | Resultado esperado | Estado físico |
|---|---|---|
| Seleccionar varios videos y cerrar durante la primera copia; abrir después | Misma selección/usuario/negocio/lote; recuperar copia o informar original perdido. Ningún parcial se entrega al motor. | Pendiente |
| Cerrar después de validar temporal o renombrar, antes del siguiente guardado | Verificar digest/tamaño y recuperar copia aunque el selector ya no conserve el original. | Pendiente |
| Seleccionar sin red y volver con conexión | Selección persistida, espera reconocible, misma clave/reserva al recuperar. | Pendiente |
| Cambiar de cuenta durante preparación o vencer sesión durante permiso | Sin entrega con la otra cuenta; pendiente conservado y visible sólo a su propietario. | Pendiente |
| Agotar espacio durante preparación; liberar y reintentar | Aviso, parcial identificado, ningún permiso remoto antes de copia validada; recuperación sin perder la tarjeta. | Pendiente |
| Mantener pulsada la tarjeta durante preparación o transferencia para quitarla | Conservar tarjeta/copia si Android no confirma; limpiar después de confirmación. Revisar aparte la carrera con finalización remota. | Pendiente |
| Android aplaza/reintenta una tarea y después la ejecuta | Mostrar espera de Android como activa; progresar sin pedir manualmente otra transferencia. | Pendiente |
| Volver con trabajo duplicado y elegir «subir de todos modos» | Conservar aviso/medio anterior; repetir sólo después de esa confirmación explícita. | Pendiente |

No se asegura ejecución de copia Dart con el proceso muerto ni continuidad tras detención forzada. Si el origen desapareció antes de tener una copia privada íntegra, se solicita elegirlo otra vez. Multipart y coordinación de cancelación/finalización remota continúan pendientes. No hay Mac/Android físico disponible para cerrar esas validaciones en esta sesión.

## Matriz previa D30/D31

Alcance de D30/D31: subidas de Contenido mediante PUT completo y background_downloader 9.5.9. Este documento describe pruebas por ejecutar; ninguna fila acredita una prueba física realizada. iPhone conserva el flujo anterior.

## Orden de entrega

1. Preparar y desplegar la API con el contrato D30 mediante el procedimiento habitual autorizado. Esta unidad no despliega.
2. Comprobar con una sesión de pruebas que `/media/usage` anuncia `subidaEnFondo: 1`. Una API anterior hace que la app conserve el flujo anterior.
3. Instalar el APK de esta revisión en un Android de pruebas y conservar su versión, modelo, versión de Android y restricciones de batería para el reporte. No es necesario conectar USB para los casos manuales siguientes.
4. Ejecutar la tabla, verificar Contenido y cuota desde la API y registrar resultados reales. No confundir una notificación completada con un medio confirmado.
5. Antes de publicar, resolver cualquier fallo, revisar la declaración del servicio `dataSync` en Play y asignar un `versionCode` mayor al publicado. La revisión conserva `1.0.13+14`; no se cambió la versión ni se publicó.

## Pruebas físicas pendientes

Usar archivos de prueba identificables y un negocio autorizado. Anotar qué archivos llegaron para retirar después únicamente esos medios de prueba mediante el flujo normal. No limpiar objetos de otros usuarios ni borrar directamente en el bucket medios registrados por la API.

| Caso | Acción | Resultado exigido |
|---|---|---|
| Cambio de app | Elegir un video suficientemente grande y abrir otra app después de la preparación/entrega nativa | La transferencia puede continuar; al volver aparece el mismo trabajo y finalmente el medio confirmado |
| Pantalla bloqueada | Entregar otro video y bloquear la pantalla durante la carga | El resultado se conserva y queda listo según la API; registrar cualquier restricción del dispositivo |
| Pérdida de red | Interrumpir y recuperar la conexión durante un video | No requiere volver a elegir el archivo; puede reenviar el PUT completo; no crea otra reserva |
| Respuesta de apertura perdida | Con instrumentación controlada, perder la respuesta del permiso y reintentar | Se conserva la copia y se reutiliza `clientUploadId`; no se duplica cuota/conteo diario |
| Regreso a la app | Volver mientras aún hay pendientes | Reconstruye las tarjetas del negocio correcto y consulta estado de servidor |
| Cambio de negocio | Iniciar una tarea en A, pasar a B y regresar a A | No reserva/renueva con B ni muestra los trabajos de A en B; la tarea ya entregada puede terminar para A |
| Cierre de sesión | Iniciar una tarea y cerrar sesión antes de volver | No hace nuevas peticiones autenticadas con otra sesión; el servidor puede rescatar lo ya recibido |
| Sin Flutter activo | Entregar un archivo y dejar que Android termine el proceso mediante una prueba controlada | Transferencia/recuperación y confirmación del servidor conservan la identidad; una salida por el selector no acredita terminación por el sistema |
| Confirmación autónoma | Completar PUT sin mantener Flutter activo | El worker confirma el medio íntegro y la app lo reconoce posteriormente; registrar tiempos reales |
| API inaccesible al confirmar | Terminar PUT mientras falla la comunicación con API | La tarjeta permanece confirmando; no declara listo ni elimina el trabajo sin confirmación |
| Repetidos y cuota | Elegir el mismo archivo y probar límites de cuota | Mantiene los rechazos/confirmación de repetido del producto y no crea reservas duplicadas al reintentar |
| Varios archivos | Elegir varias fotos/videos y revisar la entrega de cada uno | No desaparecen trabajos; las tareas entregadas sobreviven independientemente de la pantalla. Cerrar durante preparación no acredita entrega de los que aún no fueron entregados |
| Recuperación de firma | Mediante entorno controlado, dejar vencer una firma y reabrir/reintentar | Renueva con la misma identidad y negocio, o muestra fallo recuperable; no promete renovación sin Flutter activo |
| Compatibilidad | Ejecutar en Android anterior a 14 y Android 14+ | Registrar ambos resultados; la compilación release no sustituye estas pruebas |

El cierre forzado desde Ajustes, apagar el teléfono y las restricciones del fabricante tienen límites del sistema; no se promete ejecución continua en esos estados. No mostrar esta implementación como reanudación por bytes: un PUT interrumpido empieza nuevamente el archivo.

## Evidencia que debe quedar al cerrar

- Logs de tests/análisis/build y hashes de APK/AAB de la misma revisión.
- Resultado por fila con dispositivo, archivo/tamaño y momento de prueba; `pendiente` si no se ejecutó.
- Medio READY y reserva/cuota sin duplicados, sin copiar tokens, URLs firmadas ni logs nativos sensibles.
- Identificación y retirada de medios de prueba creados; no afirmar limpieza si no se verificó.
- iOS permanece pendiente de Mac/Xcode y pruebas propias; esta matriz no lo valida.
