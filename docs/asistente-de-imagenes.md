# Crear imágenes conversando, no llenando formularios

Plan para cambiar la creación de imágenes en la app móvil: en vez de tres
pasos de formulario, una conversación que entiende el negocio, va juntando el
contexto que le falta y, cuando ya sabe lo suficiente, crea. Después se elige
la pieza que gustó y se sigue trabajando sobre ella.

## Qué cambia

**Hoy** ([`campaign_prototype_page.dart`](../../metricol_app/lib/pages/campaign/campaign_prototype_page.dart),
2 346 líneas): elegir formato → elegir redes y fotos → escribir la idea →
crear. Un disparo. Si no gustó, se piden "cambios" que viajan pegados a la
idea en la siguiente vuelta.

**Lo que se quiere:** un chat. La persona dice lo que quiere con sus palabras,
el asistente pregunta lo que de verdad falta, y cuando entre los dos juntaron
lo suficiente, se crea. Lo que salió se puede elegir, afinar y usar.

**El formulario se retira.** No queda como modo avanzado: una sola forma de
crear, que es lo que hace que valga la pena.

## La pieza que lo sostiene todo: la ficha

Esto es lo que evita que el chat sea un juguete. El generador que ya existe
**no entiende conversaciones**: necesita datos concretos (qué formato, qué
redes, qué fotos, qué idea, qué objetivo). Así que la conversación no
reemplaza esos datos — **los llena**.

La **ficha** es ese puñado de datos, visible en pantalla todo el tiempo,
llenándose solo mientras se habla:

| Campo | De dónde sale | ¿Obligatorio? |
|---|---|---|
| Qué se anuncia | de lo que dice la persona | **sí** |
| Formato (publicación / historia / carrusel) | lo dice o lo deduce el asistente | **sí** |
| Redes | del formato y de las que tiene conectadas | sí (se puede deducir) |
| Fotos propias | las que adjunte, o ninguna | no |
| Qué quiere que pase | lo pregunta si no se dijo | no |
| Texto que va en la imagen | de lo que dice la persona | no |
| Tono, colores, qué evitar | **ya está en el perfil de marca** | — |

Dos cosas que esto resuelve de golpe:

- **La persona ve lo que el asistente entendió** y lo puede corregir tocando,
  sin tener que explicarlo otra vez con palabras. Es la diferencia entre un
  chat que te entiende y uno con el que peleas.
- **No se crea nada hasta que los campos obligatorios están.** Crear a medias
  gasta 5 créditos para tirar el resultado, y es el error que enoja.

## Lo que ya existe y se reutiliza tal cual

Casi nada de esto hay que inventarlo:

| Pieza | Dónde | Qué aporta |
|---|---|---|
| **`MarcaDelNegocio`** | `service/ai/` | qué vende, a quién, tono, **qué evitar**, historia, valores, frases, pilares |
| **Colores de la marca** | se sacan del logo | la paleta sin que nadie la escriba |
| **`CampaignImageService`** | `service/campaign/` | el generador entero: director de arte, lienzo, recorte, logo encima |
| **Varias versiones por tanda** | `Diseno.versiones` | **ya genera varias piezas del mismo plan**: es justo lo que se va a elegir |
| **`ContenidoJobs`** | `service/campaign/` | trabajo en segundo plano con sondeo (crear tarda más de lo que aguanta una petición) |
| **Créditos** | `billing.credits.per_generation` | 5 por generación, devueltos si no sale nada |
| **Dictado por voz** | ya está en la pantalla actual | contar la idea hablando, que es como la gente lo hace |

**Lo único nuevo de verdad** es el hilo de conversación y quien lo lleva.

## Cómo se debería sentir

> **—** Quiero algo para el 2x1 de tacos del viernes
>
> **—** Va. ¿Tienes foto de los tacos o la creamos?
> *(la ficha ya marcó: anuncio = 2x1 de tacos, día = viernes)*
>
> **—** *(manda dos fotos)*
>
> **—** Se ven bien. ¿Va para Instagram y Facebook como siempre?
>
> **—** Sí, y que diga 2x1 bien grande
>
> **—** Listo, lo tengo. Lo creo. *(se cobran los créditos aquí, y solo aquí)*
>
> **—** *(salen tres piezas)*
>
> **—** La segunda. Pero acerca más los tacos.
>
> **—** *(afina sobre esa, no empieza de cero)*

Lo que hace que eso funcione y no sea un interrogatorio:

- **Una pregunta a la vez.** Tres preguntas juntas es un formulario con otra
  cara, y es exactamente de lo que se está huyendo.
- **Solo pregunta lo que no puede deducir.** Las redes conectadas ya se saben.
  El tono ya está en la marca. La ciudad también. Preguntar lo que ya está en
  el sistema hace sentir que nadie te conoce.
- **Pregunta hasta tenerlo claro, no hasta un tope.** Si falta algo que de
  verdad cambia la imagen, lo pregunta, aunque sean cuatro turnos. Crear a
  medias gasta 5 créditos y entrega algo que no era.

**El riesgo de esto, dicho claro:** preguntar hasta tenerlo todo claro es
justo lo que puede convertir el chat en el formulario que estamos quitando. Lo
que evita que pase no es poner un tope, es que **cada pregunta cueste un
toque**: nunca una pregunta abierta, siempre dos o tres respuestas que se
tocan, más "otra cosa" para escribir o dictar. Preguntar cinco veces no cansa
si responder son cinco toques; preguntar dos veces cansa si hay que escribir
un párrafo.

Y el asistente dice en qué va: *"solo me falta saber si es para hoy o para el
viernes"*. Saber cuánto queda es la diferencia entre contestar y abandonar.

## Las reglas que no se rompen

**Que no se salga del negocio** tiene que ser código, no buenos deseos:

1. **`evitar` manda.** El campo del perfil de marca que hoy casi no se usa
   pasa a ser una regla dura del prompt. Si dice "sin personas", no salen
   personas aunque se pidan.
2. **No inventa datos.** Ni precios, ni horarios, ni direcciones, ni promesas
   que nadie dio. Es la misma regla que ya tiene el redactor de textos.
3. **Solo habla de crear imágenes.** Si le preguntan otra cosa, lo dice y
   vuelve. Un asistente que opina de todo deja de ser una herramienta.
4. **Los créditos se cobran al crear la imagen, nunca por conversar.**
   Conversar es texto y cuesta centavos: toda la redacción de un mes costó
   **$0.12 USD**. Cobrar por mensaje haría que la gente deje de hablar — y
   entonces no hay contexto, que era el punto.
5. **Nada se publica solo.** El asistente crea y propone; publicar sigue
   siendo un acto de la persona.

## El filtro: que no salga nada que te meta en problemas

**No se inventa vocabulario nuevo.** El agente ya clasifica contenido con tres
veredictos y una lista de preocupaciones muy pensada
(`RevisorDeMarca`, `AnalistaDeVideo`). El asistente de imágenes usa los
mismos, para que una cosa descartada aquí lo sea también allá:

- **VA** — encaja con el negocio. Se crea.
- **OBSERVACIÓN** — no decide la máquina, decide el dueño. Se avisa y se
  pregunta antes de gastar créditos.
- **DESCARTADA** — no se crea, y se dice por qué en una línea.

### Qué se revisa, y cuándo

El filtro actúa en **tres momentos**, porque un solo punto deja huecos:

**1. Antes de crear, sobre lo que se pidió.** Es el que importa: aquí todavía
no se gastó un crédito.

- **Personas y privacidad:** menores identificables, caras de gente que no dio
  permiso, teléfonos, placas de coche, domicilios.
- **Regulado:** alcohol, medicamentos, "antes y después" de salud, promesas de
  resultados, nada dirigido a menores.
- **De otros:** logos de marcas ajenas, personajes conocidos, famosos,
  contenido con derechos.
- **Engañoso:** descuentos o precios que nadie dio, "el mejor de la ciudad",
  premios inventados.
- **Lo que la marca pide evitar** (`evitar` del perfil). Esto es específico de
  cada negocio y manda sobre el gusto del modelo.

**2. Sobre las fotos que sube la persona.** Ya existe: `RevisorDeMarca` las
mira. Se conecta, no se reescribe.

**3. Después de crear, sobre lo que salió.** El modelo puede entregar algo que
no se pidió. Una revisión barata antes de enseñarla, con los mismos tres
veredictos.

### Las dos reglas que hacen que el filtro no estorbe

- **Ante la duda, preguntar — no descartar.** Es la regla que ya tiene el
  agente: *"si la marca dice poco de sí misma, nunca DESCARTADA"*. Un filtro
  que bloquea de más es un filtro que la gente aprende a esquivar, o que la
  hace irse.
- **Decir por qué, en palabras de la persona.** "No puedo usar esa foto porque
  se ve el rostro de un menor" — nunca `moderation_blocked`, que es justo lo
  que hoy devuelve OpenAI cuando rechaza (`OpenAiImageClient` ya lo detecta;
  falta traducirlo).

## Para quien no es técnico

Esto no es un apartado de estilo: es la mitad del producto. Las reglas, todas
comprobables mirando la pantalla:

- **Ninguna pregunta abierta.** Siempre dos o tres respuestas que se tocan,
  más "otra cosa" para escribir o dictar. Un cuadro de texto vacío delante de
  alguien que no sabe qué se espera es donde se abandona.
- **Palabras de la calle, no del oficio.** "Foto cuadrada para el muro", no
  "1:1 para feed". Nunca: prompt, modelo, render, variante, proporción,
  generar, procesar.
- **La voz es entrada de primera.** Dictar ya existe en la pantalla actual y
  es como de verdad cuenta las cosas la gente. Se queda.
- **Siempre se puede decir "tú decide".** Quien no sabe qué contestar tiene que
  poder seguir, no quedarse trabado. El asistente propone y lo dice.
- **Se ve lo que ya entendió.** La ficha en pantalla, en palabras simples, y se
  corrige tocando.
- **Deshacer siempre.** Cualquier cosa que se elija o se cambie se puede
  revertir sin empezar de nuevo.
- **Los errores dicen qué hacer.** No "falló la generación", sino "no se pudo
  crear; se te devolvieron los créditos, inténtalo otra vez".
- **Se avisa antes de cobrar.** "Voy a crear, son 5 créditos" antes de gastar,
  no después.

## Las fases

### Fase 1 — La ficha y el hilo (la base)

Tabla `hilos_de_imagen` y `mensajes_de_imagen`: la conversación guardada, con
la ficha como JSON que se va completando. Endpoints para abrir un hilo, mandar
un mensaje y leerlo.

**Listo cuando:** se puede conversar y ver la ficha llenarse, sin que todavía
se cree ninguna imagen.

### Fase 2 — Quien conversa, y el filtro

El prompt del asistente, con la marca dentro y las cinco reglas de arriba.
Decide tres cosas en cada turno: qué entendió (actualiza la ficha), si falta
algo imprescindible (pregunta UNA cosa) y si ya puede crear.

**Listo cuando:** de una frase suelta llega a la ficha completa preguntando
solo lo que falta y con respuestas que se tocan; con "sin personas" en el
perfil nunca propone personas; y pedir algo con un menor o con el logo de otra
marca se detiene **antes** de gastar un crédito, explicando por qué.

### Fase 3 — Crear desde la ficha

Conectar la ficha con `CampaignImageService`, que no cambia nada. Se cobran
los créditos aquí. Las piezas que salen quedan colgadas de ese turno.

**Listo cuando:** la conversación del ejemplo termina en imágenes reales.

### Fase 4 — Elegir la pieza y seguir

Lo que pediste: tocar la que gustó y seguir sobre ella. La elegida entra al
contexto del hilo, así que "acerca más los tacos" afina **esa**, no empieza de
cero. Desde ahí: usarla en una publicación, guardarla en Contenido o pedir
otra vuelta.

**Listo cuando:** se elige una, se pide un cambio y vuelve esa misma imagen
cambiada, no una distinta.

### Fase 5 — Retirar el formulario

Cambiar la pantalla de la app por el chat y borrar las 2 346 líneas del
prototipo. Se hace al final, cuando lo nuevo ya funciona.

## Riesgos, dichos de frente

- **El chat invita a pedir cosas que el generador no sabe hacer** ("quítale el
  fondo", "ponle a mi hermano"). Hay que contestar que no se puede y ofrecer
  lo que sí, en vez de intentarlo y entregar algo raro.
- **Conversar es más lento que un formulario** para quien ya sabe exactamente
  qué quiere. Lo compensa la regla de las dos preguntas: quien lo dice todo de
  una vez, crea de una vez.
- **Un hilo largo se vuelve caro y confuso.** Hay que acotarlo: los últimos N
  turnos más la ficha, no la conversación entera.
- **Que el chat se sienta un formulario disfrazado.** Es el riesgo más real, y
  crece ahora que pregunta hasta tenerlo claro. La prueba no es contar turnos
  —a veces hacen falta cuatro— sino contar **cuántas veces hubo que escribir
  en vez de tocar**. Si para llegar a la primera imagen hubo que teclear más
  de una vez, se hizo un formulario con burbujas.
- **Un filtro que bloquea de más.** Si descarta cosas razonables, la gente deja
  de usarlo o aprende a engañarlo. Por eso ante la duda pregunta en vez de
  descartar, y por eso hay que mirar qué se está descartando las primeras
  semanas.

## Por dónde empezaría

Fases 1 y 2 primero, sin generar ni una imagen. Ahí se ve enseguida si el
asistente de verdad entiende el negocio o si hace preguntas de relleno — y eso
se puede juzgar sin gastar un crédito. Si en esa fase la ficha se llena sola y
bien, el resto es conectar cosas que ya funcionan.
