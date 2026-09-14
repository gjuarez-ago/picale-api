package com.metricol.api.models.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * Comprobar el código antes de pedir la contraseña nueva.
 *
 * <p>Es el paso de en medio de la app: así «elige tu contraseña» solo se
 * enseña cuando el código ya sirve, en vez de descubrir al final que estaba
 * mal y perder lo escrito. No lo consume: eso lo hace el restablecimiento.
 */
@Getter
@Setter
public class VerifyResetCodeRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Pattern(regexp = "\\d{6}", message = "El código tiene seis dígitos.")
    private String code;
}
