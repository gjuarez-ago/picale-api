package com.metricol.api.models.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.metricol.api.entity.ConexionIa;

/** Una IA conectada, como la ve la pantalla de perfil y el propio MCP. */
public record ConexionIaResponse(
        UUID id,
        String cliente,
        String alcance,
        LocalDateTime creadaEn,
        LocalDateTime expiraEn,
        LocalDateTime ultimaActividadEn,
        String ultimaAccion,
        LocalDateTime revocadaEn,
        boolean activa) {

    public static ConexionIaResponse de(ConexionIa c) {
        return new ConexionIaResponse(c.getId(), c.getCliente(), c.getAlcance() == null ? "write" : c.getAlcance(),
                c.getCreadaEn(), c.getExpiraEn(), c.getUltimaActividadEn(), c.getUltimaAccion(), c.getRevocadaEn(),
                c.activa(LocalDateTime.now()));
    }
}
