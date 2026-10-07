# Comentarios (bandeja en la web, avisos en el teléfono)

## Qué resuelve

Hoy Pícale publica y mide, pero lo que la gente **responde** a esas
publicaciones se queda en cada red. El dueño del negocio tiene que entrar a
Instagram, a Facebook y a TikTok uno por uno para ver si alguien preguntó un
precio. Con esto, todos los comentarios de todas sus cuentas llegan a un solo
lugar, se responden desde ahí, y el teléfono avisa solo cuando vale la pena.

Dos caras, a propósito distintas:

- **Teléfono (app)**: enterarse. Un aviso agrupado, nunca uno por comentario,
  y una bandeja dentro de la app para leer y responder rápido.
- **Web (panel)**: trabajar. Un módulo tipo Twitter: columna de conversaciones,
  responder sin cambiar de pantalla, marcar como atendido, filtrar.

## De dónde salen: upload-post ya los da

upload-post tiene una **API de comentarios unificada**, con la misma llave
(`Authorization: Apikey …`) y el mismo perfil que ya usamos para publicar. No
hace falta una App propia de Meta, ni App Review, ni guardar tokens de cada
red. Nuestro `UploadPostClient` todavía no la llama, eso es todo.

| Qué | Endpoint |
|---|---|
| Listar | `GET /api/uploadposts/comments` — `platform`, `user`, `post_id`\|`post_url`, `limit`, `after`, `comment_id` (para ver las respuestas de un comentario) |
| Responder | `POST /api/uploadposts/comments/create` — `platform`, `user`, `message`, y uno de `comment_id`\|`post_id`\|`post_url` |
| Borrar | `DELETE` (o `POST`) `/api/uploadposts/comments/delete` — `platform`, `user`, `comment_id` |
| Moderar | `POST /api/uploadposts/comments/action` — `action`: `hide`, `unhide`, `like`, `unlike`, `pin`, `unpin`, `enable_comments`, `disable_comments`, … |

Listar devuelve `{success, comments[], pagination:{next_cursor, has_next}}`:
cursor, no páginas numeradas.

### Lo que cada red deja hacer (esto manda en la interfaz)

| Red | Listar | Responder | Ocultar / moderar | Detalle que importa |
|---|---|---|---|---|
| Instagram | sí | **solo como respuesta** a un comentario (`comment_id`) | hide/unhide; nada de like ni pin | no se puede comentar de cero desde la API — da igual, nosotros siempre respondemos |
| Facebook | sí | sí | hide/unhide/like/pin; `edit` necesita `message` | puede adjuntar imagen (`attachment_url`) |
| YouTube | sí | sí | hide (= rechazado), unhide, `ban_author` | **pide el permiso `youtube.force-ssl`**: hay que reconectar la cuenta |
| TikTok | sí | sí (`post_id` siempre) | sí | **hay que reconectar la cuenta** para habilitar comentarios; ~10 s de retraso antes de que aparezca lo que escribimos |
| LinkedIn | sí | sí | no | el identificador es el URN (`urn:li:ugcPost:…`) |
| Threads | parcial (`partial: true`) | no | sí | no se puede borrar |
| X | sí (vía menciones) | sí | no se puede ocultar | |
| Reddit | no (503) | no | no | no aparece en la bandeja |

Lo que **sí** cambia para la persona: TikTok y YouTube piden **reconectar la
cuenta una vez**. Eso ya existe en Pícale (`RecordatorioDeReconexion`,
`ConexionesCaducadas`), así que es el mismo aviso de siempre, no un flujo
nuevo: "Vuelve a conectar tu TikTok para poder ver y contestar comentarios".

**Orden sugerido**: Instagram y Facebook primero (ya están conectadas, no
piden nada a nadie), luego TikTok y YouTube con su reconexión, y LinkedIn, X y
Threads al final. Una red sin soporte simplemente no aparece en la bandeja.

### No hay webhook de comentarios

Los webhooks de upload-post son solo cuatro —`upload_completed`,
`social_account_connected`, `social_account_disconnected`,
`social_account_reauth_required`— y ninguno avisa de comentarios nuevos. O sea
que **hay que sondear**, igual que las métricas.

Y el sondeo cuesta: el tope de upload-post es **por llave, no por red**, y lo
comparte todo lo demás (publicar, estado, métricas). Según el plan son 60–500
peticiones por minuto y 300–2500 cada 10 minutos, más 2/min por cada perfil
extra. Con una publicación por consulta, 200 publicaciones vivas son 200
peticiones por vuelta. Por eso:

- se consultan solo las publicaciones de los últimos 30 días **y** con
  movimiento reciente (el contador `comments` de las métricas ya dice cuáles
  crecieron: si no subió, no se pide la lista);
- tope por vuelta configurable, como `app.metricas.por-vuelta`;
- se leen las cabeceras `X-RateLimit-Remaining` y `X-RateLimit-Reset` y se baja
  el ritmo solo; con 429, la vuelta se corta y sigue en la siguiente.

El diseño queda así:

```
service/comentarios/
  ComentariosWorker.java     (sondeo periódico, en su propio hilo, como MetricasWorker)
  ComentariosService.java    (guardar, deduplicar, marcar, responder, moderar)
  LecturaDeComentarios.java  (traduce el JSON de cada red a nuestro modelo, como LecturaDeMetricas)
  CapacidadesPorRed.java     (qué deja hacer cada red — lo consulta también la interfaz)
  AvisoDeComentarios.java    (el resumen que se manda al teléfono)
```

y en `UploadPostClient` cuatro métodos nuevos: `comentarios(...)`,
`comentar(...)`, `borrarComentario(...)`, `accionSobreComentario(...)`.

## Cómo funciona

1. `ComentariosWorker` corre en su propio hilo (como `MetricasWorker`: el
   programador de Spring tiene uno solo para todo), cada ~10 minutos, y pide a
   upload-post los comentarios de las publicaciones de los últimos 30 días
   cuyo contador de comentarios subió desde la última vuelta.
2. Cada comentario se guarda con su `id_en_la_red`. Si ya está, no se duplica
   ni se vuelve a avisar — esta es la regla que sostiene todo lo demás.
3. Los comentarios propios (los que escribió la misma cuenta) se guardan como
   respuesta, no como pendiente: nadie se avisa a sí mismo.
4. Cuando hay comentarios nuevos sin atender, `AvisoDeComentarios` manda **un
   solo aviso agrupado** por espacio de trabajo, por `AvisosPush`.
5. La persona abre la bandeja (app o web), lee, responde. La respuesta se
   manda con `comments/create` y se guarda; el comentario pasa a "atendido".
   En TikTok tarda ~10 s en aparecer en la red: la bandeja la muestra ya puesta
   y no la vuelve a pedir en esa vuelta.

### Tabla `comentarios`

| Columna | Para qué |
|---|---|
| `id` | UUID |
| `tenant_id` | `@TenantId`, como el resto |
| `workspace_id`, `social_account_id`, `post_target_id` | de quién y de qué publicación |
| `id_en_la_red` | único junto con la red — es lo que evita duplicados |
| `red` | INSTAGRAM, FACEBOOK, … |
| `autor_nombre`, `autor_avatar_url` (2048, como `SocialAccount`) | quién comentó |
| `texto` | el comentario |
| `padre_id` | si es respuesta a otro comentario (hilo) |
| `escrito_en` | cuándo lo escribió la persona en la red |
| `leido_en`, `atendido_en`, `atendido_por` | el estado de la bandeja |
| `respuesta_texto`, `respondido_en`, `respuesta_id_en_la_red` | lo que contestamos |
| `oculto_en` | si se ocultó/borró en la red |
| `sentimiento` | opcional, de la IA: pregunta / queja / elogio / spam |

Índice por `(workspace_id, atendido_en, escrito_en desc)` — es la consulta de
la bandeja y va a ser la más usada.

### Endpoints

```
GET    /api/v1/comentarios?estado=pendientes|todos&red=&cuenta=&q=&page=
GET    /api/v1/comentarios/resumen          -> {pendientes, porCuenta[]}  (barra de arriba)
GET    /api/v1/comentarios/{id}             -> el hilo completo
POST   /api/v1/comentarios/{id}/responder   -> {texto}
POST   /api/v1/comentarios/{id}/atender     -> marcar sin responder
POST   /api/v1/comentarios/leidos           -> {ids[]}  (al abrir la bandeja)
POST   /api/v1/comentarios/{id}/ocultar     -> spam/ofensivo, si la red deja
POST   /api/v1/comentarios/{id}/sugerir     -> la IA propone una respuesta
```

Permisos: leer con cualquier permiso de lectura del espacio; responder y
ocultar exigen un permiso propio (`COMMENT_REPLY`), porque responder es hablar
en nombre del negocio. Todo filtrado por `workspace_id` y `tenant_id`.

## Teléfono: avisar sin molestar

Reglas duras, todas comprobables:

- **Uno agrupado, nunca uno por comentario.** "3 comentarios nuevos en tu
  Instagram" — y si son de varias cuentas, "5 comentarios nuevos en 2 cuentas".
- **Como mucho un aviso cada 2 horas** por espacio de trabajo.
- **Nada entre las 22:00 y las 8:00** (hora del espacio). Lo de la noche se
  junta con el primero de la mañana.
- **Tope de 4 avisos al día.** Pasado eso, se acumula y se avisa al siguiente
  hueco.
- **Nada mientras la bandeja está abierta.**
- **Se apaga en un toque**, desde el mismo aviso y desde Ajustes. Hay tres
  opciones y se entienden sin explicación: *Todos* · *Solo preguntas* ·
  *Ninguno*.
- Si no hay credenciales de FCM, no pasa nada: los avisos son una ayuda, nunca
  una condición. (`AvisosPush` ya se comporta así.)

Dentro de la app: un punto en la pestaña, la lista de comentarios pendientes,
y responder desde ahí con el teclado o con una sugerencia de la IA.

**Nada de compras.** La bandeja no habla de créditos ni de recargas. Si la
sugerencia de la IA no está disponible, el botón no aparece y ya.

## Web: el módulo tipo Twitter

Tres zonas, sin menús escondidos:

```
┌─────────────┬───────────────────────────┬──────────────────┐
│ Pendientes 7│  "¿Cuánto cuesta?"        │  La publicación  │
│ Atendidos   │  María López · Instagram  │  [miniatura]     │
│ Todos       │  hace 2 horas             │                  │
│ ─────────   │                           │  12 me gusta     │
│ Instagram 4 │  [ Escribe tu respuesta ] │   7 comentarios  │
│ Facebook  3 │  [Responder] [Sugerir]    │                  │
└─────────────┴───────────────────────────┴──────────────────┘
```

- La lista se lee como un chat: foto, nombre, lo que dijo, hace cuánto.
- Responder **nunca cambia de pantalla**. Se escribe y se manda ahí mismo.
- Al responder, el comentario se va de "Pendientes" solo, con un "Deshacer"
  de 5 segundos.
- **Palabras simples**: "Pendientes" / "Ya atendidos", no "inbox", "queue",
  "SLA" ni "engagement". Los errores dicen qué pasó y qué hacer:
  "Instagram no aceptó la respuesta. Inténtalo otra vez en un momento."
- La barra de arriba lleva el número de pendientes, igual que el saldo.
- Se actualiza sola cada minuto mientras la pestaña está abierta; si llega algo
  nuevo, un botón discreto "3 nuevos" en vez de moverle la lista bajo el dedo.

## Matriz de pruebas

`A` = automática (JUnit / Jasmine / `flutter test`) · `M` = manual.
Prioridad: `1` bloquea el lanzamiento, `2` importante, `3` deseable.

### 1. Traer comentarios (API)

| # | Caso | Precondición | Resultado esperado | P | Tipo |
|---|---|---|---|---|---|
| C-01 | Primera lectura de una cuenta | cuenta con 3 comentarios en la red | se guardan los 3, todos pendientes | 1 | A |
| C-02 | Segunda vuelta sin novedad | C-01 ya corrió | 0 nuevos, 0 duplicados, 0 avisos | 1 | A |
| C-03 | Mismo `id_en_la_red` dos veces | respuesta repetida de la red | una sola fila (restricción única), sin excepción | 1 | A |
| C-04 | Comentario de la propia cuenta | la marca contestó desde Instagram | se guarda como respuesta, no como pendiente, no avisa | 1 | A |
| C-05 | Respuesta a un comentario | hilo de 2 niveles | `padre_id` apunta al original; el hilo se ve completo | 2 | A |
| C-06 | La red contesta 429 | fuente simulada | la vuelta se corta, se reintenta a la siguiente, nada se pierde | 1 | A |
| C-07 | La red contesta 401 (token vencido) | token caducado | la cuenta se marca para reconectar, las demás siguen | 1 | A |
| C-08 | Red sin soporte | Reddit conectado (upload-post responde 503) | no se consulta, no aparece en la bandeja, sin error en log | 2 | A |
| C-09 | Comentario borrado en la red | existía y ya no viene | se marca `oculto_en`, no se borra el historial | 2 | A |
| C-10 | Publicación de hace 60 días | fuera de ventana | no se consulta | 3 | A |
| C-11 | Texto con emojis, acentos y 2000 caracteres | — | se guarda íntegro, se muestra íntegro | 2 | A |
| C-12 | 500 comentarios en una vuelta | cuenta viral | se siguen los cursores (`next_cursor`/`has_next`) hasta el final, sin tumbar el hilo ni el pool | 2 | A |
| C-13 | Dos instancias de la API a la vez | despliegue con dos contenedores | no se duplica ni se avisa dos veces | 1 | A |
| C-14 | El contador no subió | `comments` igual que la vuelta anterior | no se pide la lista — así se ahorra el tope de upload-post | 1 | A |
| C-15 | Cabecera `X-RateLimit-Remaining` baja | quedan 5 | la vuelta se detiene y deja margen para publicar | 1 | A |
| C-16 | Threads devuelve `partial: true` | — | se guarda lo que vino y se vuelve a pedir, sin marcarlo como completo | 2 | A |
| C-17 | LinkedIn con URN | `urn:li:ugcPost:…` | se manda el URN como identificador, no el id suelto | 2 | A |
| C-18 | TikTok sin reconectar | cuenta conectada antes | no rompe; la cuenta queda marcada "hay que reconectar" | 1 | A |

### 2. Separación de datos y permisos

| # | Caso | Precondición | Resultado esperado | P | Tipo |
|---|---|---|---|---|---|
| S-01 | Comentarios de otro espacio | dos espacios del mismo dueño | la bandeja solo muestra los del espacio activo | 1 | A |
| S-02 | Comentarios de otro inquilino | dos organizaciones | 404/403, nunca datos de la otra | 1 | A |
| S-03 | Pedir un comentario ajeno por id | id válido de otro espacio | 404 | 1 | A |
| S-04 | Miembro solo lectura intenta responder | sin `COMMENT_REPLY` | 403, mensaje claro, el botón no se ve en la interfaz | 1 | A |
| S-05 | Admin de la organización | no es miembro del espacio | sí ve y sí responde | 2 | A |
| S-06 | Invitado quitado del espacio | sesión aún abierta | 403 en la siguiente llamada | 1 | A |
| S-07 | Eliminación definitiva de una persona | — | sus comentarios atendidos quedan, sin su nombre | 2 | A |
| S-08 | Eliminar un espacio para siempre | — | sus comentarios se van con él | 2 | A |

### 3. Responder

| # | Caso | Precondición | Resultado esperado | P | Tipo |
|---|---|---|---|---|---|
| R-01 | Responder bien | comentario pendiente | sale en la red, queda `respondido_en`, pasa a atendido | 1 | A |
| R-02 | La red rechaza la respuesta | fuente simulada 400 | sigue pendiente, mensaje en palabras simples, se puede reintentar | 1 | A |
| R-03 | Respuesta vacía o solo espacios | — | no se manda, aviso en la propia caja de texto | 1 | A |
| R-04 | Respuesta más larga que el tope de la red | 2500 caracteres en Instagram | se avisa antes de mandar, con el número que falta recortar | 2 | A |
| R-05 | Doble clic en Responder | red lenta | una sola respuesta en la red | 1 | A |
| R-06 | Responder algo ya atendido por otra persona | dos sesiones abiertas | aviso "ya lo atendió Ana", sin duplicar | 2 | A |
| R-07 | Marcar atendido sin responder | — | sale de pendientes, no se manda nada a la red | 1 | A |
| R-08 | Deshacer dentro de 5 s | — | vuelve a pendientes y la respuesta no se manda | 2 | M |
| R-09 | Sugerencia de la IA | créditos disponibles | propone un texto editable; nunca se manda solo | 1 | M |
| R-10 | Sugerencia sin IA disponible | sin llave / sin créditos | el botón no aparece; **nunca** se menciona recargar | 1 | A |
| R-11 | Ocultar un comentario ofensivo | Facebook o Instagram | `comments/action` con `hide`; desaparece de la red y queda marcado | 2 | A |
| R-12 | Ocultar en una red que no lo permite | LinkedIn o X | la opción no se ofrece en la pantalla | 3 | A |
| R-13 | Responder en TikTok | — | se manda con `post_id`; la bandeja la muestra puesta aunque la red tarde ~10 s | 1 | A |
| R-14 | Responder en Instagram | — | se manda con `comment_id` (ahí solo se puede responder, no comentar de cero) | 1 | A |
| R-15 | Responder en Threads | red sin responder | el botón no aparece; se puede marcar como atendido | 2 | A |
| R-16 | Borrar una respuesta propia | Instagram/Facebook/YouTube | se va de la red y queda marcada | 3 | A |

### 4. Avisos al teléfono (que no molesten)

| # | Caso | Precondición | Resultado esperado | P | Tipo |
|---|---|---|---|---|---|
| N-01 | 1 comentario nuevo | — | 1 aviso: "1 comentario nuevo en tu Instagram" | 1 | A |
| N-02 | 7 comentarios en una vuelta | — | **1 solo** aviso agrupado con el número, no 7 | 1 | A |
| N-03 | Comentarios en 2 cuentas | — | 1 aviso: "5 comentarios nuevos en 2 cuentas" | 1 | A |
| N-04 | Segunda tanda a los 20 minutos | ya avisó hace 20 min | no avisa; espera a las 2 h | 1 | A |
| N-05 | Comentario a las 23:30 | — | no avisa de noche; se junta con el de las 8:00 | 1 | A |
| N-06 | Quinto aviso del día | ya van 4 | no avisa; se acumula | 1 | A |
| N-07 | Bandeja abierta en ese momento | app en primer plano, en la bandeja | no avisa; la lista se actualiza sola | 2 | M |
| N-08 | Ajuste "Solo preguntas" | — | solo avisa de comentarios con `?` o marcados pregunta | 2 | A |
| N-09 | Ajuste "Ninguno" | — | cero avisos; la bandeja sigue funcionando | 1 | A |
| N-10 | Apagar desde el propio aviso | — | queda apagado sin entrar a Ajustes | 2 | M |
| N-11 | Tocar el aviso | app cerrada | abre directo en ese comentario, no en el inicio | 1 | M |
| N-12 | Sin credenciales de FCM | entorno de pruebas | no avisa, no rompe nada, lo dice una vez en el log | 1 | A |
| N-13 | Teléfono desinstalado | token muerto | FCM responde 404, el token se borra solo | 2 | A |
| N-14 | Dos teléfonos de la misma persona | — | ambos avisan, una sola vez cada uno | 2 | A |
| N-15 | Quién recibe | miembro sin permiso de responder | no recibe aviso de comentarios | 2 | A |
| N-16 | Cerrar sesión | — | ese teléfono deja de recibir | 1 | A |

### 5. App móvil

| # | Caso | Precondición | Resultado esperado | P | Tipo |
|---|---|---|---|---|---|
| M-01 | Punto en la pestaña | hay pendientes | se ve el punto con el número | 1 | M |
| M-02 | Abrir la bandeja | — | carga en menos de 2 s, lo más nuevo arriba | 1 | M |
| M-03 | Sin comentarios todavía | cuenta recién conectada | pantalla vacía amable, sin palabras técnicas | 1 | M |
| M-04 | Sin internet | avión | dice que no hay conexión y ofrece reintentar; no pantalla en blanco | 1 | M |
| M-05 | Responder desde el teléfono | — | funciona con el teclado abierto, el texto no queda tapado | 1 | M |
| M-06 | Deslizar para atender | — | sale de la lista con un "Deshacer" | 2 | M |
| M-07 | Letra grande del sistema | accesibilidad al 200 % | nada se corta ni se encima | 2 | M |
| M-08 | Teléfono chico (360 dp) | — | se lee completo, sin desbordes | 2 | M |
| M-09 | Modo oscuro | — | contraste suficiente en todo | 3 | M |
| M-10 | Cambiar de espacio de trabajo | 2 espacios | la lista cambia con él | 1 | M |
| M-11 | Nada de compras | toda la pantalla | ni "créditos", ni "recarga", ni precios | 1 | M |

### 6. Panel web (módulo tipo Twitter)

| # | Caso | Precondición | Resultado esperado | P | Tipo |
|---|---|---|---|---|---|
| W-01 | Entrar al módulo | hay pendientes | tres zonas visibles, el primero ya seleccionado | 1 | M |
| W-02 | Contador en la barra de arriba | — | coincide con los pendientes de la lista | 1 | A |
| W-03 | Responder sin cambiar de pantalla | — | se manda y el siguiente se selecciona solo | 1 | M |
| W-04 | Filtrar por red | — | solo esa red; el contador del filtro cuadra | 2 | A |
| W-05 | Buscar por texto o por nombre | — | encuentra con y sin acentos | 2 | A |
| W-06 | Llega algo nuevo mientras lees | otra pestaña responde | botón "3 nuevos"; la lista **no** se mueve sola | 1 | M |
| W-07 | 1000 comentarios | cuenta grande | se cargan por páginas, el navegador no se traba | 2 | M |
| W-08 | Ver la publicación del comentario | — | miniatura y números a la derecha | 2 | M |
| W-09 | Solo con teclado | sin ratón | se puede navegar y responder; el foco se ve siempre | 2 | M |
| W-10 | Lector de pantalla | NVDA | lee autor, texto y estado de cada comentario | 3 | M |
| W-11 | Pantalla de 1366 px | portátil común | las tres zonas caben; nada en horizontal | 2 | M |
| W-12 | Se cae la API | 500 | mensaje claro y botón de reintentar; no pantalla en blanco | 1 | M |
| W-13 | Palabras simples | revisión de toda la pantalla | ni "inbox", ni "engagement", ni "SLA", ni "thread" | 1 | M |
| W-14 | Recargar en medio de una respuesta | texto escrito a medias | avisa antes de perderlo | 3 | M |

### 7. Reconectar TikTok y YouTube

| # | Caso | Precondición | Resultado esperado | P | Tipo |
|---|---|---|---|---|---|
| P-01 | Aviso en palabras simples | TikTok conectado de antes | "Vuelve a conectar tu TikTok para poder ver y contestar comentarios" | 1 | M |
| P-02 | La persona no reconecta | — | todo lo demás sigue igual; esa cuenta no sale en la bandeja, sin errores repetidos | 1 | M |
| P-03 | Reconectar | — | empieza a traer, sin duplicar nada de lo que ya estaba | 1 | A |
| P-04 | YouTube sin `youtube.force-ssl` | permiso viejo | mismo aviso de reconectar; no se intenta en cada vuelta | 1 | A |
| P-05 | Cuenta desconectada desde la red | — | se marca sola en la siguiente vuelta y se avisa una vez | 2 | A |

### 8. Carga y costo (tope de upload-post)

| # | Caso | Resultado esperado | P | Tipo |
|---|---|---|---|---|
| L-01 | 100 cuentas conectadas | la vuelta termina dentro de su intervalo sin agotar el tope | 2 | M |
| L-02 | El tope es por llave, no por red | el sondeo **nunca** deja sin cupo a publicar ni a las métricas: publicar tiene prioridad | 1 | A |
| L-03 | upload-post responde 429 | la vuelta se corta, se respeta `X-RateLimit-Reset`, nada se pierde | 1 | A |
| L-04 | Bandeja con 50 000 filas | la consulta responde en menos de 300 ms con el índice | 2 | M |
| L-05 | Sondeo apagado por configuración | `app.comentarios.enabled=false` deja todo quieto | 2 | A |

## Cómo va

**API (hecho).**

| Qué | Dónde |
|---|---|
| Entidad y bandeja | `entity/Comentario`, `repository/ComentarioRepository` |
| Traer, contestar, ocultar | `service/comentarios/ComentariosService` |
| Sondeo | `service/comentarios/ComentariosWorker` |
| Leer el JSON de cada red | `service/comentarios/LecturaDeComentarios` |
| Qué deja hacer cada red | `service/comentarios/CapacidadesPorRed` |
| Avisos agrupados al teléfono | `service/comentarios/AvisoDeComentarios` |
| Llamadas a upload-post | `service/social/UploadPostClient` (`comentarios`, `comentar`, `accionSobreComentario`, `borrarComentario`) |
| Endpoints | `controller/ComentariosController` |
| Permiso | `Permission.COMMENT_REPLY` (lo trae EDITOR y ADMIN) |
| Cupo que queda en upload-post | `UploadPostClient.cupoRestante()` (lee `X-RateLimit-Remaining`) |
| Ajustes | `app.comentarios.*` en `application.properties` |

Dos columnas nuevas en `post_targets` —`comentarios_en` y
`comentarios_revisados`— son las que evitan preguntar por lo que no cambió, y
una en `social_accounts` —`comentarios_bloqueado_en`— marca la cuenta que hay
que volver a conectar. Las crea Hibernate con `ddl-auto=update`, no hay
migración que correr.

Tres detalles que son el motivo de media clase cada uno:

- **Los avisos van a quien puede CONTESTAR** (`COMMENT_REPLY`), no a quien
  puede programar. `AvisosPush.avisarAQuienPuede` lo hace general: cada aviso
  elige su permiso.
- **Con la bandeja abierta no se avisa.** Servir `GET /comentarios` marca el
  espacio como "lo están mirando" durante 5 minutos.
- **El sondeo se detiene con poco cupo.** El tope de upload-post es por llave y
  lo comparten publicar y las métricas; `app.comentarios.reserva` (15) es lo
  que nunca se toca.

**Panel web (hecho)**, en `metricol_web`:

| Qué | Dónde |
|---|---|
| Servicio y resumen que se refresca solo | `services/comentarios.service.ts` |
| El módulo de tres zonas | `pages/panel/pages/comentarios/` |
| Ruta y número en el menú | `app.routes.ts`, `pages/panel/layout/panel-layout/` |

**App (hecho)**, en `metricol_app`:

| Qué | Dónde |
|---|---|
| Modelo y servicio | `models/comentario.dart`, `services/comentarios_service.dart` |
| La bandeja | `pages/comentarios/comentarios_page.dart` |
| La puerta en Hoy, solo si hay pendientes | `widgets/tarjeta_comentarios.dart` |
| Tocar el aviso abre la bandeja | `services/avisos_service.dart`, `pages/shell/shell_page.dart` |

En la app **no hay quinta pestaña** a propósito: la barra de abajo ya tiene las
cuatro cosas de todos los días, y una quinta las encoge todas. Se entra por la
tarjeta de Hoy —que no aparece cuando no hay nada— y por el aviso.

**Falta**, y es todo lo que falta:

- **El ajuste de avisos por persona** (Todos / Solo preguntas / Ninguno). Las
  reglas de no molestar se cumplen, pero viven en la configuración del servidor
  y no se pueden cambiar desde el teléfono (N-08, N-09, N-10).
- **Borrar una respuesta propia** (R-16). `UploadPostClient.borrarComentario`
  existe y funciona, pero no hay endpoint ni botón que lo llame.
- **Probarlo contra upload-post de verdad.** Los nombres de los campos de cada
  red salen de su documentación, no de una respuesta vista. `LecturaDeComentarios`
  acepta varios nombres por cada cosa y lo que no reconoce lo deja nulo, así que
  el riesgo es perder un avatar o una fecha, no reventar.

## Lo que queda fuera a propósito

- Mensajes directos (DM) y los AutoDM de upload-post (`/uploadposts/autodms`,
  que mandan un privado a cada quien comente). Existen y funcionan, pero
  mandar privados solos a desconocidos es otra decisión, y no es esta.
- Responder automáticamente sin que una persona lo apruebe. La IA **sugiere**;
  quien manda es la persona.
- Moderación automática por palabras. Después, si hace falta.
