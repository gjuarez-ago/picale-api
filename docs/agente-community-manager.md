# Agente: la IA como community manager

Estado: **primera parte construida, sin desplegar.** Ya funciona: el switch por cuenta, el filtro
de marca (va, observación, descartada), el texto de cada red, todas las redes conectadas que
acepten fotos, la fecha propuesta, y la pantalla **Agente** de la web con aprobar, aprobar todas,
cambiar, descartar, sí va / no va y rescatar. Solo fotos, tal cual: sin diseño con IA, sin logo y
sin videos todavía. El código está en `service/agente/` y `AgenteController`; en la web,
`pages/panel/pages/agente/`. El resto de este documento sigue siendo el plan.

## El objetivo

Que la IA haga el trabajo del community manager y la persona solo lo autorice.

Se sube el material de un cliente —muchas fotos y videos, sin ordenar— y Pícale devuelve
publicaciones listas: revisadas contra la marca, con formato, logo, diseño, texto de cada red,
redes y fecha ya decididos. El community manager, que lleva muchas cuentas, solo dice **sí o no**.

El flujo tiene que ser **lo más autónomo posible**: nadie debería tener que tocar un botón para
que el agente empiece, siga o se recupere de algo. Lo único que se le pide a la persona es la
aprobación.

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

Hoy **1 crédito = 1 generación con todas sus versiones** (una imagen por proporción), y la
licencia trae **5 al mes**; los paquetes de 10, 25 y 50 no vencen (ver [cobros.md](cobros.md)).
Cada crédito vale **$5 MXN**: los 5 del mes son $25 de diseño incluidos en la licencia, y un
diseño extra le cuesta $5 al cliente.

Con 5 al mes el diseño con IA es escaso, así que el agente lo raciona:

| Qué hace el agente | Créditos | Costo para nosotros |
|---|---|---|
| Revisar cada archivo (marca, cuidado, calidad) | 0 | Una llamada de visión; tope diario `limits.ai.max_calls_per_day` |
| Tal cual: ajustar a la medida de cada red, logo | 0 | Proceso de ffmpeg en el servidor |
| Texto de cada red | 0 | Una llamada de texto |
| Diseño con IA | 1 por generación ($5 MXN) | La generación de imagen; tope diario `limits.ai.max_images_per_day` |

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
| Filtro de marca (va, observación, descartada) | Horas aprendidas por cuenta |
| Reglas de cuidado y promociones vencidas | Fechas importantes por giro (10 de mayo, Buen Fin…) |
| Formato, logo, tal cual o diseño, texto, todas las redes | Agrupar fotos parecidas en carruseles |
| Calendario con topes, horario y espacio entre publicaciones | Videos con marca de agua y recorte |
| Bandeja multi-cuenta: aprobar, cambiar, descartar, aprobar todas | Pedir material y reporte semanal |
| Propuestas que vencen y replanear solo | Hashtags y ubicación por red |
| Bitácora y pausa de emergencia | Métricas y respuestas a comentarios (si Upload-Post lo permite) |

## Preguntas abiertas

1. **Aprobación automática por confianza:** ¿entra en la primera versión o después? Es lo que más
   autonomía da.
2. **¿Cuántos créditos reserva el agente para fin de mes?**
3. **¿A partir de qué porcentaje de marca el agente puede descartar solo?**
4. **¿Cuánto material guarda en reserva** antes de dejar de programar? Por ejemplo, programar dos
   semanas y guardar el resto.
5. **La mezcla de contenido** (70 / 20 / 10): ¿fija para todos o se ajusta según el giro?
