package com.metricol.api.service.brand;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.exception.MarcaIncompletaException;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.response.BrandResponse.Completitud;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.BrandService;

/** Regla única que autoriza operaciones productivas según el contexto de marca. */
@Component
public class BrandAccess {

    private final WorkspaceRepository workspaces;

    public BrandAccess(WorkspaceRepository workspaces) {
        this.workspaces = workspaces;
    }

    @Transactional(readOnly = true)
    public Completitud estado(User user) {
        if (user == null || user.getWorkspace() == null) {
            throw new ResourceNotFoundException("Espacio de trabajo no encontrado.");
        }
        return estado(user.getWorkspace().getId());
    }

    @Transactional(readOnly = true)
    public Completitud estado(java.util.UUID workspaceId) {
        Workspace actual = workspaces.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Espacio de trabajo no encontrado."));
        return BrandService.completitud(actual);
    }

    @Transactional(readOnly = true)
    public void exigirCompleta(User user) {
        Completitud estado = estado(user);
        exigir(estado);
    }

    @Transactional(readOnly = true)
    public void exigirCompleta(java.util.UUID workspaceId) {
        exigir(estado(workspaceId));
    }

    private static void exigir(Completitud estado) {
        if (estado.percent() < 100) {
            throw new MarcaIncompletaException(estado.percent(), estado.faltan());
        }
    }
}
