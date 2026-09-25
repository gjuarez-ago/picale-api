# Contenido: archivar, eliminar y liberar

Lo que pasa con las fotos y videos de un espacio cuando alguien los quita, y qué
se lleva cada acción por delante. Aplica igual en la app móvil y en la web.

## Las tres acciones

| Acción | Qué hace | Libera espacio | Qué pasa con las publicaciones que lo usan |
| --- | --- | --- | --- |
| **Archivar** | Lo esconde de Contenido. Se puede devolver. | No | Nada. |
| **Eliminar** | Sale de R2 y su fila desaparece. No se deshace. | Sí | Se eliminan también (ver abajo). |
| **Liberar** (solo videos ya publicados) | El archivo sale de R2; la fila y la portada se quedan. | Sí | Nada: se siguen viendo con su portada y el video está entero en la red. |

## Eliminar un archivo en uso

`DELETE /api/v1/media/{id}` mira cuántas publicaciones **de la persona** usan el
archivo (las que ella eliminó ya no cuentan):

1. **Sin usos**: se elimina y contesta cuánto se liberó.
2. **Con usos y sin `conPublicaciones=true`**: contesta `409 MEDIA_IN_USE` con las
   cuentas ("se usa en 3 publicaciones, 1 todavía sin salir"). No toca nada. La
   pantalla pregunta con ese texto delante.
3. **Con usos y `conPublicaciones=true`**: exige además el permiso `POST_DELETE`, y
   las publicaciones que lo usan **se eliminan para la persona**: se marcan
   (`posts.deleted_at`), desaparecen de todas sus listas y las que estaban por
   salir se cancelan de la cola. Después se elimina el archivo.

Lo que la app le dice antes de confirmar: *"esas publicaciones también se
eliminan de Pícale: dejan de aparecer en tus listas, y las que todavía no han
salido ya no saldrán. Lo que ya se publicó en tus redes se queda ahí. No se puede
deshacer."*

La galería (`GET /api/v1/media`) trae por archivo `usos`, `usosSinSalir` y `enUso`
para que la pregunta salga con las cuentas correctas sin una llamada extra.

## Eliminar es marcar (para nosotros)

Una publicación eliminada **no se borra de la tabla**: se le pone `deleted_at`.
Para la persona no existe (lista, calendario, estados, dashboard, `GET /posts/{id}`
contesta 404, y la cola no la publica). Para nosotros sigue ahí con sus destinos y
sus motivos, así un aviso tardío del proveedor cierra sin ruido y se puede
contestar qué pasó con ella. `DELETE /api/v1/posts/{id}` sigue archivando; el
servicio `PostService.delete` es el que elimina así, y hoy lo usa el borrado de
contenido.

Volver a enseñar una eliminada es a mano: `update posts set deleted_at = null where id = ...`.
Lo que estaba en la cola ya quedó cancelado y no se recupera solo.

## Lo que NO se elimina desde Contenido

- **El logotipo del espacio** (`workspaces.logo_url`): contesta 409 y manda a Mi
  marca, que es donde se sabe qué lo reemplaza.
- **Un archivo a medio subir** (`PENDING`): de esos se ocupa `MediaOrphanWorker`.

## Los procesos automáticos (no cambian)

- `MediaSinUsarWorker`: borra lo que ninguna publicación viva usa tras 72 h. Un
  archivo que solo usaban publicaciones eliminadas cuenta como sin usar.
- `MediaPublicadoWorker`: libera los videos publicados hace más de 30 días.
- `MediaOrphanWorker`: limpia los prefirmados que nunca se confirmaron.
- Lo **archivado** no lo toca ningún proceso y sigue contando en la cuota.

## Permisos

- `MEDIA_DELETE`: archivar, eliminar y liberar.
- `POST_DELETE` además, cuando eliminar se lleva publicaciones.

## Al desplegar

Columna nueva `posts.deleted_at` (timestamp, nula). La crea Hibernate
(`ddl-auto=update`); respaldar la base antes como con cualquier cambio de esquema.
