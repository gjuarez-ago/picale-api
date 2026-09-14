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
RUN mvn -B package -DskipTests

# Etapa 2: ejecutar
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# ffmpeg y ffprobe: los dos, porque el codigo mide con ffprobe antes de
# convertir con ffmpeg. Van por nombre y no por ruta porque
# app.media.adapt.ffmpeg usa el del PATH salvo que se le diga otra cosa.
# tzdata para que TZ signifique algo dentro del contenedor: sin el, Alpine
# no conoce America/Mexico_City y la JVM se queda en UTC aunque se lo pidan.
RUN apk add --no-cache ffmpeg tzdata

COPY --from=build /app/target/metricol-api.jar app.jar

# El puerto real lo inyecta quien manda: PORT en Cloud Run, y aqui el que
# mapee docker-compose. 8080 es solo el valor por defecto del perfil prod.
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=${PORT:-8080} -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod} -Duser.timezone=${TZ:-UTC} -jar app.jar"]
