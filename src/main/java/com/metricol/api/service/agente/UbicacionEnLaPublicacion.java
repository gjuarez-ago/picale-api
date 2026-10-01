package com.metricol.api.service.agente;

import java.util.Locale;
import java.util.Set;

/**
 * Si una publicación del agente sale con la ubicación del negocio.
 *
 * <p>Solo cuando lo que se ve es del negocio: el local, lo que se vende ahí,
 * su gente, un evento o una promoción en el lugar. Un testimonio, un recorrido
 * (en una inmobiliaria es la propiedad, que está en otro sitio) o algo que no
 * se reconoce salen sin ella: una ubicación que no corresponde confunde más de
 * lo que ayuda.
 */
final class UbicacionEnLaPublicacion {

    /** Los tipos (de foto o de video) en que lo que se ve está en el negocio. */
    private static final Set<String> EN_EL_NEGOCIO = Set.of(
            "LUGAR", "PRODUCTO", "EQUIPO", "EVENTO", "PROMOCION", "DEMOSTRACION", "DETRAS_DE_CAMARAS");

    private UbicacionEnLaPublicacion() {
    }

    static boolean va(String tipo) {
        return tipo != null && EN_EL_NEGOCIO.contains(tipo.strip().toUpperCase(Locale.ROOT));
    }
}
