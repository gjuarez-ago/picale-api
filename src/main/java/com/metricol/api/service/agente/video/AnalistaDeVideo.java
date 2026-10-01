package com.metricol.api.service.agente.video;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.agente.DecisorDelAgente;
import com.metricol.api.service.agente.RevisorDeMarca;
import com.metricol.api.service.ai.OpenAiClient;
import com.metricol.api.service.ai.Redactor;
import com.metricol.api.service.media.FfmpegImagen;

/**
 * El Analista de video: lo mira entero y lo escucha, y cuenta qué pasa.
 *
 * <p>No decide nada; eso es del {@link DecisorDeVideo}. Hace tres cosas:
 * <ol>
 * <li>Saca seis cuadros repartidos de principio a fin, directo de la URL del
 * video (ffmpeg pide solo esos tramos, no lo baja entero).</li>
 * <li>Pasa a texto lo que se dice, hasta diez minutos.</li>
 * <li>Le enseña todo junto a la IA —los cuadros con su segundo, lo que se dice
 * y la marca— y le pide qué es, cómo está, qué tomas valen, cuál es la mejor
 * portada y cuál el mejor tramo de hasta 90 segundos.</li>
 * </ol>
 *
 * <p>Los cuadros viajan a la IA dentro de la petición, no subidos a R2: son
 * seis imágenes chicas que solo sirven para esta llamada.
 *
 * <p>El resultado se guarda en el archivo para que los agentes que vengan
 * después (el editor de tomas, el de audio) no vuelvan a pagarlo.
 */
@Service
public class AnalistaDeVideo {

    private static final Logger log = LoggerFactory.getLogger(AnalistaDeVideo.class);

    /** Cuántos cuadros se miran. Seis cubren un video de diez minutos sin perder el hilo y cuestan poco. */
    static final int CUADROS = 6;

    /** Lo más que se transcribe: el tope de subida de Pícale. */
    public static final int MAX_SEGUNDOS = 600;

    /** El tope de un Reel en Pícale (ver app.post.max-video-seconds). */
    static final int TRAMO_MAX = 90;

    private static final int ANCHO_CUADRO = 512;

    private static final String SISTEMA = """
            Eres el community manager de un negocio. Te llega un video que el
            negocio subio: varios cuadros repartidos de principio a fin, con el
            segundo de cada uno, y lo que se dice en el (puede venir vacio).
            Decides si va con la marca y lo calificas; lo que se hace con el lo
            decide otro paso con tu calificacion, asi que se honesto.

            Contestas SOLO un JSON:
            {"veredicto": "VA" | "OBSERVACION" | "DESCARTADA",
             "motivo": "una frase para el dueno, en espanol, diciendo por que",
             "descripcion": "que pasa en el video, de principio a fin, en dos o tres frases",
             "idea": "que comunicarias con este video, en una frase, como encargo para quien escribe",
             "tipo": "RECORRIDO" | "DEMOSTRACION" | "TESTIMONIO" | "DETRAS_DE_CAMARAS" | "EVENTO" | "PROMOCION" | "PRODUCTO" | "OTRO",
             "calidad": 1-5,
             "queFalla": "si la calidad es baja: movido, oscuro, desenfocado, sin sonido...",
             "arreglable": true | false,
             "efimero": true | false,
             "necesitaTexto": true | false,
             "intencion": "VENDER" | "INFORMAR" | "COMUNIDAD" | "CONFIANZA",
             "portada": numero del cuadro mas claro y representativo (1 a N),
             "tomas": [{"cuadro": 1, "nota": 1-5, "que": "que se ve, en pocas palabras"}],
             "tramo": {"inicio": segundos, "fin": segundos}}

            Como calificar:
            - calidad: imagen y sonido. 5 = profesional, 3 = buen video de
              telefono, 1 = casi inservible. arreglable: true si solo es luz o
              color; false si esta movido, desenfocado o sin el audio que pide.
            - efimero: algo del momento (detras de camaras, aviso de hoy, un
              evento en vivo) que va mejor en historia que en el perfil.
            - necesitaTexto: el mensaje tiene que leerse en pantalla (precio,
              oferta, fecha) y no se entiende solo con verlo y oirlo.
            - tomas: una por cuadro, en orden; nota 5 = toma clara y antojable.
            - tramo: el mejor tramo continuo de hasta 90 segundos para un Reel.
              Si el video dura 90 s o menos, todo el video. Quita inicios y
              finales muertos (pantalla en negro, nadie habla, se mueve la mano).

            VA: encaja con lo que el negocio vende o con su dia a dia.
            OBSERVACION (decide el dueno): no esta claro que tenga que ver con el
            negocio; musica o contenido de otros con derechos; menores
            identificables, telefonos, placas o domicilios; contenido regulado
            (alcohol, medicamentos, antes y despues de salud, promesas de
            rendimiento); una promocion con fecha que ya paso.
            DESCARTADA: de otro giro, de la competencia, o lo que la marca pide
            evitar. Si la marca dice poco de si misma, nunca DESCARTADA.
            """;

    private final FfmpegImagen ffmpeg;
    private final OpenAiClient ia;
    private final MediaAssetRepository assets;
    private final ObjectMapper mapper = new ObjectMapper();

    public AnalistaDeVideo(FfmpegImagen ffmpeg, OpenAiClient ia, MediaAssetRepository assets) {
        this.ffmpeg = ffmpeg;
        this.ia = ia;
        this.assets = assets;
    }

    /**
     * Analiza un video. Nunca lanza: si no se pudo (sin cuadros, la IA falló)
     * devuelve {@code null} y el coordinador lo reintenta más tarde.
     */
    public AnalisisDeVideo analizar(MediaAsset video, double duracion, Redactor.Negocio negocio, boolean marcaCompleta) {
        try {
            List<Double> segundos = momentos(duracion);
            List<String> cuadros = new ArrayList<>();
            List<Double> vistos = new ArrayList<>();
            for (double s : segundos) {
                byte[] jpg = ffmpeg.cuadro(video.getUrl(), s, ANCHO_CUADRO, 5);
                if (jpg != null) {
                    cuadros.add("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpg));
                    vistos.add(s);
                }
            }
            if (cuadros.isEmpty()) {
                log.warn("No se pudo sacar ningún cuadro del video {}", video.getId());
                return null;
            }

            String transcripcion = transcribir(video, duracion);
            String respuesta = ia.describeImages(AiOperacion.AGENTE_VIDEO, SISTEMA,
                    contexto(negocio, marcaCompleta, duracion, vistos, transcripcion), cuadros);
            AnalisisDeVideo analisis = interpretar(respuesta, vistos, duracion, transcripcion, marcaCompleta);
            if (analisis != null) {
                guardar(video, analisis);
            }
            return analisis;
        } catch (Exception ex) {
            log.warn("El Analista no pudo con el video {}: {}", video.getId(), ex.toString());
            return null;
        }
    }

    /** Seis momentos del 5 % al 95 % del video: ni el arranque ni el cierre, que suelen estar muertos. */
    static List<Double> momentos(double duracion) {
        List<Double> m = new ArrayList<>();
        for (int i = 0; i < CUADROS; i++) {
            m.add(Math.max(0, duracion * (0.05 + i * 0.18)));
        }
        return m;
    }

    private String transcribir(MediaAsset video, double duracion) {
        try {
            byte[] audio = ffmpeg.audio(video.getUrl(), (int) Math.min(MAX_SEGUNDOS, Math.ceil(duracion)));
            if (audio == null) {
                return "";
            }
            return ia.transcribir(AiOperacion.AGENTE_TRANSCRIBIR, audio, "audio.mp3");
        } catch (Exception ex) {
            // Sin transcripción se analiza igual, solo con lo que se ve.
            log.info("No se pudo transcribir el video {}: {}", video.getId(), ex.toString());
            return "";
        }
    }

    static String contexto(Redactor.Negocio negocio, boolean marcaCompleta, double duracion, List<Double> vistos,
            String transcripcion) {
        StringBuilder t = new StringBuilder(RevisorDeMarca.contexto(negocio, marcaCompleta)
                .replace("\nRevisa esta foto.", ""));
        t.append("\nEl video dura ").append(Math.round(duracion)).append(" segundos.\n");
        for (int i = 0; i < vistos.size(); i++) {
            t.append("Cuadro ").append(i + 1).append(": segundo ").append(Math.round(vistos.get(i))).append('\n');
        }
        t.append(transcripcion.isBlank() ? "\nNo se dice nada (o no tiene audio).\n"
                : "\nLo que se dice:\n" + recortar(transcripcion, 4000) + "\n");
        t.append("\nAnaliza este video.");
        return t.toString();
    }

    AnalisisDeVideo interpretar(String respuesta, List<Double> vistos, double duracion, String transcripcion,
            boolean marcaCompleta) throws Exception {
        if (respuesta == null) {
            return null;
        }
        int a = respuesta.indexOf('{');
        int b = respuesta.lastIndexOf('}');
        if (a < 0 || b <= a) {
            return null;
        }
        JsonNode n = mapper.readTree(respuesta.substring(a, b + 1));

        RevisorDeMarca.Veredicto veredicto;
        try {
            veredicto = RevisorDeMarca.Veredicto.valueOf(n.path("veredicto").asText("").strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            veredicto = RevisorDeMarca.Veredicto.OBSERVACION;
        }
        if (veredicto == RevisorDeMarca.Veredicto.DESCARTADA && !marcaCompleta) {
            veredicto = RevisorDeMarca.Veredicto.OBSERVACION;
        }
        DecisorDelAgente.Intencion intencion;
        try {
            intencion = DecisorDelAgente.Intencion.valueOf(n.path("intencion").asText("VENDER").strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            intencion = DecisorDelAgente.Intencion.VENDER;
        }

        // La portada llega como número de cuadro; se convierte a su segundo.
        int portada = n.path("portada").asInt(1);
        double portadaSegundo = vistos.get(Math.max(0, Math.min(vistos.size() - 1, portada - 1)));

        List<AnalisisDeVideo.Toma> tomas = new ArrayList<>();
        for (JsonNode tm : n.path("tomas")) {
            int cuadro = tm.path("cuadro").asInt(0);
            if (cuadro >= 1 && cuadro <= vistos.size()) {
                tomas.add(new AnalisisDeVideo.Toma(vistos.get(cuadro - 1),
                        Math.max(1, Math.min(5, tm.path("nota").asInt(3))), recortar(tm.path("que").asText(""), 120)));
            }
        }

        // El tramo, dentro del video y de hasta 90 s; si la IA no dio uno, el principio.
        double inicio = Math.max(0, n.path("tramo").path("inicio").asDouble(0));
        double fin = n.path("tramo").path("fin").asDouble(Math.min(duracion, TRAMO_MAX));
        if (fin <= inicio || fin > duracion) {
            fin = Math.min(duracion, inicio + TRAMO_MAX);
        }
        if (fin - inicio > TRAMO_MAX) {
            fin = inicio + TRAMO_MAX;
        }

        return new AnalisisDeVideo(veredicto,
                recortar(n.path("motivo").asText(""), 400),
                recortar(n.path("descripcion").asText(""), 900),
                recortar(n.path("idea").asText(""), 400),
                n.path("tipo").asText("OTRO").strip().toUpperCase(Locale.ROOT),
                Math.max(1, Math.min(5, n.path("calidad").asInt(3))),
                recortar(n.path("queFalla").asText(""), 60),
                n.path("arreglable").asBoolean(true),
                n.path("efimero").asBoolean(false),
                n.path("necesitaTexto").asBoolean(false),
                intencion,
                portadaSegundo,
                tomas,
                inicio,
                fin,
                recortar(transcripcion == null ? "" : transcripcion, 2000));
    }

    /** En el archivo, para los agentes que vengan después. Sin esto habría que volver a pagarlo. */
    private void guardar(MediaAsset video, AnalisisDeVideo analisis) {
        try {
            video.setAgenteAnalisis(recortar(mapper.writeValueAsString(analisis), 8000));
            assets.save(video);
        } catch (Exception ex) {
            log.debug("No se guardó el análisis de {}: {}", video.getId(), ex.toString());
        }
    }

    private static String recortar(String s, int max) {
        String limpio = s == null ? "" : s.strip();
        return limpio.length() <= max ? limpio : limpio.substring(0, max);
    }
}
