#!/usr/bin/env bash
# Crea la CUENTA RAIZ de Picale en la VM: la de la casa, sin limites ni vigencia y
# con la marca de Picale llena (ver docs/cuenta-raiz.md).
#
# Pone DEMO_ACCOUNT_EMAIL, DEMO_ACCOUNT_PASSWORD, DEMO_ACCOUNT_ORGANIZATION y
# DEMO_ACCOUNT_ROOT=true en ~/metricol.api/.env.vps, recrea el contenedor (la API crea
# la cuenta al arrancar, solo si NO existe) y comprueba en el log que nacio.
#
# La contrasena NO se pasa por la linea de comandos ni por el chat: se pide aqui con la
# escritura oculta, viaja por scp en un archivo temporal con permiso 600 y se borra en
# cuanto se usa. Es el mismo patron de configurar-correo.sh.
#
# Si la cuenta ya existe no se toca (ni su contrasena ni su bandera): para cambiar la
# contrasena se usa "olvide mi contrasena".
#
# Uso:   bash configurar-cuenta-raiz.sh
set -euo pipefail

VM="api-videos-prod"
ZONA="us-central1-a"
PROYECTO="cmrg-505321"

# ---------------------------------------------------------------- datos
read -r -p "Correo de la cuenta [demo@picale.click]: " CORREO
CORREO="${CORREO:-demo@picale.click}"
if ! [[ "$CORREO" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]]; then
  echo "ERROR: '$CORREO' no parece un correo valido." >&2
  exit 1
fi

read -r -p "Nombre de la organizacion [PICALE HUB]: " ORGANIZACION
ORGANIZACION="${ORGANIZACION:-PICALE HUB}"
# Letras, numeros, espacios y unos pocos signos: lo que es seguro escribir en el .env.
if ! [[ "$ORGANIZACION" =~ ^[A-Za-z0-9\ ._-]{2,80}$ ]]; then
  echo "ERROR: el nombre de la organizacion solo admite letras, numeros, espacios, punto, guion y guion bajo." >&2
  exit 1
fi

read -r -s -p "Contrasena de la cuenta (no se muestra): " CLAVE
echo
read -r -s -p "Repitela: " CLAVE2
echo
# Desde PowerShell (Git Bash en Windows) la lectura oculta puede traer un retorno de carro
# al final: no es parte de la contrasena y romperia la validacion de abajo.
CLAVE="$(printf '%s' "$CLAVE" | tr -d '\r')"
CLAVE2="$(printf '%s' "$CLAVE2" | tr -d '\r')"
if [ "$CLAVE" != "$CLAVE2" ]; then
  echo "ERROR: las dos contrasenas no coinciden." >&2
  exit 1
fi
unset CLAVE2
# Minimo 6 (lo que exige la app). Se admiten letras, numeros y . _ @ % + = : , - ! $ * ^ ~ ? #
# Quedan fuera las comillas, la barra invertida, la barra vertical, &, el espacio y el acento
# grave: romperian el sed de la VM o el propio .env.
# Un $ es seguro porque la contrasena se guarda ENTRE COMILLAS SIMPLES (ver mas abajo): sin
# ellas, Docker Compose leeria "$cd" como una variable y se comeria parte de la contrasena.
# La expresion va en una variable con comillas simples: dentro de [[ ]] el shell leeria "$*" como
# parametro y se la comeria.
PATRON='^[A-Za-z0-9._@%+=:,!$*^~?#-]{6,72}$'
if ! [[ "$CLAVE" =~ $PATRON ]]; then
  echo "ERROR: la contrasena debe tener de 6 a 72 caracteres: letras, numeros y . _ @ % + = : , - ! \$ * ^ ~ ? #" >&2
  echo "       (no se admiten comillas, barras, &, espacios ni acento grave)" >&2
  exit 1
fi

# ---------------------------------------------------------------- viaje
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
chmod 600 "$TMP"
{
  printf 'DEMO_ACCOUNT_EMAIL=%s\n' "$CORREO"
  # Entre comillas simples: literal para Docker Compose, con o sin $ adentro.
  printf "DEMO_ACCOUNT_PASSWORD='%s'\n" "$CLAVE"
  printf 'DEMO_ACCOUNT_ORGANIZATION=%s\n' "$ORGANIZACION"
  printf 'DEMO_ACCOUNT_ROOT=true\n'
} > "$TMP"
unset CLAVE

echo "==> Copiando a la VM"
# Ruta relativa: gcloud en Windows no expande "~" y crearia una carpeta con ese nombre.
gcloud compute scp "$TMP" "$VM:cuenta-raiz.env.tmp" --zone "$ZONA" --project "$PROYECTO"

echo "==> Aplicando en la VM"
gcloud compute ssh "$VM" --zone "$ZONA" --project "$PROYECTO" --command "
set -euo pipefail
cd \"\$HOME/metricol.api\"
NUEVAS=\"\$HOME/cuenta-raiz.env.tmp\"
trap 'rm -f \"\$NUEVAS\"' EXIT

# Copia del .env por si hay que volver atras.
cp .env.vps \".env.vps.antes-cuenta-raiz-\$(date +%F-%H%M%S)\"

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

# Este archivo tiene la llave de la base, el JWT y las de los proveedores.
chmod 600 .env.vps

echo '--> Recreando el contenedor'
sudo docker compose --project-directory . --env-file .env.vps -f docker-compose.vps.yml up -d
"

echo "==> Esperando a que arranque"
sleep 35

# ---------------------------------------------------------------- comprobacion
# Se mira el log de arranque, que dice si la cuenta nacio o ya existia (sin la contrasena).
gcloud compute ssh "$VM" --zone "$ZONA" --project "$PROYECTO" --command "
echo \"estado: \$(sudo docker ps --filter name=metricol-api --format '{{.Status}}')\"
echo '--- log de la cuenta:'
sudo docker logs metricol-api 2>&1 | grep -i 'cuenta de demostracion' | tail -3 | cut -c1-220 || echo '(sin lineas: revisa sudo docker logs metricol-api)'
"

echo
echo "Listo. Entra en https://picale.rodtech.cloud/login con $CORREO y la contrasena que pusiste."
echo "Copia del .env anterior: ~/metricol.api/.env.vps.antes-cuenta-raiz-* en la VM."
