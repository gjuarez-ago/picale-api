package com.metricol.api.models.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Lo que se manda para aceptar una invitación.
 *
 * <p>La contraseña sirve para dos cosas según el caso: crea la cuenta de quien
 * no la tiene, o prueba la identidad de quien ya la tiene. El correo no viene
 * aquí a propósito: sale de la invitación, para que nadie use un enlace ajeno
 * cambiándolo.
 */
@Getter
@Setter
public class AceptarInvitacionRequest {

    @NotBlank(message = "Falta el enlace de la invitación.")
    private String token;

    /** Solo se usa al crear la cuenta. */
    private String name;

    @NotBlank(message = "Escribe tu contraseña.")
    private String password;
}
