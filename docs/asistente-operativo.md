# Asistente operativo de Pícale

## Visión

Crear una nueva vista `Asistente` dentro del panel de Pícale: una conversación
persistente por workspace que conoce la marca, consulta información vigente,
organiza campañas y usa las capacidades existentes del MCP para realizar trabajo
real. No es un chatbot de soporte ni una pantalla paralela para cada módulo. Es
la entrada conversacional a Pícale.

La experiencia debe permitir peticiones como:

- «Planea una campaña para el Día de Muertos durante tres semanas».
- «¿Qué contenido nos funcionó mejor este mes y qué repetirías?».
- «Tengo estas ocho fotos; decide qué piezas conviene crear».
- «Prepara la próxima semana, pero no publiques nada todavía».
- «¿Qué está fallando y qué necesita mi atención?».
- «Cambia el tono de esta campaña para que se sienta más premium».

El asistente conversa, investiga con herramientas, explica sus decisiones y
propone acciones. Las lecturas pueden ejecutarse automáticamente; las acciones
externas o difíciles de revertir requieren confirmación humana.

## Principios de producto

1. **Una conversación, no otra consola.** El usuario habla; los detalles técnicos,
   nombres de herramientas, prompts y llamadas MCP permanecen ocultos.
2. **Datos reales antes que memoria del modelo.** Marca, publicaciones, métricas,
   contenido y estados se consultan en el momento mediante herramientas.
3. **Proponer antes de ejecutar.** El asistente puede preparar campañas,
   calendarios, textos y piezas; publicar ahora siempre requiere confirmación.
4. **Toda afirmación importante muestra evidencia.** «Instagram rindió mejor»
   debe indicar periodo, publicaciones analizadas y cifras que sostienen la conclusión.
5. **Reutilizar las pantallas existentes.** Una tarjeta puede abrir Contenido,
   Calendario, Agente o el compositor; no se recrean esos productos dentro del chat.
6. **Aislamiento total por workspace.** Conversaciones, búsqueda, herramientas y
   documentos respetan el workspace activo y los permisos de la persona.
7. **Una acción, un recibo.** Después de usar una herramienta se muestra qué cambió,
   dónde verlo y cómo corregirlo o deshacerlo cuando aplique.

## Experiencia de usuario

### Ruta y navegación

- Nueva ruta: `/panel/asistente`.
- Entrada principal del menú: `Asistente`, cerca de `Hoy` y `Agente`.
- En móvil ocupa toda la pantalla; en escritorio conserva una sola columna de
  conversación con un panel lateral plegable para campaña y fuentes.
- Cada workspace conserva sus propios hilos. Cambiar de workspace nunca mezcla
  memoria ni resultados.

### Pantalla inicial

En vez de una caja vacía, muestra el estado del negocio y cuatro accesos útiles:

- **Planear una campaña**
- **Preparar mi semana**
- **Revisar resultados**
- **Resolver pendientes**

Debajo aparecen como máximo tres avisos accionables: publicaciones fallidas,
propuestas esperando aprobación, redes por reconectar o huecos del calendario.

### Respuestas enriquecidas

El texto se combina con componentes pequeños y reutilizables:

- tarjeta de campaña: objetivo, periodo, audiencia y avance;
- calendario propuesto: fecha, pieza, red y estado;
- publicación o propuesta: preview, texto, redes y acciones permitidas;
- resultados: comparación, periodo y fuente;
- solicitud de confirmación: consecuencia exacta y botón inequívoco;
- recibo de herramienta: hecho, resultado, enlace y eventual error parcial.

Las tarjetas ayudan a decidir; no convierten el chat en un dashboard complejo.

### Contexto implícito

El asistente recibe, sin que la persona lo repita:

- workspace, usuario, zona horaria y permisos;
- ruta actual y, cuando aplique, el ID de la publicación, campaña o archivo abierto;
- perfil de marca y redes conectadas;
- campaña activa del hilo;
- resumen acotado de la conversación anterior.

Puede aceptar archivos adjuntos o referencias a elementos existentes: «usa la
foto del escaparate», «la publicación de ayer» o «estas cuatro imágenes».

## Casos de uso prioritarios

### 1. Consultor del negocio

Responde qué pasó, por qué probablemente pasó y qué conviene hacer después.
Consulta `panel`, `marca`, `publicaciones`, `rendimiento`, `bitacora_ia` y las
reglas vigentes. No usa embeddings para métricas vivas.

### 2. Planeador de campañas

Convierte una intención en un brief claro:

- objetivo y resultado esperado;
- audiencia, oferta, tono y mensaje central;
- fechas y redes;
- pilares y mezcla de formatos;
- material disponible y faltante;
- calendario de piezas;
- métricas con las que se evaluará.

Si faltan datos que cambian de verdad el resultado, hace una pregunta breve.
Después presenta el plan completo y permite aprobarlo o ajustar una parte.

### 3. Productor de contenido

Busca material en `contenido`, inspecciona archivos, llama al Director Creativo,
genera piezas y prepara borradores. Debe preferir activos existentes antes de
gastar créditos y mostrar el costo estimado antes de una generación pagada.

### 4. Operador del calendario

Busca huecos, prepara publicaciones, mueve borradores y explica conflictos por
red, formato o duración. Programar requiere una confirmación resumida; publicar
ahora conserva la confirmación reforzada que ya usa el MCP.

### 5. Copiloto diario

Al abrir puede contestar «¿qué necesita mi atención?» con un resumen ordenado:
fallos, comentarios, aprobaciones, faltantes de contenido y oportunidades. Nunca
ejecuta toda la lista silenciosamente.

## Capacidades MCP que se reutilizan

El MCP ya cubre una base amplia y debe ser el catálogo canónico de capacidades:

| Dominio | Herramientas actuales |
|---|---|
| Contexto | `cuenta_actual`, `cambiar_cuenta`, `panel` |
| Marca | `marca`, `actualizar_marca` |
| Contenido | `contenido`, `ver_archivo`, `subir_archivo` |
| Publicaciones | `publicaciones`, `ver_publicacion`, `crear_publicacion`, `publicar_ahora`, `cancelar_publicacion`, `archivar_publicacion` |
| Producción | `generar_contenido`, `estado_generacion` |
| Agente | `agente_estado`, `agente_bandeja`, `aprobar_propuesta`, `cambiar_propuesta`, `descartar_propuesta`, `decidir_observacion`, `revisar_con_agente`, `agente_configurar`, `agente_pausar` |
| Inteligencia | `rendimiento`, `bitacora_ia` |

### Capacidades que conviene agregar

1. `crear_lote_creativo` y `estado_lote_creativo`: exponer el Director Creativo
   y sus reels desde el mismo catálogo.
2. `comentarios_pendientes`, `responder_comentario` y `marcar_comentario`.
3. `comparar_rendimiento`: dos periodos, redes o campañas con estructura estable.
4. `guardar_campana`, `ver_campana`, `actualizar_campana` y `cerrar_campana`.
5. `buscar_conocimiento`: aparece sólo cuando exista la base documental.

No se debe enseñar al modelo a llamar controladores arbitrarios. Únicamente ve
herramientas allowlistadas, tipadas y con anotaciones de riesgo.

## Evolución del MCP: de conector a centro de control

Para que la experiencia se sienta realmente potente, el MCP debe cubrir ciclos
completos de trabajo, no solamente operaciones aisladas. La unidad visible para
la persona es: **entender → proponer → producir → aprobar → programar → medir →
mejorar**.

### 1. Archivos y contexto visual

El chat integrado debe subir archivos directamente a R2 con las URLs firmadas de
Pícale, no hacer pasar videos grandes por el servidor MCP. Al terminar, entrega al
modelo IDs de `MediaAsset` seguros y pertenecientes al workspace.

Nuevas herramientas:

| Herramienta | Resultado |
|---|---|
| `buscar_contenido` | Busca por nombre, fecha, tipo, etiquetas, descripción visual, campaña o uso |
| `analizar_material` | Resume qué muestran varios archivos, calidad, duplicados y usos recomendados |
| `organizar_contenido` | Etiqueta, agrupa o asigna material a una campaña sin mover el original |
| `editar_imagen` | Recibe una instrucción: «quita el fondo», «hazla más cálida», «borra este texto» |
| `crear_variantes` | Produce tamaños y composiciones para feed, historia, reel y portada |
| `comparar_versiones` | Presenta original y variantes, explica cambios y conserva trazabilidad |
| `archivar_contenido` | Archiva con preview y confirmación; nunca borra definitivamente |

«Actualiza esto» debe resolverse con referencias explícitas en este orden:

1. archivo o tarjeta seleccionada en el mensaje;
2. entidad abierta en la ruta actual;
3. último artefacto mencionado en el hilo;
4. si quedan dos candidatos plausibles, preguntar mostrando ambos.

Toda edición crea una versión nueva ligada al original. El usuario puede comparar,
volver a una versión anterior y saber qué instrucción originó cada variante.

### 2. Campañas como objeto operativo

Nuevas herramientas:

| Herramienta | Resultado |
|---|---|
| `planear_campana` | Devuelve brief, fases, calendario, formatos, material y métricas objetivo |
| `guardar_campana` | Persiste el plan aprobado y sus decisiones |
| `generar_piezas_campana` | Llama al Director Creativo y crea un lote vinculado a la campaña |
| `preparar_calendario_campana` | Convierte piezas aprobadas en borradores con fecha y red |
| `validar_campana` | Detecta huecos, formatos incompatibles, material faltante y fechas riesgosas |
| `estado_campana` | Muestra avance, pendientes, costo, piezas y publicaciones |
| `medir_campana` | Compara resultados contra objetivos y propone el siguiente experimento |
| `cerrar_campana` | Congela resultados y produce un resumen reutilizable |

Una campaña no debe ser un texto largo guardado en el chat. Debe ser una entidad
con estado, responsables, objetivos, piezas, publicaciones, decisiones y resultados.

### 3. Edición mediante instrucciones naturales

El asistente puede interpretar solicitudes como:

- «Cambia el precio a $299 en todas las piezas, pero no publiques».
- «Haz una versión más elegante de la segunda imagen».
- «Recorta este video a sus mejores 30 segundos y agrega subtítulos».
- «Usa estas fotos, pero evita repetir el producto rojo».
- «Actualiza el WhatsApp de la campaña y vuelve a generar los artes afectados».

Antes de una modificación en lote muestra alcance e impacto: cuántas piezas,
cuáles están publicadas, cuáles se regenerarán, créditos estimados y qué no puede
cambiarse. Nunca altera silenciosamente una publicación ya publicada.

Además de `editar_imagen`, el MCP necesitará posteriormente:

- `editar_video`: recorte, portada, subtítulos, silencio y formato;
- `actualizar_pieza`: texto, CTA, oferta, logo, colores o instrucciones creativas;
- `regenerar_afectadas`: aplica un cambio de campaña únicamente a piezas no publicadas;
- `deshacer_accion`: revierte una operación cuando existe una versión anterior segura.

### 4. Bandeja unificada y conversación con la audiencia

Para acercarse a la sensación de control de HighLevel, Pícale necesita una bandeja
operativa, aunque siga enfocado en contenido:

| Herramienta | Resultado |
|---|---|
| `comentarios_pendientes` | Agrupa lo que requiere respuesta por urgencia y red |
| `proponer_respuestas` | Redacta opciones con voz de marca y contexto de la publicación |
| `responder_comentario` | Publica una respuesta sólo después de confirmarla |
| `marcar_comentario` | Atendido, seguimiento o ignorado con motivo |
| `resumen_conversaciones` | Preguntas frecuentes, objeciones y oportunidades detectadas |

Una etapa posterior puede convertir conversaciones en oportunidades o tareas, pero
no conviene intentar construir un CRM completo antes de dominar contenido,
publicación, comentarios y resultados.

### 5. Control multi-cuenta para agencias

El MCP ya cambia de cuenta; debe sumar una vista de cartera sin mezclar permisos:

- `resumen_cuentas`: salud, publicaciones fallidas, aprobaciones y huecos por cuenta;
- `prioridades_agencia`: lista ordenada de lo que merece atención hoy;
- `comparar_cuentas`: métricas normalizadas y tendencias, no rankings engañosos;
- `ejecutar_en_cuentas`: operación por lote con preview y autorización por cuenta;
- `reporte_cliente`: resumen de una cuenta listo para compartir o exportar.

El asistente puede decir «prepara la semana de mis cinco cuentas», pero debe crear
un plan separado por workspace y mostrar costo, permisos y confirmación de cada una.

### 6. Automatizaciones visibles y pausables

No se deben esconder automatizaciones dentro de una conversación. Cada una se
guarda como una receta visible con disparador, condiciones, pasos, límites, última
ejecución y botón de pausa.

Ejemplos:

- cada lunes, analizar la semana anterior y preparar cinco borradores;
- cuando se suba material nuevo, revisarlo y proponer una campaña;
- avisar si una publicación falla o una red necesita reconexión;
- después de siete días, medir una campaña y sugerir un ajuste;
- resumir preguntas frecuentes detectadas en comentarios.

Herramientas: `crear_automatizacion`, `simular_automatizacion`,
`automatizaciones`, `pausar_automatizacion`, `reanudar_automatizacion` y
`historial_automatizacion`.

El primer lanzamiento sólo permite disparadores internos conocidos. No acepta
webhooks o código proporcionado por el modelo.

### 7. Resultados que terminan en decisiones

Además de `rendimiento`, agregar herramientas analíticas con entradas y salidas
estables:

- `comparar_periodos`;
- `comparar_formatos`;
- `analizar_publicacion`;
- `detectar_patrones`;
- `medir_objetivo`;
- `recomendar_experimento`.

La respuesta debe separar **dato**, **interpretación** y **recomendación**. Una
correlación nunca se presenta como causa y una recomendación siempre puede
rastrearse a publicaciones y periodos concretos.

### 8. Plan de acciones antes de tocar nada

Para solicitudes grandes, el asistente genera un artefacto `ActionPlan`:

- objetivo entendido;
- supuestos y datos usados;
- acciones en orden;
- herramientas que utilizará;
- costo/créditos estimados;
- cambios reversibles y no reversibles;
- confirmaciones necesarias;
- resultado esperado.

La persona puede aprobar todo el plan, editar un paso o pedir una simulación. La
aprobación general sólo cubre operaciones de niveles 0–2 incluidas y acotadas en
ese plan; publicar o responder públicamente conserva su confirmación final.

### 9. Modos de autonomía

Cada workspace elige un modo comprensible:

| Modo | Alcance |
|---|---|
| **Recomendar** | Lee y propone; no crea nada |
| **Preparar** | Puede crear campañas, variantes y borradores; no programa |
| **Operar con aprobación** | También programa y ejecuta cambios confirmados |

No se ofrece inicialmente un modo «haz todo solo». El modo nunca amplía permisos
del usuario ni evita confirmaciones de nivel 3.

### 10. Funciones profesionales del propio MCP

El protocolo necesita capacidades operativas que hagan confiable el crecimiento:

- registro único de herramientas con versión y propietario;
- `dryRun` estándar para escrituras y operaciones por lote;
- esquemas de salida, errores tipados y recibos consistentes;
- progreso y cancelación para trabajos largos;
- idempotencia en toda creación o generación;
- paginación y filtros consistentes;
- capability discovery según permisos, plan y estado de la cuenta;
- telemetría por herramienta: latencia, éxito, costo y correcciones;
- compatibilidad hacia atrás y deprecación visible;
- pruebas contractuales MCP ↔ API y evaluaciones conversacionales;
- límites por herramienta, usuario y workspace;
- confirmaciones firmadas, acotadas y con expiración;
- compensaciones o `deshacer` para operaciones reversibles.

## Experiencias demostrativas que deben funcionar

Estas conversaciones sirven como norte de producto y como pruebas E2E:

### «Sube esto y crea una campaña»

1. La persona adjunta fotos y videos.
2. Se suben directamente a R2 y aparecen en Contenido.
3. El asistente analiza el material, descarta duplicados y pregunta objetivo/fecha
   sólo si no puede inferirlos con seguridad.
4. Presenta brief, plan, piezas propuestas, calendario y costo.
5. La persona aprueba; se genera el lote creativo y se muestra progreso.
6. Revisa variantes, permite cambios naturales y prepara borradores.
7. Programar requiere confirmación; publicar ahora requiere otra confirmación.

### «Actualiza esto»

1. La persona selecciona una pieza y pide un cambio.
2. El asistente identifica inequívocamente el objeto y muestra qué versión editará.
3. Crea una variante, no pisa el original.
4. Presenta comparación y piezas dependientes afectadas.
5. Con aprobación, reemplaza únicamente borradores o propuestas; lo publicado queda intacto.

### «¿Cómo vamos y qué harías?»

1. Consulta objetivos, campaña, publicaciones y rendimiento del periodo.
2. Presenta cifras y fuentes.
3. Distingue hechos de hipótesis.
4. Recomienda un experimento concreto y medible.
5. Puede convertirlo en campaña o borradores sin obligar a cambiar de pantalla.

### «Pon en orden todas mis cuentas»

1. Resume la cartera y ordena fallos, aprobaciones y huecos por impacto.
2. Propone un plan separado por cuenta.
3. Ejecuta lecturas en lote; cada escritura respeta permisos y confirmaciones.
4. Entrega un recibo final con completadas, omitidas y fallidas.

## Arquitectura propuesta

```text
metricol_web /panel/asistente
        │  SSE: texto, tarjetas, herramientas y progreso
        ▼
metricol.api · AssistantOrchestrator
        ├── conversaciones, campañas, permisos, costos y auditoría
        ├── proveedor LLM con tool calling
        └── cliente MCP interno
                    ▼
             picale-mcp · catálogo y ejecutor de herramientas
                    ▼
             servicios de dominio de metricol.api
```

### Decisión importante

El asistente debe consumir el catálogo MCP; no copiar a mano veinte herramientas
en otro prompt. Para evitar que API → MCP → API pierda identidad, se usa un token
delegado de vida corta, firmado por la API y ligado a `userId`, `workspaceId`,
permisos, `threadId` y `runId`. El MCP valida esa delegación y llama a la API en
nombre de esa persona. Nunca recibe una llave maestra ni puede cambiar de workspace
fuera del token.

En una segunda etapa se puede extraer un módulo compartido de contratos para que
el MCP externo y el asistente integrado usen exactamente los mismos esquemas. El
MVP no necesita reescribir los servicios de negocio.

### Flujo de un turno

1. La web envía mensaje, adjuntos y contexto de pantalla.
2. La API valida workspace, licencia, permisos, cuota y pertenencia del hilo.
3. Se recupera un resumen corto del hilo y contexto estructurado necesario.
4. El modelo decide responder o solicitar una herramienta.
5. El orquestador valida herramienta, argumentos, riesgo e idempotencia.
6. Las lecturas se ejecutan; una escritura sensible se detiene en `WAITING_CONFIRMATION`.
7. La web transmite progreso y presenta la confirmación si corresponde.
8. Con la autorización, se ejecuta una sola vez y se guarda un recibo auditable.
9. La respuesta final enlaza los objetos reales creados o consultados.

El turno completo tiene un máximo de herramientas y tiempo. Si una llamada falla,
el asistente conserva lo ya hecho y explica el resultado parcial.

## Modelo de datos

### Conversación

- `assistant_threads`: workspace, creador, título, campaña opcional, resumen y fechas.
- `assistant_messages`: rol, contenido visible, adjuntos, tokens y modelo.
- `assistant_runs`: estado, intención, latencia, costo, error seguro e idempotency key.
- `assistant_tool_calls`: herramienta, argumentos saneados, riesgo, resultado resumido,
  entidad afectada, confirmación, duración y estado.

### Campaña

- `campaigns`: nombre, objetivo, audiencia, oferta, fechas, estado y responsable.
- `campaign_items`: tipo de pieza, fecha sugerida, redes, publicación/propuesta asociada
  y estado (`IDEA`, `DRAFT`, `READY`, `SCHEDULED`, `PUBLISHED`, `FAILED`).
- `campaign_goals`: indicador, valor base, objetivo, periodo y resultado final.

La campaña organiza entidades existentes; no duplica el contenido ni la publicación.

## Memoria y base de conocimiento

### Nivel 1: verdad estructurada

Primero se consulta por herramientas: marca, contenido, campañas, publicaciones,
comentarios, resultados, calendario, permisos y reglas. Es información cambiante y
no debe vectorizarse.

### Nivel 2: memoria de conversación

Se conserva el hilo y un resumen incremental. Decisiones estables aceptadas por la
persona —por ejemplo «esta campaña no usa descuentos»— se guardan en la campaña,
no quedan enterradas únicamente en mensajes.

### Nivel 3: conocimiento documental con RAG

Después del MVP se agrega una biblioteca de conocimiento por workspace:

- catálogos, menús, fichas de producto, preguntas frecuentes y manuales;
- PDF, DOCX, texto y páginas autorizadas;
- extracción, fragmentación, embeddings, índice `pgvector` y metadatos;
- búsqueda híbrida por similitud, palabras, tipo, vigencia y campaña;
- respuesta con citas al documento y fragmento utilizado.

Cada fragmento lleva `workspaceId`, documento, versión, permisos y vigencia. Al
reemplazar o borrar un documento se invalidan sus fragmentos. Nunca se mezcla
conocimiento entre cuentas. Las instrucciones encontradas dentro de un archivo se
tratan como contenido, no como órdenes para el agente.

## Seguridad y control humano

### Niveles de riesgo

| Nivel | Ejemplos | Comportamiento |
|---|---|---|
| 0 · lectura | métricas, marca, calendario, contenido | automático |
| 1 · preparación reversible | crear borrador, plan, generar propuesta | aviso de costo y recibo |
| 2 · cambio operativo | programar, cambiar marca, aprobar varias piezas | preview y confirmación |
| 3 · efecto externo | publicar ahora, responder públicamente | confirmación explícita y específica |

- Los permisos se validan en backend en cada llamada, aunque el modelo crea que puede.
- Confirmar una acción no confirma acciones futuras ni una campaña completa.
- Las escrituras usan idempotency keys para evitar duplicados por reintento.
- Cada acción se registra como `X-Picale-Origen: asistente` en `acciones_ia`.
- Prompts, tokens, secretos, URLs firmadas y respuestas crudas no van a logs.
- El contenido subido y los resultados de herramientas se consideran datos no confiables
  frente a prompt injection.

## API y streaming

Endpoints sugeridos:

- `POST /api/v1/assistant/threads`
- `GET /api/v1/assistant/threads`
- `GET /api/v1/assistant/threads/{id}`
- `POST /api/v1/assistant/threads/{id}/messages`
- `GET /api/v1/assistant/runs/{id}/events` mediante SSE
- `POST /api/v1/assistant/runs/{id}/confirm`
- `POST /api/v1/assistant/runs/{id}/cancel`
- CRUD de campañas bajo `/api/v1/campaigns`

Eventos SSE: `message.delta`, `tool.started`, `tool.progress`, `tool.completed`,
`confirmation.required`, `artifact.ready`, `run.completed` y `run.failed`.

## Entregas por fase

### Fase 0 · Contrato y evaluación

- Inventariar y clasificar todas las herramientas MCP.
- Definir token delegado, riesgos, permisos, idempotencia y recibos.
- Preparar 40–60 conversaciones de evaluación con cuentas ficticias.
- Acordar qué puede hacer sin preguntar y qué siempre confirma.

**Termina cuando:** una matriz herramienta × permiso × confirmación está aprobada.

### Fase 1 · Asistente útil de solo lectura

- Ruta web, hilos, streaming, historial y cambio seguro de workspace.
- `panel`, `marca`, `contenido`, `publicaciones`, `rendimiento` y `bitacora_ia`.
- Respuestas con evidencia, enlaces a pantallas y estados de carga/error.
- Sin acciones de escritura.

**Termina cuando:** responde correctamente estado, rendimiento y pendientes sin
inventar cifras ni cruzar cuentas.

### Fase 2 · Preparar y ejecutar con confirmaciones

- Crear borradores, generar contenido, operar bandeja y programar.
- Tarjetas, previews, costos, confirmaciones, cancelación e idempotencia.
- Auditoría completa y recibos visibles.

**Termina cuando:** una persona puede preparar una semana desde el chat y ninguna
acción sensible ocurre sin la confirmación requerida.

### Fase 3 · Campañas completas

- Entidades de campaña, brief conversacional, calendario y objetivos.
- Integración con Director Creativo, reels y compositor.
- Seguimiento del avance y cierre con resultados contra objetivo.

**Termina cuando:** desde una idea se obtiene una campaña persistente con piezas
editables y trazabilidad hasta sus publicaciones y resultados.

### Fase 4 · Base de conocimiento

- Ingesta documental, extracción, versionado, embeddings y `pgvector`.
- `buscar_conocimiento`, citas, vigencia y eliminación segura.
- Evaluaciones de recuperación y respuestas sin fuente suficiente.

**Termina cuando:** responde sobre productos y procesos internos citando la fuente,
y reconoce cuando el conocimiento cargado no contiene la respuesta.

### Fase 5 · Proactividad controlada

- Resúmenes semanales y avisos inteligentes dentro de Pícale.
- Recomendaciones basadas en objetivos y anomalías reales.
- Nunca publica ni responde comentarios por iniciativa propia.

### Estimación orientativa

Con una célula pequeña de producto/diseño, frontend y backend/IA:

| Entrega | Esfuerzo estimado |
|---|---:|
| Fase 0 · contrato y evaluaciones | 1 semana |
| Fase 1 · lectura y conversación | 2–3 semanas |
| Fase 2 · acciones y confirmaciones | 2–3 semanas |
| Fase 3 · campañas completas | 3–4 semanas |
| Fase 4 · conocimiento/RAG | 2–3 semanas |

El MVP útil y seguro corresponde a Fases 0–2: aproximadamente 5–7 semanas. Las
estimaciones deben recalibrarse después de implementar el token delegado y dos
herramientas verticales de extremo a extremo.

## Calidad, observabilidad y costos

Métricas mínimas:

- tarea resuelta sin salir del asistente;
- tiempo hasta primera respuesta y hasta resultado;
- éxito, fallo y latencia por herramienta;
- acciones confirmadas, canceladas y corregidas;
- borradores que terminan programados o publicados;
- campañas que alcanzan su objetivo;
- respuestas con evidencia suficiente;
- tokens y costo por turno, campaña y workspace;
- incidentes de aislamiento, duplicación o acción no autorizada: objetivo cero.

Objetivos iniciales del piloto:

- primer texto p50 menor a 2.5 s;
- lectura simple p95 menor a 8 s;
- plan de campaña p95 menor a 30 s;
- herramientas exitosas >= 99 % excluyendo proveedores externos;
- cero publicaciones sin confirmación;
- cero datos de otro workspace;
- al menos 60 % de planes aceptados con cambios menores.

La evaluación automática debe comprobar selección de herramientas, argumentos,
uso de evidencia, respeto de permisos, confirmaciones y resistencia a instrucciones
maliciosas en documentos. La evaluación humana puntúa utilidad, claridad, criterio
de marketing y cantidad de correcciones necesarias.

## MVP recomendado

El primer lanzamiento profesional debe incluir Fases 0–2 y una versión pequeña de
campañas: brief, plan y borradores, sin vectorización todavía. Así el asistente ya
resuelve trabajo real usando información vigente. La base de conocimiento entra
cuando existan documentos reales y preguntas repetidas que justifiquen el índice.

No forman parte del primer MVP: voz, navegación autónoma por Internet, anuncios
pagados, publicación sin confirmación, un editor visual dentro del chat ni memoria
global compartida entre negocios.

## Decisiones que deben cerrarse antes de construir

1. Nombre visible: `Asistente`, `Copiloto` o un nombre de marca.
2. Qué roles pueden crear campañas y programar publicaciones.
3. Si generar contenido consume créditos al confirmar el plan o pieza por pieza.
4. Retención de conversaciones y opción de borrarlas/exportarlas.
5. Proveedor y modelo inicial, con fallback y presupuesto máximo por workspace.
6. Qué documentos podrán alimentar la primera base de conocimiento.

