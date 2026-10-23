# Goal: Chispa rápida, resolutiva y confiable

Documento preparado el 21 de octubre de 2026. Listo para usar como instrucción de un goal de implementación. Crear este documento no activa el goal ni autoriza un despliegue.

## Objetivo

Optimizar el asistente existente de Picale para que resuelva peticiones sin consultas redundantes, bucles de errores ni esperas indefinidas. Priorizar el recorrido de **retocar una fotografía real desde el chat**, sin romper creación de imágenes, carruseles, onboarding, edición de marca ni confirmaciones.

Mantener una sola conversación por negocio y la interfaz actual. No incorporar pantallas, agentes adicionales ni ajustes técnicos que el usuario deba administrar.

## Evidencia inicial: verificar antes de cambiar

La revisión de la base local encontró:

- Un turno de retoque con 148 segundos entre inicio y final; ese intervalo **incluye dos esperas de confirmación**, por lo que no representa exclusivamente tiempo de cómputo.
- En ese turno se consultaron tres veces `marca`, `identidad_visual`, `panel` y la misma fotografía mediante `ver_archivo`.
- Dos llamadas a `generar_contenido` fallaron con «Indica un texto de hasta 300 caracteres por diapositiva, en el orden de las fotos». No se generó la imagen.
- La respuesta final volvió a pedir autorización en lugar de resolver el fallo de parámetros.
- Una respuesta registró 22.161 tokens de entrada; el último turno disponible terminó en aproximadamente 11,5 segundos. No todas las peticiones presentan la misma demora.
- El fallo anterior de carga de `AssistantToolResult` dejó un turno sin terminar. Ya existen cambios de manejo de fallos, comprobación de arranque y recuperación de turnos interrumpidos: revisar su implementación, pruebas y activación, no duplicarlos.

Estos datos corresponden al entorno local y a registros del 20 de octubre. No extrapolarlos a producción. No asumir que un archivo de log antiguo representa al proceso actual.

## Instrucción para el agente ejecutor

Trabaja sobre los repositorios existentes `metricol.api`, `../picale-mcp` y `../metricol_web`, únicamente donde sea necesario. Primero inspecciona las instrucciones aplicables, el estado de Git y los contratos actuales. Conserva todos los cambios ajenos y adapta el plan a lo que realmente esté implementado.

Implementa por etapas, verifica cada una y conserva evidencia reproducible. No te limites a modificar prompts: corrige contratos, flujo de ejecución y observabilidad cuando sean la causa. No declares el goal completo porque compila, porque hay animaciones o porque pasan pruebas con respuestas simuladas.

### 1. Medir y localizar la demora

Registrar por turno y por paso, sin datos sensibles:

- Tiempo en cola, preparación de contexto, modelo y herramientas.
- Espera por confirmación humana, separada del trabajo efectivo.
- Reintentos por límite de tasa, errores de validación y consultas repetidas.
- Tiempo hasta el primer avance útil y hasta la respuesta final.
- Tokens de entrada/salida y, cuando esté disponible, uso de caché reportado por el proveedor. No confundir continuar una respuesta con eliminar automáticamente el contexto facturado o procesado.

Usar el ID del turno como correlación. Diferenciar preparación de respuesta, generación de imagen y publicación. No registrar claves, base64, instrucciones completas del cliente ni información personal innecesaria.

### 2. Corregir el contrato de retoque primero

- Reproducir el error con una fotografía, modo `RETOQUE` y sin texto añadido.
- Revisar la traducción de argumentos entre asistente, MCP y servicio de imágenes; identificar exactamente por qué aparecen campos de carrusel en un retoque individual.
- Definir cómo tratar campos opcionales ausentes, nulos y arrays vacíos. No eliminar textos válidos de un carrusel para hacer pasar la validación.
- Validar el pedido antes de pedir confirmación de ejecución y antes de iniciar cualquier generación de pago.
- El retoque individual no debe exigir textos de diapositivas. Conservar alimentos, ingredientes, porciones, ambiente y tratamiento de logo solicitado.
- Conservar las instrucciones literales y los límites vigentes; nunca resumir ni recortar silenciosamente el brief.
- No mezclar retoque con diseño de un anuncio ni introducir títulos, CTA o elementos que no se pidieron.

### 3. Evitar exploración repetida y bucles

- Reutilizar lecturas idénticas dentro del mismo turno mediante una clave de herramienta y argumentos normalizados, aislada por workspace y permisos.
- No usar esa caché para escrituras, generaciones, publicaciones ni confirmaciones.
- Invalidar las lecturas afectadas después de una modificación; permitir refresco explícito. Los estados de trabajos en curso deben poder consultarse nuevamente.
- No volver a enviar innecesariamente la misma imagen al modelo dentro de una continuación. Mantener su disponibilidad visual y las restricciones originales.
- Detectar repeticiones del mismo error. Permitir corrección acotada y justificable de parámetros; si requiere una decisión del usuario, pedir solo el dato que falta.
- No reiniciar la exploración completa de marca, panel y archivos tras cada error.
- Preservar el significado de la confirmación: una acción o un coste distintos requieren nueva aprobación; no transformar una confirmación anterior en autorización abierta.

### 4. Reducir contexto sin perder capacidad

- Medir qué ocupa el contexto: instrucciones, catálogo, historial, memoria y resultados de herramientas.
- Eliminar duplicaciones y enviar resultados relevantes, sin quitar restricciones, permisos, IDs necesarios ni reglas de seguridad.
- Mantener la memoria de marca y las últimas decisiones verificadas; no confundir datos históricos con estado actual.
- Evaluar selección de herramientas por intención solo si conserva acceso a las capacidades necesarias y una ruta segura cuando la clasificación sea incierta.
- Verificar el comportamiento real de la continuidad del proveedor. Consultar documentación oficial vigente si se modifica la integración; no asumir capacidades por nombres de modelos.
- No cambiar modelo, proveedor, razonamiento o límites globales como primera solución. Cualquier cambio debe justificarse con comparación de calidad, latencia y coste.

### 5. Mostrar avance útil sin saturar la interfaz

- Reutilizar el canal de eventos y componentes existentes.
- Mostrar estados breves y verdaderos: «Revisando tu fotografía», «Preparando el retoque», «Necesito tu confirmación» o «La imagen sigue generándose».
- Evaluar texto progresivo cuando el transporte lo permita. No mostrar razonamiento interno ni exponer una respuesta provisional como resultado confirmado.
- No interpretar una llamada de herramienta o una propuesta como una imagen terminada o una acción realizada.
- Si se pierde la conexión, recuperar el estado durable y reconectar sin repetir la operación.
- Mantener párrafos legibles, espacios y emojis discretos. Las animaciones no sustituyen el progreso real.

### 6. Acotar esperas y recuperarse con seguridad

- Introducir un presupuesto configurable de trabajo activo para el turno, además de los límites por llamada y por número de pasos. Pausarlo mientras se espera confirmación humana.
- Acotar los reintentos por límite de tasa y notificarlos cuando prolonguen la espera; no inventar un tiempo estimado.
- Al agotar el presupuesto, terminar con un estado honesto y recuperable. Un corte del chat no significa que se haya cancelado una acción externa.
- No cancelar, volver a generar ni publicar automáticamente para recuperar la interfaz.
- Verificar que fallos del trabajador y del commit dejan estado terminal persistido y un aviso visible, usando una transacción independiente cuando corresponda.
- Verificar el arranque con clases necesarias disponibles y la recuperación de turnos interrumpidos. Si hay varias instancias, no cerrar trabajos activos de otra instancia: revisar propiedad/lease antes de generalizar el mecanismo de recuperación.
- Conservar recibos e IDs de acciones externas. Si su resultado es incierto, consultar su estado antes de permitir un reintento que pueda duplicarla.

## Pruebas y criterios de aceptación

El goal debe demostrar al menos lo siguiente:

1. Un retoque con una foto y sin textos de carrusel supera la validación, solicita la confirmación correspondiente y permite iniciar un único trabajo.
2. Un carrusel válido conserva textos, cantidad y orden; los pedidos inválidos fallan antes de consumir generación.
3. Confirmar el brief no equivale a aceptar el coste ni a guardar permanentemente la identidad visual.
4. Dos lecturas idénticas del mismo estado no hacen dos consultas externas en el turno, salvo invalidación o necesidad explícita de actualizarlo.
5. Una escritura invalida las lecturas afectadas; el aislamiento entre negocios y usuarios se mantiene.
6. El mismo error no produce un bucle de generación, consulta y confirmación. Los reintentos están acotados y registrados.
7. Fallos de carga de clases, excepciones del trabajador, rechazo de cola y fallos transaccionales dejan un aviso recuperable; no hay reejecución automática de acciones externas.
8. El presupuesto de tiempo y la recuperación de conexión se prueban sin dejar acciones duplicadas o estados falsamente completados.
9. Recargar la conversación recupera el estado correcto. Una acción que espera confirmación no se muestra como si siguiera procesándose.
10. No hay regresiones en onboarding, edición de marca, imagen nueva, carrusel, selección de recursos y preparación de borradores.

### Objetivos de rendimiento

Son metas iniciales para evaluar, no garantías del proveedor:

- Respuesta visual local de recepción: menos de 1 segundo desde el envío, sin esperar al modelo.
- Primera respuesta útil en consultas simples: mediana de hasta 10 segundos y p95 de hasta 20 segundos, bajo condiciones de prueba documentadas.
- Preparación de una propuesta visual con una fotografía: hasta 30 segundos como objetivo; medir por separado generación de imagen y confirmación humana.
- Cero consultas idénticas evitables y cero reintentos automáticos de generación de pago en los recorridos de aceptación.

Comparar escenarios equivalentes antes/después. Para publicar percentiles, usar al menos 30 observaciones por escenario, indicar tamaño de muestra y condiciones. Si no hay autorización para llamadas pagadas suficientes, entregar métricas deterministas y señalar la validación real pendiente; no presentar tiempos de mocks como rendimiento del proveedor.

## Seguridad, permisos y límites de alcance

- No desplegar en producción ni cambiar configuración, créditos, planes o licencias de cuentas.
- No publicar ni programar contenido real durante las pruebas.
- No ejecutar nuevas generaciones pagadas sin autorización explícita y un límite acordado. Las autorizaciones anteriores de imágenes de evaluación ya se consumieron.
- Usar dobles de prueba para errores, demoras y reintentos. Proponer una prueba real acotada si es necesaria; no ejecutarla por asumir que los créditos ilimitados la autorizan.
- No guardar reglas de marca inferidas sin confirmación ni acceder a recursos de otro workspace.
- No reiniciar servicios con acciones externas activas sin comprobar antes su estado. Reiniciar solo los procesos del proyecto que sean necesarios y anunciarlo.
- Si existe un bloqueo de la suite por cambios ajenos, documentarlo y ejecutar pruebas dirigidas sin borrar o reparar esos cambios por iniciativa propia.

## Entregables y definición de terminado

- Correcciones de API, MCP y web estrictamente necesarias, con pruebas de regresión.
- Evidencia de la causa del fallo de retoque y de su corrección.
- Comparación antes/después de consultas, pasos, tokens y tiempos disponibles.
- Instrucciones reproducibles de prueba local, configuración nueva y reversión sin pérdida de datos.
- Resumen final que separe: implementado, probado con dobles, validado con servicios reales y pendiente de autorización.

No declarar terminado mientras exista un fallo conocido en el recorrido principal dentro del alcance. Si una validación de pago o un acceso externo queda pendiente, explicar el bloqueo y solicitar la autorización necesaria; no sustituirla por una afirmación de éxito.

## Lecturas iniciales

- `docs/identidad-visual-chispa.md`
- `docs/asistente-flujo-visual.md`
- `docs/asistente-migracion-responses.md` — contrastar sus afirmaciones históricas con código y mediciones actuales.
- `AssistantOrchestrator`, `AssistantLlmClient`, `AssistantRunDispatcher`, `AssistantRunEvents`, `AssistantToolResult` y `TurnosInterrumpidos`.
- `GenerarContenidoTool` en MCP; `CampaignImageRequest` y `CampaignImageService` en API.
- Servicio de eventos y componentes del asistente en web, con sus pruebas existentes.
