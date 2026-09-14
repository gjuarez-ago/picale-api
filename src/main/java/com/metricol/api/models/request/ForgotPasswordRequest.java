package com.metricol.api.models.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** «Olvidé mi contraseña»: solo el correo. La respuesta es la misma exista o no. */
@Getter
@Setter
public class ForgotPasswordRequest {

    @NotBlank
    @Email
    private String email;
}
