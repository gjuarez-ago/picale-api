package com.metricol.api.models.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * El ID token que devuelve Google en la app tras elegir la cuenta.
 *
 * <p>Es un JWT firmado por Google, no un dato de la persona: aquí no llega
 * ni el correo ni el nombre. Eso sale de dentro del token una vez verificada
 * la firma, y por eso no se puede falsear — mandar un correo ajeno en el
 * cuerpo no serviría de nada, porque el cuerpo no se lee.
 */
@Getter
@Setter
public class GoogleLoginRequest {

    @NotBlank
    private String idToken;
}
