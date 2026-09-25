package com.metricol.api.models.request.root;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** A quién darle la administración de la plataforma: una cuenta que ya existe. */
@Getter
@Setter
public class AdministradorRequest {

    @NotBlank
    @Email
    private String email;
}
