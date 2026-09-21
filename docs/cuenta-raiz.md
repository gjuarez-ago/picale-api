# Cuenta raíz (la de la casa)

La cuenta con la que Pícale se usa a sí mismo: **sin límites ni vigencia** y con la **marca de Pícale**
ya llena. Es una organización aparte (`Super Admin`) que nace al arrancar la API.

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

La bandera **no tiene endpoint**: solo la enciende el arranque (`CuentaRaizService`), para que nadie
pueda dársela a sí mismo.

## Cómo se crea

Reutiliza la cuenta de demostración (`DemoAccountInitializer`), con variables **en el servidor**
(`.env.vps`), nunca en el repositorio:

```
DEMO_ACCOUNT_EMAIL=demo@picale.click
DEMO_ACCOUNT_PASSWORD=<la contraseña>
DEMO_ACCOUNT_ORGANIZATION=Super Admin
DEMO_ACCOUNT_ROOT=true
```

Al arrancar, si esa cuenta **no existe**, se registra como cualquiera (mismo `AuthService.register`),
su negocio se llama **Pícale**, su organización **Super Admin**, se enciende la bandera y se llena la
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

Apagar la bandera a mano en la base (`update organizations set sin_limites=false where name='Super Admin'`)
la devuelve al régimen normal.
