package com.metricol.api.service;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.metricol.api.repository.WorkspaceRepository;

/**
 * ¿Este espacio pertenece a una organización sin límites ni vigencia?
 *
 * <p>Es la cuenta de la casa (Pícale usando Pícale): no paga, no vence y no se topa con los
 * cupos que existen para proteger la plataforma de un cliente cualquiera. La bandera vive en la
 * organización ({@code Organization.sinLimites}) y se enciende solo desde el arranque, con la
 * cuenta raíz ({@link CuentaRaizService}); ningún endpoint la cambia, así que nadie se la puede
 * dar a sí mismo.
 *
 * <p>Se consulta por espacio y no por usuario porque los cupos (IA, publicaciones, créditos) se
 * cuentan por espacio, y algunos se cobran desde workers que no tienen sesión.
 */
@Component
public class CuentaSinLimites {

    private final WorkspaceRepository workspaces;

    public CuentaSinLimites(WorkspaceRepository workspaces) {
        this.workspaces = workspaces;
    }

    public boolean deWorkspace(UUID workspaceId) {
        return workspaceId != null && workspaces.organizacionSinLimites(workspaceId);
    }
}
