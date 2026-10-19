package com.metricol.api.service.media;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.metricol.api.config.MediaAdaptProperties;

/**
 * De qué segundo del video sacar la portada, sin preguntárselo a nadie.
 *
 * <p>Antes era el segundo uno, fijo, en la portada y en la miniatura de la
 * galería. Y el segundo uno de un video grabado con el teléfono suele ser lo
 * peor del video: la mano todavía se movía, el sensor no había ajustado la luz,
 * o el encuadre apuntaba al suelo mientras la persona buscaba el motivo. De ahí
 * las portadas negras en la cuadrícula del perfil y que TikTok acabara pidiendo
 * una a mano.
 *
 * <p>La alternativa no es pedirle a la persona que elija: es elegir bien. Se
 * prueban unos pocos segundos, se descartan los que no tienen imagen —negros,
 * quemados, desenfocados— y gana el más nítido.
 *
 * <p><b>Sin inteligencia artificial y sin coste.</b> El criterio es la varianza
 * del laplaciano, la medida de siempre para saber si una imagen está enfocada,
 * que este proyecto ya usa para puntuar fotos en
 * {@link HuellaDeImagen#nitidez}. Son unos pocos ffmpeg sobre cuadros pequeños,
 * nada que se le cobre a nadie.
 *
 * <p>Nunca lanza y nunca deja sin respuesta: si todo falla devuelve el segundo
 * uno, que es exactamente lo que se hacía antes.
 */
@Component
public class MejorCuadroDeVideo {

    private static final Logger log = LoggerFactory.getLogger(MejorCuadroDeVideo.class);

    /**
     * Los segundos que se prueban.
     *
     * <p>Cinco y no veinte: cada uno es un ffmpeg, y a partir del quinto la
     * ganancia no paga la espera en el momento de publicar. Empiezan en el 1 y
     * no en el 0 porque el cuadro cero es justo el que se quiere evitar.
     *
     * <p>No se pasa de 5 s a propósito: una portada que sale del segundo
     * veinte no representa el video para quien lo ve en una cuadrícula.
     */
    private static final double[] CANDIDATOS = {1, 2, 3, 4, 5};

    /** El de siempre, cuando no se pudo puntuar nada. */
    public static final double POR_OMISION = 1;

    /**
     * Ancho al que se puntúa. Pequeño a propósito: la nitidez no necesita
     * resolución —{@link HuellaDeImagen} reduce aún más para medirla— y a 320
     * cada ffmpeg tarda una fracción de lo que tardaría a tamaño real.
     */
    private static final int ANCHO_AL_PUNTUAR = 320;

    /**
     * Fuera de esta franja de luz (0 negro, 255 blanco) el cuadro se descarta
     * sin mirar su nitidez.
     *
     * <p>Hace falta aparte de la nitidez porque un cuadro negro con grano puede
     * puntuar alto en nitidez y seguir siendo inservible como portada. Y un
     * cuadro quemado —un contraluz, un flash— tampoco dice nada del video.
     */
    private static final double LUZ_MINIMA = 18;
    private static final double LUZ_MAXIMA = 242;

    private final MediaAdaptProperties props;
    private final FfmpegImagen ffmpeg;

    public MejorCuadroDeVideo(MediaAdaptProperties props, FfmpegImagen ffmpeg) {
        this.props = props;
        this.ffmpeg = ffmpeg;
    }

    /**
     * El segundo del cuadro más representativo de este video.
     *
     * @param video      el archivo, ya en disco
     * @param duracionSg cuánto dura, para no probar segundos que no existen;
     *                   {@code null} o 0 si no se sabe, y entonces se prueban
     *                   todos y los que no den imagen se descartan solos
     */
    public double segundoDe(Path video, Integer duracionSg) {
        if (!props.isEnabled() || video == null) {
            return POR_OMISION;
        }

        double mejorSegundo = -1;
        double mejorNitidez = -1;

        for (double candidato : CANDIDATOS) {
            // Un video de 4 s no tiene segundo 5. Se comprueba cuando se sabe
            // la duracion; cuando no, el cuadro vacio lo descarta igual.
            if (duracionSg != null && duracionSg > 0 && candidato >= duracionSg) {
                break;
            }
            Double nitidez = nitidezEn(video, candidato);
            if (nitidez != null && nitidez > mejorNitidez) {
                mejorNitidez = nitidez;
                mejorSegundo = candidato;
            }
        }

        if (mejorSegundo < 0) {
            log.debug("Ningun cuadro servia para la portada; se usa el segundo {}.", POR_OMISION);
            return POR_OMISION;
        }
        return mejorSegundo;
    }

    /**
     * Lo nítido que está ese segundo, o {@code null} si no sirve: no hay cuadro
     * ahí, no se pudo leer, o es demasiado oscuro o demasiado claro.
     */
    private Double nitidezEn(Path video, double segundo) {
        String cuando = String.format(java.util.Locale.US, "%.3f", segundo);
        byte[] jpeg = ffmpeg.fotogramaExacto(video, cuando, ANCHO_AL_PUNTUAR, props.getCalidad());
        if (jpeg == null || jpeg.length == 0) {
            return null;
        }
        try {
            BufferedImage imagen = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (imagen == null) {
                return null;
            }
            double luz = luzMedia(imagen);
            if (luz < LUZ_MINIMA || luz > LUZ_MAXIMA) {
                return null;
            }
            return HuellaDeImagen.nitidez(imagen);
        } catch (Exception ex) {
            // Un cuadro que no se puede leer no es un fallo del que avisar: se
            // descarta y se sigue con el siguiente.
            log.debug("No se pudo puntuar el segundo {}: {}", cuando, ex.getMessage());
            return null;
        }
    }

    /**
     * La luz media del cuadro, de 0 a 255.
     *
     * <p>Se muestrea en rejilla en vez de leer todos los píxeles: para saber si
     * un cuadro está negro no hace falta mirarlo entero, y así da igual el
     * tamaño que entre.
     */
    private static double luzMedia(BufferedImage imagen) {
        int ancho = imagen.getWidth();
        int alto = imagen.getHeight();
        int pasoX = Math.max(1, ancho / 32);
        int pasoY = Math.max(1, alto / 32);
        double suma = 0;
        long n = 0;
        for (int y = 0; y < alto; y += pasoY) {
            for (int x = 0; x < ancho; x += pasoX) {
                int rgb = imagen.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                // Los coeficientes de luminancia de siempre (Rec. 601): el ojo
                // ve el verde mucho mas que el azul, y un promedio plano daria
                // por claro un cuadro que se ve oscuro.
                suma += 0.299 * r + 0.587 * g + 0.114 * b;
                n++;
            }
        }
        return n == 0 ? 0 : suma / n;
    }
}
