# Chispa: verificación de rapidez y confiabilidad

Fecha: 21 de octubre de 2026. Alcance: API, MCP y web locales; una instancia API. Plan: [goal original](goal-asistente-rapido-confiable.md).

Estado: implementación y verificación funcional completadas, incluido el recorrido principal con servicios reales. Metas estadísticas de latencia no certificadas por tamaño de muestra. No desplegado en producción.

## Qué se corrigió

La demora no tenía una sola causa: campos vacíos de carrusel invalidaban retoques, se repetían lecturas/contexto y la confirmación esperaba el estado de la imagen y otra respuesta del modelo.

- El retoque individual acepta ausencia, nulos y textos opcionales vacíos sin descartar textos válidos de un carrusel. Brief literal, máximo 1.500 caracteres, sin recortes silenciosos.
- El MCP valida argumentos, referencias y coste antes de mostrar la confirmación y vuelve a comprobar el coste al confirmar. El panel no es la fuente de créditos. No se confunde confirmar el brief con aceptar costes o guardar identidad.
- Lecturas reutilizables por turno, aisladas por negocio/usuario/permisos, TTL de 30 segundos y límites de memoria. Las escrituras invalidan las lecturas afectadas; los trabajos vivos no se cachean.
- Presupuesto configurable de trabajo activo, reintentos acotados y métricas de cola, contexto, modelo, tokens, herramientas y segmentos. La espera humana no consume el presupuesto.
- Confirmar una generación aceptada termina con un recibo y texto determinista: no espera la imagen ni vuelve a llamar al modelo. La tarjeta se actualiza mediante consultas de estado sin generar nuevos mensajes o imágenes.
- Reconexión SSE con cursor, recuperación por GET y espera máxima por intento. El seguimiento automático es limitado y permite consulta manual, sin borrar el borrador del usuario.
- Reserva durable por workspace y turno antes de iniciar una generación. Un reenvío devuelve el mismo trabajo; parámetros diferentes o un resultado incierto no provocan otra generación automática. La reserva sobrevive a rollback y reinicio.
- Fallos del trabajador, rechazo de cola y errores de carga tienen recuperación visible. Un fallo posterior a aceptar un trabajo no afirma que la operación externa se canceló.

No se cambiaron modelos, proveedor, créditos, licencias, perfiles ni pantallas. No se añadieron agentes.

## Auditoría de los diez criterios

| Criterio del plan | Evidencia y alcance |
|---|---|
| 1. Retoque de una foto, sin textos, confirmación y un trabajo | Prueba real `93539710-3087-40d7-bd2d-6c0435a66a7c`, trabajo `c4d22066-bf17-44eb-a037-4481e39e48c5`, READY; pruebas de contrato/orquestador/registro. |
| 2. Carrusel conserva textos y orden; inválidos antes de pagar | Suites `GenerarContenidoToolTest`, `CampaignImageRequestTest`, `CampaignImageServiceTest`. |
| 3. Brief, coste e identidad separados | Pruebas de confirmación, coste verificado, brief literal y preferencias; prueba real no guardó marca. |
| 4. Lecturas idénticas se reutilizan | `AssistantReadCacheTest`, `AssistantOrchestratorTest`; no se cachean trabajos vivos. |
| 5. Invalidación y aislamiento | Pruebas de caché por workspace/usuario/permisos y escritura; control de pertenencia del seguimiento. |
| 6. No bucle de errores/generación | Pruebas de firma de fallo, validación previa, presupuesto y reserva durable. |
| 7. Fallos visibles y sin repetir efectos externos | Dispatcher, recuperación, orquestador y registro; persistencia en transacciones independientes probada con H2. |
| 8. Tiempo y desconexión | Presupuesto/cliente LLM, SSE sin bytes, cursor y seguimiento: pruebas controladas, sin consumo real. |
| 9. Recarga y confirmación pendiente | Pruebas web y dos E2E: recuperación de tarjeta, borrador preservado y sin nuevo POST de generación. |
| 10. Regresiones funcionales | Suites dirigidas de hilos, orquestador, campañas y web cubren onboarding, marca, imágenes, carruseles, selección y borradores. No equivalen a probar manualmente todos los módulos ni a ejecutar toda la suite del producto. |

## Verificación automática

- API: **165 pruebas dirigidas**, 16 suites, cero fallos/errores. Informe de ejecución: `target/goal-verification-final.log`; XML de Surefire. Incluye 52 pruebas del orquestador, 55 de campañas, 9 de jobs y pruebas nuevas del registro/controlador/persistencia.
- Tras ajustar el texto de acuse se repitieron las **52 pruebas del orquestador**, cero fallos/errores y salida Maven 0: `target/goal-ack-final.out.log` y XML Surefire. No sumar esta repetición al total como pruebas distintas.
- MCP: **13 pruebas** entre `GenerarContenidoToolTest` y `PoliticaDelCatalogoTest`, cero fallos.
- Web: **71 pruebas** del asistente y transporte SSE, cero fallos; `assistant-goal-stream-tests.log`.
- Navegador: **2 E2E** de galería y actualización en segundo plano, cero fallos; `assistant-goal-async-e2e.log` y `test-results/assistant-generation-background.png` en web.
- Compilación web completa correcta, con advertencias de tamaño preexistentes: `assistant-goal-build-final.log`.

Los fallos y las desconexiones se simularon; no se provocaron fallos de proveedor pagados. Los resultados no son una medición de latencia del proveedor.

## Evidencia real y límites

Perfil autorizado: Banquetera y Postres, workspace `ed843752-8e99-4b67-8b13-9359ce327b12`.

| Muestra | Resultado |
|---|---|
| Consulta simple | 11,728 s de trabajo activo; un panel; ACK HTTP 1,877 s. |
| Primer intento de retoque | 26,677 s; no generó por falta de coste en el panel. Causa corregida usando `/billing/saldo`. |
| Retoque tras corregir coste | 15,679 s hasta confirmación; una lectura de foto y validación interna. |
| Confirmación antigua, antes del desacoplamiento | 48,169 s de trabajo activo; incluía ~37,9 s de espera de estado y otra llamada al modelo. Imagen READY. Ese acoplamiento ya no está en el código final. |
| Consulta final adicional | Run `aa7bb55a-4a9b-4ae9-a9e4-a629f310b0b0`, ACK 1,301 s, duración 41,281 s. Recuperó una imagen existente: dos consultas de contenido con filtros distintos y dos archivos distintos. Consumió una consulta y cero generaciones. No valida por sí sola la nueva confirmación rápida y supera la meta de 30 s de propuesta visual. |
| Nueva versión explícita, autorizada por separado | Run `7e4427d0-4228-4569-afe5-5480f3c41ab8`: ACK HTTP 0,616 s; 16,155 s activos hasta confirmación, una lectura de la foto. Confirmación HTTP **1,602 s**, segmento activo 1,536 s, una llamada a generación de 918 ms, sin modelo ni espera de estado después de confirmar. |

La autorización inicial de tres consultas y un retoque está agotada. La autorización final y su consulta extra expresamente aprobada se consumieron: dos consultas y **un único retoque adicional en total**. Trabajo `5b2c8d0e-1fa0-40c7-a8d3-0199782a690e`, reserva durable única por workspace/turno. No reutilizar ni reiniciar contadores para obtener más consultas o generaciones sin permiso.

Resultado final adicional: **READY**, una versión 4:5 para Instagram, sin titular, texto de apoyo ni captions. La tarjeta `5b28ae64-5328-47bd-bf14-5067ff8273dc` pasó de CREATING a READY conservando su ID. La base registra una sola llamada `generar_contenido` y una sola reserva/trabajo. El seguimiento fue GET sin otro turno del modelo. Se descargó e inspeccionó `target/assistant-goal-eval/retouch-final.jpg`: seis platos y ambiente nocturno conservados en términos generales, sin texto añadido. La corrección de un acuse secundario evita que siga diciendo «en proceso» cuando la tarjeta ya terminó; no requiere otra generación para probarla.

Verificación tras el último reinicio: `Status -FinalTrialAuthorized` recuperó por GET la galería READY persistida, el mismo trabajo y el acuse corregido, sin depender de recuperar la cola en memoria. No se enviaron consultas de texto ni generaciones adicionales. Contadores finales conservados: dos consultas y una imagen adicional.

Evidencia: `target/assistant-goal-real.json`, `target/assistant-goal-real-final.json`; fotografía y primer resultado en `target/assistant-goal-eval/`. Se inspeccionaron visualmente la escena y los platillos: sin texto añadido y con ambiente nocturno conservado en términos generales. Un retoque generativo no garantiza identidad absoluta de píxeles, ingredientes o porciones; requiere revisión humana antes de usarlo.

No se publicó, programó ni guardó marca. **No hay muestra suficiente para mediana/p95** ni para afirmar cumplimiento general de 10/20/30 segundos. La respuesta visual inmediata de la web no es el ACK HTTP. La selección de herramientas por intención y el streaming de tokens se evaluaron, pero no se introdujo un clasificador restrictivo ni se presenta el avance de eventos como streaming de texto.

## Operación y despliegue

- API local reiniciada con las fuentes finales: log `target/goal-20261021-085323.out.log` (prueba real previa en `target/goal-20261021-084139.out.log`). MCP con validación final activo en 8095; web en 4200. Se verificó ausencia de turnos QUEUED/RUNNING y galería READY persistida antes del reinicio. No se tocó la confirmación antigua de otro workspace.
- Configuración nueva: `app.assistant.active-timeout-seconds=120` por defecto. Acota trabajo del chat; no cancela procesos externos ya aceptados.
- Antes de desplegar, aplicar por el procedimiento del entorno [la migración SQL](../sql/20261021_assistant_generation_requests.sql). En desarrollo el arranque creó la entidad mediante la configuración JPA existente. No se ejecutó la migración en producción.
- La recuperación al arrancar se verificó para **una instancia API**. No tiene leases multiinstancia; no habilitar varias instancias suponiendo que esta recuperación protege trabajos de otros workers.
- En el cierre de este goal la cola de imágenes seguía en memoria. Ese pendiente se abordó posteriormente: ver [trabajos de imágenes persistentes](trabajos-imagenes-persistentes.md), su migración y sus límites. Las métricas anteriores pertenecen al recorrido original, no a un benchmark del cambio posterior.

### Reversión sin pérdida de datos

Revertir únicamente los cambios de este goal en una rama/release controlada, conservando cambios ajenos. Coordinar API/MCP/web como un conjunto compatible. Antes de reiniciar comprobar trabajos externos activos. Conservar la tabla de reservas y sus filas, recibos, eventos e imágenes: **no borrar reservas ni resetear identificadores para reintentar**. Volver a una versión que no respete el registro pierde esa protección; no reabrir confirmaciones antiguas para generar otra vez.

### Repetición sin servicios pagados

```powershell
# API
mvn -q '-Dtest=CampaignImageRequestTest,CampaignImageServiceTest,ContenidoJobsTest,ContenidoControllerGenerationTest,AssistantGenerationRegistryTest,AssistantGenerationRequestPersistenceTest,AssistantOrchestratorTest,AssistantExecutionBudgetTest,AssistantReadCacheTest,AssistantLlmClientTest,AssistantMcpClientTest,AssistantRunEventsTest,AssistantRunDispatcherTest,AssistantThreadsTest,AssistantToolResultTest,TurnosInterrumpidosTest' test

# MCP, desde ../picale-mcp
mvn -q '-Dtest=GenerarContenidoToolTest,PoliticaDelCatalogoTest' test

# Web, desde ../metricol_web; servidor existente en 4200 para E2E
npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/pages/panel/pages/assistant/*.spec.ts --include=src/app/services/assistant-stream.spec.ts
npx playwright test e2e/visual-identity.spec.ts --config=playwright.goal.config.ts
$env:NG_BUILD_MAX_WORKERS='1'
npm run build
```

`tools/assistant-goal-smoke.ps1` es exclusivamente local y está limitado al workspace autorizado. Reserva consumo antes de enviar, mantiene libros separados y no publica ni cambia de negocio. No imprimir secretos de `.env`, JWT o argumentos JVM; no incluir cuerpos HTTP/base64 en informes de latencia. Para operaciones reales nuevas hace falta autorización específica.
