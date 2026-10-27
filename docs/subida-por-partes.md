# Subida por partes (archivos grandes)

> Estado al 27 de octubre de 2026: **API lista y probada, apagada por defecto.**
> La web y la app todavía suben todo con un solo PUT. Ver "Puesta en marcha".

## Por qué

Un archivo de 3 GB por un solo `PUT` necesita una conexión que aguante una hora
sin cortarse. Si se corta, empieza de cero. Para eso hubo que estirar el permiso
de subida a 4 horas y el limpiador de pendientes a 5 horas (ver
`MEDIA_PRESIGN_TTL_SECONDS` y `MEDIA_ORPHAN_AFTER_MINUTES`).

Por partes, el archivo viaja en trozos de 16 MB. Cada trozo tiene su propio
permiso corto y se reintenta solo. Si se cierra la pestaña, R2 recuerda qué
trozos llegaron y la subida se retoma. Es lo que hacen YouTube, Dropbox, Meta y
S3: una sesión de subida reanudable.

Lo que no cambia: el archivo sigue yendo **directo del cliente a R2**. La API
solo abre, firma, pregunta y cierra; nunca ve los bytes.

## Flujo

```
cliente                                   API                              R2
  │ POST /media/multipart  ─────────────▶ valida tipo, tope y cuota
  │                                       CreateMultipartUpload ─────────▶
  │ ◀── assetId, partSizeBytes, partCount
  │ POST /media/{id}/multipart/parts  ──▶ firma un lote (≤ 50)
  │ ◀── [{partNumber, url}]
  │ PUT url (trozo n) ─────────────────────────────────────────────────▶ guarda la parte
  │ ◀──────────────────────────────────────────────────────────── ETag
  │   … (4 en paralelo, reintentos; más lotes según avanza)
  │ POST /media/{id}/multipart/complete ▶ ListParts: ¿están todas?, ¿suman lo declarado?
  │                                       CompleteMultipartUpload ────────▶
  │                                       confirm() de siempre (tamaño, cuota, miniatura)
  │ ◀── MediaAssetResponse (READY)
```

Retomar: `GET /media/{id}/multipart/parts` dice qué partes ya están en R2, y el
cliente sube solo las que faltan. Cancelar: `DELETE /media/{id}/multipart`.

## Endpoints

Todos piden el permiso `POST_CREATE`, igual que `/presign`.

| Método y ruta | Qué hace |
|---|---|
| `POST /media/multipart` | Abre la subida. Mismo cuerpo que `/presign` (`fileName`, `contentType`, `sizeBytes`, lote opcional). Devuelve `assetId`, `partSizeBytes`, `partCount`, `url` |
| `POST /media/{id}/multipart/parts` | Cuerpo `{ "partNumbers": [1,2,3] }`, hasta 50. Devuelve `signed: [{partNumber, url}]` y `expiresInSeconds` |
| `GET /media/{id}/multipart/parts` | Devuelve `uploaded: [{partNumber, sizeBytes}]` y `uploadedBytes` |
| `POST /media/{id}/multipart/complete` | Une y confirma. Seguro de repetir |
| `DELETE /media/{id}/multipart` | Aborta en R2 y libera la cuota |

El `PUT` de cada parte va **sin `Content-Type`** (la parte no lo lleva; el del
archivo se fija al abrir) y **sin el token de sesión** (es una petición a R2, no
a la API). El cliente guarda el `ETag` de la respuesta solo para su propio
control: al cerrar, la API arma la lista preguntándole a R2, no al cliente.

## Reglas que hace cumplir la API

- Abrir valida lo mismo que `/presign`: tipo de archivo, tope diario, tope por
  archivo y cuota. La fila nace `PENDING` y ya cuenta para el espacio usado.
- Tamaño de parte: 16 MiB, salvo que haga falta más para no pasar de 10,000
  partes. Nunca menos de 5 MiB. Todas iguales salvo la última (lo exige R2).
- Solo se firman partes que existen en esa subida (1 a `partCount`).
- Cada lote firmado y cada consulta de partes cuentan como señal de vida
  (`upload_renewed_at`): una subida lenta no se da por abandonada mientras siga.
- Cerrar exige **todas** las partes y que **sumen exactamente** lo declarado.
- Un `PUT` entero (`POST /media/{id}/presign`) sobre una subida por partes se
  rechaza: la pisaría a medias.
- Una subida abandonada: el limpiador (`MediaOrphanWorker` →
  `MediaAssetReconciler`) **aborta en R2** antes de borrar la fila. Si R2 no
  contesta, la fila se queda y se reintenta, para no perder el id.

## Configuración

| Variable | Por defecto | Qué es |
|---|---|---|
| `MEDIA_MULTIPART_MODO` | `APAGADA` | `APAGADA`, `SIN_LIMITES` (solo organizaciones sin límites) o `TODAS` |
| `MEDIA_MULTIPART_DESDE_BYTES` | 100 MB | Desde qué tamaño se sube por partes |
| `MEDIA_MULTIPART_TAMANO_PARTE_BYTES` | 16 MiB | Tamaño de cada parte |
| `MEDIA_MULTIPART_TTL_PARTE_SEGUNDOS` | 3600 | Cuánto vale el permiso de cada parte |

La web y la app no deciden: leen `multipartFromBytes` de `GET /media/usage`.
Si es nulo, suben como siempre. **Apagar** es poner `MEDIA_MULTIPART_MODO=APAGADA`
en `.env.vps` y recrear la API, sin desplegar la web ni la app.

## R2: lo comprobado

Probado el 27 oct 2026 contra `meyi-imagenes`, con un archivo de 10 MiB en
`pruebas/` que se borró al terminar:

- R2 **acepta `UploadPart` firmado**, aunque su página de URLs firmadas solo
  lista `GET`, `HEAD`, `PUT` y `DELETE` de objetos completos.
- El preflight desde `https://picale.click` contesta 204 y cada parte devuelve
  `ETag` con `Access-Control-Expose-Headers: ETag`. **La CORS del bucket ya
  sirve**: la regla de `PUT` para picale.click expone `ETag`.
- El bucket tiene la regla "Default Multipart Abort Rule": aborta las subidas
  incompletas a los 7 días. Es la red de seguridad si algo se escapa.
- Límites de R2: partes de 5 MiB a 5 GiB, máximo 10,000, objeto de hasta 5 TiB.

## Puesta en marcha

1. Desplegar la API con `MEDIA_MULTIPART_MODO=APAGADA`. Respaldar la base antes:
   agrega dos columnas a `media_assets` (`multipart_upload_id`,
   `multipart_part_size`).
2. Web: subida por partes en `media.service` (trozos, 4 en paralelo,
   reintentos, retomar, cancelar).
3. `MEDIA_MULTIPART_MODO=SIN_LIMITES` y probar con una cuenta de la casa: 1 GB,
   3 GB, cortando la red a la mitad y recargando la página.
4. `TODAS`.
5. Cuando la app también suba por partes, volver a bajar
   `MEDIA_PRESIGN_TTL_SECONDS` a 1800 y `MEDIA_ORPHAN_AFTER_MINUTES` a 60.

## Diagnóstico

Una subida atorada es una fila `PENDING` con `multipart_upload_id`:

```sql
select id, file_name, size_bytes, multipart_part_size, created_at, upload_renewed_at
from media_assets where status = 'PENDING' and multipart_upload_id is not null;
```

Qué partes tiene en R2 (desde la VM, con las credenciales de `.env.vps`):

```
aws s3api list-parts --bucket meyi-imagenes --key <storage_key> --upload-id <multipart_upload_id>
```

Los logs dicen `Subida por partes abierta`, `completada`, `cancelada` o
`R2 no unió las partes`, con el id del archivo.

## Pruebas

`SubidaPorPartesTest` (17): abrir, interruptor apagado y por cuenta, tope por
archivo, firmar lotes y límites, subida ajena, PUT encima, retomar, unir, unir
dos veces, faltan partes, suma distinta, cancelar, abandonada con y sin R2, y el
cálculo de partes. R2 va simulado: el `.env` local apunta al bucket de
producción y ninguna prueba debe tocarlo.
