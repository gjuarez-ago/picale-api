package com.metricol.api.models.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.metricol.api.entity.AccionIa;
import com.metricol.api.service.bitacora.AccionesIa;

/**
 * Una acción de la bitácora de IA, lista para una lista.
 *
 * @param accion      el código (PROGRAMAR_PUBLICACION…)
 * @param descripcion lo mismo en palabras ("Programó una publicación")
 */
public record AccionIaResponse(
        UUID id,
        LocalDateTime cuando,
        String origen,
        String cliente,
        String usuario,
        String accion,
        String descripcion,
        String entidadId,
        String detalle,
        String metodo,
        String ruta) {

    public static AccionIaResponse de(AccionIa a) {
        return new AccionIaResponse(a.getId(), a.getCreatedAt(), a.getOrigen(), a.getCliente(), a.getUserEmail(),
                a.getAccion(), AccionesIa.etiqueta(a.getAccion()), a.getEntidadId(), a.getDetalle(), a.getMetodo(),
                a.getRuta());
    }
}
