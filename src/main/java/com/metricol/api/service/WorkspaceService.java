package com.metricol.api.service;

import org.springframework.stereotype.Service;

import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.request.WorkspaceUpdateRequest;
import com.metricol.api.models.response.WorkspaceResponse;
import com.metricol.api.repository.WorkspaceRepository;

@Service
public class WorkspaceService {

    private final WorkspaceRepository repository;

    public WorkspaceService(WorkspaceRepository repository) {
        this.repository = repository;
    }

    public WorkspaceResponse get(User currentUser) {
        return toResponse(findOrThrow(currentUser));
    }

    public WorkspaceResponse update(User currentUser, WorkspaceUpdateRequest request) {
        Workspace workspace = findOrThrow(currentUser);
        workspace.setName(request.getName());
        workspace.setLogoUrl(request.getLogoUrl());
        workspace.setUploadPostProfile(request.getUploadPostProfile());

        // El contexto del negocio solo se toca si viene, al reves que los de
        // arriba. Es a proposito: una app ya instalada no conoce estos campos
        // y manda el resto; con la regla de siempre los dejaria en blanco sin
        // que nadie lo pidiera, y ahi se va la mitad de lo que hace util a la
        // IA. Para borrar uno se manda vacio, no nulo.
        if (request.getGiro() != null) {
            workspace.setGiro(limpio(request.getGiro()));
        }
        if (request.getCiudad() != null) {
            workspace.setCiudad(limpio(request.getCiudad()));
        }
        if (request.getDescripcion() != null) {
            workspace.setDescripcion(limpio(request.getDescripcion()));
        }
        if (request.getObjetivo() != null) {
            workspace.setObjetivo(request.getObjetivo());
        }
        return toResponse(repository.save(workspace));
    }

    private Workspace findOrThrow(User currentUser) {
        return repository.findById(currentUser.getWorkspace().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Workspace no encontrado."));
    }

    private WorkspaceResponse toResponse(Workspace workspace) {
        return WorkspaceResponse.builder()
                .id(workspace.getId())
                .name(workspace.getName())
                .logoUrl(workspace.getLogoUrl())
                .uploadPostProfile(workspace.getUploadPostProfile())
                .giro(workspace.getGiro())
                .ciudad(workspace.getCiudad())
                .descripcion(workspace.getDescripcion())
                .objetivo(workspace.getObjetivo())
                .build();
    }

    /**
     * Recorta y convierte el vacio en nulo.
     *
     * <p>Guardar " " o "" seria peor que no guardar nada: el prompt de la IA
     * acabaria con una linea "Giro:" sin giro, que es ruido que cuesta tokens
     * y no dice nada.
     */
    private String limpio(String valor) {
        String recortado = valor.strip();
        return recortado.isEmpty() ? null : recortado;
    }
}
