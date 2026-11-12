# Validación de infraestructura: D45

12 de noviembre de 2026, America/Mexico_City. Unidad cerrada. API productiva y
base de producción sin cambios; no se publicó app ni se generó otro APK.

## Resultados verificados

| Comprobación | Resultado |
|---|---|
| Suite del contrato sobre PostgreSQL real | 152/152 en 13 clases, sin fallos/errores/omisiones; PostgreSQL 18.3 local. |
| HTTP autenticado + PostgreSQL + R2 real | 4/4, sin fallos/errores/omisiones. Login real de cuentas temporales en la API local, con JWT de clave local nueva. |
| Migración aditiva | D43/D44 aplicadas dos veces a fixture legado fresco en una segunda BD privada; 13 columnas, tabla de admisión, índice, fila antigua y unicidad conservados. |
| Compatibilidad del arnés con H2 | 5/5 de MediaTopeDiarioTest conservando sus defaults anteriores. |
| Integridad | Video local de 12,187,330 bytes: SHA-256 del objeto final igual al original; fuente original sin modificaciones. |
| Limpieza R2 | Cuatro claves del ensayo real retiradas; cero objetos y cero sesiones multipart en su prefijo. Intento previo sin archivos también verificado vacío. |
| Infraestructura temporal | Cluster privado detenido y puerto 55445 sin listener; servicio PostgreSQL original continúa Running. |

No es la suite completa del repositorio ni una validación del esquema/versionado
de la base productiva. El código API coincide con las veinte entradas verificadas
en D44; D45 sólo agrega arnés/herramienta y permite seleccionar la BD de una clase
de pruebas, sin cambiar sus expectativas.

## Qué ejercitó R2 real

1. Dos aperturas HTTP con la misma clave recuperaron una reserva y una sesión
   remota. Se enviaron rangos de 5 MiB, se consultó la primera parte recibida y
   se continuó con las faltantes. Un tick explícito del worker terminó el medio
   sin petición Flutter de complete. El reintento no duplicó miniaturas. Otro
   usuario del mismo negocio recibió 404 al consultar la sesión; cancelar READY
   devolvió conflicto y dejó el objeto disponible. La descarga SDK coincidió en
   SHA-256 con el original.
2. Se pausó el cuerpo de una petición real UploadPart después de comenzar su
   envío, se canceló por HTTP y luego se dejó terminar el cliente. Se comprobaron
   registro terminal, objeto ausente y ninguna sesión remota restante.
3. Se creó una sesión real y se inyectó pérdida de respuesta antes de guardar
   uploadId. La consulta recuperó la misma sesión mediante ListMultipartUploads
   sin otra llamada Create; después se canceló y limpió.
4. Se inyectó un fallo antes del aborto. Se comprobó reserva/cuota conservada y
   CANCELLING; el worker ejecutó el aborto real posterior, liberó la reserva y
   dejó sólo la cuota del archivo ya confirmado.

Las pérdidas/fallos de 3 y 4 fueron inyecciones controladas sobre R2 real, no
incidentes del proveedor. Se invocó el mismo método del worker, pero no se probó
esperar su temporizador ni matar/reiniciar un teléfono. Sólo se simuló miniaturas
para evitar trabajos de derivados/IA; almacenamiento, HTTP, autenticación y base
de esos cuatro casos fueron reales.

## Aislamiento y limpieza

Se utilizó un cluster propio en `target/subidas-d45/postgres/data`, loopback y
puerto 55445, con rol/bases `subidas_d45` y `subidas_d45_migrations`. Se verificó
data_directory antes de ejecutar migraciones o detenerlo. No se usó ni detuvo el
servicio PostgreSQL original, ni se conectó la prueba a una base productiva.

Se leyó únicamente el video autorizado en Downloads. Cada ejecución R2 usó
`pruebas/subidas-background/<UUID>/d45/`. La clave se registró en un manifiesto
antes de Create. La limpieza abortó sesiones y eliminó sólo las claves exactas
registradas, con validación de bucket/prefijo/formato; comprobó además el prefijo
completo vacío. No se borraron objetos ajenos ni se consultó la galería productiva.

El arnés toma exclusivamente las cinco propiedades R2 necesarias del archivo
local. No carga sus claves de IA, correo ni la base/JWT de producción. Tokens,
contraseñas temporales y URLs firmadas no se guardan en el manifiesto. Se revisaron
los logs live: sin coincidencias de credenciales R2 reales, JWT o URL firmada.

## Evidencia reproducible

- `target/subidas-d45/report.json`: resultados, hashes, límites y estado de cierre.
- `postgres-suite-final.log` y `pg-reports/`: 152 casos/13 XML archivados antes de
  otras corridas. Los contextos conectados informaron PostgreSQL 18.3.
- `live-release.log` y `live-report.xml`: cuatro casos HTTP/R2.
- `r2-ba4d7a61-ab18-432f-8e48-d849bf866901.json`: cuatro claves y eventos reales,
  cleaned=true, objectsRemaining=0, multipartRemaining=0. El manifiesto anterior
  sin archivos también quedó limpio.
- `migration.log` y `migration-input.sql`: prueba sobre esquema legado mínimo
  fresco, sin representar una copia de todas las tablas/datos productivos.
- `h2-compat.log` y `h2-report.xml`: cinco casos compatibles con H2.
- `inputs.json`: tres entradas del arnés verificadas sin cambios al cerrar.

Código opt-in: `src/test/java/com/metricol/api/service/storage/SubidasInfraRealIT.java`.
No se ejecuta con la suite ordinaria: requiere seleccionar IT y subidas.real=true;
rechaza una URL de base distinta del cluster privado esperado. Utilizar sólo ese
cluster, un video de 11 a 15 MiB autorizado y la configuración R2 aprobada.
El main `cleanup <manifest>` permite recuperar una limpieza interrumpida sin
arrancar Spring ni depender de la BD, conservando las mismas validaciones.

Herramienta de esquema: `tools/validar-subidas-esquema.ps1`; valida que el servidor
sea el del workspace y trabaja sólo en la BD/esquema de fixture. Las propuestas
SQL de D43/D44 siguen pendientes de aplicación en producción.

## Incidencias del arnés, sin cambios en la API

La primera corrida PG mezcló una clase fijada a H2 con dialecto PG; se añadieron
propiedades opcionales para seleccionarle motor y se repitió correctamente.
Los primeros intentos live se detuvieron por nombre Role ambiguo, configuración
CORS de QA y lectura incorrecta de `data` en vez del sobre real `result`. Se
corrigió el arnés, no la API. Ese último intento no creó archivos y se limpió.
La ejecución final sobre la fuente corregida pasó los cuatro casos.

El wrapper inicial de pg_ctl retuvo su pipe en Windows; el servidor sí arrancó.
Se creó la BD con CLI aparte. Tras el stop correcto del cluster, el wrapper
liberó el pipe y su comando anidado tardío de createdb falló porque el puerto ya
estaba cerrado. No se reabrió el servidor para ese comando innecesario.

## Pendientes

Pícale continúa devolviendo UNAUTHORIZED/reauth en cuenta_actual. No se considera
probada la sesión de Juan Rodríguez ni se solicitan otra vez los datos ya
contestados. La autenticación validada aquí es de cuentas temporales de la API
local, no del conector ni del entorno productivo.

Antes de desplegar falta revisar el esquema y versión reales del destino,
respaldo/restauración, entrega exacta, autorización nueva y drenaje de instancias
anteriores. El permiso cb483b7 no cubre el código actual. Después siguen migración
explícita del manifiesto y tareas por rangos Android, pruebas físicas y iOS/Mac.
multipartMobileReady continúa false; APK D41 y manifiesto v3 no cambiaron.
