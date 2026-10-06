package com.metricol.api.enums;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * De qué quiere hablar la marca además de lo que vende: sus pilares de
 * contenido. Los elige el dueño en Marca (o los sugiere la IA) y le dicen al
 * asistente qué fotos van aunque no sean del producto —una foto suya de viaje
 * va si le importa contar su historia o motivar— y qué escribir con ellas.
 */
public enum PilarDeContenido {

    MOTIVACION("Motivación", "Frases que inspiran a tu público, conectadas con lo que haces.",
            "motivar a su publico con frases que conecten con su oficio"),
    CONSEJOS("Consejos del oficio", "Lo que sabes y le sirve a tus clientes.",
            "dar consejos practicos de su oficio"),
    DETRAS_DE_CAMARAS("Detrás de cámaras", "Cómo trabajas, el día a día.",
            "ensenar como trabaja por dentro, el dia a dia"),
    HISTORIA_DUENO("Tu historia como dueño", "Quién está detrás: tus viajes, tus logros, por qué empezaste.",
            "contar la historia de quien esta detras: sus viajes, logros y por que empezo"),
    CLIENTES("Clientes y resultados", "Lo que logras para ellos, sus testimonios.",
            "presumir resultados y testimonios de clientes"),
    EQUIPO("Tu equipo", "Las personas que hacen el trabajo.",
            "presentar al equipo y reconocer su trabajo"),
    COMUNIDAD("Tu comunidad", "Tu ciudad, causas, fechas que importan.",
            "conectar con su comunidad y su ciudad");

    public final String etiqueta;
    public final String descripcion;
    /** Para el prompt: qué hace la marca con este pilar. */
    public final String instruccion;

    PilarDeContenido(String etiqueta, String descripcion, String instruccion) {
        this.etiqueta = etiqueta;
        this.descripcion = descripcion;
        this.instruccion = instruccion;
    }

    /** Los que se reconocen, sin repetir y en el orden del catálogo. */
    public static List<PilarDeContenido> de(Collection<String> codigos) {
        if (codigos == null) {
            return List.of();
        }
        List<String> limpios = codigos.stream().filter(Objects::nonNull)
                .map(c -> c.strip().toUpperCase(Locale.ROOT)).toList();
        return java.util.Arrays.stream(values()).filter(p -> limpios.contains(p.name())).toList();
    }

    /** Fotos personales del dueño (un viaje, un momento) van con estos pilares. */
    public static boolean conVidaPersonal(Collection<PilarDeContenido> pilares) {
        return pilares != null && (pilares.contains(HISTORIA_DUENO) || pilares.contains(MOTIVACION));
    }
}
