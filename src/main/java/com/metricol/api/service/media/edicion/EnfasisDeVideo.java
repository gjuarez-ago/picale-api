package com.metricol.api.service.media.edicion;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.metricol.api.enums.AiOperacion;
import com.metricol.api.service.ai.OpenAiClient;
import com.metricol.api.service.media.subtitulos.DisenoVideo;
import com.metricol.api.service.media.subtitulos.Letrero;
import com.metricol.api.service.media.subtitulos.Palabra;

/**
 * El énfasis de un video que se edita solo: sus letreros informativos y sus
 * palabras clave, con una sola llamada de texto por video.
 *
 * <p>Es la mitad del {@link SelectorDeMomentos} que ya funciona en el
 * servidor. Los acercamientos salen de la misma decisión, pero necesitan la
 * evidencia de la persona hablando, y ese detector todavía no corre aquí:
 * mientras tanto los letreros van solos, arriba.
 *
 * <p>Nunca detiene un video: si la IA falla o contesta mal, el video sale con
 * sus subtítulos de siempre, sin letreros.
 */
@Service
public class EnfasisDeVideo {

    private static final Logger log = LoggerFactory.getLogger(EnfasisDeVideo.class);

    /** Con menos que esto no hay nada que resumir. */
    static final int MIN_PALABRAS = 12;

    /** Arriba, pero debajo del logo de la esquina cuando el video lo lleva. */
    static final double ALTURA_CON_LOGO = 0.19;

    private final OpenAiClient ia;

    public EnfasisDeVideo(OpenAiClient ia) {
        this.ia = ia;
    }

    /**
     * @param palabras las de siempre, con las clave marcadas si se pidieron
     * @param letreros en segundos de la copia (ya recortada); vacía si no se pidieron
     */
    public record Enfasis(List<Palabra> palabras, List<Letrero> letreros) {
    }

    /**
     * @param palabras    todo lo dicho, en segundos del original
     * @param inicio      dónde empieza lo que se queda del video (0 si va entero)
     * @param fin         dónde termina lo que se queda
     * @param conLetreros si la cuenta quiere letreros
     * @param conClaves   si la cuenta quiere palabras clave en color
     * @param conLogo     si la copia lleva el logo arriba: el letrero baja un poco
     */
    public Enfasis decidir(UUID videoId, List<Palabra> palabras, double inicio, double fin, boolean conLetreros,
            boolean conClaves, boolean conLogo) {
        return decidir(videoId, palabras, inicio, fin, conLetreros, conClaves, conLogo, DisenoVideo.Cantidad.NORMAL);
    }

    /** @param cantidad cuántos letreros quiere la cuenta */
    public Enfasis decidir(UUID videoId, List<Palabra> palabras, double inicio, double fin, boolean conLetreros,
            boolean conClaves, boolean conLogo, DisenoVideo.Cantidad cantidad) {
        Enfasis nada = new Enfasis(palabras, List.of());
        if ((!conLetreros && !conClaves) || palabras == null) return nada;
        // Solo lo que se queda en la copia: de lo recortado no se hace letrero.
        int desde = 0;
        while (desde < palabras.size() && palabras.get(desde).inicio() < inicio - 0.05) desde++;
        int hasta = palabras.size();
        while (hasta > desde && palabras.get(hasta - 1).fin() > fin + 0.05) hasta--;
        List<Palabra> dentro = palabras.subList(desde, hasta);
        if (dentro.size() < MIN_PALABRAS) return nada;
        try {
            var ideas = SelectorDeMomentos.ideas(dentro);
            String respuesta = ia.completeJson(AiOperacion.ENFASIS_VIDEO, SelectorDeMomentos.SISTEMA,
                    SelectorDeMomentos.peticion(ideas, fin - inicio, List.of(), cantidad));
            var seleccion = SelectorDeMomentos.leer(respuesta, ideas);
            var resuelto = SelectorDeMomentos.resolver(dentro, ideas, seleccion, List.of(), inicio, fin - inicio,
                    cantidad.ritmo());
            List<Palabra> todas = new ArrayList<>(palabras);
            if (conClaves) {
                for (int i = 0; i < resuelto.palabras().size(); i++) {
                    todas.set(desde + i, resuelto.palabras().get(i));
                }
            }
            List<Letrero> letreros = !conLetreros ? List.of() : resuelto.letreros().stream()
                    .map(l -> conLogo ? new Letrero(l.tipo(), l.texto(), l.inicio(), l.fin(), ALTURA_CON_LOGO) : l)
                    .toList();
            log.info("Énfasis del video {}: {} letreros, {} palabras clave.", videoId, letreros.size(),
                    todas.stream().filter(Palabra::clave).count());
            return new Enfasis(todas, letreros);
        } catch (Exception ex) {
            log.warn("El video {} sale sin letreros ni palabras clave: {}", videoId, ex.toString());
            return nada;
        }
    }
}
