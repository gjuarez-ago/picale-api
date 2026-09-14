package com.metricol.api.models.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Restablecer la contraseña con el código que llegó al correo.
 *
 * <p>Es para quien <b>no puede entrar</b>; quien ya tiene sesión la cambiará
 * desde su cuenta, y ahí sí se le pedirá la actual.
 *
 * <p>La contraseña nueva se valida con la misma regla que el registro. Esta es
 * justo la puerta que usa alguien que acaba de perder el control de su cuenta,
 * y dejar que quede con una sola letra sería abrirle la puerta al siguiente.
 */
@Getter
@Setter
public class ResetPasswordRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Pattern(regexp = "\\d{6}", message = "El código tiene seis dígitos.")
    private String code;

    @NotBlank
    @Size(min = 6, max = 100)
    private String newPassword;
}
