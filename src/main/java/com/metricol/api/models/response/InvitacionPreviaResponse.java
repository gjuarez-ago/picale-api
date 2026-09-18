package com.metricol.api.models.response;

import java.util.List;

/**
 * Lo que se enseña al abrir el enlace, antes de pedir una contraseña.
 *
 * @param yaTieneCuenta si ese correo ya está registrado: decide si la pantalla
 *                      pide crear una contraseña o escribir la de siempre
 */
public record InvitacionPreviaResponse(String email, String organizacion, String invitadoPor,
        List<String> espacios, boolean yaTieneCuenta) {
}
