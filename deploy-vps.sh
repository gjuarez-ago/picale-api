#!/usr/bin/env bash
# Despliegue de metricol.api en la VM api-videos-prod, donde ya viven
# direccion360 y api-videos.
#
# Se ejecuta desde esta carpeta, en tu maquina. Lo que hace:
#   1. Empaqueta el codigo fuente (sin target/, sin .env locales).
#   2. Lo copia a la VM por scp.
#   3. Construye la imagen y levanta el contenedor alli.
#
# NO toca nginx ni Cloudflare: eso va una sola vez y a mano, porque un error
# ahi tumba tambien a direccion360, que comparte ese nginx.
#
# Requisitos, una sola vez antes del primer despliegue:
#   - La base existe:  gcloud sql databases create metricol --instance=cmrg
#   - El usuario existe y su clave la conoces:
#       gcloud sql users create metricol --instance=cmrg --password='...'
#   - En la VM, ~/metricol.api/.env.vps relleno a partir de .env.vps.example
set -euo pipefail

VM="api-videos-prod"
ZONA="us-central1-a"
PROYECTO="cmrg-505321"
DESTINO="~/metricol.api"

echo "==> Empaquetando el codigo"
# Se excluye target/ porque la imagen compila dentro: mandar 71 MB de jar por
# la red para luego no usarlo es tiempo tirado. Y .env se queda fuera a
# proposito: las credenciales del servidor son las de .env.vps, que vive
# SOLO en la VM y no se sobreescribe desde aqui.
tar --exclude=target \
    --exclude=.git \
    --exclude=.env \
    --exclude=uploads \
    --exclude='*.log' \
    -czf /tmp/metricol-api.tar.gz .

echo "==> Copiando a $VM"
gcloud compute scp /tmp/metricol-api.tar.gz "$VM":/tmp/ \
    --zone="$ZONA" --project="$PROYECTO"

echo "==> Construyendo y levantando en la VM"
gcloud compute ssh "$VM" --zone="$ZONA" --project="$PROYECTO" --command "
set -e
mkdir -p $DESTINO
# El .env.vps se conserva: es el unico archivo que vive solo en el servidor.
tar -xzf /tmp/metricol-api.tar.gz -C $DESTINO
cd $DESTINO

if [ ! -f .env.vps ]; then
  echo 'FALTA .env.vps en la VM. Copia .env.vps.example y rellenalo.' >&2
  exit 1
fi

# El --env-file no es adorno: sin el, DB_NAME no llega a donde hace falta.
# La clave env_file del compose mete las variables DENTRO del contenedor, pero
# la que aparece en SPRING_DATASOURCE_URL la resuelve compose ANTES de arrancar
# nada, y para eso lee el archivo .env —que aqui no existe, el tar lo excluye—
# y no .env.vps. Sin el flag, cambiar DB_NAME en .env.vps no cambia nada: la
# API se levanta contra metricol mientras uno cree que fue a la base nueva.
sudo docker compose --env-file .env.vps -f docker-compose.vps.yml up -d --build
sudo docker compose --env-file .env.vps -f docker-compose.vps.yml ps
"

echo "==> Comprobando que responde por dentro"
# Se espera en vez de preguntar una vez: el contenedor arranca en un segundo
# pero Spring tarda cerca de treinta en levantar, y preguntar justo despues
# devuelve 000 y hace pensar que el despliegue fallo cuando iba bien.
#
# 401 es la respuesta correcta y sana: la API esta viva y pide sesion.
gcloud compute ssh "$VM" --zone="$ZONA" --project="$PROYECTO" --command "
for i in \$(seq 1 30); do
  codigo=\$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18085/api/v1/posts || true)
  if [ \"\$codigo\" != '000' ]; then
    echo \"localhost:18085 -> \$codigo\"
    exit 0
  fi
  sleep 3
done
echo 'La API no contesto en 90 s. Revisa: sudo docker logs metricol-api' >&2
exit 1
"

echo "==> Listo. Si es el primer despliegue, falta nginx y el DNS de Cloudflare."
