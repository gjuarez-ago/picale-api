# Ejecución del goal: asistente rápido y confiable

Registro intermedio del 21 de octubre de 2026. **Las secciones de pendientes y sesiones de terminal de este archivo son históricas.** Consultar el estado actualizado en [Verificación final](asistente-rapido-verificacion-final.md).

Plan principal: `docs/goal-asistente-rapido-confiable.md`.

## Implementado

- MCP normaliza `textos_diapositivas: []` y, en piezas individuales, `[""]` como ausencia de textos. No descarta textos reales, tipos inválidos ni el orden de un carrusel.
- `ValidarGeneracionTool` reutiliza la preparación de `GenerarContenidoTool`, verifica referencias y obtiene coste/saldo desde `/billing/saldo`. No crea trabajos. Esto funciona también cuando `/billing/summary` no trae créditos por estar exenta la cuenta.
- API valida antes de presentar la confirmación. El coste presentado sale de la validación, no del texto del modelo. Se guarda como dato interno, se oculta de las tarjetas/argumentos MCP y se comprueba otra vez al confirmar. Si aumenta, no se genera.
- Lecturas por turno: claves canónicas, aislamiento por usuario/workspace/permisos, TTL 30 s, límites de memoria y exclusión de escrituras/trabajos vivos. Invalida las lecturas afectadas tras una modificación. Evita reenviar una misma imagen en una continuación si ya está en el contexto.
- Corte de pedidos de generación idénticos ya fallidos: no hay otra confirmación ni repetición de pago automática en ese turno.
- Presupuesto de trabajo activo configurable con `app.assistant.active-timeout-seconds` (120 s por defecto). Acumula segmentos durables en eventos y no consume la espera humana por confirmación. Acota llamadas/reintentos; un corte no afirma haber cancelado una operación externa.
- Métricas de cola, contexto, pasos/modelo/tokens, caché reportada por proveedor, herramientas/reutilización y segmentos activos. No se registran cuerpos/base64/secretos en estas métricas.
- Web muestra eventos `run.progress`, detiene el indicador ante confirmación pendiente y recupera lecturas tras una desconexión. El SSE reconecta tras 25 s sin bytes con `Last-Event-ID`, sin reenviar acciones.
- No se cambió el modelo ni se añadieron pantallas o agentes.
- Se corrigió en `asistente-migracion-responses.md` la interpretación de tokens: continuar con `previous_response_id` no elimina la entrada previa del cómputo/facturación. Referencia: [Conversation state](https://developers.openai.com/api/docs/guides/conversation-state).
- Se evalúa el streaming de texto frente al canal de avance: [Streaming responses](https://developers.openai.com/api/docs/guides/streaming-responses). Los eventos implementados no son streaming de tokens ni prueba de menor tiempo del proveedor.

## Prueba real autorizada y consumida

El usuario autorizó expresamente **3 consultas del asistente y 1 generación de retoque**. Se consumieron las tres consultas y la generación. **No ejecutar más llamadas pagadas ni generaciones sin nueva autorización.** El seguimiento de estado por GET no requiere otra generación ni otra llamada al modelo.

Workspace: `ed843752-8e99-4b67-8b13-9359ce327b12`, Banquetera y Postres.

| Recorrido | Evidencia | Resultado |
|---|---|---|
| Consulta simple | Run `e7d1c15d-6918-41f7-a7e3-d1f2c7a4dab6` | 11,728 s de trabajo activo; una consulta a panel. ACK HTTP 1,877 s, distinto del indicador local inmediato. |
| Primer intento de propuesta de retoque | Run `95e4b66a-ed21-4cc5-85eb-c228814df16e` | 26,677 s; inspeccionó foto y panel, pero no avanzó por falta de coste en el panel. Este fallo motivó la corrección de `/billing/saldo` y coste verificado. No generó imagen. |
| Propuesta de retoque tras corregir coste | Run `93539710-3087-40d7-bd2d-6c0435a66a7c` | 15,679 s hasta confirmación. Solo `ver_archivo` una vez y preflight interno. Sin consultas de marca/identidad/panel repetidas. |
| Confirmación y resultado | Mismo run | 48,169 s de segmento activo; una generación y una consulta de estado de ~37,9 s. Run final `COMPLETED`, trabajo `READY`. |

La espera humana entre segmentos no se incluyó en el trabajo activo. Son muestras puntuales, **no un benchmark ni un p95**; no hay 30 observaciones por escenario.

Trabajo generado: `c4d22066-bf17-44eb-a037-4481e39e48c5`. Una sola versión 4:5 para Instagram, sin titular, texto de apoyo ni captions. No se publicó ni programó y no se guardaron preferencias de marca.

Evidencia local:

- `target/assistant-goal-real.json`: libro de consumo y resultado; no contiene tokens de acceso.
- `target/assistant-goal-eval/original.png` y `retouch.jpg`: original y única salida real, inspeccionados visualmente.
- La escena nocturna y los tipos de platillos se conservan en términos generales y no se añadió texto. La salida es generativa: no certificar identidad de píxeles, ingredientes o porciones con absoluta precisión. La revisión automática `NO_ISSUES_FOUND` es orientativa, no una aprobación humana definitiva.
- API/MCP: logs `target/goal-20261021-080253.out.log` en cada repositorio.

## Verificación automática disponible

- MCP: `GenerarContenidoToolTest` 11 pruebas y `PoliticaDelCatalogoTest` 2, sin fallos.
- API: orquestador 49, presupuesto 3, caché 6, cliente LLM 10, eventos 3; además suites dirigidas de dispatcher, MCP, hilos, resultados visuales, recuperación y campaña. Revisar los XML recientes para el total final; no sumar informes antiguos como si fueran una ejecución única.
- Web: las 66 pruebas de `pages/panel/pages/assistant/*.spec.ts` pasaron.
- Navegador: `visual-identity.spec.ts` pasó sobre el servidor local existente usando `playwright.goal.config.ts`; verifica galería/selección, ausencia de regeneración automática, persistencia y límite de brief.
- Compilación web completa con `NG_BUILD_MAX_WORKERS=1`: pasó, con advertencias de tamaño preexistentes.
- En curso al escribir este estado: última regresión API `target/goal-final-regression.log`, sesión de terminal `66472`. Incluye la normalización equivalente de campos opcionales en `CampaignImageRequest` y sus 3 pruebas nuevas. **Consultar ese handle o los informes antes de volver a ejecutar.**

## Pendiente para completar el objetivo original

1. **Desacoplar la espera de imagen del cierre de la respuesta.** La prueba real reveló que confirmar todavía ejecuta el seguimiento del trabajo y otra llamada al modelo dentro del HTTP de confirmación. `estado_generacion` esperó ~38 s. El trabajo ya corre en segundo plano, pero el usuario sigue esperando la respuesta del chat. No confundir la mejora de 16 s de propuesta con resolver este acoplamiento.
   - Tras iniciar una generación confirmada, devolver inmediatamente el recibo/estado del trabajo con texto determinista y terminar ese segmento sin otra llamada al modelo ni polling bloqueante.
   - Actualizar la tarjeta existente con consultas de estado de solo lectura, sin nuevos mensajes del usuario, sin nuevas generaciones y sin consumir consultas de texto pagadas.
   - Reutilizar el ID guardado, verificar pertenencia del run/trabajo y persistir el resultado para recarga. Mantener claro `CREATING`, `READY`, error de consulta y fallo real del trabajo.
   - Evitar que la propia reconexión del SSE o la recuperación de la tarjeta dispare el modelo o una escritura externa.
2. Revisar fallos de confirmación por `LinkageError`/commit y resultados externos inciertos: no dejar tokens reutilizables que permitan duplicar una generación. Mantener los recibos recuperables y comprobar estado antes de repetir algo incierto. La protección de dispatcher existente cubre la ejecución inicial; no asumir que prueba todo el camino de confirmación.
3. Completar pruebas de desconexión/SSE sin bytes y del nuevo seguimiento de tarjetas, incluyendo recuperación tras recarga, sin generación duplicada.
4. Auditar cada criterio del plan principal con evidencia actual. Documentar configuración, límites, pruebas reproducibles y reversión. No declarar percentiles reales con estas tres muestras ni atribuir al proveedor los tiempos de mocks.
5. Verificar recuperación únicamente para la topología probada: una instancia API local. La recuperación al arrancar existente no tiene leases/propietario de worker y no debe generalizarse a múltiples instancias sin ese diseño.

## Operación local y precauciones

- API y MCP fueron reiniciados de forma acotada; último arranque de API a las 08:03 aproximadamente del 21 de octubre. No se tocó producción. Web sigue en 4200.
- La única generación autorizada ya terminó. No reiniciar si hay nuevas acciones externas en curso; comprobarlo otra vez antes de actuar.
- Existe una confirmación antigua de otro workspace del 18 de octubre; no cancelarla ni recuperarla como si fuera un trabajo activo de Banquetera.
- No borrar cambios ajenos: los tres repositorios tienen muchos cambios anteriores/concurrentes.
- `tools/assistant-goal-smoke.ps1` está limitado al workspace aprobado y guarda contadores antes de enviar/confirmar. Ya agotó su presupuesto; no editar ni reiniciar el libro para volver a consumir.
- Los secretos de `.env` y los argumentos JVM se manejan en memoria, nunca se imprimen ni se incluyen en el informe.

## Repetición sin consumo de pago

API (selección dirigida; comprobar el estado actual de fuentes antes):

```powershell
mvn -q '-Dtest=CampaignImageRequestTest,CampaignImageServiceTest,AssistantOrchestratorTest,AssistantExecutionBudgetTest,AssistantReadCacheTest,AssistantLlmClientTest,AssistantMcpClientTest,AssistantRunEventsTest,AssistantRunDispatcherTest,AssistantThreadsTest,AssistantToolResultTest,TurnosInterrumpidosTest' test
```

MCP:

```powershell
mvn -q '-Dtest=GenerarContenidoToolTest,PoliticaDelCatalogoTest' test
```

Web, desde su repositorio:

```powershell
npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/pages/panel/pages/assistant/*.spec.ts
npx playwright test e2e/visual-identity.spec.ts --config=playwright.goal.config.ts
$env:NG_BUILD_MAX_WORKERS='1'
npm run build
```

La configuración de Playwright del goal usa el servidor ya disponible en 4200 y no reinicia procesos. Los tests usan respuestas controladas y no llaman al modelo real.
