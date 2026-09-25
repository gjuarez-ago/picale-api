# Cuenta raíz (la de la casa)

La cuenta con la que Pícale se usa a sí mismo: **sin límites ni vigencia** y con la **marca de Pícale**
ya llena. Es una organización aparte (`PICALE HUB`) que nace al arrancar la API.

## Qué significa «sin límites ni vigencia»

Una bandera en la organización (`organizations.sin_limites`). Con ella encendida, sus espacios:

| Qué | Qué pasa |
| --- | --- |
| Licencia | No se le crea prueba, el barrido de vencimientos no la toca, nunca se archiva. |
| Pagos y avisos | El resumen de facturación llega como con los cobros apagados: sin avisos de prueba ni de pago. |
| Créditos de imagen | No se gastan ni se agotan. |
| IA | Sin tope diario de llamadas ni de imágenes. |
| Publicaciones | Sin tope de pendientes ni por día, ni cupo diario por red, ni el global (y no le resta al de los demás). |
| Espacios | Sin tope de espacios por organización, y puede crearlos sin comprar licencia. |

Lo que **no** cambia: los límites propios de cada red social. Si Instagram o TikTok rechazan una
publicación por sus reglas, la cuenta raíz también se topa con eso.

La bandera la enciende el arranque (`CuentaRaizService`) y, desde la pantalla **Administración** de
la web, quien administra la plataforma (ver abajo). Ningún permiso de organización llega a ella.

## Cómo se crea

Reutiliza la cuenta de demostración (`DemoAccountInitializer`), con variables **en el servidor**
(`.env.vps`), nunca en el repositorio:

```
DEMO_ACCOUNT_EMAIL=demo@picale.click
DEMO_ACCOUNT_PASSWORD=<la contraseña>
DEMO_ACCOUNT_ORGANIZATION=PICALE HUB
DEMO_ACCOUNT_ROOT=true
```

Al arrancar, si esa cuenta **no existe**, se registra como cualquiera (mismo `AuthService.register`),
su negocio se llama **Pícale**, su organización **PICALE HUB**, se enciende la bandera y se llena la
marca. Si ya existe no se toca (ni contraseña ni bandera), así que reiniciar es seguro.

Sin `DEMO_ACCOUNT_ROOT=true` la cuenta demo funciona como siempre (la de Google Play).

## La marca de Pícale

Se siembra en `CuentaRaizService.marcaDePicale()` y se edita después desde **Mi marca** como
cualquier espacio. Sin precios ni cifras (cambian), y sin ciudad ni WhatsApp: no hay un dato real y
la IA no debe inventarlo. Conviene completarlos a mano.

## Al desplegar

Agrega la columna `organizations.sin_limites` (`boolean not null default false`), que Hibernate crea
solo (`ddl-auto=update`). Es un cambio de esquema: hacer antes un respaldo de la base
(`gcloud sql backups create --instance=cmrg --project=cmrg-505321`).

## Para quitarlo

Apagar la bandera a mano en la base (`update organizations set sin_limites=false where name='PICALE HUB'`)
la devuelve al régimen normal.

## Administrar la plataforma (super admin)

Aparte de «sin límites», la cuenta raíz **administra la plataforma**: una marca en el usuario
(`users.platform_admin`) que abre `/api/v1/root/**` y la pantalla **Administración** de la web
(`/panel/administracion`). Desde ahí se ve y se toca de **todas** las organizaciones:

| Qué | Cómo |
| --- | --- |
| Lista de organizaciones | Con dueño, espacios, personas, licencias vigentes y vencidas, situación de pago y próximo vencimiento. Filtro por situación y búsqueda por nombre o correo. |
| Detalle | Sus personas (papel en la organización), sus espacios y, de cada espacio, la licencia, los créditos y quién entra con qué rol. |
| Exentar de pago | El interruptor «Exenta de pago» enciende o apaga `sin_limites` en esa organización. |
| Cupo de espacios | Cambia `max_workspaces`. |
| Licencia | Estado (prueba, activa, pago pendiente, terminada) y hasta cuándo. Un espacio sin licencia recibe una nueva con sus créditos mensuales; terminarla archiva el espacio ya (sin borrar nada); dejarla vigente restaura el que archivó el barrido. Si la lleva Stripe, la pantalla avisa de que el siguiente aviso de Stripe puede pisar las fechas. |
| Créditos de imagen | Sumar o quitar; van a la bolsa de paquete (no vence). Queda un movimiento `ADJUSTMENT` con referencia `root:<usuario>:<uuid>`. |

Quién la tiene:

- La cuenta raíz (`DEMO_ACCOUNT_ROOT=true`): `convertir` la marca, y en cada arranque se asegura
  aunque la cuenta venga de un despliegue anterior a la columna.
- Cualquier otra cuenta a la que se la dé alguien que ya la tiene, desde la tarjeta **Quién administra
  la plataforma** de la pantalla Administración (`GET/POST/DELETE /api/v1/root/administradores`,
  `AdministradoresService`). Vive solo en la base: no hay variable de entorno ni se toca en el arranque.
  Tiene que ser una cuenta ya registrada (404 si no). No se puede quitar a la raíz (`ADMIN_ES_RAIZ`)
  ni quitarse uno mismo (`ADMIN_ES_USTED`), así que siempre queda al menos una. Dar o quitar vale desde
  la siguiente petición (el filtro JWT recarga al usuario) y queda en el log como `WARN`.

La primera vez, sin nadie más que la raíz, se da entrando con la cuenta raíz, o a mano:
`update users set platform_admin = true where email in ('…', '…');`

La marca viaja en `GET /me` como `root`; la web enseña el menú **Administración** solo con eso, y
el servidor exige la marca en cada petición (`RootAccessService`, 403 a los demás).

Al desplegar: la columna `users.platform_admin` (`boolean not null default false`) la crea Hibernate
(`ddl-auto=update`). Respaldar antes, como con cualquier cambio de esquema.
