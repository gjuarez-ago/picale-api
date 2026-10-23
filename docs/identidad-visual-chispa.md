# Identidad visual y creación desde Chispa

## Alcance

Chispa sigue siendo la única interlocutora. La generación recibe una identidad confirmada del negocio, distingue el logo del personaje y permite ajustar una pieza elegida sin sobrescribir el archivo anterior. Los cambios abarcan API, MCP y la conversación web; no despliegan ni publican contenido.

## Preferencias persistentes

`BrandProfile.visualIdentity` se guarda en el JSON existente del workspace; no requiere una columna nueva. Los perfiles históricos siguen leyéndose y el guardado de marca de clientes anteriores conserva la identidad visual. Las escrituras de marca y preferencias toman el mismo bloqueo de workspace.

`GET /api/v1/workspace/brand/visual` devuelve colores, estilo, referencias aprobadas/rechazadas, referencias del personaje Chispa, qué evitar/conservar/adaptar, tratamiento del logo, tratamiento del fondo y permiso de reinterpretar forma. Incluye revisión, fecha y usuario que confirmó. Revisión cero significa sin aprobación previa.

`PUT` en la misma ruta exige `WORKSPACE_EDIT`, `confirmed: true` y `expectedRevision`. Ausencia conserva; lista vacía limpia. Una revisión obsoleta se rechaza y requiere consultar de nuevo. Las referencias deben ser imágenes listas del mismo workspace. Las listas aprobadas y rechazadas no pueden contradecirse.

El MCP expone `identidad_visual` y `actualizar_identidad_visual`. Guardar preferencias tiene política de vista previa y confirmación. Una opinión o una corrección local no modifica automáticamente el estilo futuro.

## Tratamientos del logo

- `ORIGINAL`: compone el archivo original sin reinterpretar letras o formas. Con `PRESERVE` conserva también el fondo y el lienzo original; por ello un archivo con mucho margen puede mostrar el símbolo pequeño. Se puede elegir otra presentación o proporcionar una referencia mejor preparada.
- `INTEGRATED`: envía el archivo original con rol `LOGO_IDENTITY` y solicita una sola integración coherente con la escena. El compositor posterior no vuelve a estamparlo. No se promete identidad píxel por píxel.
- `PRESERVE`: el fondo forma parte de la referencia y se conserva.
- `REMOVE_SOLID`: una preferencia confirmada permite separar exclusivamente un fondo exterior uniforme. En modo original se usa separación conectada a los bordes: conserva huecos internos y nunca sobrescribe el original. Un fondo complejo o una separación vacía se rechazan antes del cobro. En modo integrado la especificación indica al generador cómo tratarlo.

Cambiar geometría requiere `allowShapeChange` explícitamente confirmado; no se deduce de “no me gusta”. Letras y colores esenciales se conservan por defecto. Un logo ilegible en modo original se detecta antes de generar.

## Dirección compartida y referencias

Se construye `CreativeSpecification` con objetivo, audiencia, mensaje, composición, escena, texto exacto, paleta, restricciones, revisión de identidad, número de diapositiva y función de cada referencia.

Los roles son producto/lugar real, estilo, logo, personaje y pieza anterior. Las referencias de estilo no se confunden con productos; Chispa solo se incluye cuando se solicita y existen referencias aprobadas. Las preferencias de color aprobadas tienen prioridad sobre la extracción de colores del logo.

Imágenes y carruseles comparten director artístico y especificación. El respaldo varía según exista fotografía o se necesite un póster tipográfico. `textos_diapositivas` permite un texto literal por imagen en el mismo orden de las fotos; deben coincidir las cantidades. El generador actual conserva su requisito de 2–5 fotos para carruseles.

## Ajustes y revisión

`generar_contenido` admite `pieza_anterior` y `ajuste`: la referencia elegida va primero, se pide conservar la composición y solo modificar lo solicitado. Se genera un archivo nuevo. Un ajuste se hace sobre una diapositiva por vez. No hay promesa de edición exacta de capas o píxeles.

Si se pide cambiar una pieza existente a `ORIGINAL`, el generador retira las marcas anteriores y el compositor añade una única copia del archivo fiel. Un ajuste común sin cambio explícito de tratamiento conserva el logo existente y no vuelve a estamparlo. La eliminación generativa puede dejar residuos: la revisión y aprobación siguen siendo necesarias. El cambio de tratamiento sobre una pieza existente tiene prueba automatizada; queda pendiente su aprobación visual real en una cuenta de prueba, sin ampliar las 10 generaciones autorizadas.

Antes de guardar se valida que la imagen pueda leerse y tenga resolución mínima. La revisión visual recibe resultados y referencias de identidad en detalle alto; revisa texto, marca, duplicaciones, fondos, personaje, márgenes y coherencia. Es orientativa: puede devolver observaciones o estar no disponible. No provoca regeneraciones automáticas ni cobros adicionales de generación. La revisión usa una llamada de visión cuyo consumo se registra aparte.

El resultado de la revisión viaja API → MCP → recibo durable del chat. Se muestran los problemas concretos o que falta revisar. Las imágenes permanecen en Contenido y pueden usarse mediante las confirmaciones existentes de publicación.

## Interfaz

La galería ofrece “Me gusta”, “Ajustar”, “No representa mi marca” y “Usar imagen”. El tratamiento del logo está en un desplegable contextual. Las acciones preparan una petición editable con el archivo seleccionado; no generan al tocar una opinión. No pisan un borrador existente ni guardan preferencias globales sin confirmación.

## Modelo y costes

Los valores predeterminados de API, desarrollo, QA y producción son `gpt-image-2`. Un `OPENAI_IMAGE_MODEL` explícito continúa teniendo prioridad; hay que comprobarlo en el entorno que se ejecute.

GPT Image 2 no recibe `input_fidelity`: procesa las referencias en alta fidelidad. `OPENAI_IMAGE_QUALITY` permite `low`, `medium`, `high` o `auto`; se conserva `medium` mientras se evalúa si la mejora de `high` compensa tiempo y coste. No se cambian automáticamente las tarifas ni las reglas de créditos del producto.

Fuentes oficiales consultadas: [edición y referencias](https://developers.openai.com/api/docs/guides/image-prompting), [precios](https://developers.openai.com/api/docs/pricing).

## Evaluación visual reproducible

`VisualIdentityEvaluationTest` es una prueba opt-in con generación real. Utiliza los recursos de Picale y Chispa del proyecto, fija texto/dirección para aislar el cambio visual y compara:

1. Logo colocado mediante el flujo anterior, medium.
2. Logo integrado por referencia, medium y high.
3. Personaje descrito sin referencia, medium.
4. Personaje con referencia aprobada, medium y high.
5. Carrusel de dos piezas con dirección compartida, medium.

Primera tanda: 8 solicitudes autorizadas. Después se autorizaron explícitamente 2 adicionales para comprobar la corrección de logos duplicados y el ajuste de una pieza seleccionada: límite total de 10, ya consumido. No usa BD ni R2 reales y no publica. El coste se carga a la cuenta de OpenAI de la llave utilizada, no al saldo del workspace: el almacenamiento y créditos de producto se sustituyen en este ensayo aislado.

Ejecución desde una consola con la llave en el entorno, nunca en argumentos:

```powershell
$env:PICALE_VISUAL_EVAL = 'true'
mvn -q '-Dtest=VisualIdentityEvaluationTest#compare' test
```

El ensayo guarda `target/visual-identity-eval/manifest.json`, imágenes, prompts y `index.html` para revisión. Registra cada intento antes de enviarlo y no repite uno sin resultado confirmado. Conservar este manifiesto es necesario para respetar el límite; no borrar ni reiniciar la evaluación a ciegas.

El informe muestra latencia, tokens y una estimación conservadora de USD de generación. La aprobación humana permanece pendiente hasta que se revise cada pieza. El coste por pieza aprobada solo puede calcularse después; el ensayo no inventa aprobaciones ni porcentajes de mejora. Una muestra pequeña orienta decisiones y no demuestra una tasa de fallo general.

### Resultados observados el 20 de octubre de 2026

Se obtuvieron 10 archivos reales. La comparación mantiene texto y dirección controlados; el generador de imágenes es real, mientras que director/texto, almacenamiento y créditos están aislados. No equivale a un recorrido completo contra una cuenta real.

| Caso | Tiempo observado | Estimación conservadora USD |
| --- | ---: | ---: |
| Marca anterior, medium | 40,1 s | 0,0287 |
| Marca integrada, medium / high | 36,8 / 92,7 s | 0,0322 / 0,1112 |
| Chispa anterior, medium | 38,0 s | 0,0288 |
| Chispa con referencia, medium / high | 103,3 / 99,5 s | 0,0381 / 0,1171 |
| Carrusel de dos imágenes, medium | 38,2 s en conjunto | 0,0886 en conjunto |
| Logo único tras corrección, medium | 37,3 s | 0,0376 |
| Ajuste de pieza seleccionada, medium | 32,2 s | 0,0335 |

Total estimado de generación: USD 0,5156; excluye revisión con visión, otras llamadas de texto e impuestos. No es una factura ni una tarifa comercial.

La referencia conserva mejor los ojos, colores y bolsa de Chispa que la descripción sin imagen. Las dos diapositivas comparten personaje, paleta y estilo. En la primera tanda aparecieron duplicaciones de marca en varias salidas: se eliminaron instrucciones de modos inactivos y se reforzó una única aparición del conjunto símbolo/nombre. La novena imagen mostró una sola integración; la décima retiró la copia plana de la esquina conservando el logo tridimensional, composición, texto y personaje de la pieza elegida. El original permanece separado. Esto verifica esos casos, no garantiza ausencia de duplicaciones futuras.

Se mantiene `medium` como valor predeterminado: `high` costó aproximadamente tres veces más en estos casos y no eliminó por sí solo los problemas de duplicación. La latencia varió; esta muestra no permite prometer un tiempo fijo. La selección final depende de tu aprobación visual, todavía pendiente.

La comprobación adicional se ejecutó exclusivamente con `PICALE_VISUAL_FOLLOWUP=true` y el método `verifyCorrectedLogoAndSelectedPiece`. No autoriza una tanda nueva. El método `reviewSavedOutputs` revisa archivos existentes con visión sin generar imágenes; usa `OPENAI_API_MODEL` del entorno y también tiene consumo de API. El informe indica si la revisión estuvo disponible o quedó sin verificar.

La revisión real se ejecutó sobre las 10 salidas con el modelo de visión configurado en el entorno. Señaló duplicaciones y posibles problemas de márgenes; no encontró incidencias en el ajuste final. También produjo falsos avisos: interpretó la zona reservada al compositor como prohibición del logo final y desconocía el texto de la segunda diapositiva en el ensayo. Se corrigió ese contexto para futuras revisiones. Las observaciones históricas del informe se conservan tal como fueron recibidas, no se convierten en aprobaciones. Las estimaciones de posición del revisor no son mediciones de píxeles.

## Cómo probarlo localmente

### Brief y retoque fotográfico

El asistente ofrece «Crear imagen», «Crear carrusel» y «Retocar foto» en la misma conversación. Las peticiones visuales tienen contador de 1500 caracteres y bloqueo al excederlo. El texto pegado se conserva para editar; no se recorta silenciosamente. El chat general conserva su límite de 12000. Los formularios `brief` también muestran 1500 y rechazan exceso en cliente y servidor; el contenido libre conserva comas y saltos de línea.

El orquestador envía el brief confirmado de forma literal al MCP, sin aceptar que el modelo lo sustituya por un resumen. Si una petición con foto es demasiado larga, presenta el original en un formulario editable incluso cuando hubo una aclaración del nombre entre medias. Confirmar ese texto no confirma un coste ni guarda preferencias de marca.

`generar_contenido.modo` distingue `ANUNCIO` (predeterminado compatible) de `RETOQUE`. La API lo recibe en `creative.mode` como `AD` o `PHOTO_RETOUCH`. El retoque requiere una foto real del workspace o una pieza anterior; no usa el director publicitario ni redacta titulares. Conserva alimentos, ingredientes, porciones, personas, instalaciones, decoraciones, ambiente y logos visibles; no añade texto, escenarios ni personajes. No exige completar la marca en el MCP. Para añadir expresamente el archivo original del logo se requiere `logo_tratamiento=ORIGINAL`; no se coloca automáticamente sobre todo retoque. El cambio sigue siendo generativo: no garantiza identidad de píxeles o edición de capas.

El nombre comercial distinto al del espacio se aclara brevemente, sin renombrar ni cambiar de cuenta automáticamente. Las reglas del usuario pueden proponerse como identidad futura, pero solo se guardan mediante la confirmación existente. La lectura de una identidad con revisión cero ya no la llama «confirmada».

### Visión de los adjuntos

Las imágenes de `ver_archivo` se separan del JSON textual antes del límite de 40000 caracteres. Se aceptan PNG/JPEG/WebP reconocibles, hasta tres imágenes de 2 MB por resultado y dimensiones de hasta 4096 por lado. Datos inválidos se señalan como no disponibles, sin fingir una inspección. El MCP habitual genera previsualizaciones de hasta 1024 px; el generador sigue recibiendo los archivos originales mediante el almacenamiento validado del workspace.

Responses recibe `function_call_output.output` multimodal (`input_text` e `input_image`, detalle alto). La compatibilidad Chat mantiene todos los resultados de herramientas en orden y agrega las imágenes como un mensaje multimodal de usuario después de resolverlos. Los bytes no se guardan en mensajes, recibos ni eventos de interfaz; la política de almacenamiento del proveedor sigue siendo la ya configurada para el asistente. Al reanudar otro turno, se puede volver a consultar el archivo por su ID.

Referencias oficiales: [visión](https://developers.openai.com/api/docs/guides/images-vision), [salidas de funciones](https://developers.openai.com/api/reference/resources/responses/methods/create). La corrección se verifica con pruebas de serialización contra un servidor local simulado y regresiones de formulario/retoque; no se generan nuevas imágenes de pago como parte de esta validación.

Verificación de esta ampliación: 108 pruebas dirigidas de API, 7 de MCP, 24 unitarias web y una prueba de navegador pasaron. La compilación web con prerender pasó usando un worker. La compilación ordinaria de todos los tests API encontró un literal sin cerrar en `RedactorTest.java`, modificado fuera de este trabajo: se dejó intacto. Para ejecutar las pruebas dirigidas se compiló primero el código principal con `mvn -Dmaven.test.skip=true compile`, después esos cuatro archivos de tests con `javac` del JDK 17 y el classpath de Surefire, y finalmente `mvn -Dtest=AssistantToolResultTest,AssistantLlmClientTest,AssistantOrchestratorTest,CampaignImageServiceTest surefire:test`. No equivale a aprobar la suite completa. La captura `metricol_web/test-results/visual-brief-limit.png` verifica el aviso de exceso y conservación del texto. La regresión de coste usa el nivel real de la herramienta (1): confirmar el brief nunca sustituye al aviso de coste.

1. Con API, MCP y web locales en sus configuraciones habituales, abre `/panel/asistente` en un workspace de prueba. No se cambian llaves ni se despliega este código automáticamente.
2. Adjunta el logo original y, si corresponde, una imagen aprobada de Chispa. Pide: «Propón mi identidad visual con estos recursos; no la guardes todavía». Revisa la propuesta y confirma qué reglas deben aplicarse a futuras piezas.
3. Recarga y pregunta qué identidad quedó guardada. Comprueba que conserva colores, referencias y tratamiento aprobados.
4. Pide una imagen con texto literal y «Integrarlo creativamente» o «Conservar mi logo». Revisa coste/confirmación antes de generar; este recorrido consume generaciones nuevas, fuera de las 10 de la comparación.
5. Selecciona «Ajustar» sobre una pieza y pide un cambio puntual. Comprueba el nuevo archivo y que la versión anterior siga disponible. «Me gusta» o «No representa mi marca» solo preparan un mensaje editable.
6. Usa la imagen para preparar una publicación como borrador. No confirmes publicación o programación hasta revisar redes, texto y fecha.

## Verificación y límites

Hay pruebas de persistencia/compatibilidad JSON, referencias ajenas, revisiones obsoletas, rechazo sin confirmar, ambos caminos del logo, referencias de Chispa/estilo, textos y orden de carrusel, ajustes sin borrado y ausencia de regeneración automática. El contrato MCP comprueba permisos y confirmaciones. La prueba de navegador `e2e/visual-identity.spec.ts` comprueba presentación, selección, envío del ID correcto y recarga con respuestas controladas; no sustituye el recorrido con modelo, BD y MCP reales.

`VisualIdentityPersistenceTest` usa repositorios y H2 reales en una base aislada: guarda mediante el servicio, vacía el contexto JPA, vuelve a leer y comprueba que guardar la marca no elimina las preferencias. No toca la BD de `.env`. El esquema de prueba fuerza el dialecto H2 para no heredar el de PostgreSQL local.

Verificación web: 20 pruebas unitarias y la prueba de navegador pasaron. La compilación completa, incluido prerender, pasó con `NG_BUILD_MAX_WORKERS=1`; dos intentos paralelos anteriores agotaron los 30 s del prerender de `/planes`. No se modificó esa página ni se ocultó la fase de prerender.

Verificación dirigida de API: 184 pruebas entre servicio de imágenes, identidad visual, persistencia H2, aislamiento del fondo, cliente de imágenes, prompts, compositor, trabajos, marca, director y orquestador. MCP: 5 pruebas del contrato de generación/identidad y 2 de políticas del catálogo. Estas suites incluyen fallos simulados y pruebas deterministas; la evidencia de imagen real se distingue en la comparación anterior. No se ejecutó la suite completa de todos los módulos del producto.

Los trabajos de `ContenidoJobs` siguen en memoria durante su seguimiento; este cambio no incorpora una cola durable. Los recibos del chat conservan lo que haya llegado a consultarse y guardarse. Cambiar o eliminar una referencia puede exigir actualizar la preferencia antes de generar nuevamente.

Antes de liberar deben revisarse el informe real, las preferencias guardadas en un espacio de prueba y el recorrido de publicación como borrador. No afirmar que una pieza está publicada por haberla generado o programado.
