package com.metricol.api.service.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.metricol.api.config.MediaAdaptProperties;

/**
 * El trato directo con ffmpeg y ffprobe. Nadie más en la aplicación sabe que
 * existen.
 *
 * <p>Está aparte del servicio que decide QUÉ adaptar para que esa decisión se
 * pueda leer sin tropezar con líneas de comando, y para que cambiar de
 * herramienta algún día toque un archivo y no cinco.
 */
@Component
public class FfmpegImagen {

    private static final Logger log = LoggerFactory.getLogger(FfmpegImagen.class);

    private final MediaAdaptProperties props;

    public FfmpegImagen(MediaAdaptProperties props) {
        this.props = props;
    }

    public record Medidas(int ancho, int alto) {
        public double proporcion() {
            return alto == 0 ? 1 : (double) ancho / alto;
        }
    }

    /** Ancho y alto reales del archivo, o {@code null} si no se pudo leer. */
    public Medidas medir(Path archivo) {
        List<String> comando = List.of(
                props.getFfprobe(),
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height",
                "-of", "csv=p=0:s=x",
                archivo.toString());

        String salida = ejecutar(comando, "ffprobe");
        if (salida == null) {
            return null;
        }

        // Se busca la linea con las medidas en vez de leer la salida entera,
        // porque la salida entera puede traer mas cosas: los avisos de ffprobe
        // van por el canal de error y aqui se juntan con el normal para que
        // lleguen al log. Partir todo por la "x" funcionaba mientras la imagen
        // no diera ningun aviso; con el primer aviso benigno la medicion
        // habria fallado y la foto se habria publicado sin adaptar, sin que
        // nada lo dijera.
        Medidas medidas = null;
        for (String linea : salida.split("\\R")) {
            String limpia = linea.trim();
            if (!limpia.matches("\\d+x\\d+")) {
                continue;
            }
            String[] partes = limpia.split("x");
            int ancho = Integer.parseInt(partes[0]);
            int alto = Integer.parseInt(partes[1]);
            // Un flujo sin medidas reales contesta "0x0": no es una medida.
            if (ancho > 0 && alto > 0) {
                medidas = new Medidas(ancho, alto);
            }
        }

        if (medidas == null) {
            log.warn("ffprobe no dio medidas utiles para {}: {}", archivo.getFileName(), recortar(salida));
        }
        return medidas;
    }

    /**
     * Deja la imagen en el lienzo pedido y la escribe como JPEG.
     *
     * <p>Lo que hace el filtro, en orden: parte la imagen en dos copias; a una
     * la agranda hasta CUBRIR el lienzo, la recorta y la desenfoca —ese es el
     * fondo—; a la otra la reduce hasta CABER entera, sin recortar nada; y
     * pone la segunda centrada encima de la primera.
     *
     * <p>El resultado es que la foto se ve completa, sin barras blancas ni
     * recortes, sobre un fondo que sale de ella misma. Es la razón de que esto
     * no tenga que preguntarle nada a nadie: no hay una decisión que tomar
     * entre perder encuadre y ganar franjas.
     *
     * <p>El {@code -q:v} sube en los reintentos, no aquí: quien controla el
     * peso es {@link AdaptadorDeImagenes}, que es el que sabe cuánto pesó.
     */
    public byte[] encajar(Path origen, int anchoLienzo, int altoLienzo, int calidad) {
        Path destino = null;
        try {
            destino = Files.createTempFile("picale-adapt-", ".jpg");

            String filtro = String.format(Locale.US,
                    "[0:v]split=2[bg][fg];"
                            + "[bg]scale=%d:%d:force_original_aspect_ratio=increase,"
                            + "crop=%d:%d,gblur=sigma=%d[fondo];"
                            + "[fg]scale=%d:%d:force_original_aspect_ratio=decrease[foto];"
                            + "[fondo][foto]overlay=(W-w)/2:(H-h)/2",
                    anchoLienzo, altoLienzo,
                    anchoLienzo, altoLienzo, props.getDesenfoque(),
                    anchoLienzo, altoLienzo);

            List<String> comando = List.of(
                    props.getFfmpeg(),
                    "-hide_banner",
                    "-loglevel", "error",
                    "-y",
                    "-i", origen.toString(),
                    "-filter_complex", filtro,
                    // sRGB y sin transparencia: es lo único que acepta
                    // Instagram, y un PNG con alfa acabaria con fondo negro.
                    "-pix_fmt", "yuvj420p",
                    "-q:v", String.valueOf(calidad),
                    // Sin metadatos: el EXIF ya se aplicó a los pixeles y
                    // llevarlo encima solo suma peso y datos de ubicacion.
                    "-map_metadata", "-1",
                    destino.toString());

            if (ejecutar(comando, "ffmpeg") == null) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(destino);
            return bytes.length == 0 ? null : bytes;
        } catch (IOException ex) {
            log.warn("No se pudo escribir el temporal de la imagen adaptada: {}", ex.getMessage());
            return null;
        } finally {
            borrar(destino);
        }
    }

    /**
     * Saca un fotograma de un video y lo devuelve como JPEG.
     *
     * <p>El {@code -ss} va ANTES del {@code -i}, y no es un detalle de estilo:
     * puesto antes, ffmpeg salta directo a ese punto del archivo; puesto
     * despues, decodifica el video entero desde el principio hasta llegar
     * ahi. En un video de tres minutos la diferencia es de milisegundos a
     * varios segundos, por el mismo fotograma.
     *
     * <p>Si el video dura menos que {@code segundo}, ffmpeg no encuentra nada
     * y devuelve un archivo vacio. Por eso se reintenta desde el principio:
     * un video de medio segundo es raro, pero existe, y quedarse sin miniatura
     * por eso seria tonto.
     */
    public byte[] fotograma(Path video, String segundo, int ancho, int calidad) {
        byte[] bytes = extraer(video, segundo, ancho, calidad);
        return bytes != null ? bytes : extraer(video, "0", ancho, calidad);
    }

    private byte[] extraer(Path video, String segundo, int ancho, int calidad) {
        Path destino = null;
        try {
            destino = Files.createTempFile("picale-frame-", ".jpg");

            List<String> comando = List.of(
                    props.getFfmpeg(),
                    "-hide_banner",
                    "-loglevel", "error",
                    "-y",
                    "-ss", segundo,
                    "-i", video.toString(),
                    "-frames:v", "1",
                    // -2 conserva la proporcion y redondea a par, que es lo
                    // que exige el submuestreo de color del JPEG.
                    "-vf", "scale=" + ancho + ":-2",
                    "-q:v", String.valueOf(calidad),
                    "-map_metadata", "-1",
                    destino.toString());

            if (ejecutar(comando, "ffmpeg") == null) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(destino);
            return bytes.length == 0 ? null : bytes;
        } catch (IOException ex) {
            log.warn("No se pudo escribir el temporal del fotograma: {}", ex.getMessage());
            return null;
        } finally {
            borrar(destino);
        }
    }

    /** ¿Está ffmpeg donde dice la configuración? */
    public boolean disponible() {
        return ejecutar(List.of(props.getFfmpeg(), "-version"), "ffmpeg") != null;
    }

    /**
     * Corre el proceso y devuelve su salida, o {@code null} si algo salió mal.
     *
     * <p>Nunca lanza. Quien llama va a publicar igual con la imagen original:
     * que ffmpeg falte o falle es un motivo para no adaptar, no para perder la
     * publicación.
     */
    private String ejecutar(List<String> comando, String quien) {
        Process proceso = null;
        Path registro = null;
        try {
            // La salida va a un archivo y no se lee del proceso mientras
            // corre, a proposito. Leer con readAllBytes() bloquea hasta que el
            // proceso cierre su salida, asi que un ffmpeg colgado dejaba el
            // hilo esperando ahi dentro y el timeout de abajo no llegaba a
            // ejecutarse nunca. Con un archivo de por medio, waitFor manda de
            // verdad — y de paso no hay buffer que se llene y trabe a ffmpeg.
            registro = Files.createTempFile("picale-ff-", ".log");

            proceso = new ProcessBuilder(comando)
                    .redirectErrorStream(true)
                    .redirectOutput(registro.toFile())
                    .start();

            if (!proceso.waitFor(props.getTimeoutSeconds(), TimeUnit.SECONDS)) {
                proceso.destroyForcibly();
                log.warn("{} tardo mas de {} s y se corto", quien, props.getTimeoutSeconds());
                return null;
            }
            String salida = Files.readString(registro);
            if (proceso.exitValue() != 0) {
                log.warn("{} termino con codigo {}: {}", quien, proceso.exitValue(), recortar(salida));
                return null;
            }
            return salida;
        } catch (IOException ex) {
            log.warn("No se pudo ejecutar {} ({}): {}", quien, comando.get(0), ex.getMessage());
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (proceso != null && proceso.isAlive()) {
                proceso.destroyForcibly();
            }
            borrar(registro);
        }
    }

    private static String recortar(String texto) {
        if (texto == null) {
            return "";
        }
        String limpio = texto.strip();
        return limpio.length() <= 400 ? limpio : limpio.substring(0, 400) + "...";
    }

    static void borrar(Path archivo) {
        if (archivo == null) {
            return;
        }
        try {
            Files.deleteIfExists(archivo);
        } catch (IOException ex) {
            log.debug("Quedo un temporal sin borrar: {}", archivo);
        }
    }
}
