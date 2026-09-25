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

    /** Tope de un archivo suelto, en bytes. */
    private long maxFileBytes = 200L * 1024 * 1024;

    /**
     * Espacio total por workspace, en bytes (1 GB). Un 0 o negativo significa
     * "sin tope": útil para un entorno donde no se quiera medir nada.
     */
    private long maxBytesPerWorkspace = 1024L * 1024 * 1024;

    public boolean cuotaActiva() {
        return maxBytesPerWorkspace > 0;
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
