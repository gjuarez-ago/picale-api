# Mensajes del asistente: quitar lo robótico y pasar a bloques

## Estado de implementación

Implementado en API y web el 16 de octubre de 2026:

- Traducción defensiva de formatos y códigos internos.
- Listas con un nivel de sangría, conservando el escape de HTML.
- Herramienta propia `responder_con_bloques`, limitada a ocho piezas.
- Bloques de texto, medio, propuesta y opciones en una secuencia.
- Resolución de medios exclusivamente desde el servidor y contra el workspace activo.
- `content` permanece como respaldo, historial y contenido del botón Copiar.
- Renderizado compatible con mensajes anteriores que no tienen bloques.
- Una sola conversación continua por workspace, con memoria operativa basada en recibos MCP.

El diseño de continuidad y la evolución hacia memoria tipada están detallados en
[`asistente-memoria-global.md`](asistente-memoria-global.md).

### Validación para liberar

La integración se considera lista cuando pasan estas capas, sin depender de una
cuenta real ni ejecutar publicaciones externas:

| Capa | Cobertura exigida |
|---|---|
| API | Catálogo de herramientas propias; bloques; límites; medio válido, inexistente y de otro workspace; respaldo `content` |
| Web unitaria | Traducción defensiva, HTML escapado y listas con una sola sangría |
| Web E2E | Mensaje antiguo → bloques con medio y propuesta → opción → formulario → recibo → confirmación sensible |
| MCP | Catálogo, políticas de riesgo, permisos, confirmación, idempotencia, reintentos y ausencia de secretos |
| Compilación | API y web de producción sin prerender, porque la aplicación autenticada no necesita generar rutas privadas en build time |

El workflow `Assistant E2E` ejecuta la compilación web y Playwright en cada pull
request a la rama principal. Publicar o desplegar sigue siendo una decisión
separada y explícita.

### Límites operativos visibles

- Máximo ocho bloques por respuesta, seis campos por propuesta y cinco opciones.
- El pie de un medio se limita a 200 caracteres.
- Solo se muestra un medio `READY` del workspace activo; uno inválido se omite.
- Los bloques presentan y recogen decisiones: no sustituyen el recibo del MCP.
- Lecturas pueden ejecutarse sin confirmación. Las escrituras obedecen la política
  publicada por el MCP; `publicar_ahora` requiere confirmación explícita.
- Si una función no existe en el catálogo MCP, Chispa puede recomendar el camino,
  pero no afirmar que la ejecutó.

## Reparto de responsabilidades: MCP, servicios y modelo

El MCP sigue siendo el árbol de decisión y la única superficie que autoriza
consultas y acciones del negocio. Los servicios internos no compiten con él:
enriquecen la interacción con información que ya pertenece al sistema, como la
URL segura de un archivo, el workspace activo, los permisos o el estado durable
de una ejecución.

| Capa | Puede hacer | No puede hacer |
|---|---|---|
| MCP | Declarar capacidades, consultar y ejecutar acciones con política y recibo | Delegar una acción no catalogada |
| Servicios internos | Validar pertenencia, resolver medios y aportar estado seguro | Inventar capacidades o marcar trabajo como realizado |
| Modelo | Explicar, proponer y elegir herramientas disponibles | Afirmar resultados sin recibo o fabricar datos actuales |
| Web | Presentar bloques y recoger decisiones | Interpretar IDs, ejecutar reglas de negocio o asumir éxito |

La consecuencia práctica es importante: si el catálogo no ofrece una acción,
Chispa explica que no puede ejecutarla desde el chat, indica dónde se realiza y
continúa con lo que sí puede resolver. Nunca promete trabajo futuro ni simula un
resultado para que la conversación “se vea bien”.

## Por qué

El asistente responde bien, pero **se le ve la tripa**. En una conversación real
sobre una foto de tamalitos, el mensaje decía:

```
Archivo: imagen id cad63c21-4b99-47ed-8c76-140e37991b3c
Formato: PHOTO
Redes: todas conectadas
```

Tres problemas en tres renglones:

1. **Un UUID delante de quien no sabe qué es.** El modelo solo conoce la foto por
   su identificador, así que lo escribe. La persona tendría que ver la foto.
2. **`PHOTO` en vez de «Foto».** El código del enum sale crudo, igual que antes
   salían `MAS_CLIENTES` y `PROFESIONAL` en el formulario de marca.
3. **Todo es un bloque de texto.** Una propuesta de publicación —con su imagen,
   su texto y sus redes— se aplana en renglones sueltos, porque el mensaje solo
   tiene un campo de texto donde meterlo todo.

Los dos primeros son suciedad que se limpia hoy. El tercero es un límite de
diseño: `AssistantMessage` guarda `content` (texto) y `artifactsJson` (tarjetas al
final). No hay forma de intercalar una imagen en medio de una explicación.

## Lección que gobierna este trabajo

Tres veces en la misma sesión pasó lo mismo: pedirle algo al modelo **por prompt**
falló, y ponerlo **en la estructura** funcionó.

- Pedirle formato en prosa → lo ignoró. Un ejemplo al final del prompt → listas.
- Pedirle que escribiera la propuesta → preguntaba por «esta propuesta» sin
  escribirla. Campo `respuesta` obligatorio en la herramienta → siempre viene.
- Pedirle que no inventara giros → se los inventaba. Catálogo en el esquema de la
  herramienta → ofrece los del alta.

El modelo es `gpt-5-mini` con esfuerzo `medium`. Obedece lo que no puede evitar.
**Todo lo que importe va en la estructura; el prompt es solo refuerzo.**

---

## Fase 1 — Dejar de enseñar identificadores y códigos

Barata, sin cambio de contrato, se nota de inmediato.

### 1.1 Regla en el prompt

En `AssistantOrchestrator.SYSTEM_PROMPT`, junto al bloque de escritura:

> Nunca enseñes identificadores ni códigos internos: ni un id de archivo, ni
> `PHOTO`, ni `MAS_CLIENTES`. A un archivo se le nombra por lo que se ve en él o
> por su nombre; a un formato, «foto», «reel», «historia».

Refuerzo, no garantía. La red está en 1.2.

### 1.2 Traducir al dibujar

`metricol_web/.../assistant/detalles-legibles.ts` ya tiene `etiquetaLegible()`,
que traduce los códigos conocidos y deja pasar lo demás. Hoy cubre tonos,
objetivos y redes. Añadir los formatos:

```ts
['PHOTO', 'Foto'], ['VIDEO', 'Video'], ['REEL', 'Reel'], ['STORY', 'Historia'],
```

Aplicarlo donde hoy no se aplica: el texto libre del mensaje. En el pipe
`TextoDelAsistentePipe`, al resaltar una etiqueta `Clave: valor`, pasar el valor
por `etiquetaLegible()` cuando sea una sola palabra en mayúsculas.

**Riesgo:** traducir dentro del texto libre puede tocar algo que no era un código
(un hashtag en mayúsculas, un nombre propio). Limitar a valores que estén en el
mapa y que sean exactamente `[A-Z_]{3,}`.

### 1.3 Pruebas

- `etiquetaLegible('PHOTO')` → `Foto`; `etiquetaLegible('#TAMALITOS')` → igual.
- El pipe no toca `Texto: Antojo de TAMALITOS caseros`.

---

## Fase 2 — Sangría en el renderizador

Hoy `TextoDelAsistentePipe` hace `linea.trim()` antes de clasificar, así que un
sub-punto indentado se aplana al mismo nivel que su padre.

### 2.1 Qué cambia

Medir la sangría **antes** del `trim`, y abrir una lista anidada cuando un punto
viene con dos espacios o más que el anterior:

```
- Instagram
  - 3 publicaciones
  - mejor hora: 19:00
```

Un solo nivel de anidamiento. Dos ya es un documento, no un mensaje de chat.

### 2.2 Qué NO cambia

**No se mete una librería de markdown.** Se evaluó `marked` + sanitizador: daría
tablas y anidamiento profundo, pero significa ejecutar HTML generado por un
modelo, y el pipe actual escapa todo antes de construir. El subconjunto acotado
cubre lo que un chat necesita y no abre esa puerta.

### 2.3 Pruebas

- Dos niveles con sangría producen `<ul>` dentro de `<li>`.
- La sangría no convierte en lista un párrafo que empieza con espacios.
- Sigue escapando `<script>` dentro de un sub-punto.

---

## Fase 3 — Mensajes por bloques

El cambio de fondo. **Hacer después de 1 y 2, con lo demás quieto.**

### 3.1 El contrato

`artifactsJson` deja de ser «tarjetas al final» y pasa a ser **la secuencia de
piezas del mensaje**, en orden:

```json
[
  {"type": "texto",  "markdown": "Esta foto sí sirve: se ven los tamalitos…"},
  {"type": "media", "url": "https://…", "thumbnailUrl": "https://…",
   "format": "Foto", "caption": "La que propongo"},
  {"type": "propuesta", "title": "Publicación lista para revisar",
   "fields": [{"label": "Formato", "value": "Foto"}, {"label": "Redes", "value": "Todas"}]},
  {"type": "choices", "question": "¿Creo el borrador?",
   "options": [{"label": "Sí, créalo"}, {"label": "Quiero cambiarlo"}]}
]
```

`content` se conserva: es lo que se copia con el botón de copiar, lo que viaja al
modelo como historial y el respaldo para un cliente que no entienda bloques.
**Un mensaje sin bloques se dibuja como hoy**, así que lo viejo sigue funcionando.

### 3.2 Quién resuelve el identificador

**La clave de toda la fase.** El modelo nombra el archivo por su `assetId`, que es
lo único que conoce. `AssistantOrchestrator` **ya tiene inyectado**
`MediaAssetRepository` (lo usa `validateAttachments`), así que al construir el
bloque busca el medio y escribe `url` y `thumbnailUrl`.

El identificador se queda en el servidor y tampoco viaja en `artifactsJson`. La
persona y el navegador reciben únicamente el medio ya validado para su espacio.

Validar siempre que el medio **pertenezca al workspace del turno** antes de
resolverlo: un id que venga del modelo no es de fiar. Si no existe o es de otro
espacio, el bloque se omite.

### 3.3 Cómo los declara el modelo

Herramienta propia nueva, al lado de `ofrecer_opciones` y `pedir_datos`, con el
mismo patrón: no ejecuta nada, cierra el turno.

```
responder_con_bloques(bloques: [
  {tipo: "texto", markdown: "…"},
  {tipo: "media", archivo: "<assetId>", pie: "…"},
  {tipo: "propuesta", titulo: "…", campos: [{etiqueta, valor}]}
])
```

Se registra en `withOwnTools()` (hoy `withChoicesTool`), se intercepta en
`execute()` como las otras dos, y termina en `finish(...)`.

Límites: 8 bloques por mensaje, 6 campos por propuesta, pie de 200 caracteres.

### 3.4 La web

`assistant.component.ts` gana `bloques(message)`, que devuelve la secuencia o
`null` si el mensaje es de los de antes. La plantilla recorre los bloques:

- `texto` → el pipe que ya existe.
- `media` → el mismo `<img>` de los adjuntos, con su pie.
- `propuesta` → la lista de definición de la tarjeta de confirmación.
- `choices` / `form` → los componentes actuales, sin tocar.

### 3.5 Pruebas

- Un bloque `media` con un asset de otro workspace se omite, y el resto del
  mensaje se dibuja igual.
- Un asset inexistente no rompe el turno.
- Un mensaje sin bloques se sigue dibujando con el camino viejo.
- El `content` guardado permite copiar el mensaje completo.

---

## Orden y por qué

| Fase | Esfuerzo | Se nota | Riesgo |
|---|---|---|---|
| 1 · Códigos e identificadores | Bajo | Mucho | Bajo |
| 2 · Sangría | Bajo | Medio | Bajo |
| 3 · Bloques | Alto | Mucho | Medio: contrato entre API y web |

1 y 2 son independientes y se pueden soltar por separado. 3 cambia el contrato,
así que va cuando lo demás esté estable y con el camino viejo intacto como
respaldo.

## Lo que este trabajo NO resuelve

- **Que el asistente redacte mejor.** Eso depende del modelo. `gpt-5-mini` está
  marcado como deprecado; cuando toque migrar, `gpt-5.4-mini` hay que habilitarlo
  antes en el proyecto de OpenAI (hoy devuelve 403) y actualizar sus dos precios
  en el `.env`, o el costo en `ai_usage` sale mal.
- **Los emojis.** Salen del modelo. La pantalla no puede inventarlos sin
  inventarse también el significado.
- **Streaming.** El mensaje sigue apareciendo entero al final. Es otro trabajo.
