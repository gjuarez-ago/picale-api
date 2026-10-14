# Director creativo automático

## Decisión de producto

El MVP convierte una tanda de archivos ya subida en propuestas editables de `REEL`, `CAROUSEL`, `IMAGE` y `STORY`. La experiencia visible conserva cuatro acciones: seleccionar contenido, generar propuestas, revisar/aprobar y abrir en el compositor. No expone prompts, recetas, colas, reintentos, confianza ni FFmpeg.

La IA decide **qué conviene hacer** y el backend decide **qué está permitido ejecutar**. Ninguna respuesta del modelo es autoridad sobre archivos, permisos, redes o publicación. Aprobar tampoco publica: habilita la transferencia al compositor. Programar o publicar continúa en `PostService`.

## Arquitectura y responsabilidades

```text
POST /creative-director/batches
        │ valida permiso, cuota y pertenencia
        ▼
CreativeBatchJob (durable e idempotente)
        │ dispatcher + claim atómico + recuperación
        ▼
CreativePlanner ── OpenAI JSON estricto, solo metadatos/descripciones
        │
        ├─ REEL ─────► ReelRenderService ─► cola durable + FFmpeg existente
        └─ otras ────► AdaptadorDeImagenes existente
                              │ preview persistido
                              ▼
                        aprobación humana
                              │
                              ▼
                    borrador para el compositor
```

- `CreativeBatchJob`: entrada, hash idempotente, versión, estado, intentos, bloqueo y error saneado.
- `CreativeProposal`: fuentes ordenadas, objetivo, título, captions, redes, duración, motivo interno, generación y aprobación.
- `AiCreativePlanner`: encargo sin URLs privadas y validación estricta por lista positiva.
- `CreativeDirectorQueue/Dispatcher/Runner`: asincronía, reintentos acotados, timeout y rescate tras reinicio.
- `ReelRenderService`: único ejecutor de reels; no existe una segunda cola de video.
- `AdaptadorDeImagenes`: derivados seguros de imagen, carrusel e historia dentro del worker, antes de permitir la aprobación. Los originales no cambian.
- `PostService`: conserva en exclusiva programación y publicación.

## Contrato de plan v1

```json
{
  "recipeVersion": "creative-plan-v1",
  "pieces": [{
    "type": "REEL",
    "assetIds": ["uuid-1", "uuid-2", "uuid-3", "uuid-4"],
    "objective": "Mostrar el recorrido del evento",
    "title": "Así se vivió",
    "captions": {"INSTAGRAM": "Texto basado en el material disponible."},
    "networks": ["INSTAGRAM"],
    "durationSeconds": 20,
    "reason": "La secuencia tiene inicio, desarrollo y cierre"
  }]
}
```

Todos los campos son obligatorios; `title` y `durationSeconds` pueden ser `null` cuando no aplican. Campos extra, markdown, JSON parcial, UUID mal formado, IDs ajenos/inventados, duplicados, redes desconocidas o combinaciones incompatibles invalidan el plan completo.

- Reel: 4–8 imágenes distintas, orden explícito, 15–30 segundos y sin música.
- Carrusel: 2–10 imágenes distintas y orden explícito.
- Imagen e historia: una imagen.
- Historia: solo Instagram o Facebook.
- Imagen individual: no se recomienda para TikTok.
- Cada red recomendada debe tener caption. El texto se limita al brief y las descripciones.
- Si el material no soporta un formato, se omite; no se genera relleno.

## Estados y aprobación

El lote pasa por `PENDING → RUNNING → READY | PARTIAL | FAILED`. `PARTIAL` significa que el plan fue válido y alguna pieza quedó disponible, pero otro ejecutor no pudo iniciar.

Cada pieza usa `GENERATING → READY | FAILED`. `READY` exige un preview real y persistido; una bandera sin media no puede aprobarse. La aprobación es separada y explícita: `PENDING | APPROVED | DISCARDED`, junto con `approvedAt` y `approvedBy`. Una pieza lista aún no está aprobada. Editar el texto o regenerar una pieza la devuelve a `PENDING`; si cambia el titular incrustado de un reel se encola una receta nueva. La API del compositor rechaza piezas pendientes, descartadas o sin preview. En reels, `PENDING/RUNNING` se refleja como `GENERATING`, `READY` como `READY`, y `FAILED/CANCELLED` como `FAILED`.

## API para una interfaz simple

- `POST /api/v1/creative-director/batches`: `assetIds` y `brief` opcional; responde `202`.
- `GET /api/v1/creative-director/batches`: historial del workspace.
- `GET /api/v1/creative-director/batches/{id}`: progreso y propuestas.
- `POST /api/v1/creative-director/batches/{id}/retry`: vuelve a encolar un análisis fallido sin crear un lote duplicado.
- `POST /api/v1/creative-director/proposals/{id}/approve`: aprobación explícita; no publica.
- `POST /api/v1/creative-director/proposals/{id}/text`: edita título y captions; exige una nueva aprobación.
- `POST /api/v1/creative-director/proposals/{id}/discard`: descarta la propuesta y bloquea su uso.
- `POST /api/v1/creative-director/proposals/{id}/regenerate`: repite el ejecutor determinista con la receta guardada y exige una nueva aprobación.
- `GET /api/v1/creative-director/proposals/{id}/composer`: borrador para precargar la vista existente.

El cliente solo muestra “Pensando/Creando/Listo/No se pudo”, las propuestas y las acciones simples: editar texto, aprobar, descartar, regenerar/intentar nuevamente y continuar al compositor. No requiere una vista nueva ni expone receta, prompts, cola o FFmpeg.

### Contrato visible para web y móvil

La API vive en este repositorio y la primera integración de cliente vive en el proyecto hermano `metricol_web`. Se agregó al flujo existente de Contenido y reutiliza el compositor de publicaciones; no existe una pantalla paralela ni un publicador nuevo. El cliente Flutter todavía no incorpora este flujo.

La entrada web está protegida además por `environment.creativeDirectorEnabled`. Está activa en producción desde la liberación inicial y permanece apagada en desarrollo local. La API conserva la protección autoritativa mediante `CREATIVE_DIRECTOR_ENABLED`; los reels también requieren `REELS_ENABLED`.

La respuesta de cada propuesta incluye `previewUrls` en orden y `previewThumbnailUrl` para video. El cliente usa esos campos para la tarjeta visual; nunca intenta construir una URL de R2 ni lee `recipeVersion`, `selectionReason`, intentos o identificadores de ejecutores para mostrarlos.

| Estado del servidor | Texto visible | Acciones visibles |
|---|---|---|
| Lote `PENDING` o `RUNNING` sin propuestas | Analizando tu contenido… | Ninguna |
| Pieza `GENERATING` | Creando vista previa… | Descartar |
| Pieza `READY` + aprobación `PENDING` | Lista para revisar | Editar, Aprobar, Descartar, Regenerar |
| Pieza `READY` + aprobación `APPROVED` | Aprobada | Editar, Descartar, Regenerar, Usar en publicación |
| Pieza `FAILED` | No se pudo crear esta propuesta | Regenerar, Descartar |
| Lote `FAILED` sin propuestas | No pudimos analizar este contenido | Intentar nuevamente |
| Aprobación `DISCARDED` | Propuesta descartada | Regenerar |

El botón de generación queda deshabilitado desde el primer clic hasta recibir la respuesta `202`. Repetir accidentalmente la misma solicitud sigue siendo seguro porque el hash del servidor devuelve el mismo lote.

### Polling

1. Tras el `202`, consultar `GET /batches/{id}` cada 2 segundos.
2. Continuar mientras el lote esté `PENDING/RUNNING` **o** exista una propuesta `GENERATING`. Un lote puede estar `READY` mientras su reel termina en su cola propia.
3. Detener al no quedar piezas `GENERATING` y estar el lote en `READY`, `PARTIAL` o `FAILED`.
4. Detener inmediatamente al desmontar el componente, cerrar la sesión o cambiar de workspace. La clave de caché debe incluir el workspace; nunca reutilizar el lote anterior.
5. Ante pérdida de red, aplicar esperas de 2, 4, 8 y máximo 15 segundos. Reanudar con el mismo `id`, no crear otro lote.
6. Después de regenerar, volver a sondear sólo mientras la propuesta esté `GENERATING`.

El frontend debe conservar un único controlador de cancelación por pantalla (`AbortController`, `takeUntil` o equivalente) y bloquear acciones repetidas mientras su solicitud esté pendiente. Esto cubre doble clic, desmontaje y cambio de workspace sin agregar estado técnico visible.

## Idempotencia y recuperación

El hash incluye workspace, versión, IDs **en orden** y brief normalizado. Repetir el pedido devuelve el mismo lote; una restricción única cubre también carreras concurrentes.

El dispatcher reclama con un `UPDATE` condicionado a `PENDING`, incrementa intentos al reclamar y nunca mantiene una transacción durante la llamada de red. Un `RUNNING` vencido vuelve a `PENDING` si quedan intentos; si no, termina `FAILED`. El backoff y el timeout son acotados. Las propuestas se materializan una sola vez y el reel conserva además su idempotencia por receta.

Los previews no-reel forman parte del mismo trabajo persistente: una propuesta queda `GENERATING` hasta guardar sus URLs ordenadas. Si la instancia cae después de crear el plan, el siguiente worker detecta las propuestas existentes, omite una segunda llamada a la IA y continúa sólo los previews pendientes. Las claves deterministas de `AdaptadorDeImagenes` reutilizan derivados ya subidos. Una pieza puede fallar sin cancelar las demás.

## Seguridad y aislamiento

- El endpoint exige `POST_CREATE`; la IA pasa por la cuota diaria existente.
- Cada ID se carga bajo el tenant activo y se compara explícitamente con el workspace.
- El modelo no recibe credenciales, claves de R2 ni URLs: solo ID, tipo, nombre saneado y descripción.
- Todo ID propuesto debe estar en el conjunto validado inicialmente.
- Lotes y propuestas se consultan por workspace; un acceso cruzado parece “no encontrado”.
- Los errores públicos son genéricos y limitados.
- Este módulo nunca llama al publicador.
- El compositor recibe las mismas URLs que se revisaron y aprobaron; no vuelve a adaptar la pieza a escondidas.

## Coste y tiempos

Hay una llamada de texto por lote, más los ejecutores elegidos. Imagen/carrusel/historia usan originales y derivados deterministas; no consumen generación de imagen. El reel consume CPU y almacenamiento según [reels.md](reels.md).

Objetivos iniciales: creación HTTP menor a 1 s; planificación p50 menor a 15 s y p95 menor a 60 s; timeout 75 s; máximo 3 intentos con backoff 15–120 s; un worker por instancia en el piloto.

## Hipótesis, métricas y rollout

Hipótesis: una tanda produce más valor si se proponen pocas piezas complementarias con una razón clara, sin obligar a elegir formatos uno por uno.

Métricas: tiempo hasta plan, piezas por tipo, plan inválido, fallos por ejecutor, aprobación por tipo, tiempo hasta aprobación, apertura del compositor, publicación posterior, reintentos, coste IA por lote y lotes parciales.

La instrumentación mínima usa eventos de log `creative_event` para `batch_started`, `batch_finished`, `plan_invalid`, `plan_timeout`, `plan_provider_error`, `batch_retry_scheduled`, `piece_failed`, `preview_ready`, `proposal_approved`, `proposal_regenerated` y `composer_opened`. No incluye prompts, captions, URLs ni errores internos. El costo y los tokens de `DIRECTOR_CREATIVO` continúan en `AiUsage` y en el reporte operativo existente.

1. `CREATIVE_DIRECTOR_ENABLED=false` por defecto.
2. Desarrollo con reels habilitados y escenarios reales.
3. Piloto interno con un worker y revisión diaria de calidad/coste.
4. Activar el despliegue general sólo al cumplir objetivos; la bandera actual es global, no una segmentación por cohortes.
5. Apagar el flag ante planes inválidos, aislamiento o coste fuera de rango. Los trabajos quedan auditables.

## Escenarios de aceptación

- 4 fotos: reel de cuatro y, si aporta, una pieza complementaria.
- 8 fotos variadas: selección y orden de 4–8; derivados verticales con el adaptador existente.
- Material insuficiente: solo formatos válidos o fallo sin relleno.
- ID inventado/otro workspace: rechazo antes de ejecutar.
- JSON inválido/campos extra: `FAILED`, sin propuestas.
- Timeout/fallo temporal: reintento acotado; tras reinicio se rescata el trabajo.
- Reel fallido y otra pieza válida: `PARTIAL`.
- Aprobación y compositor: no publican.
- Preview no-reel: adaptación real con FFmpeg a 9:16, JPEG legible y original con el mismo SHA-256.
- Los flujos actuales de medios, campañas, composición y publicación siguen compatibles.

## Procedimiento de staging

1. Desplegar API y migrar/crear `creative_proposal_previews` y `preview_thumbnail_url` mediante el mecanismo de esquema vigente.
2. Confirmar OpenAI, R2, `ffmpeg` y `ffprobe`; mantener `CREATIVE_DIRECTOR_ENABLED=false` y `REELS_ENABLED=false` durante el arranque.
3. Ejecutar la suite, `FfmpegReelRendererIntegrationTest`, `AdaptadorDeImagenesIntegrationTest` y el benchmark de reels con 4 y 8 fotografías reales.
4. Desplegar `metricol_web` con `creativeDirectorEnabled: true` sólo en el entorno piloto. La integración está en Contenido, usa el selector de medios y precarga el compositor existente. Repetir en staging la validación manual de teclado, lector de pantalla, foco tras cada acción y una columna en viewport móvil.
5. Activar `REELS_ENABLED=true` y `CREATIVE_DIRECTOR_ENABLED=true` sólo en el entorno piloto. La implementación actual no tiene allowlist por workspace.
6. Probar: lote insuficiente, lote mixto, fallo parcial, reintento, edición, nueva aprobación, descarte, regeneración y apertura del compositor.
7. Verificar en logs que no aparece ninguna llamada de publicación y revisar tiempo, fallos, tokens y costo. Volver ambas banderas a `false` si se incumple el aislamiento o crece la tasa de planes inválidos.

## Limitaciones deliberadas

- El plan usa descripciones/metadatos; no envía binarios ni URLs privadas.
- V1 materializa piezas nuevas solo desde imágenes; rechaza videos tratados como fotos.
- La integración existe por ahora sólo en `metricol_web`; Flutter queda fuera de esta fase. El cliente web incluye pruebas automatizadas de polling, cancelación, bloqueo de acciones, estados, previews, accesibilidad básica, diseño móvil y traspaso al compositor, pero el piloto aún requiere validación manual con credenciales y servicios reales.
- Los derivados se comparten por clave determinista mientras el original exista. `MediaService`, el borrado definitivo del workspace y `MediaDerivadosWorker` eliminan sus prefijos cuando dejan de ser reclamables; descartar una propuesta no borra una copia que otra publicación pueda reutilizar.
- No hay editor visual avanzado ni panel de operaciones nuevo.
- No genera música ni contenido sintético de relleno.
- La calidad depende de descripciones útiles; cambiar el brief genera un hash nuevo.
