# Reels automáticos con lo que ya se sube

Plan para que de las tandas de fotos y videos que sube la gente salgan reels
verticales que se vean profesionales, hechos en nuestra propia VM.

## Lo que ya tenemos (y es más de lo que parece)

Esto no se empieza de cero. Antes de proponer nada, lo que hay:

| Pieza | Dónde | Qué nos da |
|---|---|---|
| **ffmpeg 8.1.2** con `libx264` y `aac` | ya en la imagen (`Dockerfile`) | cortar, escalar, concatenar y codificar video |
| **Fuentes** (`fontconfig`, `ttf-dejavu`) | ya en la imagen | texto quemado en pantalla (`drawtext`) sin instalar nada |
| **La VM** | `api-videos-prod` | 4 vCPU, 16 GB RAM, **carga 0.04**: está ociosa |
| **`EditorDeVideo`** | `service/agente/video/` | **la interfaz ya está diseñada y sin implementar**: hoy solo existe `SinEditor`, que dice "no puedo" |
| **`AnalisisDeVideo`** | `service/agente/video/` | por cada video ya pagado: tomas con nota 1–5, el mejor tramo, el segundo de la portada y la transcripción |
| **`OrganizadorDeContenido`** | `service/agente/` | ya agrupa una tanda en carrusel / post / historia; falta un formato más |
| **`FfmpegImagen`** | `service/media/` | ya mide, encaja, saca cuadros y extrae audio |
| **`MiniaturasEnSegundoPlano`** | `service/media/` | el patrón de trabajo pesado en segundo plano, ya probado |
| **Carga masiva** | web, `utils/carga-masiva.ts` | 20 archivos por tanda, 5 videos |

**El hueco está dibujado y vacío.** `EditorDeVideo` existe precisamente para
esto: *"el agente que algún día saque las mejores tomas, las edite, recorte lo
que sobra y le agregue audio"*. Quien lo escribió dejó el enchufe puesto. El
plan es enchufarse ahí, no inventar una arquitectura nueva.

## La restricción que manda sobre todo lo demás: el audio

**No podemos ponerle música comercial a un reel.** Un reel se publica por API
con su audio dentro; no es como el carrusel de TikTok, donde la propia red
elige la canción (`auto_add_music`). Si le metemos una pista con derechos, la
red la silencia, limita el alcance o tumba la cuenta — y el daño no lo pagamos
nosotros, lo paga el negocio del cliente.

Tres salidas, y conviene decidirlo antes de escribir una línea:

1. **Audio original del video** (lo que se grabó). Gratis, legal, y en
   testimonios y demostraciones es lo que hay que oír. No sirve para fotos.
2. **Sin música, con texto en pantalla.** La mayoría ve los reels en silencio:
   un reel mudo con texto grande funciona. Es la opción honesta para fotos.
3. **Biblioteca propia libre de regalías.** Diez o quince pistas compradas una
   vez, guardadas en R2, elegidas por tipo de contenido. Es lo que lo hace
   sonar "profesional" de verdad, pero cuesta dinero y hay que elegirlas.

**Recomendación:** arrancar con 1 y 2, y dejar la 3 para cuando el resto
funcione. Sin audio no se bloquea nada; con audio equivocado se bloquea la
cuenta del cliente.

## Qué es "que se vea profesional", en concreto

No es un adjetivo. Es esta lista, y cada punto se puede comprobar:

- **1080×1920, 9:16 exacto.** Nada de barras negras: lo que no encaja se
  recorta al centro de interés, no se encoge.
- **De 15 a 30 segundos.** Más corto de 15 no se siente reel; más de 30 se
  abandona.
- **Cortes cada 2–4 segundos.** Un plano fijo de ocho segundos es una foto con
  pretensiones.
- **El primer segundo es el gancho.** Nada de intro, logo ni fundido de
  entrada: ahí es donde se pierde a la gente.
- **Texto grande, arriba, con contraste.** Que se lea en un teléfono al sol y
  sin sonido. Nunca en los bordes: la interfaz de la red se los come.
- **Nada tiembla.** Si la toma está movida, no entra — para eso están las notas
  del Analista.
- **30 fps, H.264, AAC, `-movflags +faststart`.** Lo que las redes aceptan sin
  recodificar.
- **Portada elegida**, no el primer cuadro (que suele salir negro o movido).

## El plan, por fases

Cada fase entrega algo que sirve sola. Si se para después de cualquiera de
ellas, lo hecho ya vale.

### Fase 0 — Medir antes de prometer (medio día)

Antes de diseñar nada, saber cuánto tarda la VM en renderizar un reel de 30 s
a 1080×1920. Un script a mano, con material real, midiendo tiempo y pico de
CPU y RAM.

Esto decide todo lo demás: si un reel tarda 40 segundos, se puede hacer al
vuelo; si tarda cuatro minutos, tiene que ser una cola con aviso al terminar.
**Mi apuesta** es 20–60 s para 30 s de salida en 4 vCPU con `-preset veryfast`,
pero una apuesta no es un dato.

También hay que mirar el disco: **quedan 19 GB libres de 77**. Un render toca
el archivo original, los intermedios y la salida; con varios a la vez eso se
llena. La fase 0 fija el tope y la limpieza.

### Fase 1 — Recortar el mejor tramo (`EditorFfmpeg`)

Implementar `EditorDeVideo` de verdad. Es lo más barato que hay porque **todo
el flujo ya está escrito esperando esto**: `DecisorDeVideo` ya pide "recorta de
tal a tal segundo", y hoy recibe "no puedo" y manda el video a Observación.

- Entrada: el `AnalisisDeVideo` ya pagado (`tramoInicio`, `tramoFin`).
- Salida: un `MediaAsset` nuevo, vertical, recortado. **El original nunca se
  toca** (ya lo dice la interfaz).
- Se gana de inmediato: los videos largos dejan de atascarse en Observación.

**Listo cuando:** un video de 3 minutos entra y sale un vertical de 30 s con el
mejor tramo, y el original sigue intacto.

### Fase 2 — Reel de fotos, de las tandas masivas

Aquí está lo que pediste: **mucho contenido subido de golpe que se convierte
solo en reels**. Y es la fase que más rinde, porque la mayoría de lo que sube
la gente son fotos.

- `OrganizadorDeContenido` gana un formato: **REEL**, cuando una tanda trae
  4 o más fotos del mismo tema con nota alta.
- Cada foto, 2.5 s, con un *Ken Burns* suave (un zoom lento del 100 % al 108 %):
  es lo que separa un pase de diapositivas de algo que parece grabado.
- Cortes secos entre fotos, no fundidos: los fundidos envejecen mal.
- Encuadre a 9:16 recortando al centro de interés, reusando lo que ya hace
  `AdaptadorDeImagenes`.
- Texto en pantalla con el titular que ya escribe el `Redactor`.

**Listo cuando:** se suben 8 fotos de un producto y sale un reel de 20 s
vertical, con el título en pantalla, sin tocar nada.

### Fase 3 — Reel de varias tomas de video

Juntar los mejores momentos de uno o varios videos: el `AnalisisDeVideo` ya
trae cada toma con su nota del 1 al 5.

- Se eligen las tomas de nota 4–5, en orden cronológico, 2–4 s cada una.
- Se normaliza todo a 1080×1920/30 fps **antes** de concatenar: concatenar
  cosas con distinto tamaño o fps es de donde salen los saltos y los audios
  desincronizados.
- Si hay voz (`hayVoz()`), ese tramo manda y se respeta entero: cortar a
  alguien a media frase es peor que un reel más largo.

**Listo cuando:** de tres videos de un evento sale uno de 25 s con lo mejor de
cada uno.

### Fase 4 — Texto en pantalla con la marca

`drawtext` con las fuentes que ya están en la imagen, usando los colores de
marca que ya guarda `BrandProfile`.

- Titular en los primeros 3 s, arriba, con fondo semitransparente para que se
  lea sobre cualquier imagen.
- Zona segura: 12 % de margen arriba y abajo. Lo que se sale de ahí lo tapa la
  interfaz de Instagram o TikTok.
- Subtítulos de la transcripción cuando hay voz. **Esto es lo que más sube el
  alcance** y ya tenemos la transcripción pagada; es el mejor retorno de las
  cuatro fases.

### Fase 5 — Audio (solo si se decidió la biblioteca)

Pista de fondo a −18 dB bajo la voz, con `ducking` si hay transcripción.
Depende de la decisión de arriba.

## Dónde y cómo corre, para no tumbar nada

Esto es lo que hace que la idea sea viable o que se lleve el servicio por
delante:

- **Cola propia, UN render a la vez.** Con 4 vCPU, dos renders simultáneos
  dejan la API sin aire. `-threads 3` y uno en curso.
- **`nice`/baja prioridad**: publicar siempre va antes. Que un reel tarde cinco
  minutos más no se lo nota nadie; que una publicación no salga a su hora, sí.
- **Tope de duración por trabajo** (4 minutos) y se mata: un ffmpeg colgado
  retiene CPU y disco para siempre.
- **Carpeta temporal propia, borrada siempre**, pase lo que pase. Con 19 GB
  libres, dos reels huérfanos al día llenan el disco en un mes.
- **El original nunca se toca.** El reel es un `MediaAsset` nuevo, con
  `generadaPorIa` puesto.
- **Apagable por configuración** (`app.reels.enabled`), como el resto.
- Se reusa el patrón de `MiniaturasEnSegundoPlano`, que ya hace trabajo pesado
  fuera de la petición web.

## Qué cuesta

- **El render no cuesta nada**: es nuestra CPU, y hoy está al 1 %.
- **La IA ya está pagada**: el análisis de video y las notas de las fotos se
  cobran una vez al subir, y el reel las reutiliza. Un reel de fotos ya
  analizadas **no gasta un crédito nuevo**.
- Lo único que costaría dinero es la biblioteca de música, si se decide.

## Lo que NO hay que hacer

- **Nada de servicios externos de edición.** Tenemos ffmpeg y una VM ociosa;
  mandar el video a un tercero es pagar, esperar y entregarle el contenido del
  cliente a alguien más.
- **Nada de reels de una sola foto.** Eso es una historia, y ya existe.
- **Nada de publicar un reel sin que lo apruebe una persona**, al menos al
  principio. Un reel malo con la marca encima se queda en la red para siempre.
- **Nada de música con derechos.** Ver arriba.

## Por dónde empezaría

Fase 0 y Fase 1 primero: medir, y después implementar `EditorFfmpeg`, que es
media tarde de trabajo porque el enchufe ya está puesto y desatasca algo que
hoy ya molesta. Con eso se aprende cómo se porta la VM de verdad, y la Fase 2
—la que convierte las tandas de fotos en reels, que es lo que de verdad
pediste— se construye sobre terreno medido en vez de sobre una apuesta.
