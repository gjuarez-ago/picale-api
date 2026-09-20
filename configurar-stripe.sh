#!/usr/bin/env bash
# Configura los cobros con Stripe en la VM api-videos-prod.
#
# Pone STRIPE_SECRET_KEY y STRIPE_WEBHOOK_SECRET en
# ~/metricol.api/.env.vps, recrea el contenedor para que las lea y comprueba que
# llegaron (y en que modo: prueba o real).
#
# Las llaves NO se pasan por la linea de comandos ni por el chat: se piden aqui
# con la escritura oculta, viajan en un archivo temporal con permiso 600 por scp
# y se borra en cuanto se usa.
#
# Empieza SIEMPRE con las de prueba (sk_test_): mueven dinero de mentira y se
# pueden borrar y rehacer sin consecuencias. Las reales (sk_live_) piden una
# confirmacion aparte.
#
# Antes de correrlo, en https://dashboard.stripe.com (con "Modo de prueba"):
#   1. Developers > API keys: copia la clave secreta (sk_test_...).
#      Los productos y precios NO se crean a mano: la API crea los productos la
#      primera vez que vende algo, y el monto sale de la tabla billing_settings.
#   2. Developers > Webhooks > Add endpoint:
#        URL:     https://picale.rodtech.cloud/api/v1/billing/webhook
#        Eventos: checkout.session.completed, customer.subscription.updated,
#                 customer.subscription.deleted, invoice.paid,
#                 invoice.payment_failed
#      Copia el "Signing secret" (whsec_...).
#   3. Settings > Billing > Customer portal: activalo, para que cada quien
#      cambie su tarjeta o cancele sin escribirnos.
#
# Uso:   bash configurar-stripe.sh
# Cada dato se puede omitir con Enter para dejar el que ya haya en la VM.
set -euo pipefail

VM="api-videos-prod"
ZONA="us-central1-a"
PROYECTO="cmrg-505321"

# ---------------------------------------------------------------- datos
read -r -s -p "Clave secreta (sk_test_... / sk_live_...; Enter = dejar la actual): " CLAVE
echo
if [ -n "$CLAVE" ]; then
  if ! [[ "$CLAVE" =~ ^(sk|rk)_(test|live)_[A-Za-z0-9]{20,}$ ]]; then
    echo "ERROR: la clave no tiene el formato de Stripe (sk_test_... / sk_live_...)." >&2
    exit 1
  fi
  if [[ "$CLAVE" == *_live_* ]]; then
    echo
    echo "ATENCION: es una clave REAL. Con ella los cobros son de verdad."
    read -r -p "Escribe SI para continuar: " CONFIRMA
    if [ "$CONFIRMA" != "SI" ]; then
      echo "Cancelado. Prueba primero con la clave sk_test_."
      exit 1
    fi
  fi
fi

read -r -s -p "Secreto del webhook (whsec_...; Enter = dejar el actual): " WEBHOOK
echo
if [ -n "$WEBHOOK" ] && ! [[ "$WEBHOOK" =~ ^whsec_[A-Za-z0-9]{16,}$ ]]; then
  echo "ERROR: el secreto no tiene el formato de Stripe (whsec_...)." >&2
  exit 1
fi

if [ -z "$CLAVE$WEBHOOK" ]; then
  echo "No hay nada que cambiar."
  exit 0
fi

# ---------------------------------------------------------------- viaje
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
chmod 600 "$TMP"
{
  [ -n "$CLAVE" ] && printf 'STRIPE_SECRET_KEY=%s\n' "$CLAVE"
  [ -n "$WEBHOOK" ] && printf 'STRIPE_WEBHOOK_SECRET=%s\n' "$WEBHOOK"
  true
} > "$TMP"
unset CLAVE WEBHOOK

echo "==> Copiando a la VM"
# Ruta relativa: gcloud en Windows no expande "~" y crearia una carpeta con ese nombre.
gcloud compute scp "$TMP" "$VM:stripe.env.tmp" --zone "$ZONA" --project "$PROYECTO"

echo "==> Aplicando en la VM"
gcloud compute ssh "$VM" --zone "$ZONA" --project "$PROYECTO" --command "
set -euo pipefail
cd \"\$HOME/metricol.api\"
NUEVAS=\"\$HOME/stripe.env.tmp\"
trap 'rm -f \"\$NUEVAS\"' EXIT

# Copia del .env por si hay que volver atras.
cp .env.vps \".env.vps.antes-stripe-\$(date +%F-%H%M%S)\"

# Que el archivo termine en salto de linea antes de agregar.
[ -z \"\$(tail -c1 .env.vps)\" ] || echo >> .env.vps

poner() {
  clave=\"\${1%%=*}\"
  if grep -q \"^\$clave=\" .env.vps; then
    sed -i \"s|^\$clave=.*|\$1|\" .env.vps
  else
    printf '%s\n' \"\$1\" >> .env.vps
  fi
}
while IFS= read -r linea; do poner \"\$linea\"; done < \"\$NUEVAS\"

# Este archivo tiene la llave de la base, el JWT y las de los proveedores: no
# tiene por que leerlo nadie mas que quien lo administra.
chmod 600 .env.vps

echo '--> Recreando el contenedor'
sudo docker compose --project-directory . --env-file .env.vps -f docker-compose.vps.yml up -d
"

echo "==> Esperando a que arranque"
sleep 30

# Solo se muestra si esta definida y el PREFIJO de la clave (sk_test_ / sk_live_),
# que dice el modo y no es secreto.
gcloud compute ssh "$VM" --zone "$ZONA" --project "$PROYECTO" --command "
echo \"estado: \$(sudo docker ps --filter name=metricol-api --format '{{.Status}}')\"
sudo docker exec metricol-api sh -c '
  if [ -n \"\$STRIPE_SECRET_KEY\" ]; then
    modo=prueba; case \"\$STRIPE_SECRET_KEY\" in *_live_*) modo=REAL;; esac
    echo \"STRIPE_SECRET_KEY:      definida (modo \$modo)\"
  else echo \"STRIPE_SECRET_KEY:      VACIA (cobros apagados)\"; fi
  if [ -n \"\$STRIPE_WEBHOOK_SECRET\" ]; then echo \"STRIPE_WEBHOOK_SECRET:  definido\"; else echo \"STRIPE_WEBHOOK_SECRET:  VACIO\"; fi
'
"

echo
echo "Listo. Copia del .env anterior: ~/metricol.api/.env.vps.antes-stripe-* en la VM."
