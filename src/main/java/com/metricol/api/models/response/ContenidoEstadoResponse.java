package com.metricol.api.models.response;

import java.util.List;
import java.util.Map;

/**
 * Cómo va la creación de contenido: la app la consulta cada par de segundos y
 * va pintando cada versión en cuanto está lista.
 *
 * <p>{@code status}: QUEUED, THINKING, CREATING, READY o FAILED. READY puede
 * traer versiones que fallaron (cada una lo dice en su propio {@code status});
 * FAILED es que no salió ninguna.
 *
 * <p>{@code captions} lleva un texto por red (clave = nombre de la red, por
 * ejemplo {@code INSTAGRAM}), solo de las versiones que sí salieron.
 */
public record ContenidoEstadoResponse(
        String id,
        String status,
        String stage,
        List<Version> versions,
        String headline,
        String supportingCopy,
        Map<String, String> captions,
        Integer creditsRemaining,
        String error) {

    /** Una imagen (o un carrusel) y las redes que la usan. {@code status}: PENDING, READY o FAILED. */
    public record Version(
            String id,
            String ratio,
            List<String> networks,
            String status,
            List<String> imageUrls,
            String error) {
    }
}
