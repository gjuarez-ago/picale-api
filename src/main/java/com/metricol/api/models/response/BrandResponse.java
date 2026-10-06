package com.metricol.api.models.response;

import java.util.List;

import com.metricol.api.enums.ObjetivoRedes;

/** La marca de un espacio y qué tan completa está. */
public record BrandResponse(
        String giro,
        String ciudad,
        String descripcion,
        ObjetivoRedes objetivo,
        String queVende,
        String publico,
        List<String> tono,
        String evitar,
        String whatsapp,
        String web,
        String direccion,
        Completitud completitud,
        /** Cómo trabaja; nulo = todavía no se deduce. */
        List<String> rasgos,
        /** Los eligió el dueño (no la IA). */
        boolean rasgosDelDueno,
        String historia,
        String valores,
        String frases,
        List<String> pilares,
        /** Lo subido como logo es una foto: no se pega en las publicaciones. */
        boolean logoEsFoto,
        /** Los colores de la marca, sacados del logotipo ("#1A3A6B"…). */
        List<String> colores) {

    /**
     * @param percent 0 a 100
     * @param faltan  qué falta, con las mismas claves que usa la pantalla: giro, ciudad, descripcion, objetivo,
     *                queVende, publico, tono, contacto
     */
    public record Completitud(int percent, List<String> faltan) {
    }
}
