#!/usr/bin/env bash
# Pasa el sitio de Picale a https://picale.click (Cloudflare Pages) y deja la VM
# exponiendo SOLO la API en picale.rodtech.cloud.
#
# Correlo DESPUES de conectar picale.click a Pages (Custom domains) y de dar de
# alta el dominio en Google. Antes de tocar nada comprueba que el sitio nuevo
# responde; si algo falla, se detiene sin cambiar nada.
#
# Lo que hace, en orden:
#   1. Comprueba https://picale.click: paginas, archivos estaticos y la API por
#      el proxy de Pages.
#   2. Guarda el nginx actual (picale.conf.antes-cutover-<fecha>), instala
#      nginx/metricol.conf (solo API; lo demas, 301 a picale.click), prueba con
#      `nginx -t` y recarga. Si la prueba falla, restaura el anterior.
#   3. En ~/metricol.api/.env.vps pone WEB_URL y APP_BASE_URL = https://picale.click
#      (enlaces de los correos, regreso de Stripe y puente de regreso a la app),
#      con copia del .env, y recrea el contenedor de la API.
#   4. Comprueba que la API sigue respondiendo en picale.rodtech.cloud y que
#      la ruta vieja del sitio ya redirige.
#
# No borra nada: /var/www/picale y /var/www/picale.anterior se quedan en disco.
#
# Uso:   bash cutover-picale-click.sh
set -euo pipefail

VM="api-videos-prod"
ZONA="us-central1-a"
PROYECTO="cmrg-505321"
SITIO="https://picale.click"
API="https://picale.rodtech.cloud"

falla() { echo "ERROR: $*" >&2; exit 1; }

# ---------------------------------------------------------------- 1. el sitio nuevo responde
echo "==> Comprobando $SITIO"
for ruta in / /planes /privacidad /terminos /eliminar-cuenta /registro /social-connected.html; do
  codigo="$(curl -s -L -o /dev/null -w '%{http_code}' "$SITIO$ruta" || true)"
  printf '   %-22s %s\n' "$ruta" "$codigo"
  [ "$codigo" = "200" ] || falla "$SITIO$ruta contesta $codigo. Conecta el dominio en Pages y vuelve a intentar."
done
tipo="$(curl -s -o /dev/null -w '%{content_type}' "$SITIO/robots.txt" || true)"
case "$tipo" in text/plain*) ;; *) falla "/robots.txt no llega como archivo ($tipo): revisa public/_redirects." ;; esac
planes="$(curl -s "$SITIO/api/v1/billing/plans" || true)"
case "$planes" in *'"ok":true'*) echo "   /api por el proxy      ok" ;; *) falla "$SITIO/api no llega a la API (¿falta API_ORIGIN en Pages?)." ;; esac

# ---------------------------------------------------------------- 2. nginx
echo "==> Instalando el nginx de solo API"
DIR="$(cd "$(dirname "$0")" && pwd)"
[ -f "$DIR/nginx/metricol.conf" ] || falla "no encuentro nginx/metricol.conf"
gcloud compute scp "$DIR/nginx/metricol.conf" "$VM:picale.conf.nuevo" --zone "$ZONA" --project "$PROYECTO"

gcloud compute ssh "$VM" --zone "$ZONA" --project "$PROYECTO" --command '
set -euo pipefail
DEST=/etc/nginx/sites-available/picale.conf
COPIA="$DEST.antes-cutover-$(date +%F-%H%M%S)"
sudo cp "$DEST" "$COPIA"
sudo cp "$HOME/picale.conf.nuevo" "$DEST"
rm -f "$HOME/picale.conf.nuevo"
if sudo nginx -t; then
  sudo systemctl reload nginx
  echo "nginx recargado (copia del anterior: $COPIA)"
else
  echo "nginx -t fallo: se restaura el conf anterior" >&2
  sudo cp "$COPIA" "$DEST"
  sudo nginx -t
  exit 1
fi
'

# ---------------------------------------------------------------- 3. direcciones del servidor
echo "==> WEB_URL y APP_BASE_URL -> $SITIO"
gcloud compute ssh "$VM" --zone "$ZONA" --project "$PROYECTO" --command "
set -euo pipefail
cd \"\$HOME/metricol.api\"
cp .env.vps \".env.vps.antes-cutover-\$(date +%F-%H%M%S)\"
[ -z \"\$(tail -c1 .env.vps)\" ] || echo >> .env.vps
poner() {
  if grep -q \"^\$1=\" .env.vps; then sed -i \"s|^\$1=.*|\$1=\$2|\" .env.vps; else printf '%s=%s\n' \"\$1\" \"\$2\" >> .env.vps; fi
}
poner WEB_URL $SITIO
poner APP_BASE_URL $SITIO
chmod 600 .env.vps
echo '--> Recreando el contenedor de la API'
sudo docker compose --project-directory . --env-file .env.vps -f docker-compose.vps.yml up -d
"

echo "==> Esperando a que arranque la API"
sleep 35

# ---------------------------------------------------------------- 4. comprobacion final
echo "==> Comprobando"
printf '   %-40s %s\n' "$API/api/v1/billing/plans" "$(curl -s -o /dev/null -w '%{http_code}' "$API/api/v1/billing/plans")"
printf '   %-40s %s\n' "$SITIO/api/v1/billing/plans" "$(curl -s -o /dev/null -w '%{http_code}' "$SITIO/api/v1/billing/plans")"
printf '   %-40s %s -> %s\n' "$API/privacidad (ruta vieja)" \
  "$(curl -s -o /dev/null -w '%{http_code}' "$API/privacidad")" "$(curl -s -o /dev/null -w '%{redirect_url}' "$API/privacidad")"

echo
echo "Listo. El sitio vive en $SITIO y picale.rodtech.cloud solo atiende la API."
echo "Para volver atras: restaurar /etc/nginx/sites-available/picale.conf.antes-cutover-* y el"
echo ".env.vps.antes-cutover-* en la VM, y recrear el contenedor."
