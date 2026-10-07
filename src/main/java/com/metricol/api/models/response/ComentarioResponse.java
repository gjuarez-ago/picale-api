package com.metricol.api.models.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.metricol.api.entity.Comentario;
import com.metricol.api.service.comentarios.CapacidadesPorRed;

import lombok.Builder;
import lombok.Getter;

/**
 * Un comentario como lo pintan la web y la app.
 *
 * <p>Lleva puesto lo que se puede hacer con él ({@code puedeResponder},
 * {@code puedeOcultar}) en vez de dejar que cada pantalla deduzca las reglas de
 * cada red por su cuenta: son dos clientes, y dos copias de la misma regla
 * acaban siendo dos reglas distintas.
 */
@Getter
@Builder
public class ComentarioResponse {

    private final UUID id;
    private final String red;
    private final String redNombre;
    private final String autor;
    private final String autorAvatarUrl;
    private final String texto;
    private final LocalDateTime escritoEn;
    private final boolean leido;
    private final boolean atendido;
    private final boolean esPregunta;
    private final String respuesta;
    private final LocalDateTime respondidoEn;
    private final boolean puedeResponder;
    private final boolean puedeOcultar;

    public static ComentarioResponse de(Comentario c) {
        return ComentarioResponse.builder()
                .id(c.getId())
                .red(c.getRed() == null ? null : c.getRed().name())
                .redNombre(c.getRed() == null ? null : c.getRed().getLabel())
                .autor(c.getAutorNombre())
                .autorAvatarUrl(c.getAutorAvatarUrl())
                .texto(c.getTexto())
                .escritoEn(c.getEscritoEn())
                .leido(c.getLeidoEn() != null)
                .atendido(c.getAtendidoEn() != null)
                .esPregunta(c.pareceSerPregunta())
                .respuesta(c.getRespuestaTexto())
                .respondidoEn(c.getRespondidoEn())
                .puedeResponder(c.getRespondidoEn() == null && CapacidadesPorRed.seContesta(c.getRed()))
                .puedeOcultar(c.getOcultoEn() == null && CapacidadesPorRed.seOculta(c.getRed()))
                .build();
    }
}
