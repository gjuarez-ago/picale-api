# Agente: la IA como community manager

Estado: **fase 1 construida en la rama `agente`, sin desplegar.** Ya funciona:

- El switch por cuenta, y solo cuenta lo que se sube después de encenderlo.
- Filtro de marca (va, observación, descartada) con las reglas de cuidado y las promociones
  vencidas, y fotos repetidas fuera antes de gastar en la IA (huella de imagen).
- **Flujo de decisión** (`DecisorDelAgente`): la IA de visión califica cada foto (calidad, si
  se arregla, fuerza visual, si ya es un arte, si el mensaje necesita leerse en la imagen,
  intención) y un decisor con reglas fijas resuelve tal cual, retoque, diseño u observación, y
  si lleva logo. Cada propuesta explica el camino paso a paso. El retoque es ffmpeg (0
  créditos); el diseño, 5 créditos (una generación).
- **Ritmo de créditos** (`RitmoDeCreditos`): lo que queda del mes repartido entre las semanas
  que faltan, uno siempre de reserva. Las candidatas a diseño de prioridad media o baja dejan
  un crédito libre para una urgente.
- **Aprende por cuenta:** cada diseño descartado sube el umbral para diseñar en esa cuenta y
  cada diseño aprobado lo baja (`Workspace.agenteAjusteDiseno`, de -1 a 2).
- Si el diseño falla, va tal cual (y no se cobra); si el retoque o el logo fallan, va sin
  ellos. Lo dice en la propuesta.
- El texto de cada red, todas las redes conectadas que acepten fotos, y la fecha dentro del
  horario del negocio (2 al día como mucho, a las 11 y a las 18 si caben).
- Propuestas que vencen sin aprobar se mueven solas al siguiente hueco.
- Pausa de emergencia, el resumen de todas las cuentas y la pantalla **Agente** de la web.
- **¿Le cambiamos algo?** en cada propuesta: se escribe el cambio y el agente la rehace desde la
  foto original (`Cambio`: "sin logo", "tal cual", "diséñala" cambian la decisión; el resto
  llega como instrucción a quien escribe y al diseño).
- **Bandeja de todas las cuentas** ("Todas mis cuentas"): aprobar y descartar sin entrar a cada
  una, con el permiso de la persona en CADA cuenta.
- **Replanear:** al descartar o rehacer, lo que venía después se adelanta al hueco libre.
- **Atajos de teclado** en la bandeja: J/K moverse, A aprobar, E cambiar, D descartar.

**Fase 2, en curso:**

- **Videos:** el agente mide cada video con ffprobe (orientación con la rotación del teléfono
  aplicada, y duración) sin bajarlo entero, lo mira por su portada contra la marca y lo propone
  como Reel en las redes que aceptan su duración (las que no, se quitan y se dice). Horizontal,
  de menos de 3 s, ilegible o más largo que todas tus redes: a Observación con el porqué. Por
  ahora tal cual: sin marca de agua ni recorte. (`MedidorDeVideo`, `AgenteService.procesarVideo`)

- **Análisis completo del video:** seis cuadros de principio a fin y la transcripción de lo que
  se dice (hasta 10 minutos, el tope de subida). La IA dice qué es (recorrido, demostración,
  testimonio, detrás de cámaras…), su calidad, sus tomas, la mejor portada y el mejor tramo de
  hasta 90 s. El decisor elige Reel o historia (lo del momento y corto va de historia), la
  portada por publicación (`Post.portadaMs`, que llega a TikTok como `cover_timestamp` y a
  Instagram como imagen) y, si dura más de 90 s, deja en Observación el tramo exacto a recortar
  hasta que exista el editor. El texto de cada red usa lo que se ve y lo que se dice.
- **Mezcla de contenido:** cada propuesta lleva su categoría (promoción, venta, comunidad, día a
  día) y el calendario nunca pone tres de venta seguidas ni dos promociones seguidas ("seguidas"
  = a menos de 3 días, sin otra en medio). Si el primer hueco rompe la mezcla toma el siguiente
  que la cumpla y lo dice en la propuesta; si en dos meses ninguno cumple, el primero libre. Lo
  hecho a mano cuenta como neutro. (`CalendarioDelAgente.siguienteHueco` con categoría)

Todavía no: carruseles, lo que aprende de la marca
y la aprobación automática por confianza. El código está en `service/agente/`, `AgenteController`,
`service/campaign/LogoSobreFoto`, `service/media/HuellaDeImagen` y `service/media/RetoqueDeFoto`;
en la web,
`pages/panel/pages/agente/`. El resto de este documento sigue siendo el plan.

## El objetivo

Que la IA haga el trabajo del community manager y la persona solo lo autorice.

Se sube el material de un cliente —muchas fotos y videos, sin ordenar— y Pícale devuelve
publicaciones listas: revisadas contra la marca, con formato, logo, diseño, texto de cada red,
redes y fecha ya decididos. El community manager, que lleva muchas cuentas, solo dice **sí o no**.

El flujo tiene que ser **lo más autónomo posible**: nadie debería tener que tocar un botón para
que el agente empiece, siga o se recupere de algo. Lo único que se le pide a la persona es la
aprobación.

## Arquitectura: los agentes

El agente no es una sola pieza: son roles separados, para que cada uno se pueda cambiar o
crecer sin tocar los demás (por ejemplo, el editor de video que saque las mejores tomas y
agregue audio).

| Rol | Qué hace | Código |
|---|---|---|
| **Coordinador** (el community manager) | Toma lo nuevo, llama a los demás en orden, arma la propuesta, maneja la bandeja | `agente/AgenteService`, `AgenteWorker` |
| **Analista** | Mira y escucha. Solo observa | Fotos: `agente/RevisorDeMarca`. Video: `agente/video/AnalistaDeVideo` (6 cuadros + transcripción) |
| **Decisor** | Reglas fijas que vuelven el análisis una decisión explicable | Fotos: `agente/DecisorDelAgente`. Video: `agente/video/DecisorDeVideo` |
| **Productores** | Hacen el trabajo, siempre sobre una copia | Fotos: `media/RetoqueDeFoto`, `campaign/LogoSobreFoto`, diseño con IA. Video: `agente/video/EditorDeVideo` (interfaz; hoy `SinEditor`) |
| **Calendario** | Cuándo sale: horario, topes, ritmo de créditos, mezcla | `agente/CalendarioDelAgente`, `agente/RitmoDeCreditos` |

El análisis de cada video se guarda en el archivo (`MediaAsset.agenteAnalisis`): sus tomas con
segundo y nota, el mejor tramo y la mejor portada. El editor que venga lo reutiliza sin volver
a pagar por mirarlo y escucharlo. Para conectarlo: implementar `EditorDeVideo` (y marcarlo
`@Primary` o quitar `SinEditor`); el decisor ya pide `RECORTAR` cuando hay quien recorte.

## Principios

1. **La IA decide todo; la persona aprueba.** Formato, logo, diseño, texto, redes, fecha y hora.
2. **Nada sale sin aprobar.** Aprobar es la única puerta a las redes. Una propuesta sin aprobar
   nunca se publica, aunque llegue su fecha.
3. **Solo aprueba el community manager.** No hay doble aprobación ni el cliente entra a revisar.
4. **El agente nunca se queda parado.** Si no hay créditos, si una propuesta vence o si se
   rechaza, el agente replantea solo. Siempre explica qué hizo.
5. **Nunca inventa el producto.** El diseño con IA parte de la foto real y el logo se pega
   idéntico (no lo redibuja la IA).
6. **Nada se borra.** Lo descartado se guarda con su motivo y se puede rescatar. Los originales
   no se tocan: los arreglos se guardan aparte.

## El switch

Un switch **"Agente"** por cuenta (por espacio de trabajo).

- **Encendido:** todo lo que se suba a Contenido desde ese momento lo procesa el agente, sin que
  nadie se lo pida. No se procesa lo que ya estaba antes de encenderlo, para no gastar de golpe
  el historial de la cuenta.
- **Apagado:** Pícale funciona como hoy. Las propuestas pendientes se quedan en la bandeja.
- Si la marca está incompleta (ver [Filtro de marca](#1-filtro-de-marca)), el switch se puede
  encender, pero avisa: *"Completa tu marca para que el agente decida mejor"*.

## El flujo

```
Se sube material
   │
   ▼
1. Filtro de marca ──► Observación (no sé si va) ──► bandeja, sin gastar nada
   │               └─► Descartada (no va)       ──► "Descartadas por la IA", rescatable
   ▼ va
2. Reglas de cuidado ──► Observación (derechos, privacidad, regulado, calidad)
   │
   ▼
3. Decisiones de la IA: formato, logo, tal cual o diseño, texto de cada red, redes
   │
   ▼
4. Calendario: fecha y hora según volumen, topes y horario del negocio
   │
   ▼
5. Bandeja "Por aprobar" ──► Aprobar ──► programada en su fecha
                          ├─► Cambiar  ──► el agente rehace con el cambio pedido
                          └─► Descartar ──► el agente aprende y replanea la semana
```

### 1. Filtro de marca

La sección de **Marca** es el criterio del agente. Hoy guarda: giro, ciudad, descripción,
objetivo, qué vende, a quién le habla, tono, qué evitar y datos de contacto. Con eso la IA juzga
cada archivo **antes de gastar un crédito**:

| Resultado | Cuándo | Qué pasa |
|---|---|---|
| **Va** | Encaja con el giro y con lo que vende | Sigue el flujo |
| **Observación** | No está claro: una foto personal, un meme, otro tema, marcas ajenas | A la bandeja sin procesar, con la duda dicha: *"No sé si va con tu marca: parece una foto personal"* |
| **Descartada** | Choca con la marca o con "qué evitar": otro giro, contenido de la competencia | No se propone. Queda en "Descartadas por la IA" con su motivo |

Con la marca por debajo de cierto porcentaje de avance, lo dudoso va a **Observación** y nunca a
Descartada: sin marca no hay contra qué comparar.

### 2. Reglas de cuidado

Van a Observación sin preguntar, aunque encajen con la marca:

- **Derechos de autor:** marcas de agua de bancos de imágenes, fotos que parecen de internet.
- **Privacidad:** menores identificables, teléfonos o chats en capturas, placas, domicilios
  particulares.
- **Contenido regulado** por las políticas de Meta y TikTok: alcohol, medicamentos, "antes y
  después" de salud o estética, promesas de rendimiento en inversiones o inmuebles.
- **Calidad mínima:** muy pequeña, muy borrosa, texto ilegible.
- **Promociones vencidas:** si la foto o el texto dicen "solo este fin de semana" o "hasta el
  15" y esa fecha ya pasó.

### 3. Decisiones de la IA

| Decisión | Regla |
|---|---|
| **Repetidas** | De varias casi iguales se queda con la mejor; las demás, a Descartadas por repetidas |
| **Formato** | Publicación, carrusel, reel o historia. El detrás de cámaras va a historia, no al feed |
| **Tal cual o diseño** | Tal cual (ajustada a la medida de cada red) si la foto está bien. Diseño con IA si la idea es una promoción o la foto no da para más, y hay créditos |
| **Logo** | Lo decide la IA: sí en producto, promociones y piezas compartibles; no en equipo, local, eventos, testimonios ni en fotos que ya traen logo o mucho texto. Va en el rincón más limpio |
| **Texto** | El texto (speech) de cada red, con la voz de la marca, sin repetir frases de publicaciones anteriores |
| **Redes** | Todas las conectadas que acepten el formato. LinkedIn solo contenido profesional |
| **Mezcla** | Equilibrio de la semana: por ejemplo 70% valor o día a día, 20% comunidad, 10% promoción, y no más de 2 promociones seguidas |
| **Catálogo** | Que todos los productos tengan presencia y no salga siempre el mismo |

Cada propuesta lleva **qué hizo y por qué**: *"Le puse tu logo abajo a la derecha: es foto de
producto"*, *"Sin diseño: no quedan créditos este mes"*, *"La mandé a historia: es detrás de
cámaras"*.

### 4. Calendario

La frecuencia la decide el agente según lo que se suba, dentro de los topes que ya existen:

- Publicaciones por día de la cuenta (`limits.posts.max_per_day`), cupo diario de cada red
  (`quota.daily.*`) y máximo de pendientes (`limits.posts.max_pending`).
- **Horario del negocio:** días y horas en que no se publica. Se configura en Marca.
- **Espacio entre publicaciones:** un mínimo de horas en la misma red.
- **Banco de contenido:** si se suben 40 fotos, no se programan todas. Se guarda reserva para las
  semanas sin material, y el agente avisa cuánto queda: *"Te queda contenido para 9 días"*.
- **Horas:** al principio, una hora fija por red. Después, aprendidas de cómo le va a cada cuenta.

### 5. La bandeja "Por aprobar"

Una sola bandeja con **todas las cuentas** que administra el community manager, agrupada por
cliente:

> **Por aprobar · 23**
> Vivento Realty (8) · Tacos El Güero (11) · CMRG (4)

Cada propuesta muestra antes y después, el motivo de cada decisión, el texto de cada red, las
redes y la fecha. Tres acciones:

- **Aprobar:** queda programada en su fecha. También **aprobar todas las de una cuenta** de una
  vez, y atajos de teclado (A aprobar, D descartar, E cambiar).
- **Cambiar:** se escribe el cambio (*"más grande el producto, sin precio"*) y el agente rehace la
  propuesta. Es el "¿Le cambiamos algo?" que ya existe en Crear contenido con IA.
- **Descartar:** sale de la bandeja y el agente replanea la semana con lo que queda.

Lo que está en Observación aparece en la misma bandeja con su duda y dos botones: **sí va** (el
agente lo procesa) o **no va**.

## Lo que lo hace autónomo

- **Arranca solo:** con el switch encendido, cada subida dispara el agente.
- **Propuestas que vencen:** si nadie aprueba antes de su hora, la propuesta **no sale**. El agente
  la mueve a la siguiente fecha libre y avisa.
- **Sin créditos no se detiene:** la propuesta sale sin diseño, con la foto tal cual, y lo dice.
- **Replanea solo:** al descartar, al cambiar o cuando llega material nuevo, reacomoda la semana.
- **Pide material:** cuando la reserva baja, arma la lista de lo que necesita (*"2 videos de
  producto y 1 foto del equipo"*) para que el community manager se la mande al cliente.
- **Aprende:** cada decisión de la persona mejora la siguiente (ver abajo).
- **Aprobación automática por confianza (opcional):** después de N propuestas de un mismo tipo
  aprobadas sin cambios en una cuenta (por ejemplo, 20 fotos de producto), el agente ofrece
  aprobar solas las de ese tipo. Lo enciende el community manager, por cuenta y por tipo, y se
  apaga cuando quiera. Es lo más autónomo que se puede llegar sin romper el principio 2: aprobar
  sigue siendo una decisión de la persona, solo que tomada una vez y no cada vez.

## Aprendizaje: la memoria de la marca

Cada decisión de la persona alimenta a la marca:

- Rescatar una descartada (*"sí, también hacemos eventos"*) suma ese tema a lo que la marca
  publica.
- Descartar una aprobada por la IA lo suma a lo que la marca evita.
- Pedir el mismo cambio varias veces (*"sin precios"*) lo vuelve regla de la cuenta.

Todo lo aprendido se ve y se edita en Marca, en **"Lo que aprendí de tu marca"**. Si la IA
aprende algo mal, se corrige ahí.

## Créditos

**1 crédito = $1 MXN** y una generación con todas sus versiones (una imagen por proporción)
gasta **5**. La licencia trae **30 al mes** (6 generaciones); los paquetes de 39, 79 y 149 no
vencen (ver [cobros.md](cobros.md)). El agente cuenta en generaciones:
`CreditService.disponibles` ya divide el saldo entre lo que cuesta una.

Con 6 al mes el diseño con IA es escaso, así que el agente lo raciona:

| Qué hace el agente | Créditos | Costo para nosotros |
|---|---|---|
| Revisar cada archivo (marca, cuidado, calidad) | 0 | Una llamada de visión; tope diario `limits.ai.max_calls_per_day` |
| Tal cual: ajustar a la medida de cada red, logo | 0 | Proceso de ffmpeg en el servidor |
| Texto de cada red | 0 | Una llamada de texto |
| Diseño con IA | 5 por generación ($5 MXN) | La generación de imagen; tope diario `limits.ai.max_images_per_day` |

Reglas de gasto:

- El diseño va primero a lo que más rinde: promociones y productos estrella.
- No gasta todo en la primera semana: reserva una parte para lo que llegue a fin de mes.
- Antes de diseñar con créditos de paquete (los que el cliente pagó aparte), el agente dice cuánto
  va a gastar en pesos: *"Este lote usa 4 créditos de paquete ($20)"*.
- Aviso al community manager al llegar al 80% de los créditos del mes.
- Tope opcional por cuenta, para que el community manager limite lo que el agente gasta en cada
  cliente.

## Operación y confianza

- **Pausa de emergencia:** un botón por cuenta que congela todo lo que el agente programó. Para
  crisis de marca, lutos o algo grave en la ciudad, donde publicar una promoción queda mal.
- **Bitácora:** qué decidió el agente con cada archivo y por qué. Para explicarle al cliente y para
  corregir al agente.
- **Reporte semanal por cliente:** *"Esta semana: 6 publicadas, 3 descartadas por no ir con la
  marca, contenido para 5 días, necesito 2 videos."* El community manager se lo reenvía al
  cliente si quiere.

## Lo que ya existe

| Pieza | Dónde | Para qué le sirve al agente |
|---|---|---|
| Describir cada foto, una vez en su vida | `service/ai/VisorDeMedios.java`, `media_assets.descripcion_ia` | Base del diagnóstico; hay que ampliarlo para que devuelva tipo, calidad y encaje |
| Perfil de marca y su porcentaje de avance | `entity/BrandProfile.java`, `service/ai/MarcaDelNegocio.java` | El criterio del filtro de marca |
| Diseño con IA, con el logo real y en el rincón limpio | `service/campaign/CampaignImageService.java`, `SelloDeLogo.java` | El nivel "diseño" |
| Ajustar fotos a la medida de cada red | `service/media/AdaptadorDeImagenes.java` | El nivel "tal cual" |
| Portada de videos en TikTok e Instagram | `service/media/PortadaDeVideo.java`, `social/UploadPostClient.java` | Videos sin cuadro negro |
| Texto por red sin repetir captions anteriores | `CampaignImageService.captionsAnteriores()` | El texto de cada red |
| Qué archivos usa cada publicación | `PostRepository.mediosEnUso()`, tabla `post_media` | Saber qué es nuevo |
| Borradores, programar, cancelar y eliminar | `PostService`, `POST /posts/{id}/cancel` y `/delete` | Las propuestas son borradores; aprobar es programarlas |
| Cola de publicación en segundo plano | `service/publishing/PublishQueueService.java` | El modelo para el proceso del agente |
| Créditos y topes configurables | `billing/CreditService.java`, `limits/LimitesConfigurables.java` | El presupuesto del agente |

## Lo que hay que construir

**Datos**
- En el espacio de trabajo: `agente_activo` y la fecha de encendido; horario del negocio; pausa
  de emergencia.
- En cada archivo: de dónde salió (subido o generado por IA, para no hacer campañas de
  campañas), en qué etapa va (nuevo, revisado, observación, descartado, propuesto, usado), el
  diagnóstico y los motivos.
- Hoy, cuando una foto entra a una campaña con IA, la publicación guarda la imagen generada y no
  la original, así que la original parece nueva. Hay que guardar de qué fotos salió cada
  campaña.
- En cada publicación: si la propuso el agente, la fecha propuesta, el motivo de cada decisión y
  su tipo (para la mezcla y la aprobación por confianza).
- La memoria de la marca: lo aprendido, editable.

**Servidor**
- El proceso del agente, en segundo plano como la cola de publicación: por cada cuenta encendida
  toma lo nuevo y lo lleva por el flujo.
- El diagnóstico ampliado: una llamada de visión que devuelva encaje con la marca, tipo de
  contenido, calidad, reglas de cuidado, si trae logo o texto, y si se repite con otra.
- El planificador del calendario con los topes, el horario y el banco de contenido.
- Rutas para la bandeja: listar propuestas de todas las cuentas del community manager, aprobar,
  aprobar todas las de una cuenta, cambiar, descartar, y sí va o no va en Observación.

**Web** (el community manager trabaja en computadora)
- El switch en cada cuenta y la configuración del horario del negocio.
- La bandeja "Por aprobar" con todas las cuentas.
- "Descartadas por la IA" y "Lo que aprendí de tu marca".

**App:** después, para aprobar desde el teléfono.

## Fases

| Primera versión: poder encenderlo y confiar | Después: que sea un buen community manager |
|---|---|
| Switch y marca de cada archivo | Aprobación automática por confianza |
| Filtro de marca (va, observación, descartada) | Respuestas a comentarios |
| Reglas de cuidado y promociones vencidas | Fechas importantes por giro (10 de mayo, Buen Fin…) |
| Formato, logo, tal cual o diseño, texto, todas las redes | Agrupar fotos parecidas en carruseles |
| Calendario con topes, horario y espacio entre publicaciones | Videos con marca de agua y recorte |
| Bandeja multi-cuenta: aprobar, cambiar, descartar, aprobar todas | Pedir material y reporte semanal |
| Propuestas que vencen y replanear solo | Primer comentario con hashtags (`first_comment`) |
| Bitácora y pausa de emergencia | Horas de la audiencia de TikTok (`/uploadposts/audience`) |
| Métricas, horas y hashtags aprendidos por cuenta; ubicación del negocio | |

## Preguntas abiertas

1. **Aprobación automática por confianza:** ¿entra en la primera versión o después? Es lo que más
   autonomía da.
2. **¿Cuántos créditos reserva el agente para fin de mes?**
3. **¿A partir de qué porcentaje de marca el agente puede descartar solo?**
4. **¿Cuánto material guarda en reserva** antes de dejar de programar? Por ejemplo, programar dos
   semanas y guardar el resto.
5. **La mezcla de contenido** (70 / 20 / 10): ¿fija para todos o se ajusta según el giro?

## Lo que funciona (métricas, horas, hashtags y ubicación)

- **Métricas.** `MetricasWorker` (cada 3 h, su propio hilo) lee de upload-post
  `GET /uploadposts/post-analytics?platform_post_id=&platform=&user=` lo publicado en los
  últimos 14 días: cada 12 h las primeras 48 h y luego cada 2 días. Como mucho 60 por vuelta con
  2.5 s entre cada una (el proveedor permite 100 cada 5 min) y se corta si contesta 429. Se guarda
  en `post_targets`: vistas, alcance, me gusta, comentarios, compartidos y guardados
  (`LecturaDeMetricas` acepta los nombres de cada red; TikTok llama `favorites` a los guardados).
  En el perfil `dev` va apagado (`METRICAS_ENABLED=true` para probarlo en local).
- **Puntaje.** `me gusta + 2·comentarios + 3·compartidos + 3·guardados + vistas/50`, dividido
  entre la mediana de la cuenta: cada cuenta se compara contra sí misma.
- **Aprender** (`AprendizajeDeRendimiento`, sin estado). Con 8 o más publicaciones medidas en 90
  días:
  - Horas: el promedio de cada hora suavizado con sus vecinas; buena si pasa 1.05.
  - Hashtags con 3 usos o más: buenos si su mediana baja pasa 1.25, flojos si su mediana alta no
    llega a 0.7 (uno ambiguo queda neutral).
- **Usarlo.** El calendario toma las dos mejores horas que caben en el horario del negocio,
  separadas 3 h (si solo cabe una, la acompaña una de las de siempre), y la propuesta lo dice. Quien
  escribe recibe "hashtags que le han funcionado / evítalos" en el contexto del negocio (agente y
  "crear con IA").
- **Ubicación.** No aplica a todos los giros, así que va en Marca, apagada, con la pregunta
  "¿tus clientes van a un local?" (`Workspace.ubicacionActiva`):
  - Instagram: `location_id`, sacado del enlace de la ubicación que se pega desde la app.
  - TikTok: `tiktok_location_id` + `tiktok_location_name`, elegido con `GET /uploadposts/tiktok/locations?q=`.
  - Facebook no tiene campo.
  - Cada publicación decide (`Post.conUbicacion`, nulo = sí). El agente la pone solo cuando lo que
    se ve es del negocio (`UbicacionEnLaPublicacion`: lugar, producto, equipo, evento, promoción,
    demostración, detrás de cámaras). No la pone en recorridos (en una inmobiliaria son la
    propiedad), testimonios ni en lo que no reconoce, y lo dice en el porqué.
  - En Crear publicación hay un interruptor para quitarla o ponerla.
  - Pendiente: la ubicación propia de cada publicación (la casa que se vende, el lugar del evento).

## Organizar lo que se sube (carrusel, historia o post)

Como lo hace un community manager: primero ve todo lo que llegó y después decide cómo sale.

1. **Analizar.** Cada foto pasa por el revisor de marca, que además dice su orientación
   (vertical, cuadrada, horizontal), si es del momento (`efimero`) y de qué trata (`tema`). Si va,
   queda en `ANALIZADA` con ese análisis guardado (`MediaAsset.agenteAnalisis`). Los videos siguen su
   camino de siempre.
2. **Juntar la tanda.** Las fotos subidas sin una pausa de más de 15 min entre una y otra son una
   tanda. Se organiza cuando lleva `app.agente.espera-minutos` (10) sin subidas nuevas, para no
   partir una sesión a la mitad.
3. **Proponer.** Con 2 fotos o más, una llamada de texto (`AGENTE_ORGANIZAR`, sin volver a ver las
   fotos) propone los grupos: carrusel para lo que cuenta una misma historia (mismo lugar, producto o
   evento, antes y después, paso a paso), historia para lo vertical y del momento, post para la foto
   que se sostiene sola.
4. **Reglas que mandan** (`OrganizadorDeContenido.normalizar`):
   - Cada foto en una sola publicación; la que nadie acomodó sale sola.
   - Carrusel de 2 a `app.post.max-photos` (6); si pasa, se parte, y la que queda suelta es post.
   - Historia de una foto, solo vertical y solo si hay cuentas con historias; si no, post.
   - Sin respuesta de la IA, cada foto con la regla de una sola.
5. **Producir.** El carrusel usa la decisión de cada foto (retoque si lo pidió, fuera las que solo
   observan, logo solo en la portada), un solo texto y un solo hueco del feed. La historia va en su
   propio calendario: 10, 13, 17 y 20 h, hasta 3 al día, sin quitarle huecos al feed.

**Repetidos y ráfagas** (`HuellaDeImagen`, sin IA):
- La misma toma (6 bits o menos de diferencia en la huella) se descarta antes de revisarla,
  contra todo lo ya trabajado. Foto con foto y video con video.
- Un video se compara por el cuadro de su mitad (la entrada suele ser la misma en todos), antes
  de pagar por analizarlo.
- HEIC y WebP, que Java no lee, pasan por ffmpeg.
- Ráfaga: dentro de una tanda, las tomas de hasta 12 bits de diferencia son de lo mismo. Sale la
  mejor (calidad + fuerza que dio la IA; si empatan, la más nítida por la varianza del
  laplaciano) y las demás van a Descartadas, de donde se rescatan. No entran las piezas
  diseñadas (dos flyers con la misma plantilla son dos mensajes) ni las que la persona dijo
  que van.

**Contenido hecho con IA** (regla nuestra, igual para todas las cuentas; el usuario no configura nada):
- El revisor califica aparte del veredicto: `pareceIa`, `personaRealista`, `causaSocial`,
  `lugarDelNegocio` (`RevisorDeMarca.Autenticidad`).
- Lo que parece hecho con IA y puede pasar por real se pregunta antes, con el porqué: una persona
  realista, una causa social o un lugar presentado como el negocio. Un diseño o ilustración con IA
  va normal.
- Al publicar (`PostPublishStore.hechaConIa`), lo que lleva una imagen creada con IA
  (`MediaAsset.creadaConIa`, solo diseños de Crear con IA o del agente; no las copias retocadas o
  con logo de una foto real) o una foto que el revisor vio hecha con IA, sale con
  `is_ai_generated=true`: Instagram "Información de IA", TikTok `is_aigc`, YouTube contenido
  sintético, Reels de Facebook. LinkedIn y las fotos de Facebook no tienen campo.

**Avisos al teléfono** (`AvisosPush`, FCM HTTP v1; `AgenteService.avisar` al final de cada vuelta):
- La app registra el teléfono (`POST /api/v1/dispositivos`) solo si la persona dio permiso; lo pide
  con su porqué desde Hoy ("¿Te aviso cuando tenga algo listo?"). Al cerrar sesión lo quita.
- Reciben quienes pueden aprobar en ese espacio (programar), incluidos los administradores de la
  organización. El título es el nombre del espacio.
- Lo nuevo: un solo aviso con todo lo que no se ha avisado ("Tu asistente te preparó 3
  publicaciones"), como mucho uno por hora y de 8 a 21 h. Lo de la noche sale junto en la mañana.
- Lo que vence (menos de 3 h para su hora o para retirarse): un aviso por propuesta, una sola vez,
  de 7 a 22 h.
- Sin `app.push.credenciales` (ruta al JSON de la cuenta de servicio de Firebase) no se manda
  nada. Un token que FCM ya no reconoce se borra solo.

**Fila corta.** Hasta `app.agente.tope-fila` (6) propuestas esperan el sí a la vez. Con la fila
llena, las fotos se revisan igual pero se quedan en reserva (ANALIZADA) y los videos esperan sin
tocarse; entran conforme se aprueba, descarta o retira algo. `Estado.enReserva` lo cuenta, y la app
dice "Tu asistente tiene N fotos guardadas para después". Lo pedido a mano (Sí va, Revisar ahora,
Cambiar) no cuenta contra el tope.

**Urgencia.** `PostResponse.agenteCaducaEn` (calculada al terminar la vuelta en que se crea) deja a la
app y la web avisar: "Apruébala antes de las 4:00 pm; si no, la retiro…" cuando le queda menos de
un día, y "Sale hoy: necesita tu sí antes de esa hora".

**Si no llega el sí a tiempo** (`reacomodarVencidas`, en cada vuelta con el agente encendido):
- Nada sale sin aprobar. A 30 min de su hora, una propuesta sin sí se mueve al siguiente hueco
  libre (horario, topes, mezcla; las historias en su calendario). Solo esa: las demás conservan
  su fecha y lo aprobado no se toca. Queda marcada (`Post.agenteMovidaVeces`) y la app y la web
  dicen "Le cambié la hora porque no llegó tu sí a tiempo".
- Caducidad (`Post.agenteCaducaEn`, calculada en la primera vuelta): lo del momento (historia, o
  foto/video que el revisor marcó `efimero`) dura 24 h, o hasta 3 h después de su primera fecha
  si cae más tarde; lo demás, 14 días, o 3 días después de su primera fecha si el calendario la
  dejó más lejos. Pasada, o si el siguiente hueco cae después, no se
  publica: se retira y sus fotos vuelven a Observación con "¿Todavía va?". Con "sí" se prepara
  de nuevo con una fecha que tenga sentido.
- Con el agente en pausa no se mueve nada. Aprobar tarde programa en el siguiente hueco, salvo
  lo del momento ya caducado: se retira y se dice.
- Al retirarse una, lo que venía después se adelanta (`replanear`).

**Lo rescatado enseña.** Rescatar algo de Observación o Descartadas lo marca
(`MediaAsset.agenteRescatada`). Las descripciones de las últimas 8 rescatadas van al revisor
como "lo que el dueño ya te dijo que sí va", para que no vuelva a dudar de esos temas. El
revisor también acepta los temas del campo del negocio aunque no sean su servicio principal
(un despacho fiscal que habla de gastos médicos deducibles).

En la propuesta se ve la etiqueta (Carrusel · N fotos, Historia, Reel o Post) y las miniaturas en
orden. "Sepáralas" en un carrusel lo vuelve a organizar con esa instrucción, sin volver a revisar
las fotos.
