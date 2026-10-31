# Catálogo musical inicial de Pícale

Catálogo local de 48 pistas instrumentales, organizado en ocho ambientes, diez
temas de negocio y nueve intenciones editoriales para videos comerciales. Las
pistas provienen de Kaino Free Asset Library y fueron
publicadas por el proveedor bajo CC0 1.0 Universal.

## Contenido

- `audio/`: MP3 estéreo a 48 kHz. Esta carpeta no se versiona en Git.
- `catalogo.json`: metadatos editoriales y técnicos, procedencia, licencia y
  SHA-256 de cada archivo.
- `taxonomia.json`: selección ordenada por giro de negocio, intención del video,
  presencia de voz y rotación de pistas.
- `LICENSE.txt`: licencia declarada por el proveedor y enlace al texto legal.
- `evidencia/`: copia fechada de la página fuente usada durante la selección.

## Clasificación

La distribución prioriza variedad temática; cada ambiente tiene entre tres y
nueve pistas:

- `alegre`: celebraciones, promociones, comida y eventos.
- `energetica`: deportes, lanzamientos, ofertas y cortes rápidos.
- `relajada`: bienestar, hospitalidad, cafeterías y naturaleza.
- `elegante`: servicios premium, gastronomía, inmobiliaria y moda.
- `calida`: historias humanas, negocios locales, hogar y artesanía.
- `moderna`: tecnología, producto, innovación y tutoriales.
- `confiada`: testimonios, servicios profesionales y llamadas a la acción.
- `juguetona`: familia, mascotas, niños y contenido informal.

## Uso previsto

El backend debe elegir una pista activa compatible con el ambiente del video,
pero primero debe cruzar `tema + intención + presencia de voz`, usando el
ambiente únicamente como desempate. Después descarga la pista desde R2 y la
mezcla con FFmpeg. En voz hablada, el volumen inicial
recomendado es `0.12`; sin voz, `0.18`. La música debe tener entrada y salida
gradual, recortarse a la duración del video y terminar como AAC-LC estéreo a
48 kHz dentro del MP4.

Clave propuesta en R2: `music/catalog/v1/{id}.mp3`.

Antes de publicar el catálogo a todos los clientes conviene pasar cada pista
por los verificadores de copyright de las redes principales. CC0 autoriza el
uso, pero no evita por sí solo reclamaciones automáticas erróneas.
