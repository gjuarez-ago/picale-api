#!/usr/bin/env bash
# Fase 1: construir y probar la imagen nueva SIN tocar lo que está sirviendo.
# Uso: bash fase1-construir.sh <sha-completo>
set -euo pipefail
SHA="$1"
S12="${SHA:0:12}"
LIVE=/home/contacto_rodtech_cloud/metricol.api
RUN=/home/contacto_rodtech_cloud/entregas/letreros-$S12
IMAGEN=metricol-api:letreros-$S12

sudo mkdir -p "$RUN/fuente" "$RUN/prueba"
sudo chown -R "$(id -un)":"$(id -un)" "$RUN"
rm -rf "$RUN/fuente"/* "$RUN/fuente"/.[!.]* 2>/dev/null || true
tar --no-same-owner -xzf /tmp/metricol-api-$SHA.tar.gz -C "$RUN/fuente"
test "$(cat "$RUN/fuente/BUILD_SHA")" = "$SHA"
# El mismo Dockerfile de las entregas anteriores: toma los audios de la imagen que ya corre.
sudo cp "$LIVE/Dockerfile.release" "$RUN/fuente/Dockerfile.release"

ACTUAL=$(sudo docker inspect --format '{{.Image}}' metricol-api)
echo "==> Imagen que corre ahora: $ACTUAL"
echo "==> Construyendo $IMAGEN"
sudo docker build -q -f "$RUN/fuente/Dockerfile.release" --build-arg AUDIO_IMAGE="$ACTUAL" -t "$IMAGEN" "$RUN/fuente" 2>&1 | tail -3

echo "==> Dentro de la imagen"
sudo docker run --rm --entrypoint sh "$IMAGEN" -c '
  echo "sha: $(cat BUILD_SHA)";
  echo "audios: $(ls catalogo-musica/audio | wc -l)";
  ffmpeg -version | head -1;
  fc-list | grep -iE "montserrat|poppins|anton|bebas" | sed "s|.*: ||" | sort'

echo "==> Pintando una prueba con el ffmpeg y las letras del servidor"
cp /tmp/guion-*.ass "$RUN/prueba/"
sudo docker run --rm --entrypoint sh -v "$RUN/prueba":/prueba "$IMAGEN" -c '
  cd /prueba;
  for g in guion-*.ass; do
    n=${g%.ass};
    ffmpeg -hide_banner -loglevel error -y -f lavfi -i "color=c=0x5a6472:s=1080x1920:d=45:r=30" \
      -vf "subtitles=filename=$g" -ss 39.8 -frames:v 1 "$n.png" && echo "ok $n";
  done'
sudo chown -R "$(id -un)":"$(id -un)" "$RUN/prueba"
ls -la "$RUN/prueba" | grep png
sudo docker images --format '{{.Repository}}:{{.Tag}} {{.ID}}' | grep "letreros-$S12"
