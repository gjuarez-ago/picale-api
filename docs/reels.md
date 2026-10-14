# Reels verticales derivados de fotografías

Estado: MVP implementado detrás de `app.reels.enabled`, apagado de forma
predeterminada. Este documento describe lo que existe en este repositorio, no
lo que se espera construir en fases futuras.

## Resultado de producto

Una persona elige, en orden, entre 4 y 8 fotografías de su workspace y solicita
un reel. La API responde sin esperar el render, un worker produce el MP4 y la
persona consulta el trabajo hasta ver la propuesta. El resultado no se publica
ni se programa automáticamente. Aprobarlo es una acción explícita y tampoco lo
publica: deja constancia de la revisión humana y habilita usar ese activo al
crear una publicación. Mientras no esté aprobado, `PostService` lo rechaza.

El MVP resuelve la generación y revisión desde la API. Las pantallas web y
móvil están fuera de este alcance y deberán consumir estos endpoints antes de
encender la función en producción.

### Criterios de éxito iniciales

| Indicador | Umbral para habilitar gradualmente |
|---|---:|
| Renders técnicamente válidos | >= 98 % |
| Tiempo de render p95 en `api-videos-prod` | <= 120 s |
| Trabajos duplicados para la misma receta | 0 |
| Originales modificados | 0 |
| Publicaciones automáticas | 0 |
| Temporales encontrados después de terminar | 0 |
| Reels aprobados sin regenerar | Hipótesis a medir; sin umbral todavía |

No existen datos propios todavía para afirmar que una duración, transición,
titular o ritmo produzca más alcance. Esas afirmaciones son hipótesis de
producto y deberán compararse con publicaciones reales después de integrar la
interfaz.

## Alcance implementado

- 4 a 8 `MediaAsset` de tipo `IMAGE`, estado `READY`, pertenecientes al mismo
  workspace y con orden explícito.
- Duración editorial configurable entre 15 y 30 segundos; 20 segundos por
  defecto.
- Lienzo vertical de 1080x1920.
- Movimiento Ken Burns suave y cortes directos.
- La fotografía se conserva completa. Si su proporción no es 9:16, el espacio
  restante usa una copia ampliada y desenfocada. No existe recorte inteligente.
- Titular opcional, máximo tres líneas, mostrado durante los primeros tres
  segundos dentro de la zona superior segura.
- Portada JPEG tomada del reel ya renderizado.
- Nuevo `MediaAsset` de video; los originales nunca se escriben ni reemplazan.
- Receta JSON versionada, hash idempotente, fuentes y orden persistidos.
- Estados `PENDING`, `RUNNING`, `READY`, `FAILED` y `CANCELLED` con error
  legible, intentos y marcas de tiempo.
- Aprobación humana persistida mediante una acción separada y exigida por el
  servidor antes de aceptar el reel en cualquier borrador o publicación.

### Fuera de alcance

- Selección automática de fotografías.
- Publicación o programación automática.
- Música, biblioteca de audio y mezcla.
- Subtítulos.
- Videos de entrada, detección de escenas o montaje de varias tomas.
- Cambios en web o app móvil.
- Servicios externos de edición.

## Duración editorial y límites de publicación

Los 15–30 segundos son una decisión editorial de este generador, no un límite
global de publicación. Los videos que la persona sube conservan los límites y
fallbacks por red descritos en [publicacion-videos.md](publicacion-videos.md).
El generador no modifica `DecisorDeVideo`, `FormatRulesService` ni la publicación
existente.

Si cambian los límites de una red, se actualiza la configuración de publicación;
no se cambia por ello la receta editorial del generador. La referencia actual
del proveedor es:

https://docs.upload-post.com/api/video-requirements/

## API

Todos los endpoints exigen una sesión del workspace activo. Crear, cancelar y
aprobar requieren `POST_CREATE`.

| Método | Ruta | Resultado |
|---|---|---|
| `POST` | `/api/v1/reels` | Valida las fuentes y devuelve `202` con el trabajo nuevo o el existente para la misma receta. |
| `GET` | `/api/v1/reels` | Lista los trabajos del workspace actual. |
| `GET` | `/api/v1/reels/{id}` | Devuelve estado, error, fuentes, URL, portada y aprobación. |
| `POST` | `/api/v1/reels/{id}/cancel` | Cancela un trabajo pendiente o en curso. |
| `POST` | `/api/v1/reels/{id}/approve` | Registra aprobación humana de un trabajo `READY` y permite usar su activo en una publicación; no publica. |

Ejemplo de solicitud:

```json
{
  "assetIds": [
    "11111111-1111-1111-1111-111111111111",
    "22222222-2222-2222-2222-222222222222",
    "33333333-3333-3333-3333-333333333333",
    "44444444-4444-4444-4444-444444444444"
  ],
  "durationSeconds": 20,
  "title": "Café recién tostado"
}
```

## Arquitectura y recuperación

La cola verdadera es `reel_render_jobs`; no hay trabajos esperando únicamente
en memoria. `reel_render_sources` conserva los IDs y su orden. La combinación
de workspace, fuentes ordenadas, duración, titular y versión genera un SHA-256
único. Repetir la misma solicitud devuelve el mismo trabajo.

El despachador reclama filas mediante un `UPDATE` condicional. El executor tiene
capacidad de cola cero: reclama únicamente lo que puede ejecutar. El valor
inicial es un worker y puede cambiarse con `REELS_WORKERS`.

```text
POST /reels
    -> PENDING persistente
    -> RUNNING (reclamo atómico)
    -> READY + MediaAsset + portada
       o PENDING con backoff
       o FAILED al agotar intentos
       o CANCELLED por la persona
```

Un `RUNNING` cuyo candado vence vuelve a `PENDING`. Esto permite recuperar un
trabajo después de reiniciar o perder una instancia. Cada receta tiene una
clave de almacenamiento determinista: un reintento sobrescribe el mismo objeto
y el `MediaAsset` nace únicamente cuando video y portada quedaron confirmados.
Un render cancelado comprueba el estado antes de subir y de nuevo al confirmar;
una subida parcial se elimina en el mismo intento.

`READY` significa que el archivo pasó la validación técnica, no que esté
aprobado. El vínculo `resultAssetId` permite que la validación normal de
publicaciones bloquee ese video hasta que el trabajo tenga `approvedAt`.

Cada worker impone el workspace con `TenantIdentifierResolver.comoTenant` antes
de consultar las fotografías o guardar el resultado. La API busca cada trabajo
por `id` y `workspaceId`; conocer un UUID de otro espacio no permite leerlo.

## Contrato del archivo

El render se considera exitoso solo después de que `ffprobe` confirme:

- MP4 con `moov` antes de `mdat` (`faststart`).
- 1080x1920, relación 9:16 y píxeles pares.
- H.264 High Profile, nivel 4.0.
- `yuv420p` con rango limitado.
- Escaneo progresivo.
- 30 fps.
- GOP cerrado de 60 cuadros.
- Sin audio en este MVP. Cuando exista audio en otra fase deberá ser AAC-LC a
  48 kHz; esa fase no forma parte de esta implementación.

El primer ensayo real detectó que JPEG de rango completo podía producir
`yuvj420p` aunque se indicara `-pix_fmt yuv420p`. La receta convierte el rango de
forma explícita antes de codificar y la prueba de integración impide que esa
regresión vuelva a pasar.

## Operación y configuración

La función permanece apagada hasta que exista una interfaz de revisión:

```properties
REELS_ENABLED=false
REELS_WORKERS=1
REELS_TIMEOUT_SECONDS=240
REELS_MAX_ATTEMPTS=3
REELS_DEFAULT_DURATION_SECONDS=20
```

También son configurables el sondeo, rescate, lote y backoff. El render usa un
directorio temporal exclusivo y lo recorre y elimina en `finally`. El timeout
mata el proceso de FFmpeg. El video se sube desde archivo, no se carga completo
en el heap de Java.

No se reutiliza `mediaExecutor`: ese pool puede descartar miniaturas, mientras
que un reel solicitado por una persona debe conservarse y recuperarse.

## Pruebas y benchmark

Pruebas añadidas:

- Receta estable y sensibilidad al orden.
- Validación de cantidad, duración, titular y contrato técnico.
- Idempotencia.
- Aislamiento entre workspaces.
- Reintentos limitados.
- Recuperación de un worker interrumpido.
- Fotografías vertical, horizontal y cuadrada.
- Nombre y titular con acentos.
- Reel sin pista de audio.
- Portada válida.
- Creación del activo derivado solo después de confirmar video y portada.
- Aprobación explícita y rechazo del activo no aprobado al crear publicaciones.
- Eliminación definitiva del workspace incluyendo su cola de reels.
- Render real y verificación con `ffprobe`.

Comandos utilizados:

```powershell
mvn '-Dtest=PostVideoValidationTest,ReelRecipeTest,FfmpegReelRendererTest,ReelResultStoreTest,ReelRenderPersistenceTest' test
mvn -Dtest=FfmpegReelRendererIntegrationTest test
& './tools/reel-benchmark.ps1'
mvn test
mvn -DskipTests package
```

Resultado final: suite completa con 968 pruebas, 0 fallos, 0 errores y 0
omitidas; JAR de Spring Boot empaquetado correctamente.

### Resultado medido el 13 de octubre de 2026

Entrada sintética: cuatro JPEG de proporciones vertical, horizontal, cuadrada y
vertical; salida de 15 segundos con titular. Máquina de desarrollo Windows; no
es todavía una medición de la VM de producción.

| Medida | Resultado observado |
|---|---:|
| Tiempo exclusivo de render y validación | 14.6 s |
| CPU acumulada de procesos FFmpeg | 51.6 s |
| Memoria residente pico observada de FFmpeg | 775,073,792 bytes (~739 MiB) |
| Entradas JPEG | 85,207 bytes |
| Tamaño del MP4 | 132,264 bytes |
| Disco temporal aproximado al terminar | 231,387 bytes |
| Salida | 1080x1920, H.264, yuv420p, 30 fps, progresivo, faststart |
| Audio | 0 pistas |

`tools/reel-benchmark.ps1` repite el ensayo y escribe evidencia en
`target/reels-smoke/`. El tamaño bajo del archivo se debe a que las imágenes de
prueba son formas planas; no representa el bitrate ni el uso de disco de
fotografías reales. La medida de disco suma los archivos presentes en el
directorio exclusivo de la prueba y es aproximada, no un pico muestreado por el
sistema operativo.

### Decisión antes de producción

Ejecutar el mismo benchmark en `api-videos-prod`, con 4 y 8 fotografías reales,
mientras se mide la latencia de la API. Habilitar primero en un workspace
interno y mantener un solo worker. El go/no-go inicial es p95 <= 120 segundos y
sin degradación apreciable de las solicitudes web. Hasta tener esa medición,
la capacidad y el costo por reel en producción siguen siendo hipótesis.

## Riesgos y decisiones pendientes

- La memoria pico local fue mayor de lo anticipado. No aumentar workers sin
  medir en la VM.
- `ddl-auto=update` crea las tablas nuevas, como el resto del proyecto. Una
  migración formal sigue siendo deuda transversal del repositorio.
- Falta la experiencia de interfaz para seleccionar, reordenar, esperar,
  previsualizar, cancelar y aprobar.
- Falta medir el tamaño con fotografías reales y verificar la cuota antes de
  habilitarlo a clientes.
- Una aprobación habilita el activo, pero no crea una publicación. Integrar una
  pantalla que lo lleve al compositor requerirá otro cambio explícito.
- La duración, el ritmo y el estilo visual iniciales son hipótesis; deben
  versionarse, no sobrescribirse, cuando haya evidencia para cambiarlos.
