# Publicación de videos por red

La selección Reel conserva el video original. No hay un máximo global de 90
segundos: se valida la duración contra cada red seleccionada.

| Destino | Máximo | Comportamiento |
| --- | --- | --- |
| Facebook | 240 minutos | Hasta 90 s conserva Reel; sobre 90 s envía `facebook_media_type=VIDEO`. |
| Instagram | 15 minutos | Conserva Reel. |
| TikTok | 10 minutos | Publica el video. |
| LinkedIn | 30 minutos | Publica el video. |
| YouTube | 12 horas | La plataforma determina si es Short o video normal. |

Las historias conservan su máximo de 60 segundos y no se convierten.
Con duración desconocida, Facebook usa video normal para evitar el tope de
Reels; los clientes muestran el aviso. Las otras redes conservan su formato.

`GET /publishing/limits` informa `reelMaxVideoSeconds` y `longVideoFallback`
para que la web avise de la conversión de Facebook sin deseleccionarlo ni
bloquear Continuar. Los máximos reales siguen validándose al guardar y enviar.
Si se exceden, la persona puede conservar el original y seleccionar otras redes
o preparar una versión corta; este cambio no recorta archivos automáticamente.

Los borradores pueden conservar videos por encima del máximo de una red. La
restricción se exige al publicar o programar, no al guardar ese trabajo.

La app móvil tampoco impone 90 segundos a los archivos de galería. Su cámara
permite grabar hasta 10 minutos y muestra el aviso de Facebook antes de publicar.
La web redondea la duración hacia arriba para no enviar 90,4 segundos como 90.

Desplegar API, web y una nueva versión móvil. Revisar cualquier variable `VIDEO_MAX_*` configurada en el
entorno: prevalece sobre los nuevos valores predeterminados. La antigua variable
`POST_MAX_VIDEO_SECONDS` ya no impone un tope global.

Fuente verificada el 10 de octubre de 2026:
https://docs.upload-post.com/api/video-requirements/

## Video horizontal y el formato Video

El formato **Video** ya se ofrece (`PostFormat.VIDEO`). Estuvo apagado mientras
faltaba decidir su máximo; la respuesta es la de este documento: ninguno global,
el de cada red. Aparece en el compositor de la web y de la app en cuanto la API
lo dice, sin desplegarlas, porque ambas piden los formatos a
`GET /publishing/formats` y pintan lo que reciben.

El agente sigue sin proponer por su cuenta un video horizontal: Reels, TikTok e
historias piden vertical. Pero si la persona dice que va desde Observación, ya
no vuelve al mismo sitio con el mismo aviso —antes el botón contestaba que sí y
no pasaba nada—: sale como Video, entero, a las redes que publican esa forma.
Instagram queda fuera, porque ahí todo video se publica como Reel y saldría con
bandas o recortado.
