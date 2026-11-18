#!/usr/bin/env bash
# Fase 2: respaldar, cambiar a la imagen nueva y comprobar. Si algo falla al
# arrancar, regresa sola a la imagen anterior.
# Uso: bash fase2-cambiar.sh <sha-completo> <sha-que-debe-estar-corriendo>
set -euo pipefail
SHA="$1"
ANTES="$2"
S12="${SHA:0:12}"
LIVE=/home/contacto_rodtech_cloud/metricol.api
RUN=/home/contacto_rodtech_cloud/entregas/letreros-$S12
IMAGEN=metricol-api:letreros-$S12
PREVIA=metricol-api:previo-letreros-$S12
RESPALDOS=/home/contacto_rodtech_cloud/respaldos
compose() {
  sudo docker compose --project-directory $LIVE --env-file $LIVE/.env.vps -f $LIVE/docker-compose.vps.yml "$@"
}
version() { curl -s -m 5 http://127.0.0.1:18085/api/v1/ops/version || true; }
var() { sudo grep -E "^$1=" $LIVE/.env.vps | tail -1 | cut -d= -f2-; }
sql() {
  sudo docker run --rm -i --network metricolapi_default -e PGPASSWORD="$(var DB_PASS)" postgres:18-alpine \
    psql -h metricol-cloud-sql-proxy -U "$(var DB_USER)" -d "$(var DB_NAME)" -At -v ON_ERROR_STOP=1 -c "$1"
}

echo "==> Antes de tocar nada"
version | grep -q "\"sha\":\"$ANTES\"" || { echo "Producción ya no corre $ANTES: alguien más desplegó. No sigo."; exit 1; }
version | grep -q '"base":"picale_dev"' || { echo "La base no es picale_dev. No sigo."; exit 1; }
sudo docker image inspect "$IMAGEN" >/dev/null
echo "copias de video en proceso: $(sql "select count(*) from media_assets where status='PROCESSING'")"
echo "columnas nuevas ya puestas: $(sql "select count(*) from information_schema.columns where table_name='workspaces' and column_name in ('letreros_videos','claves_videos','videos_diseno')")"

echo "==> Respaldo de la base"
DUMP=$RESPALDOS/picale-antes-letreros-$(date +%Y%m%d-%H%M%S)-$S12.dump
sudo docker run --rm --network metricolapi_default -e PGPASSWORD="$(var DB_PASS)" postgres:18-alpine \
  pg_dump -h metricol-cloud-sql-proxy -U "$(var DB_USER)" -d "$(var DB_NAME)" -Fc | sudo tee "$DUMP" >/dev/null
sudo docker run --rm -i postgres:18-alpine pg_restore -l < "$DUMP" | grep -c "TABLE DATA" | sed 's/^/tablas en el respaldo: /'
ls -la "$DUMP"

echo "==> Guardando la imagen y el código anteriores"
ACTUAL=$(sudo docker inspect --format '{{.Image}}' metricol-api)
sudo docker tag "$ACTUAL" "$PREVIA"
sudo tar -czf "$RUN/fuente-anterior.tar.gz" -C "$LIVE" --exclude=logs --exclude=.env.vps --exclude=secretos .

echo "==> Instalando el código nuevo"
sudo rm -rf $LIVE/src
sudo tar --no-same-owner -xzf /tmp/metricol-api-$SHA.tar.gz -C $LIVE
DUENO=$(sudo stat -c %U $LIVE); [ "$DUENO" = UNKNOWN ] && DUENO=$(id -un)
sudo chown -R "$DUENO":"$DUENO" $LIVE

echo "==> Cambiando de imagen"
sudo docker tag "$IMAGEN" metricolapi-metricol-api:latest
compose up -d --no-build metricol-api

regresar() {
  echo "!! No arrancó bien: regreso a la imagen anterior."
  sudo docker logs --tail 60 metricol-api 2>&1 | tail -60
  sudo docker tag "$PREVIA" metricolapi-metricol-api:latest
  sudo rm -rf $LIVE/src
  sudo tar --no-same-owner -xzf "$RUN/fuente-anterior.tar.gz" -C $LIVE
  compose up -d --no-build metricol-api
  for i in $(seq 1 40); do version | grep -q "\"sha\":\"$ANTES\"" && { echo "Regresó a $ANTES."; exit 2; }; sleep 5; done
  echo "!! Tampoco regresó sola. Revisar a mano."; exit 3
}

echo "==> Esperando a que responda la versión nueva"
bien=no
for i in $(seq 1 48); do
  if version | grep -q "\"sha\":\"$SHA\""; then bien=si; break; fi
  sleep 5
done
[ "$bien" = si ] || regresar
version | grep -q '"base":"picale_dev"' || regresar

echo "==> Comprobaciones"
version; echo
compose ps --format '{{.Name}} {{.Status}}'
echo "columnas nuevas: $(sql "select string_agg(column_name||' '||data_type, ', ' order by column_name) from information_schema.columns where table_name='workspaces' and column_name in ('letreros_videos','claves_videos','videos_diseno')")"
echo "espacios: $(sql "select count(*) from workspaces")  con letreros encendidos: $(sql "select count(*) from workspaces where letreros_videos")"
echo "posts sin sesión: $(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18085/api/v1/posts)"
sleep 45
echo "errores en el arranque: $(sudo docker logs metricol-api 2>&1 | grep -c ' ERROR ' || true)"
sudo docker logs metricol-api 2>&1 | grep ' ERROR ' | tail -5 || true
echo "salud: $(sudo docker inspect --format '{{.State.Health.Status}}' metricol-api 2>/dev/null || echo sin-dato)"
