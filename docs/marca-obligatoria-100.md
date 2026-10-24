# Goal: marca obligatoria al 100% antes de producir contenido

> Estado al 24 de octubre de 2026: API, MCP y web desplegados en producción en
> ese orden. Suites completas y smoke tests autenticados en verde. La auditoría
> previa confirmó 6 de 6 espacios activos con marca al 100%; los 5 espacios
> incompletos encontrados están archivados. Comenzó la ventana de monitoreo
> productivo de 24 horas.

## Objetivo

Impedir que un espacio de trabajo use funciones que redactan, diseñan, programan
o publican contenido mientras su marca no tenga el contexto mínimo completo.
El onboarding se realiza dentro de **Publicaciones**, sin depender del Asistente
ni iniciar conversaciones que consuman tokens.

Al terminar este goal:

- La API será la fuente de verdad del porcentaje y del bloqueo.
- Una marca estará lista únicamente con `completitud.percent == 100`.
- Un usuario con permiso para editar el espacio será guiado al onboarding dentro
  de Publicaciones hasta completar la marca.
- Un usuario sin ese permiso verá quién debe completar la marca y no quedará
  atrapado en un formulario que no puede guardar.
- Las secciones administrativas seguirán disponibles.
- Web, API y MCP respetarán la misma regla.
- La app móvil queda fuera de este alcance.

## Situación actual

Ya existe una base útil:

- `BrandService.completitud(Workspace)` calcula el porcentaje en la API.
- `GET /api/v1/workspace/brand` devuelve `completitud.percent` y `faltan`.
- El alta lleva a `/panel/publicaciones?onboarding=1`.
- Publicaciones puede renderizar `BienvenidaComponent` dentro del módulo.
- El Asistente ya no inicia automáticamente el onboarding.

Sin embargo, todavía no se cumple la obligación:

- `BienvenidaComponent` declara que todo es opcional.
- Existen las salidas **Lo lleno después** y **Lo conecto después**.
- Los campos de marca pueden guardarse vacíos.
- El onboarding sólo pregunta `queVende`, `publico`, `tono` y `whatsapp`.
- El guard `negocioCompletoGuard` valida únicamente el perfil básico, no la
  completitud de marca.
- El frontend por sí solo no impide llamadas directas a la API.

## Definición única del 100%

Se conserva como fuente de verdad `BrandService.completitud(Workspace)`. Las
ocho partes pesan igual:

| Clave | Pregunta para el usuario | Condición |
| --- | --- | --- |
| `giro` | ¿A qué se dedica tu negocio? | Texto no vacío |
| `ciudad` | ¿En qué ciudad trabajas? | Texto no vacío |
| `descripcion` | Describe brevemente tu negocio | Texto no vacío |
| `objetivo` | ¿Qué quieres lograr en redes? | Objetivo válido |
| `queVende` | ¿Qué vendes o qué servicio das? | Texto no vacío |
| `publico` | ¿Quiénes son tus clientes? | Texto no vacío |
| `tono` | ¿Cómo le hablas a tus clientes? | Al menos un tono válido |
| `contacto` | ¿Cómo te contactan? | WhatsApp, web o dirección válida |

El logo, los colores y las redes conectadas aportan valor, pero no forman parte
de este 100%. Conectar al menos una red seguirá siendo obligatorio al intentar
publicar, mediante la regla específica que ya existe.

No duplicar esta fórmula en guards o controladores. Todos deben consultar el
resultado calculado por `BrandService`.

## Regla de acceso

### Se bloquea con marca menor a 100%

- Crear y editar publicaciones.
- Sugerencias o redacción con IA.
- Generación y edición de imágenes con IA.
- Director creativo, campañas y reels generados.
- Activar o ejecutar el Agente.
- Programar o publicar contenido nuevo.
- Herramientas MCP que lleguen a cualquiera de esas operaciones.
- Asistente, si posteriormente se habilita para el espacio.

### Permanece disponible

- Publicaciones, mostrando el onboarding en lugar del listado mientras falte la
  marca y el usuario pueda editarla.
- Mi marca y el guardado del propio onboarding.
- Redes sociales.
- Perfil, facturación y equipo.
- Administración raíz.
- Cambio de espacio y cierre de sesión.
- Lectura de contenido existente, siempre que no dispare generación ni cambios.

### Usuario sin permiso `WORKSPACE_EDIT`

No mostrarle campos deshabilitados como única salida. Mostrar una pantalla de
estado dentro de Publicaciones:

> La marca de este espacio todavía no está completa. Pídele a un administrador
> del espacio que termine los datos necesarios para crear contenido.

Debe poder entrar a Perfil, Equipo cuando tenga permiso, Facturación, cambiar de
espacio y cerrar sesión. Las acciones productivas continúan bloqueadas por API.

## Diseño técnico

### 1. API: guard central de marca

Crear un servicio, por ejemplo `BrandAccess`, con una sola responsabilidad:

```java
public Completitud estado(User user);
public void exigirCompleta(User user);
```

`exigirCompleta` obtiene el workspace actual, usa
`BrandService.completitud(workspace)` y, si no está al 100%, lanza una excepción
de negocio con HTTP `409 Conflict` y un código estable:

```json
{
  "code": "BRAND_INCOMPLETE",
  "message": "Completa la marca antes de crear contenido.",
  "details": {
    "percent": 63,
    "missing": ["ciudad", "publico", "contacto"]
  }
}
```

El error debe ser distinguible de permisos, créditos agotados y fallos internos.
No devolver únicamente un texto libre.

Aplicar este servicio en las entradas de API que consumen IA o producen contenido.
Como mínimo revisar:

- `AiController`
- `AsistenteImagenesController`
- `CampaignImageController`
- `CreativeDirectorController`
- `ReelController`
- operaciones de creación/edición relevantes en `PostController`
- programación/publicación en `PublishingController`
- activación y ejecución en `AgenteController`
- `AssistantController`

No proteger `GET/PUT /api/v1/workspace/brand`, porque son la vía para salir del
bloqueo. Tampoco bloquear consultas administrativas o de facturación.

Agregar al resultado de `/api/v1/me` un estado resumido para que la web pueda
decidir el recorrido sin una condición distinta:

```json
{
  "brandCompletionPercent": 63,
  "brandMissing": ["ciudad", "publico", "contacto"],
  "brandReady": false
}
```

Aunque `/me` lo exponga, cada operación protegida debe volver a validar en API;
el valor del navegador no autoriza nada.

### 2. Web: onboarding completo dentro de Publicaciones

Ampliar `BienvenidaComponent` para editar los ocho campos que cuentan:

1. **Tu negocio:** giro, ciudad, descripción y objetivo.
2. **Tu audiencia y voz:** qué vende, público y tono.
3. **Contacto:** WhatsApp, web o dirección; basta uno.
4. **Redes:** paso recomendado y separado del 100% de marca.
5. **Listo:** sólo aparece cuando la respuesta guardada confirma 100%.

Reglas de interfaz:

- Eliminar **Lo lleno después** en el recorrido obligatorio.
- No habilitar **Guardar y seguir** si falta un campo del paso.
- Validar longitudes y formato con las mismas reglas visibles de la API.
- Mostrar `X de 8 completos`, el porcentaje y los faltantes legibles.
- Después de guardar, usar la respuesta del servidor; no asumir localmente que
  llegó a 100%.
- Si sigue incompleta, llevar al primer campo incluido en `faltan`.
- Conservar lo ya capturado si la red falla.
- No llamar endpoints de sugerencias con IA durante este onboarding.
- No obligar a subir logo ni conectar una red para declarar la marca completa.

En `PostsComponent`, la condición debe depender del estado recibido del servidor,
no únicamente de `?onboarding=1`:

- `brandReady == false` y puede editar: mostrar onboarding obligatorio.
- `brandReady == false` y no puede editar: mostrar estado bloqueado.
- `brandReady == true`: mostrar normalmente Publicaciones.

El query param sólo selecciona un paso; ya no decide si el bloqueo existe.

### 3. Web: navegación y guards

Crear `brandReadyGuard` para las rutas productivas. Cuando la marca esté
incompleta debe redirigir a:

```text
/panel/publicaciones?onboarding=1&paso=marca
```

Aplicarlo a Crear, editar publicación, Agente, Calendario cuando permita acciones
productivas, y demás pantallas que generen contenido. No colocarlo en el padre
`/panel`, porque Publicaciones y Mi marca tienen que seguir accesibles y se puede
crear un ciclo de redirección.

El menú puede mostrar las secciones bloqueadas con candado y explicación, o
permitir el clic para que el guard redirija. No ocultarlas por completo: el
usuario debe entender qué desbloqueará al terminar.

El interceptor HTTP debe reconocer `BRAND_INCOMPLETE`, refrescar `/me` y navegar
al onboarding. Esto cubre una pestaña abierta mientras otra persona vacía un
campo de la marca.

### 4. MCP

MCP no debe implementar otra fórmula de porcentaje. La API seguirá siendo la
autoridad:

- Si MCP consulta elegibilidad antes de ejecutar, incluir `brandReady`.
- Si una herramienta llama una operación protegida y recibe
  `BRAND_INCOMPLETE`, conservar el código y devolver una instrucción breve para
  completar la marca desde Publicaciones.
- No reintentar automáticamente una operación bloqueada: sólo gastaría recursos.

### 5. Cambios de workspace y cambios posteriores

- Al cambiar de workspace, recargar `/me` y evaluar la marca del nuevo espacio.
- Si una marca completa se edita y queda debajo de 100%, el bloqueo reaparece en
  la siguiente respuesta del servidor.
- El `PUT /workspace/brand` puede aceptar una edición parcial, pero su respuesta
  debe incluir siempre la completitud recalculada.
- No se necesita una columna nueva ni una migración: el estado se deriva de los
  datos actuales y así no se desincroniza.

## Archivos principales previstos

### API (`metricol.api`)

- `service/BrandService.java`
- nuevo `service/brand/BrandAccess.java` o equivalente
- `models/response/MeResponse.java`
- `controller/MeController.java`
- controladores productivos enumerados anteriormente
- manejador global de errores, si hace falta estructurar `details`
- pruebas unitarias y de integración de cada familia protegida

### Web (`metricol_web`)

- `services/me.service.ts`
- `services/brand.service.ts`
- `pages/panel/pages/bienvenida/*`
- `pages/panel/pages/posts/*`
- nuevo `guards/brand-ready.guard.ts`
- `app.routes.ts`
- interceptor/manejador común de errores de API
- pruebas de guards, onboarding, permisos y navegación

### MCP (`picale-mcp`)

- `PicaleApi` o DTO de `/me`
- `McpController` o capa común de despacho
- pruebas de traducción de `BRAND_INCOMPLETE`

## Plan de ejecución

### Fase 1 — Contrato y API

- [x] Mantener `BrandService.completitud` como única fórmula.
- [x] Crear `BrandAccess` y el error `BRAND_INCOMPLETE` con detalles.
- [x] Exponer `brandReady`, porcentaje y faltantes en `/me`.
- [x] Inventariar todos los endpoints que gastan IA o producen contenido.
- [x] Aplicar el guard central a cada endpoint productivo.
- [x] Confirmar que marca, perfil, administración y facturación no se bloquean.
- [x] Agregar pruebas de marca incompleta y marca al 100%.
- [x] Probar que una llamada directa no puede saltar el bloqueo.

### Fase 2 — Onboarding web obligatorio

- [x] Agregar los ocho campos al recorrido.
- [x] Presentarlos en grupos comprensibles dentro del paso de marca.
- [x] Eliminar la salida para omitir la marca.
- [x] Validar los datos antes de avanzar.
- [x] Usar el porcentaje y faltantes devueltos por la API.
- [x] Mostrar el onboarding desde Publicaciones aunque falte el query param.
- [x] Implementar el estado informativo para usuarios sin permiso de edición.
- [x] Evitar cualquier sugerencia de IA automática durante el recorrido.

### Fase 3 — Guards, navegación y MCP

- [x] Proteger las rutas productivas con `brandReadyGuard`.
- [x] Manejar globalmente el error `BRAND_INCOMPLETE`.
- [x] Cubrir alta, login, recarga y cambio de workspace mediante `/me`.
- [x] Mantener accesibles las rutas de salida y administración.
- [x] Propagar el bloqueo por MCP sin reintentos ni consumo adicional.

### Fase 4 — Datos existentes y liberación

- [x] Obtener un conteo de workspaces por porcentaje antes de desplegar.
- [x] Revisar específicamente cuántos quedarían bloqueados y qué campos faltan.
- [x] Preparar un aviso dentro del producto para cuentas existentes incompletas.
- [x] Desplegar API antes que web y MCP.
- [x] Ejecutar smoke tests con dueño, miembro sin permiso y root.
- [ ] Vigilar durante 24 horas la frecuencia de `BRAND_INCOMPLETE` y errores de
  guardado del onboarding.

## Pruebas obligatorias

### API

- Marca en 99%: toda operación productiva responde `409 BRAND_INCOMPLETE`.
- Marca en 100%: la operación continúa a sus validaciones normales.
- Guardar el último campo actualiza inmediatamente `brandReady`.
- Vaciar ciudad o contacto vuelve a bloquear.
- Consultar y guardar marca siempre sigue permitido.
- Un usuario sin permiso no puede editar marca aunque esté bloqueado.
- El error incluye porcentaje y faltantes sin exponer datos sensibles.

### Web

- Registro nuevo aterriza en Publicaciones con onboarding.
- No se puede omitir un campo requerido.
- Al recargar se conserva el progreso guardado y abre el primer faltante.
- Con 100% aparece el listado de publicaciones sin recargar toda la sesión.
- Una URL directa a Crear redirige al onboarding cuando falta marca.
- Un miembro sin `WORKSPACE_EDIT` ve el aviso y conserva rutas de salida.
- Un fallo de red conserva los valores escritos y permite reintentar.
- El onboarding no crea threads del Asistente ni llama sugerencias de IA.

### MCP

- Marca incompleta: no despacha la herramienta protegida.
- Devuelve un error accionable y no reintenta.
- Marca completa: no cambia el comportamiento actual.

## Criterios de aceptación

- [x] Ningún endpoint productivo puede ejecutarse con marca menor a 100%.
- [x] El porcentaje se calcula en un único lugar del backend.
- [x] El onboarding obligatorio vive dentro de Publicaciones.
- [x] Llegar al onboarding no consume tokens de IA.
- [x] Los ocho campos pueden completarse desde ese recorrido.
- [x] No existe una salida para omitir la marca obligatoria.
- [x] Los usuarios sin permiso reciben una explicación y no quedan atrapados.
- [x] Perfil, facturación, equipo, cambio de espacio y logout siguen disponibles.
- [x] Web y MCP muestran correctamente `BRAND_INCOMPLETE`.
- [x] Suites completas de API, web y MCP pasan.
- [x] La app móvil no fue modificada.

## Orden de despliegue

1. API con el contrato nuevo y el guard central.
2. MCP con manejo de `BRAND_INCOMPLETE`.
3. Web con onboarding obligatorio y guards.
4. Smoke test autenticado en producción.
5. Monitoreo de bloqueos y errores durante 24 horas.

No activar primero el bloqueo de API si la versión web desplegada todavía no
permite capturar los ocho campos. Para evitar esa ventana, la API puede publicar
primero el contrato sin hacer obligatorio el guard y activarlo al desplegar la
web, mediante una propiedad temporal de liberación. Esa propiedad debe retirarse
cuando el rollout quede validado para no convertirla en una evasión permanente.

## Fuera de alcance

- Cambios en la app móvil.
- Hacer obligatorio el logotipo.
- Hacer que las redes conectadas formen parte del porcentaje de marca.
- Reactivar el Asistente para completar automáticamente la marca.
- Generar respuestas de onboarding mediante IA.

