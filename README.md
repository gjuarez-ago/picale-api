# picale-api

La API de **Pícale**: publica y programa contenido en redes sociales (Facebook,
Instagram, TikTok, LinkedIn, YouTube) a través de upload-post, guarda los medios
en Cloudflare R2 y redacta los textos con OpenAI.

Spring Boot 3.5 · Java 17 · PostgreSQL (Cloud SQL) · Docker.

Clientes: la app móvil (`metricol_app`, Flutter) y el panel web (`metricol_web`,
Angular). Las dos hablan con esta API bajo el mismo dominio,
`https://picale.rodtech.cloud/api/v1`.

## Correr en local

```bash
cp .env.example .env        # y rellena las llaves que vayas a usar
mvn spring-boot:run         # perfil dev: H2 en memoria, puerto 8087
```

Sin llaves arranca igual: cada integración se apaga sola si le falta la suya.
El perfil `dev` siembra una cuenta de prueba (ver `application-dev.yml`).

```bash
mvn test                    # 100+ pruebas; las de ffmpeg se saltan si no está instalado
```

## Desplegar

Producción vive en la VM `api-videos-prod` (GCP, proyecto `cmrg-505321`), en un
contenedor detrás de nginx y Cloudflare. Las llaves del servidor están **solo**
en la VM, en `~/metricol.api/.env.vps` (plantilla: `.env.vps.example`).

- **A mano**, con tu sesión de gcloud: `./deploy-vps.sh`
- **Automático**: cada push a `main` con las pruebas en verde despliega
  (`.github/workflows/deploy.yml`). Cómo encenderlo está explicado en la
  cabecera de ese archivo; mientras no se encienda, se salta en silencio.

`ci.yml` corre las pruebas en cada push y pull request.

## Topes y cuotas

Los topes de la plataforma —cuántas publicaciones al día por red, cuántas puede
dejar pendientes un workspace, cuántas llamadas a la IA— viven en la tabla
`app_limits` y se cambian con un `UPDATE`, sin desplegar:

```sql
select clave, valor, descripcion from app_limits order by clave;
update app_limits set valor = 30, updated_at = now() where clave = 'quota.daily.INSTAGRAM';
```

Las variables `app.quota.*` y `app.limits.*` de `application.properties` solo
siembran la tabla la primera vez. Ver `LimitesConfigurables`.

## Dónde está cada cosa

| Qué | Dónde |
|---|---|
| Entrar, registrarse, recuperar contraseña | `controller/AuthController`, `service/AuthService`, `service/auth/*` |
| Publicaciones y su cola | `service/PostService`, `service/publishing/*` |
| Cuotas y topes | `service/publishing/PublishQuotaService`, `CuotaComprometida`, `service/limits/LimitesConfigurables` |
| Medios (R2, ffmpeg) | `service/MediaService`, `service/storage/*`, `service/media/*` |
| Redes (upload-post) | `service/social/*` |
| IA (OpenAI) | `service/ai/*` |
| Bitácora de lo que hacen las IA conectadas (MCP) | `service/bitacora/*`, `controller/BitacoraIaController`, [docs/bitacora-ia.md](docs/bitacora-ia.md) |
| IA conectadas: ver y desconectar asistentes | `service/conexiones/*`, `controller/ConexionesIaController`, [docs/conexiones-ia.md](docs/conexiones-ia.md) |
| Infraestructura | `Dockerfile`, `docker-compose.vps.yml`, `nginx/metricol.conf`, `deploy-vps.sh` |
