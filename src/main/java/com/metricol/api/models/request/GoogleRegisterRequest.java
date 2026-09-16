package com.metricol.api.models.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Crear la cuenta con Google: el token, y lo que la persona pudo corregir.
 *
 * <p>No lleva contraseña y es a propósito: quien se registra así entra siempre
 * por Google, y pedirle además una contraseña que nunca va a usar es un campo
 * de más en el único momento en que se está decidiendo si quedarse.
 *
 * <p>El nombre y el del negocio llegan del formulario y no del token porque en
 * el registro se pueden editar: Google entrega el nombre de la persona, y el
 * negocio casi nunca se llama igual.
 */
@Getter
@Setter
public class GoogleRegisterRequest {

    @NotBlank
    private String idToken;

    /** Si viene vacío se usa el nombre que dé Google. */
    @Size(max = 120)
    private String name;

    /** Si viene vacío se usa {@link #name}. */
    @Size(max = 120)
    private String workspaceName;
}
