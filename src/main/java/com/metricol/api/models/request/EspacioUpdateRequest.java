package com.metricol.api.models.request;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import lombok.Getter;
import lombok.Setter;

/**
 * Cómo se llama y cómo se ve un espacio de trabajo.
 *
 * <p>Cada campo es opcional y `null` significa "no lo toques". Cadena vacía sí
 * es un cambio: quitar un logotipo viejo es algo que se quiere poder hacer.
 */
@Getter
@Setter
public class EspacioUpdateRequest {

    private String name;

    /** La URL del logotipo, del mismo sitio donde se suben las fotos. */
    private String logoUrl;

    /** El color de la inicial cuando no hay logotipo. */
    private String color;

    private List<String> tags;

    /** Sin repetidas, sin vacías y con un tope, para que la lista siga siendo legible. */
    public Set<String> etiquetas() {
        Set<String> limpias = new LinkedHashSet<>();
        if (tags == null) {
            return limpias;
        }
        tags.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::strip)
                .limit(8)
                .forEach(limpias::add);
        return limpias;
    }
}
