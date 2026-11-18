# D50 — Selección, preparación y cola conectadas al coordinador

Fecha: 13 de noviembre de 2026. Solicitud: «Selección, preparación y cola: conectar los archivos elegidos con el coordinador sin duplicar subidas. Okis vamos con ello».

## Resultado y alcance

La galería de Contenido ya prepara la ruta de admisión antes de abrir el selector. Al regresar, la cola asigna cada archivo a un único motor antes de registrar su intención durable. Un archivo nuevo elegible usa el coordinador multipart; el resto conserva el transporte simple. Los trabajos anteriores conservan su registro y motor, sin conversión de una sesión simple ya abierta.

La integración utiliza las tarjetas existentes para preparación, avance, finalización, error, reintento y cancelación. No se publica una app, no se construye un APK ni se modifica la API o la bandera de producción en esta unidad. Las pruebas nuevas ejecutan el código Dart real, archivos temporales y el adaptador real sobre un puerto nativo simulado. No acreditan transferencias reales del SDK hacia R2 ni ejecución en segundo plano en un teléfono.

## Flujo de admisión

1. `ContenidoPage._subir` captura usuario, negocio y token. Espera `ColaDeSubidas.prepararSeleccion` y verifica el contexto antes de abrir la galería y después de cerrarla. Una selección que termina bajo otra sesión se descarta sin entregarse a esa cuenta.
2. En Android se consulta `/media/usage` y se inicia un coordinador para ese contexto. Las capacidades son las de esa apertura del coordinador; una consulta fallida usa capacidades desconocidas, sin habilitar nuevas subidas multipart. La recuperación de sesiones multipart existentes sigue disponible aunque se deshabiliten nuevas aperturas.
3. `agregar` mantiene su respuesta inmediata y crea tarjetas. Asigna un lote UUID y conserva el índice original de cada selección. Una ruta ya presente con trabajo pendiente, incluso fallido, se reintenta desde su tarjeta en lugar de generar otro trabajo.
4. La ruta multipart requiere usuario/negocio coincidentes, cliente vigente, protocolo 2, autorización móvil explícita y tamaño igual o superior al umbral anunciado. Se fija tamaño y fecha de modificación del original en la intención; no se leen sus bytes todavía.
5. La intención se registra exclusivamente en `subidas-multipart`, manifiesto v4. Las intenciones simples se registran exclusivamente en `subidas`, manifiesto v3. Para una tanda mixta, la cola espera el registro de ambas rutas antes de copiar el primer archivo. Si se interrumpe un registro parcial, los trabajos ya persistidos mantienen sus identificadores; el reintento inserta idempotentemente lo restante.
6. El coordinador prepara una copia privada mediante `PreparadorDeSubidas`: comprueba origen/espacio, copia en streaming a `.part`, valida el tipo con el nombre original, calcula SHA-256 y publica por rename. Reutiliza la recuperación ya implementada en el preparador; nunca entrega un `.part` al motor.
7. Mientras se prepara, los commits conservan la fase `preparando`, incluso al terminar la copia. La huella de deduplicación se calcula desde la copia privada. Sólo después se publica `en_cola`. Las consultas automáticas y `continuar` no abren una sesión a partir de una intención incompleta.
8. Con la identidad preparada, el coordinador recupera la misma clave y aplica el protocolo de D49. Persiste la asociación remota y los intentos antes de entregar rangos al motor. La cola no realiza un segundo `presign` simple para ese trabajo.

## Evitar duplicados

| Situación | Comportamiento |
| --- | --- |
| Misma ruta elegida de nuevo | Se conserva la tarjeta/trabajo existente; un fallo pendiente también bloquea otra admisión por esa ruta. |
| Mismo nombre y tamaño, contenido distinto | Se admiten ambos; esos dos metadatos no demuestran identidad. |
| Nombres distintos y SHA-256 completo igual en el mismo contexto v4 | La preparación serializada detecta el trabajo existente y marca el nuevo `cancelada` con `SELECCION_DUPLICADA`, antes de abrir otra sesión. La tarjeta redundante se retira con aviso. |
| El trabajo equivalente ya falló pero sigue pendiente | También participa en la comparación; se conserva la sesión anterior y su reintento. |
| Respuesta perdida al abrir | Se recupera la misma clave; no se cambia de motor ni se genera otra identidad. |
| Nuevo trabajo simple frente a uno multipart | No se comparte ni se reinterpreta el registro. Las sesiones ya existentes conservan su motor. La deduplicación entre registros distintos continúa dependiendo además de la huella y las reglas del servidor; no se implementa una transacción global entre v3 y v4. |
| «Subirlo otra vez» autorizado por la persona | Se conserva la identidad de esa selección y se transmite `aunqueRepetido`; no se aplica la supresión ordinaria por contenido para ese intento explícito. |

Las copias de selecciones descartadas quedan conservadas con su historial terminal. Su eliminación corresponde al trabajo pendiente de purga conservadora; no se borran archivos para resolver incertidumbre.

## Recuperación y ciclo de sesión

- `restaurar` carga trabajos del usuario y negocio actuales desde ambas rutas. Las selecciones v4 incompletas vuelven a preparación con la misma clave, lote e índice. Los errores que requieren intervención conservan su tarjeta y reintento.
- Las sesiones ya entregadas se consultan con sus intentos anteriores. Apagar nuevas aperturas no convierte un multipart pendiente en un PUT completo.
- El progreso del coordinador entrega también el trabajo actualizado a la cola. Un `READY` conserva su `MediaAsset` en la tarjeta y aumenta el contador de terminaciones una sola vez. El manifiesto no serializa el objeto completo de UI; tras reiniciar se recupera desde la API.
- Al vaciar la cola por cambio de sesión se desvincula el stream y se cierra el coordinador anterior. Las tareas que Android aceptó permanecen intactas. Los resultados obsoletos no se aplican a las tarjetas nuevas.
- El cierre del coordinador también drena sus operaciones locales de selección/preparación antes de liberar la propiedad de la carpeta. No espera los bytes del upload ni una petición HTTP de control ya pendiente. La apertura de la cola se serializa con sus cierres para no superponer propietarios.
- Cancelar durante preparación persiste un terminal local sin consultar una sesión remota que nunca se abrió. Una apertura incierta conserva las reglas de cancelación y reconciliación de D49.
- Un v4 ilegible bloquea su inicialización y conserva el archivo. La galería no toma ese error como permiso para reenviar sus trabajos por el motor simple.

## Archivos de implementación

En `../metricol_app`:

- `lib/pages/contenido/contenido_page.dart`: preparación de ruta antes del selector y guardias de sesión tras esperas.
- `lib/services/cola_de_subidas.dart`: admisión por motor, barrera de registro de tanda mixta, tarjetas, reintento, cancelación y restauración multipart.
- `lib/services/coordinador_de_subidas_multipart.dart`: admisión de intenciones, preparación serializada, comparación de SHA completo, publicación final y drenaje de IO al cerrar.
- `lib/services/subidas_en_fondo.dart`: parámetro Android únicamente en el constructor de pruebas; producción mantiene `Platform.isAndroid`.
- `test/seleccion_multipart_test.dart`: 21 regresiones de integración y carreras.

## Validación

El foco final de selección/cola/revisión del coordinador pasa 42 pruebas: 21 nuevas y 21 existentes. Incluye tanda mixta, bandera deshabilitada, contenido igual/distinto, respuesta perdida, falta de espacio, original modificado tras seleccionar, cancelación durante validación, cambio de cuenta durante copia/consulta, recuperación de registro incompleto, registro dañado, finalización de tarjeta y cierre con HTTP pendiente.

La suite completa y el análisis estático final se registran en `target/subidas-d50/report.json` al recoger sus resultados. Fuentes congeladas en `target/subidas-d50/sources.json`; no se deben atribuir resultados de una corrida anterior a fuentes posteriores.

## Límites y siguiente unidad

Antes de activar o distribuir esta ruta hace falta el canario Android con el SDK real: archivos pequeños y grandes, bytes/cabeceras exactos en R2, Home, bloqueo, corte de red, reapertura y reconciliación sin duplicados. Después se construye y revisa el APK candidato con el estado de capacidad correspondiente. Las capacidades de nueva admisión se mantienen durante la vida del coordinador y se renuevan al abrir otro contexto/coordinador; cambiar la bandera en el servidor no refresca automáticamente un coordinador ya abierto.

Permanecen separadas la purga/reparación conservadora del manifiesto y las pruebas/implementación iOS que necesitan Mac. La exclusión de propiedad sigue siendo intra-isolate, sin cerrojo entre procesos. No se declara validación física ni producción multipart habilitada a partir de estos tests.
