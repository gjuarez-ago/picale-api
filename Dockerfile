# Imagen de metricol.api para el VPS, siguiendo el mismo esquema que
# direccion360: un contenedor por servicio, escuchando en un puerto interno, y
# nginx delante publicando el dominio real.
#
# La diferencia con direccion360 esta en el runtime: aqui hace falta ffmpeg.
# Sin el, AdaptadorDeImagenes no puede encajar las fotos a lo que pide cada
# red y las publica tal cual, que es como una foto acaba rechazada por
# Instagram. La aplicacion arranca igual sin ffmpeg —esta pensada para no
# caerse por eso— asi que su ausencia no se ve como un error de arranque sino
# como publicaciones que fallan mas tarde.

# Etapa 1: compilar
FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /app

# El pom primero y las dependencias despues: mientras el pom no cambie, esta
# capa se reaprovecha y el build no vuelve a bajar medio Maven Central.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
COPY catalogo-musica/catalogo.json catalogo-musica/taxonomia.json catalogo-musica/LICENSE.txt ./catalogo-musica/
# kaino.dev contesta 429 si se le piden las 48 canciones seguidas: una pausa
# entre cada una y reintentos con espera.
RUN apk add --no-cache curl jq \
    && mkdir -p catalogo-musica/audio \
    && jq -r '.tracks[] | [.id, .downloadUrl, .sha256] | @tsv' catalogo-musica/catalogo.json \
       | while IFS="$(printf '\t')" read -r id url checksum; do \
           curl --fail --location --retry 6 --retry-delay 10 --retry-all-errors --silent --show-error \
             "$url" -o "catalogo-musica/audio/$id.mp3"; \
           echo "$checksum  catalogo-musica/audio/$id.mp3" | sha256sum -c -; \
           sleep 2; \
         done
RUN mvn -B package -DskipTests

# Etapa 2: ejecutar
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# ffmpeg y ffprobe: los dos, porque el codigo mide con ffprobe antes de
# convertir con ffmpeg. Van por nombre y no por ruta porque
# app.media.adapt.ffmpeg usa el del PATH salvo que se le diga otra cosa.
# tzdata para que TZ signifique algo dentro del contenedor: sin el, Alpine
# no conoce America/Mexico_City y la JVM se queda en UTC aunque se lo pidan.
RUN apk add --no-cache ffmpeg tzdata fontconfig ttf-dejavu

# La letra de los subtítulos que se pintan en los videos (Montserrat
# ExtraBold y Black, licencia OFL): gruesa, como la de TikTok. libass la busca
# por nombre a través de fontconfig, por eso va a las fuentes del sistema.
COPY docker/fonts/ /usr/share/fonts/picale/
RUN fc-cache -f

# El commit de git que se construyó, para que /api/v1/ops/version diga qué corre
# sin tener que adivinarlo. Lo escribe deploy-vps.sh antes de empaquetar; sin
# ese archivo, ni siquiera para una prueba local, la build fallaría en este
# COPY, así que deploy-vps.sh SIEMPRE lo deja puesto (con "desconocido" si no
# hay forma de saberlo).
COPY BUILD_SHA .

# Catálogo CC0 local para que el worker no dependa de una descarga externa al
# renderizar. R2 sigue siendo el respaldo si se monta una imagen sin estos MP3.
COPY --from=build /app/catalogo-musica/audio/ ./catalogo-musica/audio/

COPY --from=build /app/target/metricol-api.jar app.jar

# El puerto real lo inyecta quien manda: PORT en Cloud Run, y aqui el que
# mapee docker-compose. 8080 es solo el valor por defecto del perfil prod.
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=${PORT:-8080} -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod} -Duser.timezone=${TZ:-UTC} -jar app.jar"]
