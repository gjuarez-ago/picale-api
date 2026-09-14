package com.metricol.api.models.request;

import com.metricol.api.enums.ObjetivoRedes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WorkspaceUpdateRequest {

    @NotBlank
    private String name;

    private String logoUrl;

    private String uploadPostProfile;

    /**
     * El contexto del negocio. Nulo significa "no lo toques", no "borralo":
     * ver {@code WorkspaceService.update}.
     */
    @Size(max = 120)
    private String giro;

    @Size(max = 120)
    private String ciudad;

    @Size(max = 500)
    private String descripcion;

    private ObjetivoRedes objetivo;
}
