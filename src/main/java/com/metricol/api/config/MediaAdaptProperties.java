package com.metricol.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

/**
 * Cómo se adapta una imagen a la red antes de publicarla.
 *
 * <p>Por configuración y no como constantes porque lo primero que cambia entre
 * una máquina de desarrollo y el servidor es dónde está ffmpeg: aquí viene del
 * PATH, en un contenedor es una ruta fija. Y porque {@code enabled} tiene que
 * poder apagarse sin recompilar: si ffmpeg falta o falla, la publicación debe
 * seguir saliendo con la imagen original en vez de no salir.
 */
@Configuration
@ConfigurationProperties(prefix = "app.media.adapt")
@Getter
@Setter
public class MediaAdaptProperties {

    /** Con esto en false se publica la imagen tal como se subió. */
    private boolean enabled = true;

    /** Ruta del binario, o solo el nombre si está en el PATH. */
    private String ffmpeg = "ffmpeg";
    private String ffprobe = "ffprobe";

    /**
     * Tope por imagen. Una foto tarda menos de un segundo; este número está
     * para que un archivo raro no deje un proceso colgado ocupando un worker
     * de la cola de publicación.
     */
    private int timeoutSeconds = 45;

    /**
     * Calidad JPEG en la escala de ffmpeg ({@code -q:v}), donde 2 es la mejor
     * y 31 la peor. 3 es prácticamente indistinguible del original en una
     * pantalla de teléfono y pesa la mitad que 2.
     */
    private int calidad = 3;

    /**
     * Cuánto se desenfoca el fondo que rellena lo que le falta a la foto.
     *
     * <p>Ese fondo es la misma foto ampliada y borrosa. Con poco desenfoque se
     * lee como una segunda imagen mal puesta; con este valor se lee como un
     * fondo. Es la alternativa a las barras blancas —que parecen un error— y
     * al recorte automático, que le corta la cabeza a la gente.
     */
    private int desenfoque = 28;

    /**
     * Tope de lo que se descarga para adaptar. Una imagen que pese más que
     * esto no es una imagen: es un archivo que se llama .jpg.
     */
    private long maxBytesEntrada = 80L * 1024 * 1024;
}
