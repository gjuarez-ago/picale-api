# Ruteo de redes por video, con la razón en la bitácora

> Estado al 26 de octubre de 2026: **segunda etapa implementada.** El agente ya
> conserva los videos largos completos como `VIDEO`, reutiliza el análisis
> guardado y elige las redes conectadas que aceptan su duración y peso. También
> usa el rendimiento de los últimos 90 días cuando hay muestra suficiente,
> guarda una decisión por red en `decisiones_ruteo` y expone ese detalle en la
> publicación, la Bitácora y el MCP. Nace de un
> caso real: 7 videos verticales de 4 a 8 minutos y de 256 a 464 MB que la
> persona no lograba "lanzar", parados en Observación sin que nadie le dijera
> qué hacer.
>
> Ojo con la forma: estos videos se guardan como 1920×1080 con una rotación de
> 90°, así que en pantalla son 1080×1920. Medir ancho y alto sin leer la
> rotación (`ffprobe` los da en bruto) los hace pasar por horizontales. Cualquier
> regla de "forma" tiene que aplicar la rotación primero.

## Objetivo

Que el agente mire cada video, decida **a qué redes conviene mandarlo y a cuáles
no**, y deje escrito en la bitácora **la razón de cada decisión, red por red**,
en palabras que entienda quien no es técnico. La bitácora deja de ser solo un
registro de qué pasó y pasa a enseñar: después de ver tres veces "no fue a
Instagram porque pesa 412 MB y acepta 300", la persona aprende la regla y
deja de preguntar.

## Lo resuelto y lo pendiente

- Resuelto: las reglas (`app.video.max-bytes.*`, `app.video.max-seconds.*`) se
  aplican red por red antes de crear la propuesta; las redes compatibles reciben
  el original y las demás quedan fuera con su motivo.
- Resuelto: pasar de 90 s ya no manda el original a Observación ni obliga a
  recortarlo. Sale entero como video normal; el recorte sigue siendo manual.
- Resuelto: si el video ya fue escaneado, se reutiliza `agenteAnalisis`; cambiar
  el ruteo no vuelve a gastar visión ni transcripción.
- Resuelto: `acciones_ia` conserva **qué hizo una IA conectada** y
  `decisiones_ruteo` conserva **por qué el agente eligió esas redes y no otras**.
- Resuelto: con tres o más videos medidos por red, compara la mediana del mismo
  formato; conserva la mejor y las que alcancen al menos 65% de su rendimiento.
  Una red con menos muestra se mantiene: la falta de datos nunca la castiga.

## Reglas que el agente aplica

Fuente: <https://docs.upload-post.com/api/video-requirements/> y, para
Instagram, la referencia oficial de Meta (IG User Media), ambas leídas el
25 de octubre de 2026. Los números salen de la configuración, no se copian
aquí a mano: si una red los mueve, se cambia la variable `VIDEO_MAX_*`.

| Red | Peso máx. | Duración | Otras condiciones |
|---|---|---|---|
| Instagram (reel/feed) | 300 MB | 3 s a 15 min | máx. 1920 px de ancho, H.264/HEVC, hasta 25 Mbps. Todo video sale como Reel: el horizontal saldría con bandas o recortado |
| TikTok | 3 GB | 3 s a 10 min | vertical recomendado, 15 publicaciones al día por cuenta |
| YouTube | 256 GB | sin tope práctico | un **Short** es vertical o cuadrado y de ≤ 3 min (regla de YouTube, no verificada hoy contra su documentación); lo demás es video normal |
| Facebook | 10 GB | video normal hasta 4 h | Reel solo hasta 90 s y vertical; arriba de 90 s sale como video normal |
| LinkedIn | 500 MB | 3 s a 30 min | aspecto entre 1:2.4 y 2.4:1 |

## Cómo decide

Para cada video y cada red **conectada** al espacio, en este orden y parando en
la primera que falle:

1. **Duración** contra el tope de la red.
2. **Peso** contra el tope de la red.
3. **Forma** (vertical, cuadrado, horizontal, ya con la rotación aplicada) contra lo que la red publica bien.
4. **Formato resultante**: Reel, Short, video normal. Es una decisión, no un
   descarte: un video de 4 min en Facebook sale como video normal, y eso se
   anota.
5. **Cupo** de la red (TikTok, 15 al día), si aplica.

Resultado por red: `ENVIAR` o `DEJAR_FUERA`, con un código de razón y una frase.
Si **ninguna** red queda, el video no se programa y pasa a Observación con la
lista completa de razones y **qué arreglo lo destraba** (comprimir, recortar,
pasar a vertical).

El agente nunca baja el peso ni recorta por su cuenta: eso cambia el archivo de
la persona. Lo ofrece como propuesta ("puedo dejarlo en 220 MB para que también
entre en Instagram") y la persona aprueba, como con todo lo demás.

## Qué se escribe en la bitácora

Una entrada por video, con **una línea por red**. Texto para la persona, sin
códigos ni nombres internos (ver *Usuarios no técnicos*):

```
El video "107865" salió a 3 de 5 redes.

✔ YouTube — sale como video normal. Dura 7 min 50 s y YouTube lo acepta;
   no es un Short porque dura más de 3 minutos.
✔ Facebook — sale como video normal, porque pasa de 90 segundos
   (los Reels de Facebook llegan a 90).
✔ LinkedIn — pesa 464 MB y LinkedIn acepta hasta 500 MB.
✘ Instagram — pesa 464 MB e Instagram acepta hasta 300 MB.
   Para que entre: puedo dejar un tramo de 90 segundos de unos 40 MB.
✘ TikTok — acepta hasta 10 minutos y este dura 7 min 50 s, así que cabe,
   pero un reel corto rinde más. Se ofrece, no se obliga.
   Para que entre como reel: el mismo tramo de 90 segundos.
```

Reglas de redacción: siempre el número real contra el número permitido; siempre
**qué se puede hacer** cuando una red queda fuera; nunca "regla", "validación",
"payload" ni identificadores.

### Datos que se guardan

Además del texto, los datos para filtrar y medir. La tabla
`decisiones_ruteo` guarda una fila por video y red, sin columnas nuevas en `posts`.

| Campo | Qué es |
|---|---|
| `post_id` / `media_id` | publicación y archivo |
| `plataforma` | la red |
| `decision` | `ENVIAR` o `DEJAR_FUERA` |
| `formato_resultante` | `REEL`, `SHORT`, `VIDEO`, o nulo si quedó fuera |
| `codigo_razon` | `PESO`, `DURACION`, `FORMA`, `FORMATO`, `RENDIMIENTO`, `OK` |
| `valor` / `limite` | 464 MB contra 300 MB, para poder agrupar |
| `frase` | el texto de arriba, ya redactado |
| `arreglo_ofrecido` | `COMPRIMIR`, `RECORTAR`, `PASAR_A_VERTICAL` o nulo |
| `decidido_por` | `AGENTE` en esta etapa; el cambio manual a `PERSONA` queda para la interfaz web |
| `puntaje_historico` / `muestras_historicas` | rendimiento relativo 0–100 y cuántos videos lo sostienen |

La decisión se guarda inmediatamente después de la propuesta. Si anotarla falla,
la publicación igual se conserva (la bitácora no es razón de que algo no salga)
y el fallo queda en el log.

## Dónde se ve

- **Bitácora:** `GET /api/v1/decisiones-ruteo` devuelve lo reciente y
  `GET /api/v1/decisiones-ruteo/publicacion/{postId}` el rastro de una propuesta.
- **Respuesta de publicaciones:** `decisionesRuteo` viaja dentro de cada video
  para que la web pueda mostrar la misma explicación sin recalcularla.
- **MCP:** la herramienta `bitacora_ia` y `ver_publicacion` las incluyen para
  que la IA pueda contestar "¿por qué no fue a Instagram?".
- **Observación:** cuando ninguna red sirve, la lista completa y los arreglos.

## Qué enseña, y cómo medirlo

La idea educativa solo funciona si se ve que la persona aprende. Medir, por
espacio y por semana:

- videos que llegan a Observación por peso o forma (debe **bajar**);
- veces que la persona sube un video sin comprimir o sin vertical y lo
  intenta programar solo en Instagram;
- aceptación de los arreglos ofrecidos.

## Fuera de alcance

- Recortar, comprimir o reencuadrar automáticamente. Es otra pieza (ffmpeg ya
  existe dentro del contenedor de la API) y cambia el archivo del usuario, así
  que va aparte y con aprobación.
- Cambiar los topes. Si una red mueve el suyo, se ajusta `VIDEO_MAX_*`.
- La app móvil, hasta que la web y el MCP lo muestren bien.
- Registrar como `PERSONA` los cambios manuales de redes; requiere que la web
  envíe y confirme esa decisión.

## Preguntas abiertas para la persona dueña del producto

1. **TikTok con un video largo:** ¿se ofrece solo el reel corto, o se permite el original a
   cambio de un aviso? Hoy la documentación dice "vertical recomendado", no
   obligatorio.
2. **Shorts de YouTube:** ¿el agente debe ofrecer sacar Shorts de un video
   largo, o por ahora solo decirle a la persona que no lo es?
3. **Idioma de las razones:** una sola frase por red, o frase corta con un
   "ver por qué" que despliega el detalle.

## Caso real: los 7 videos de ayer

Todos verticales (1080×1920 en pantalla), a unos 8,3 Mbps. El agente ya los
había analizado y los dejó en Observación con un tramo de 90 s propuesto en cada
uno ("recórtalo así y vuelve a subirlo"). Lo que los frenaba era **duración y
peso**, no la forma.

| Archivo | Peso | Duración | Instagram | TikTok | YouTube | Facebook | LinkedIn |
|---|---|---|---|---|---|---|---|
| 107865 | 464 MB | 7:51 | ✘ peso | ✔ | ✔ video | ✔ video | ✔ |
| 107848 | 450 MB | 7:36 | ✘ peso | ✔ | ✔ video | ✔ video | ✔ |
| 107850 | 412 MB | 6:57 | ✘ peso | ✔ | ✔ video | ✔ video | ✔ |
| 107860 | 410 MB | 6:55 | ✘ peso | ✔ | ✔ video | ✔ video | ✔ |
| 107864 | 406 MB | 6:51 | ✘ peso | ✔ | ✔ video | ✔ video | ✔ |
| 107849 | 363 MB | 6:09 | ✘ peso | ✔ | ✔ video | ✔ video | ✔ |
| 107863 | 256 MB | 4:20 | ✔ | ✔ | ✔ video | ✔ video | ✔ |

Con el tramo de 90 s de cada uno el peso baja a unos 40 MB y los cinco destinos
entran; en YouTube sale como Short (vertical y menos de 3 minutos).
