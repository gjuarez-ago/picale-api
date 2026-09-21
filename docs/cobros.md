# Cobros: licencias, créditos y Stripe

Estado: implementado y **apagado por omisión** (`billing.enabled = false`). Con los
cobros apagados la aplicación se comporta como siempre: nadie paga, nadie se archiva.

## El modelo

- **Una licencia por espacio de trabajo** (un negocio). Es una suscripción mensual de Stripe.
- **Primer negocio** de una organización: precio completo (hoy $349 MXN). **Cada negocio
  adicional**: precio menor (hoy $249). Los precios **incluyen el IVA**.
- **Prueba gratis** de 14 días, sin tarjeta, para todos: al registrarse y también para los
  espacios que ya existían al encender los cobros. Pasados los 14 días, a pagar.
- **Contratar en plena prueba SUMA, no reemplaza.** Si alguien paga con días de prueba por delante,
  esos días se suman al final del mes que pagó, y los créditos de imagen que le sobraban se suman
  a los 5 del mes. En Stripe se hace moviendo el fin de la prueba de la suscripción (`trial_end`,
  sin prorrateo) cuando llega la primera factura pagada; la de $0 que Stripe genera por ese cambio
  (`billing_reason=subscription_update`) se ignora para no reiniciar los créditos. **Probar en modo de
  prueba de Stripe antes de encender los cobros reales**: contratar con N días por delante y comprobar
  que la próxima cobranza cae N días después del mes pagado.
- **Créditos de imagen**: 1 crédito = 1 generación (con todas sus versiones). La licencia trae
  5 al mes (no se acumulan, terminan con el periodo). Los **paquetes** (10 / 25 / 50) no vencen.
  Si una generación no produce ninguna versión, el crédito se devuelve.
- **Cancelar** apaga la renovación: se usa hasta el fin de lo pagado. **Sin reembolsos.**
- **Cobro fallido**: 7 días de gracia; pasados, el espacio se **archiva** (nunca se borra) y al
  pagar se restaura.
- **Todo se paga desde la web.** La app móvil solo muestra el estado.
- La **factura (CFDI)** no la emite Stripe en México; la emite el operador por su cuenta.

## Cómo se cambia todo (sin desplegar)

Todo vive en la tabla `billing_settings` y en `credit_packs`, con una caché de 30 s. Se
cambia con la llave de operación (`X-Ops-Key`, la misma de `/api/v1/ops`):

```
GET  /api/v1/ops/billing/settings            todos los ajustes
PUT  /api/v1/ops/billing/settings/{clave}    body: {"valor": "..."}
GET  /api/v1/ops/billing/packs               paquetes de créditos
PUT  /api/v1/ops/billing/packs/{codigo}      body: {"name","credits","priceMinor","active","sortOrder"}
```

| Ajuste | Por omisión | Qué es |
|---|---|---|
| `billing.enabled` | `false` | Enciende los cobros. |
| `billing.currency` | `mxn` | Moneda. |
| `billing.tax_included` | `true` | Los precios ya traen IVA. Solo cambia el texto (“IVA incluido” / “más IVA”). |
| `billing.list.license_minor` | `34900` | Precio del primer negocio, **en centavos**. |
| `billing.list.extra_minor` | `24900` | Precio de cada negocio adicional, en centavos. |
| `billing.trial_days.signup` | `14` | Días de prueba al registrarse. |
| `billing.trial_days.existing` | `30` | Días de prueba de los espacios que ya existían. |
| `billing.grace_days` | `7` | Días de gracia tras un cobro fallido. |
| `billing.credits.monthly_per_license` | `5` | Créditos al mes por licencia. |
| `billing.warn_days` | `5` | Días antes del final para avisar en el panel. |

Los precios van **en centavos** y se validan al guardar: no bajan de 1000 ($10.00, también el
mínimo de Stripe), así que escribir `349` en vez de `34900` se rechaza. Un cambio de precio
aplica a las compras nuevas; **quien ya está suscrito conserva el importe con el que entró**.

Ejemplos (con la llave en una variable de entorno, sin pegarla en el historial):

```bash
curl -X PUT https://picale.rodtech.cloud/api/v1/ops/billing/settings/billing.list.license_minor \
  -H "X-Ops-Key: $OPS_KEY" -H "Content-Type: application/json" -d '{"valor":"39900"}'

curl -X PUT https://picale.rodtech.cloud/api/v1/ops/billing/packs/PACK_10 \
  -H "X-Ops-Key: $OPS_KEY" -H "Content-Type: application/json" \
  -d '{"name":"Arranque","priceMinor":7900,"active":true}'
```

## Stripe: qué se configura y qué no

**No se crean productos ni precios a mano.** La API crea en Stripe un producto por cosa vendida,
con id fijo (`picale_licencia`, `picale_licencia_adicional`, `picale_paquete_<código>`), la
primera vez que la vende, y manda el monto **en línea** desde la tabla de ajustes.

Lo único que se hace en el panel de Stripe, en **modo de prueba** primero:

1. **Llave secreta** (`sk_test_...`): *Developers → API keys*.
2. **Webhook**: *Developers → Webhooks → Add endpoint* a
   `https://picale.rodtech.cloud/api/v1/billing/webhook`, con los eventos
   `checkout.session.completed`,
   `customer.subscription.updated`, `customer.subscription.deleted`, `invoice.paid` e
   `invoice.payment_failed` (si algún día se aceptan pagos asíncronos, también
   `checkout.session.async_payment_succeeded`). Copia su *Signing secret* (`whsec_...`).
3. **Portal de clientes**: *Settings → Billing → Customer portal*, activarlo y guardar (para
   que cada quien cambie su tarjeta o cancele).
4. En la máquina: `bash configurar-stripe.sh` (pide las dos llaves con la escritura oculta; nunca
   se pegan en el chat ni en la línea de comandos).

### Las llaves son de cada ambiente

Se declaran en el perfil de cada ambiente, bajo `app.stripe` (`application-dev.yml`,
`application-qa.yml`, `application-prod.yml`), leyendo `STRIPE_SECRET_KEY` y
`STRIPE_WEBHOOK_SECRET` del `.env` de **ese** ambiente. No hay default compartido en
`application.properties`. Dev y QA usan solo `sk_test_`; **`StripeModeGuard` no deja arrancar**
si encuentra una `sk_live_` fuera del perfil `prod`.

## Probar en modo de prueba

Tarjetas de prueba de Stripe: `4242 4242 4242 4242` (paga), `4000 0000 0000 0341` (se acepta al
contratar y falla al renovar), `4000 0000 0000 9995` (fondos insuficientes). Cualquier fecha
futura y CVC.

Lista de comprobación: (1) espacio nuevo → paga → nace el espacio, (2) el segundo se cobra al
precio adicional, (3) comprar un paquete suma créditos, (4) *No renovar* y *Mantener*,
(5) cobro fallido → aviso ámbar en el panel → tarjeta buena en el portal → desaparece,
(6) cancelar el primero deja al otro con el precio completo en su siguiente renovación.

## Cómo funciona por dentro

```
Web ──POST /billing/licenses/checkout──▶ BillingService ──▶ StripeClient.crearCompra
                                              │  (producto del catálogo + monto en línea,
                                              │   metadata: kind, organization_id, tier, ...)
Stripe Checkout ──(pago)──▶ POST /billing/webhook ──▶ StripeWebhookVerifier (HMAC, 300 s)
                                                        └▶ StripeEventProcessor
```

- **Nada se activa al pedir el pago**: licencias y créditos nacen cuando Stripe avisa que se pagó
  (`checkout.session.completed`, `invoice.paid`). Cada `evt_...` se recuerda (`stripe_events`) y lo
  que se otorga lleva su propia referencia única (`credit_movements`): nada se hace dos veces.
- Una factura que llega **antes** que su compra devuelve 409 para que Stripe reintente.
- **Licencias** (`licenses`): `TRIALING`, `ACTIVE`, `PAST_DUE`, `ENDED`. `LicenseSweepWorker`
  (cada 10 min) cubre a los espacios sin licencia, archiva los vencidos y restaura los que
  volvieron a pagar. Solo restaura los que archivó él.
- **Créditos** (`image_credits`, `credit_movements`): dos bolsas (mensual y de paquetes). Se gasta
  1 en `CampaignImageService.preparar` y se devuelve si no sale ninguna versión.
- **Precio adicional** (`LicensePricingService`): si una organización se queda con licencias
  pagadas pero ninguna al precio completo, la más antigua que siga renovándose pasa al precio
  completo en su próxima renovación (sin prorratear). Corre en el barrido.

## Avisos en el panel

`app-avisos-de-cobro` (web) muestra una franja por tipo: cobro fallido, licencia terminada,
prueba por terminar y licencia que no se renueva. Titular = qué pasó, detalle = qué queda, un
botón directo a Stripe, tono gris → ámbar → rojo, sin cerrar y oculta en Facturación.

## Apagar todo

`PUT /api/v1/ops/billing/settings/billing.enabled` con `{"valor":"false"}`. Los cobros vuelven a
estar apagados en menos de 30 s; las suscripciones ya creadas en Stripe siguen vivas y se cancelan
desde el panel de Stripe.

## Pendiente conocido

- Cobrar `sk_live_` (modo real) exige rehacer el webhook y la llave con los datos reales.
- La app móvil no vende (políticas de las tiendas): solo enseña el estado.
