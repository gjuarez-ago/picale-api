package com.metricol.api.models.response;

import com.metricol.api.enums.ObjetivoRedes;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceResponse {
    private UUID id;
    private String name;
    private String logoUrl;
    private String uploadPostProfile;

    /** El contexto del negocio. Lo pinta Perfil y lo consume el Redactor. */
    private String giro;

    private String ciudad;

    private String descripcion;

    private ObjetivoRedes objetivo;
}
