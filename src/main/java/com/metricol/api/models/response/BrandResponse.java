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
        Completitud completitud) {

    /**
     * @param percent 0 a 100
     * @param faltan  qué falta, con las mismas claves que usa la pantalla: giro, ciudad, descripcion, objetivo,
     *                queVende, publico, tono, contacto
     */
    public record Completitud(int percent, List<String> faltan) {
    }
}
