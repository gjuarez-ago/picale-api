package com.metricol.api.service.media.subtitulos;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.ai.OpenAiClient;
import com.metricol.api.service.media.FfmpegImagen;

/**
 * El recorte automático: quita lo que sobra al principio y al final de un
 * video en el que alguien habla (silencios, «¿ya está grabando?», «ya,
 * corta»). Nunca corta en medio, y nunca deja un video largo en un tramo.
 *
 * <p>Dos pasos. Primero una propuesta con reglas fijas, a partir de lo que
 * se dice y cuándo ({@link #proponer}): solo corta entre palabras, y solo
 * propone quitar un arranque o un cierre falso si va separado del resto por
 * una pausa clara. Después un revisor (la IA) mira los cuadros y el texto de
 * lo que se quitaría y dice qué tan probable es que el video recortado quede
 * bien. Se recorta solo con {@link #CONFIANZA_MINIMA} o más (pedido de
 * Gabriel, 1 nov 2026: «cuando la probabilidad sea del 90% que quede bien»).
 * Si algo falla, el video sale entero: un video completo es mejor que uno mal
 * cortado.
 */
@Service
public class RecorteAutomatico {

    private static final Logger log = LoggerFactory.getLogger(RecorteAutomatico.class);

    /** De 0 a 100: desde aquí se recorta. */
    public static final int CONFIANZA_MINIMA = 90;
    /** Lo que se deja antes de la primera palabra y después de la última. */
    static final double MARGEN_ANTES = 0.35;
    static final double MARGEN_DESPUES = 0.6;
    /** Menos que esto no vale la pena: no se nota y cuesta volver a codificar. */
    static final double MINIMO_A_QUITAR = 1.0;
    /** La pausa que separa un arranque o cierre falso del resto. */
    static final double PAUSA_CLARA = 1.5;
    /** Un arranque o cierre falso es muy corto: «¿ya?», «ok, empiezo», «ya, corta». Con más, ya suele ser un saludo con contenido («hola, soy Juan»). */
    static final int MAX_PALABRAS_FALSAS = 3;
    /** Lo que queda no puede ser muy corto, ni menos de la mitad del original. */
    static final double MINIMO_QUE_QUEDA = 5;
    /**
     * Más que esto por lado casi nunca es solo un silencio: es una intro con
     * música hecha en CapCut (los de Juan esperan hasta 24 s para hablar).
     */
    static final double MAXIMO_POR_LADO = 6;
    /** Si en lo que se quita suena algo (música, ambiente) más de esta parte del tiempo, no es tiempo muerto. */
    static final double PARTE_QUE_SUENA = 0.4;

    /**
     * Lo que se propone quitar.
     *
     * @param inicio        desde dónde se queda (segundos del original)
     * @param fin           hasta dónde
     * @param quitaInicio   lo que se dice en lo que se quita al principio ("" si solo es silencio)
     * @param quitaFinal    lo mismo al final
     */
    public record Propuesta(double inicio, double fin, String quitaInicio, String quitaFinal) {
        double quitado(double duracion) {
            return inicio + (duracion - fin);
        }
    }

    /** El recorte ya aprobado por el revisor, con lo que se le dice a la persona. */
    public record Recorte(double inicio, double fin, int confianza, String nota) {
    }

    private final MediaAssetRepository assets;
    private final FfmpegImagen ffmpeg;
    private final OpenAiClient ia;

    public RecorteAutomatico(MediaAssetRepository assets, FfmpegImagen ffmpeg, OpenAiClient ia) {
        this.assets = assets;
        this.ffmpeg = ffmpeg;
        this.ia = ia;
    }

    /**
     * Qué se podría quitar, solo con reglas: nulo si no hay nada que valga la
     * pena o si lo que quedaría es muy poco.
     */
    public static Propuesta proponer(List<Palabra> palabras, double duracion) {
        if (palabras.isEmpty() || duracion <= 0) {
            return null;
        }
        int primera = 0;
        int ultima = palabras.size() - 1;

        // Un arranque falso: unas pocas muletillas de grabación al principio, y
        // luego una pausa clara. Solo muletillas: «hola, soy Juan» y una pausa
        // es un saludo, no algo que sobre.
        for (int i = 0; i < Math.min(MAX_PALABRAS_FALSAS, palabras.size() - 1); i++) {
            double pausa = palabras.get(i + 1).inicio() - palabras.get(i).fin();
            if (pausa >= PAUSA_CLARA && palabras.get(i).fin() < duracion * 0.25 && muletillas(palabras, 0, i + 1)) {
                primera = i + 1;
                break;
            }
        }
        // Un cierre falso: tras una pausa clara, unas pocas muletillas al final.
        for (int i = palabras.size() - 1; i > Math.max(primera, palabras.size() - 1 - MAX_PALABRAS_FALSAS); i--) {
            double pausa = palabras.get(i).inicio() - palabras.get(i - 1).fin();
            if (pausa >= PAUSA_CLARA && palabras.get(i).inicio() > duracion * 0.8
                    && muletillas(palabras, i, palabras.size())) {
                ultima = i - 1;
                break;
            }
        }

        // Los márgenes nunca se meten en una palabra que se quita.
        double antes = primera == 0 ? palabras.get(0).inicio()
                : palabras.get(primera).inicio() - palabras.get(primera - 1).fin();
        double inicio = Math.max(0, palabras.get(primera).inicio() - Math.min(MARGEN_ANTES, antes / 2));
        double despues = ultima == palabras.size() - 1 ? duracion - palabras.get(ultima).fin()
                : palabras.get(ultima + 1).inicio() - palabras.get(ultima).fin();
        double fin = Math.min(duracion, palabras.get(ultima).fin() + Math.min(MARGEN_DESPUES, despues / 2));

        Propuesta p = new Propuesta(inicio, fin, texto(palabras, 0, primera), texto(palabras, ultima + 1,
                palabras.size()));
        double queda = fin - inicio;
        if (p.quitado(duracion) < MINIMO_A_QUITAR || queda < MINIMO_QUE_QUEDA || queda < duracion / 2
                || inicio > MAXIMO_POR_LADO || duracion - fin > MAXIMO_POR_LADO) {
            return null;
        }
        return p;
    }

    /**
     * Si en lo que se quitaría suena algo: una intro con música o el ambiente
     * de un lugar son parte del video, no tiempo muerto.
     *
     * @param niveles dB cada décima de segundo (ver {@code FfmpegImagen.nivelesDeAudio})
     */
    static boolean suenaLoQueSeQuita(double[] niveles, Propuesta p, double duracion) {
        // Cada lado por su cuenta: una intro con música no se diluye en un final callado.
        return suena(niveles, 0, p.inicio()) || suena(niveles, p.fin(), duracion);
    }

    private static boolean suena(double[] niveles, double desde, double hasta) {
        int total = 0;
        int suenan = 0;
        for (int i = 0; i < niveles.length; i++) {
            double t = i / 10.0 + 0.05;
            if (t >= desde && t < hasta) {
                total++;
                if (niveles[i] > FondoDelAudio.UMBRAL_DB) suenan++;
            }
        }
        return total > 0 && suenan > PARTE_QUE_SUENA * total;
    }

    /** Lo que se dice al prender o apagar la cámara, sin contenido. */
    private static final java.util.Set<String> MULETILLAS = java.util.Set.of(
            "ya", "ok", "okay", "okey", "listo", "lista", "va", "vale", "sale", "eh", "em", "mmm", "este", "a",
            "ver", "se", "escucha", "oye", "oyes", "grabando", "graba", "grabo", "ahi", "corta", "corte",
            "cortale", "empiezo", "empezamos", "uno", "dos", "tres", "1", "2", "3", "otra", "vez", "de", "nuevo",
            "perdon", "espera", "andale", "venga", "si", "ahora");

    static boolean muletillas(List<Palabra> palabras, int desde, int hasta) {
        for (int i = desde; i < hasta; i++) {
            String limpia = java.text.Normalizer.normalize(palabras.get(i).texto(), java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "").replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(java.util.Locale.ROOT);
            if (!limpia.isEmpty() && !MULETILLAS.contains(limpia)) {
                return false;
            }
        }
        return true;
    }

    private static String texto(List<Palabra> palabras, int desde, int hasta) {
        StringBuilder sb = new StringBuilder();
        for (int i = desde; i < hasta; i++) {
            sb.append(i > desde ? " " : "").append(palabras.get(i).texto());
        }
        return sb.toString().strip();
    }

    /** Las palabras del tramo que se queda, con sus tiempos contados desde el nuevo inicio. */
    public static List<Palabra> corridas(List<Palabra> palabras, double inicio, double fin) {
        List<Palabra> quedan = new ArrayList<>();
        for (Palabra p : palabras) {
            if (p.inicio() >= inicio - 0.01 && p.fin() <= fin + 0.01) {
                quedan.add(new Palabra(p.texto(), Math.max(0, p.inicio() - inicio), Math.max(0, p.fin() - inicio), p.clave()));
            }
        }
        return quedan;
    }

    private static final String SISTEMA = """
            Eres editor de video para redes sociales. Te enseno lo que se quitaria al principio y al final \
            de un video donde alguien habla: el texto que se dice en esas partes y cuadros de ellas. Das la \
            probabilidad, de 0 a 100, de que el video recortado quede bien: que no se pierda nada importante \
            (un saludo con contenido, un nombre, un dato, un producto que se muestra, una toma que da contexto) \
            y que empiece y termine de forma natural. Quitar silencios, titubeos, "ya esta grabando?", \
            "ya, corta", o a alguien acomodando la camara esta bien. Si dudas, das menos de 90. \
            Contestas SOLO JSON: {"probabilidad": numero, "motivo": "una frase"}.""";

    private static final Pattern PROBABILIDAD = Pattern.compile("\"probabilidad\"\\s*:\\s*(\\d{1,3})");
    private static final Pattern MOTIVO = Pattern.compile("\"motivo\"\\s*:\\s*\"([^\"]*)\"");

    /**
     * Si conviene recortar este video y dónde. Nulo si no hay nada que quitar,
     * si el revisor no llega a {@link #CONFIANZA_MINIMA}, o si algo falla.
     */
    public Recorte decidir(UUID videoId, List<Palabra> palabras, double duracion) {
        Propuesta p = proponer(palabras, duracion);
        if (p == null) {
            return null;
        }
        MediaAsset video = assets.findById(videoId).orElse(null);
        if (video == null) {
            return null;
        }
        try {
            double[] niveles = ffmpeg.nivelesDeAudio(video.getUrl(), (int) Math.ceil(duracion));
            if (niveles == null || suenaLoQueSeQuita(niveles, p, duracion)) {
                log.info("Recorte del video {}: en lo que se quitaría suena algo; va entero.", videoId);
                return null;
            }
            List<String> cuadros = new ArrayList<>();
            StringBuilder que = new StringBuilder("El video dura ").append(Math.round(duracion)).append(" s.\n");
            if (p.inicio() >= 0.5) {
                que.append("AL PRINCIPIO se quitan ").append(segundos(p.inicio())).append(": ")
                        .append(p.quitaInicio().isBlank() ? "no se dice nada." : "se dice \"" + p.quitaInicio() + "\".")
                        .append(" (primer cuadro)\n");
                cuadro(cuadros, video, p.inicio() / 2);
            }
            if (duracion - p.fin() >= 0.5) {
                que.append("AL FINAL se quitan ").append(segundos(duracion - p.fin())).append(": ")
                        .append(p.quitaFinal().isBlank() ? "no se dice nada." : "se dice \"" + p.quitaFinal() + "\".")
                        .append(" (cuadro siguiente)\n");
                cuadro(cuadros, video, (p.fin() + duracion) / 2);
            }
            List<Palabra> quedan = corridas(palabras, p.inicio(), p.fin());
            // Solo un trozo de cada punta, y dicho así: con el texto cortado a
            // secas, el revisor creía que el video empezaba a media frase.
            que.append("El video recortado arranca con la primera palabra que se dice. Sus primeras palabras son: \"")
                    .append(texto(quedan, 0, Math.min(12, quedan.size())))
                    .append("...\" (sigue). Sus ultimas palabras son: \"...")
                    .append(texto(quedan, Math.max(0, quedan.size() - 12), quedan.size()))
                    .append("\" (ahi termina). Ultimo cuadro: como empieza el recortado.");
            cuadro(cuadros, video, p.inicio() + 0.5);
            if (cuadros.isEmpty()) {
                return null;
            }
            String respuesta = ia.describeImages(AiOperacion.RECORTE_AUTOMATICO, SISTEMA, que.toString(), cuadros);
            int confianza = numero(respuesta);
            String motivo = motivo(respuesta);
            log.info("Recorte del video {}: {} (se quitan {} s) — {}", videoId, confianza,
                    Math.round(p.quitado(duracion) * 10) / 10.0, motivo);
            if (confianza < CONFIANZA_MINIMA) {
                return null;
            }
            return new Recorte(p.inicio(), p.fin(), confianza, nota(p, duracion));
        } catch (RuntimeException ex) {
            log.warn("No pude revisar el recorte del video {}: {}", videoId, ex.toString());
            return null;
        }
    }

    private void cuadro(List<String> cuadros, MediaAsset video, double segundo) {
        byte[] jpg = ffmpeg.cuadro(video.getUrl(), segundo, 720, 5);
        if (jpg != null) {
            cuadros.add("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpg));
        }
    }

    static int numero(String respuesta) {
        if (respuesta == null) return 0;
        Matcher m = PROBABILIDAD.matcher(respuesta);
        return m.find() ? Math.min(100, Integer.parseInt(m.group(1))) : 0;
    }

    private static String motivo(String respuesta) {
        if (respuesta == null) return "";
        Matcher m = MOTIVO.matcher(respuesta);
        return m.find() ? m.group(1) : "";
    }

    /** «Le quité 3 s del principio y 2 s del final.» */
    static String nota(Propuesta p, double duracion) {
        long alInicio = Math.round(p.inicio());
        long alFinal = Math.round(duracion - p.fin());
        List<String> partes = new ArrayList<>();
        if (alInicio >= 1) partes.add(alInicio + " s del principio");
        if (alFinal >= 1) partes.add(alFinal + " s del final");
        if (partes.isEmpty()) return "Le quité lo que sobraba al principio y al final.";
        return "Le quité " + String.join(" y ", partes) + ": lo que sobraba.";
    }

    private static String segundos(double s) {
        return (Math.round(s * 10) / 10.0) + " s";
    }
}
