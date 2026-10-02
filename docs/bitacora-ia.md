# Bitácora de acciones de la IA

Qué hicieron las IA conectadas (Claude, ChatGPT, Claude Code…) en cada cuenta,
en nombre de quién y con qué cliente. Existe para que una agencia pueda
contestar "¿quién programó esto?" y "¿qué hizo Claude esta semana en la cuenta
de Tacos El Güero?".

## Cómo se anota

El servidor MCP (`picale-mcp`) manda en cada llamada a la API dos cabeceras:

| Cabecera | Valor |
|---|---|
| `X-Picale-Origen` | `mcp` |
| `X-Picale-Cliente` | el nombre con el que el cliente de IA se presentó al conectarse: `Claude`, `ChatGPT`, `Claude Code`… |

`BitacoraIaFilter` (después de Spring Security, con la prioridad más baja)
envuelve las peticiones de escritura (`POST`, `PUT`, `PATCH`, `DELETE` bajo
`/api/v1/`, salvo `/auth/**`) que traen la primera cabecera, deja que la API
las atienda y, si contestó 2xx, guarda una fila en `acciones_ia` con:

- la cuenta sobre la que se actuó (la del usuario, o la de la ruta en
  `/agente/cuentas/{cuentaId}/…`), el usuario y su correo;
- origen y cliente;
- la **acción** en código, deducida de método, ruta y cuerpo por
  `AccionesIa.clasificar`: `CREAR_BORRADOR`, `PROGRAMAR_PUBLICACION`,
  `PUBLICAR_AHORA`, `APROBAR_PROPUESTA`, `ACTUALIZAR_MARCA`,
  `GENERAR_CONTENIDO`, `AGENTE_PAUSAR`… y `OTRA` para lo que no esté en la
  tabla (siempre con método y ruta);
- la entidad (el UUID de la ruta, o el `result.id` de la respuesta) y un
  **detalle** legible: el texto de la publicación, el archivo, la fecha, el
  cambio pedido.

Lo que la API rechazó no se anota: no cambió nada. Un fallo al anotar se
registra en el log y la respuesta sale igual: la bitácora nunca es la razón de
que algo no se guarde. Del cuerpo se guardan hasta 64 KB (lo que ocupa un
JSON): una subida de 25 MB no se copia en memoria por una anotación.

La app y el panel web no se anotan: lo que hace una persona a mano ya tiene su
rastro en las publicaciones; lo que hace una IA en su nombre, no lo tenía.

## Leerla

- `GET /api/v1/bitacora-ia?dias=30&limite=100` — las acciones en la cuenta
  actual, la más reciente primero (`dias` 1 a 365, `limite` 1 a 500).
- `GET /api/v1/bitacora-ia/{entidadId}` — el rastro de una publicación o un
  archivo.

Cada fila trae `cuando`, `origen`, `cliente`, `usuario`, `accion`,
`descripcion` ("Programó una publicación"), `entidadId`, `detalle`, `metodo` y
`ruta`. Ver siempre se puede: quien está en la cuenta tiene derecho a saber
qué hizo la IA en ella.

En el MCP, la herramienta `bitacora_ia` y la sección "Hecho por IA" de `panel`
leen de aquí, así que la propia IA puede rendir cuentas de lo que hizo.

## Al desplegar

Tabla nueva `acciones_ia` (la crea Hibernate con `ddl-auto=update`). Como con
cualquier cambio de esquema, respaldar la base antes. Las API anteriores a
este cambio ignoran las cabeceras sin error: el MCP funciona igual, solo que
sin bitácora (su herramienta lo dice).

## Siguiente

- Pantalla "Actividad de la IA" en el panel web y en la app, con filtro por
  cliente y por tipo.
- Anotar también el origen `app` y `web` si algún día hace falta un rastro
  completo (hoy el filtro ya lo admite: basta mandar la cabecera).
