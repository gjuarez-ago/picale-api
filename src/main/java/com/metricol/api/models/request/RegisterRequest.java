package com.metricol.api.models.request;

import com.metricol.api.enums.ObjetivoRedes;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRequest {

    @NotBlank
    @Size(min = 2, max = 120)
    private String workspaceName;

    @NotBlank
    @Size(min = 2, max = 120)
    private String name;

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 6, max = 100)
    private String password;

    /**
     * Lo que conto de su negocio al registrarse: giro, ciudad, a que se dedica
     * y que busca.
     *
     * <p>Todos opcionales, y esa es la decision: los pasos 2 y 3 del registro
     * llevan "Omitir". Una cuenta creada a medias vale mas que una persona que
     * se fue en el formulario, y lo que falte se completa despues en Perfil.
     */
    @Size(max = 120)
    private String giro;

    @Size(max = 120)
    private String ciudad;

    @Size(max = 500)
    private String descripcion;

    private ObjetivoRedes objetivo;
}
