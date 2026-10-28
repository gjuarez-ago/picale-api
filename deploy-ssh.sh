#!/usr/bin/env bash
# Despliegue de metricol.api en la VM api-videos-prod por SSH directo, sin gcloud.
#
# Hace lo mismo que deploy-vps.sh (empaquetar lo confirmado, construir en la VM,
# comprobar commit y base), pero entra con la llave que gcloud ya dejo en
# ~/.ssh/google_compute_engine. Existe porque gcloud a veces pide reautenticar y
# no se puede en una sesion no interactiva (ver memoria operar-produccion-vm).
#
# Uso:
#   bash deploy-ssh.sh               despliega el commit actual (HEAD)
#   bash deploy-ssh.sh --respaldo    antes, respalda la base en ~/respaldos/ de la VM
#
# Reglas que cumple siempre:
#   - Solo viaja lo CONFIRMADO (git archive HEAD): nada a medias del arbol de trabajo.
#   - --env-file .env.vps: sin el, la API arranca contra la base equivocada.
#   - La imagen que estaba corriendo se etiqueta metricol-api:previo-<sha> para volver atras.
#   - No se da por bueno hasta que /ops/version devuelve el sha nuevo y la base de .env.vps.
set -euo pipefail

VM="contacto_rodtech_cloud@34.46.181.129"
LLAVE="$HOME/.ssh/google_compute_engine"
DESTINO="/home/contacto_rodtech_cloud/metricol.api"
SSH=(ssh -i "$LLAVE" -o BatchMode=yes -o ConnectTimeout=20 "$VM")
SCP=(scp -i "$LLAVE" -o BatchMode=yes -o ConnectTimeout=20 -q)

RESPALDO=0
[ "${1:-}" = "--respaldo" ] && RESPALDO=1

cd "$(dirname "$0")"
SHA="$(git rev-parse HEAD)"
echo "==> Desplegando $(git log --oneline -1)"
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "    (hay cambios sin confirmar: NO viajan, solo el commit $SHA)"
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/src"
git archive HEAD | tar -x -C "$TMP/src"
echo "$SHA" > "$TMP/src/BUILD_SHA"
tar --exclude=target --exclude=.git --exclude=.github --exclude=.env --exclude=.env.vps \
    --exclude=uploads --exclude='*.log' -czf "$TMP/api.tar.gz" -C "$TMP/src" .

cat > "$TMP/remoto.sh" <<'REMOTO'
#!/bin/bash
set -e
SHA=$1; RESPALDO=$2; DESTINO=/home/contacto_rodtech_cloud/metricol.api; E=$DESTINO/.env.vps
sudo test -f $E || { echo "FALTA .env.vps"; exit 1; }
if [ "$RESPALDO" = 1 ]; then
  PW=$(sudo grep -E '^DB_PASS=' $E | cut -d= -f2-)
  ARCH=~/respaldos/picale-antes-${SHA:0:7}-$(date +%Y%m%d-%H%M).sql.gz
  mkdir -p ~/respaldos
  sudo docker run --rm --network metricolapi_default -e PGPASSWORD="$PW" postgres:18-alpine \
    pg_dump -h metricol-cloud-sql-proxy -U metricol -d picale_dev --no-owner | gzip > "$ARCH"
  gzip -t "$ARCH" && echo "respaldo integro: $ARCH ($(du -h "$ARCH" | cut -f1))"
fi
IMG=$(sudo docker inspect metricol-api --format '{{.Image}}' 2>/dev/null || true)
ANTERIOR=$(curl -s --max-time 5 http://127.0.0.1:18085/api/v1/ops/version | grep -oE '"sha":"[0-9a-f]{7}' | cut -d'"' -f4 || true)
[ -n "$IMG" ] && sudo docker tag "$IMG" "metricol-api:previo-${ANTERIOR:-desconocido}" && echo "imagen anterior: metricol-api:previo-${ANTERIOR:-desconocido}"
sudo rm -rf $DESTINO/src
sudo tar --no-same-owner -xzf /tmp/metricol-api-$SHA.tar.gz -C $DESTINO
rm -f /tmp/metricol-api-$SHA.tar.gz
DUENO=$(sudo stat -c %U $DESTINO); { [ "$DUENO" = UNKNOWN ] || [ -z "$DUENO" ]; } && DUENO=$(id -un)
sudo chown -R "$DUENO":"$DUENO" $DESTINO
sudo docker compose --project-directory $DESTINO --env-file $E -f $DESTINO/docker-compose.vps.yml up -d --build 2>&1 | tail -3
for i in $(seq 1 60); do
  r=$(curl -s --max-time 10 http://127.0.0.1:18085/api/v1/ops/version || true)
  echo "$r" | grep -q "$SHA" && break
  sleep 5
done
esperada=$(sudo grep -E '^DB_NAME=' $E | cut -d= -f2-)
echo "$r" | grep -q "\"sha\":\"$SHA\"" || { echo "ERROR: la API no responde con el sha nuevo"; exit 1; }
echo "$r" | grep -q "\"base\":\"$esperada\"" || { echo "ERROR: la API no usa la base $esperada"; exit 1; }
for i in $(seq 1 24); do s=$(sudo docker inspect metricol-api --format '{{.State.Health.Status}}'); [ "$s" = healthy ] && break; sleep 5; done
echo "salud: $s"
echo "errores al arrancar: $(sudo docker logs --since 3m metricol-api 2>&1 | grep -cE ' ERROR |APPLICATION FAILED' || true)"
echo "DESPLIEGUE OK: sha $SHA, base $esperada"
REMOTO
sed -i 's/\r$//' "$TMP/remoto.sh"

echo "==> Copiando a la VM"
"${SCP[@]}" "$TMP/api.tar.gz" "$VM:/tmp/metricol-api-$SHA.tar.gz"
"${SCP[@]}" "$TMP/remoto.sh" "$VM:/tmp/deploy-ssh-$SHA.sh"

echo "==> Construyendo y levantando (tarda unos minutos)"
"${SSH[@]}" "bash /tmp/deploy-ssh-$SHA.sh $SHA $RESPALDO; rm -f /tmp/deploy-ssh-$SHA.sh"
