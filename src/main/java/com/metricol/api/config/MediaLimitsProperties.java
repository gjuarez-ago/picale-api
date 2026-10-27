package com.metricol.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

/**
 * Cuánto contenido cabe: por publicación y por workspace.
 *
 * <p>Los dos topes resuelven problemas distintos. {@code maxImagesPerPost}
 * es del producto —un carrusel de más de diez fotos no lo ve nadie, y las
 * redes tienen su propio límite— y {@code maxBytesPerWorkspace} es de la
 * factura: R2 cobra por lo almacenado, y sin un tope por cuenta el costo de
 * un solo usuario entusiasta no tiene techo.
 *
 * <p>Por configuración y no como constantes porque el tope de espacio es
 * justo lo que cambia entre "mis pruebas" y un plan de pago, sin que el
 * código tenga por qué enterarse.
 */
@Configuration
@ConfigurationProperties(prefix = "app.media")
@Getter
@Setter
public class MediaLimitsProperties {

    /** Fotos por publicación (carrusel). El video siempre va solo. */
    private int maxImagesPerPost = 6;

    /**
     * Tope de un archivo suelto, en bytes (500 MB), por el camino del presign.
     *
     * <p>Ahí el archivo va directo a R2 sin cruzar nuestro servidor, así que
     * este es el único tope que lo limita. Es cuánto estamos dispuestos a
     * guardar, no cuánto acepta un destino: a qué redes puede ir un archivo lo
     * dice {@link VideoLimitsProperties#maxBytesFor}.
     */
    private long maxFileBytes = 500L * 1024 * 1024;

    /**
     * Tope del camino viejo, el multipart a través de la API, en bytes (100 MB).
     *
     * <p>Más bajo que {@link #maxFileBytes} y tiene que serlo: esa petición sí
     * cruza nginx y el multipart de Spring, y pasarse ahí no da nuestro mensaje
     * sino un 413 del proxy. Hoy solo usa este camino la herramienta
     * {@code subir_archivo} del MCP.
     */
    private long maxUploadBytes = 100L * 1024 * 1024;

    /**
     * Espacio total por workspace, en bytes (50 GB). Un 0 o negativo significa
     * "sin tope": útil para un entorno donde no se quiera medir nada.
     */
    private long maxBytesPerWorkspace = 50L * 1024 * 1024 * 1024;

    /**
     * Lo mismo para un espacio de una organización sin límites (la cuenta de la casa): el espacio
     * total y el tope por archivo, en bytes (100 GB y 2 GB). Los demás siguen con
     * {@link #maxBytesPerWorkspace} y {@link #maxFileBytes}.
     *
     * <p>2 GB por archivo porque el permiso de subida dura media hora (ver
     * {@code app.media.presign-ttl-seconds}): con más, una subida desde el teléfono se corta.
     */
    private long maxBytesPerWorkspaceSinLimites = 100L * 1024 * 1024 * 1024;
    private long maxFileBytesSinLimites = 2L * 1024 * 1024 * 1024;

    /**
     * Archivos que puede subir un workspace en un día (desde las 0:00). Es el
     * freno de una carga masiva: sin él, pegar o arrastrar cientos de fotos
     * llena el espacio y la cola del agente de golpe. 0 = sin tope. No cuentan
     * las copias que hace la IA (retoques, logo, diseños).
     */
    private int maxSubidasPorDia = 100;

    /** Cuántos archivos acepta la web en una sola tanda (pegar, arrastrar o elegir). */
    private int maxPorTanda = 20;

    /** De esos, cuántos pueden ser video: pesan cien veces más que una foto. */
    private int maxVideosPorTanda = 5;

    /** La subida por partes de los archivos grandes. Ver {@link Multipart}. */
    private Multipart multipart = new Multipart();

    public boolean cuotaActiva() {
        return maxBytesPerWorkspace > 0;
    }

    /**
     * Subir un archivo grande en partes que se reintentan y se retoman, en vez de en un solo PUT.
     *
     * <p>Un solo PUT de 3 GB necesita una conexión que aguante una hora sin cortarse y, si se corta,
     * empieza de cero. Por partes, cada una (16 MB) se sube con su propio permiso y se reintenta
     * sola; y si se cierra la pestaña, R2 recuerda cuáles llegaron. Es el mismo archivo directo a
     * R2 de siempre: la API solo firma y cierra.
     */
    @Getter
    @Setter
    public static class Multipart {

        /** Para quién está encendida. Se apaga sin desplegar: los clientes vuelven al PUT de siempre. */
        public enum Modo { APAGADA, SIN_LIMITES, TODAS }

        private Modo modo = Modo.APAGADA;

        /** Desde qué tamaño se sube por partes; lo menor sigue en un solo PUT, que es menos ida y vuelta. */
        private long desdeBytes = 100L * 1024 * 1024;

        /**
         * Tamaño de cada parte. R2 pide que todas midan igual salvo la última, y entre 5 MiB y 5 GiB;
         * 16 MiB es poco que reintentar en un teléfono y 192 partes para 3 GB, lejos del tope de 10,000.
         */
        private long tamanoParteBytes = 16L * 1024 * 1024;

        /** Cuánto vale el permiso de cada parte. Se piden por lotes mientras se sube, así que basta una hora. */
        private long ttlParteSegundos = 3600;

        /** Cuántas partes se firman en una sola petición. */
        private int maxPartesPorLote = 50;
    }

    /** "1.4 GB" / "780 MB" / "12 KB", para el mensaje que lee la persona. */
    public static String legible(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return Math.round(kb) + " KB";
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return redondear(mb) + " MB";
        }
        return redondear(mb / 1024.0) + " GB";
    }

    private static String redondear(double valor) {
        // Un decimal solo cuando aporta: "1.4 GB" sí, "512.0 MB" no.
        return valor >= 100 || valor == Math.floor(valor)
                ? String.valueOf(Math.round(valor))
                : String.format(java.util.Locale.US, "%.1f", valor);
    }
}
