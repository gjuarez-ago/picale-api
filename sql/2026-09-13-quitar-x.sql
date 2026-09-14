-- Quitar X del enum Platform: lo que hay que hacerle a los datos.
--
-- PostgreSQL. Se corre UNA vez, y ANTES de desplegar la version que ya no
-- tiene X en `Platform`.
--
-- Por que hace falta
-- ------------------
-- `social_accounts.platform` y `daily_publish_usage.platform` guardan el
-- nombre del enum como texto (`@Enumerated(EnumType.STRING)`). Hibernate no
-- sabe leer un valor que el enum ya no tiene: una fila con 'X' no da un hueco
-- ni un null, da un `IllegalArgumentException` al cargarla — y como la lista
-- de cuentas se lee entera, una sola fila de X deja la pantalla de redes sin
-- cargar para ese workspace. Por eso se van las filas y no solo la opcion.
--
-- Que se pierde
-- -------------
-- El registro de lo que salio por X. `post_targets` apunta a la cuenta con una
-- clave foranea NOT NULL, asi que borrar la cuenta obliga a borrar sus
-- destinos: no hay forma de conservar el historial de X cuando el enum ya no
-- sabe nombrarla. Las publicaciones (`posts`) se quedan; lo que desaparece es
-- la fila que decia "y esta tambien salio a X".
--
-- Por eso lo primero que hace el script es copiar a una tabla de respaldo todo
-- lo que va a borrar. No estorba, no la lee nadie, y es la unica manera de
-- responder despues a "¿que habiamos publicado en X?".
--
-- Con que usuario se corre
-- ------------------------
-- Con el MISMO rol que usa la API: el `DB_USER` de `application-prod.yml`.
--
-- No es un detalle de comodidad. Estas tablas las creo Hibernate con
-- `ddl-auto: update` conectado como ese rol, asi que ese rol es el dueño, y el
-- dueño es quien puede borrar y quien puede crear las tablas de respaldo. Un
-- rol de consola, de solo lectura o de otra aplicacion falla en la PRIMERA
-- linea que toca `social_accounts` con:
--
--   permission denied for table social_accounts
--
-- Si pasa eso no se aplico nada: todo va dentro de una transaccion y se
-- aborta entera. No hay que limpiar nada antes de reintentar.
--
-- Para saber con quien estas conectado y que te falta:
--
--   SELECT current_user, session_user;
--
--   SELECT tablename, tableowner FROM pg_tables
--    WHERE tablename IN ('social_accounts', 'post_targets',
--                        'daily_publish_usage', 'social_connection_checks');
--
--   SELECT has_table_privilege('social_accounts', 'SELECT')     AS puede_leer,
--          has_table_privilege('social_accounts', 'DELETE')     AS puede_borrar,
--          has_schema_privilege(current_schema(), 'CREATE')      AS puede_crear;
--
-- Si no puedes cambiar de rol, que el dueño conceda lo justo:
--
--   GRANT SELECT, DELETE ON social_accounts, post_targets,
--         daily_publish_usage, social_connection_checks TO <tu_rol>;
--   GRANT CREATE ON SCHEMA public TO <tu_rol>;
--
-- Como se corre
-- -------------
--   psql "$SPRING_DATASOURCE_URL" -v ON_ERROR_STOP=1 -f sql/2026-09-13-quitar-x.sql
--
-- Desde una consola grafica vale igual: pegalo entero, no por trozos. Va todo
-- en una transaccion —o se hace entero o no se hace nada— y partirlo por
-- sentencias rompe justo esa garantia.

-- Antes de nada: cuantas publicaciones se quedan sin ningun destino.
--
-- Una publicacion cuyo UNICO destino era X pierde su ultima fila de
-- `post_targets` y queda sin redes. No rompe nada —`PostPublishStore` lo
-- contempla y la marca FAILED con "La publicacion no tiene ninguna red de
-- destino"— pero conviene saber cuantas son antes de borrar, no despues.
--
-- Las que ya estaban publicadas se quedan en el historial sin poder decir
-- donde salieron. Las que siguen en cola fallaran cuando les toque, con ese
-- mensaje, que es la verdad: ya no tienen a donde ir.
SELECT p.status AS estado_de_la_publicacion, count(*) AS se_quedan_sin_destino
  FROM posts p
 WHERE NOT EXISTS (
           SELECT 1 FROM post_targets t
            JOIN social_accounts a ON a.id = t.social_account_id
           WHERE t.post_id = p.id AND a.platform <> 'X'
       )
   AND EXISTS (
           SELECT 1 FROM post_targets t
            JOIN social_accounts a ON a.id = t.social_account_id
           WHERE t.post_id = p.id AND a.platform = 'X'
       )
 GROUP BY p.status;

BEGIN;

-- 1. Respaldo. `IF NOT EXISTS` para que correrlo dos veces no reviente.
CREATE TABLE IF NOT EXISTS respaldo_x_social_accounts AS
    SELECT * FROM social_accounts WHERE platform = 'X';

CREATE TABLE IF NOT EXISTS respaldo_x_post_targets AS
    SELECT t.* FROM post_targets t
    JOIN social_accounts a ON a.id = t.social_account_id
    WHERE a.platform = 'X';

CREATE TABLE IF NOT EXISTS respaldo_x_daily_publish_usage AS
    SELECT * FROM daily_publish_usage WHERE platform = 'X';

-- 2. Los destinos primero: la clave foranea a `social_accounts` es NOT NULL,
--    asi que la cuenta no se puede borrar mientras alguno la apunte.
DELETE FROM post_targets
 WHERE social_account_id IN (SELECT id FROM social_accounts WHERE platform = 'X');

-- 3. Las cuentas.
DELETE FROM social_accounts WHERE platform = 'X';

-- 4. Los contadores de cuota del dia. No los apunta nadie, pero una fila con
--    'X' revienta igual al leerla: es el mismo enum.
DELETE FROM daily_publish_usage WHERE platform = 'X';

-- 5. El registro de cuando se verifico cada conexion. Aqui la red es texto
--    libre, no el enum, asi que no rompia nada — pero queda huerfano, y las
--    dos llaves de X convivian segun el endpoint de upload-post.
DELETE FROM social_connection_checks WHERE platform IN ('x', 'twitter', 'X');

COMMIT;

-- Comprobacion. Las tres cuentas tienen que dar cero.
SELECT 'social_accounts'      AS tabla, count(*) AS quedan FROM social_accounts      WHERE platform = 'X'
UNION ALL
SELECT 'daily_publish_usage',        count(*) FROM daily_publish_usage        WHERE platform = 'X'
UNION ALL
SELECT 'social_connection_checks',   count(*) FROM social_connection_checks   WHERE platform IN ('x', 'twitter', 'X');
