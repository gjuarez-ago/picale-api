#!/usr/bin/env bash
# Configura el correo saliente (Resend por SMTP) en la VM api-videos-prod.
#
# Pone MAIL_PASSWORD (la llave de Resend) y MAIL_FROM (el remitente) en
# ~/metricol.api/.env.vps, recrea el contenedor para que las lea y comprueba
# que llegaron. Opcionalmente pide un envio de prueba.
#
# La llave NO se pasa por la linea de comandos ni por el chat: se pide aqui con
# la escritura oculta, se guarda en un archivo temporal con permiso 600 que
# viaja por scp, y se borra en cuanto se usa. Asi no queda en el historial ni en
# la lista de procesos de ninguna de las dos maquinas.
#
# Antes de correrlo, en Resend:
#   1. Domains -> Add Domain, y agregar en Cloudflare los registros DNS que
#      pide (nube en gris, "DNS only") hasta que diga "Verified".
#   2. API Keys -> Create, con permiso de envio y limitada a ese dominio.
#
# Uso:   bash configurar-correo.sh
# Tambien: MAIL_FROM=notificaciones@picale.click bash configurar-correo.sh
set -euo pipefail

VM="api-videos-prod"
ZONA="us-central1-a"
PROYECTO="cmrg-505321"
REMITENTE_POR_DEFECTO="notificaciones@rodtech.cloud"

# ---------------------------------------------------------------- datos
DE="${MAIL_FROM:-}"
if [ -z "$DE" ]; then
  read -r -p "Remitente [$REMITENTE_POR_DEFECTO]: " DE
  DE="${DE:-$REMITENTE_POR_DEFECTO}"
fi
# El dominio del remitente tiene que estar verificado en Resend.
if ! [[ "$DE" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]]; then
  echo "ERROR: '$DE' no parece un correo valido." >&2
  exit 1
fi

read -r -s -p "Llave de Resend (empieza con re_; no se muestra): " LLAVE
echo
# Solo letras, numeros y guion bajo: lo que Resend emite, y lo que hace seguro
# escribirla en el .env sin escapar nada.
if ! [[ "$LLAVE" =~ ^re_[A-Za-z0-9_]{16,}$ ]]; then
  echo "ERROR: la llave no tiene el formato de Resend (re_...)." >&2
  exit 1
fi

read -r -p "Correo para probar el envio (Enter para omitir): " PRUEBA
if [ -n "$PRUEBA" ] && ! [[ "$PRUEBA" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]]; then
  echo "ERROR: '$PRUEBA' no parece un correo valido." >&2
  exit 1
fi

# ---------------------------------------------------------------- viaje
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
chmod 600 "$TMP"
{
  printf 'MAIL_PASSWORD=%s\n' "$LLAVE"
  printf 'MAIL_FROM=%s\n' "$DE"
} > "$TMP"
unset LLAVE

echo "==> Copiando a la VM"
# Ruta relativa: gcloud en Windows no expande "~" y crearia una carpeta con ese nombre.
gcloud compute scp "$TMP" "$VM:correo.env.tmp" --zone "$ZONA" --project "$PROYECTO"

echo "==> Aplicando en la VM"
gcloud compute ssh "$VM" --zone "$ZONA" --project "$PROYECTO" --command "
set -euo pipefail
cd \"\$HOME/metricol.api\"
NUEVAS=\"\$HOME/correo.env.tmp\"
trap 'rm -f \"\$NUEVAS\"' EXIT

# Copia del .env por si hay que volver atras.
cp .env.vps \".env.vps.antes-correo-\$(date +%F-%H%M%S)\"

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

gcloud compute ssh "$VM" --zone "$ZONA" --project "$PROYECTO" --command "
echo \"estado:     \$(sudo docker ps --filter name=metricol-api --format '{{.Status}}')\"
sudo docker exec metricol-api sh -c 'if [ -n \"\$MAIL_PASSWORD\" ]; then echo \"MAIL_PASSWORD: definida\"; else echo \"MAIL_PASSWORD: VACIA\"; fi; echo \"MAIL_FROM:     \$MAIL_FROM\"'
"

# ---------------------------------------------------------------- prueba
if [ -n "$PRUEBA" ]; then
  echo "==> Pidiendo un codigo de recuperacion para $PRUEBA"
  echo "    (solo sale si ese correo tiene cuenta en Picale)"
  gcloud compute ssh "$VM" --zone "$ZONA" --project "$PROYECTO" --command "
curl -s -o /dev/null -w 'respuesta: %{http_code}\n' -X POST http://127.0.0.1:18085/api/v1/auth/forgot-password \
  -H 'Content-Type: application/json' -d '{\"email\":\"$PRUEBA\"}'
sleep 6
echo '--- log del envio:'
sudo docker logs --since 1m metricol-api 2>&1 | grep -iE 'correo|recuperacion|resend|mail' | cut -c1-200 || echo '(sin lineas de correo: buena senal si el correo llego)'
"
  echo
  echo "Revisa la bandeja (y spam). Si aparece 'No se pudo enviar', casi siempre es"
  echo "el dominio sin verificar en Resend o una llave equivocada."
fi

echo
echo "Listo. Copia del .env anterior: ~/metricol.api/.env.vps.antes-correo-* en la VM."
