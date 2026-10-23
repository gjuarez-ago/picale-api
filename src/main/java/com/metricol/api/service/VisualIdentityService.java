package com.metricol.api.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.metricol.api.entity.*;
import com.metricol.api.enums.*;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.request.VisualIdentityRequest;
import com.metricol.api.repository.*;

@Service
public class VisualIdentityService {
    private final WorkspaceRepository workspaces;
    private final MediaAssetRepository assets;
    public VisualIdentityService(WorkspaceRepository workspaces, MediaAssetRepository assets) {
        this.workspaces = workspaces; this.assets = assets;
    }
    @Transactional(readOnly = true)
    public VisualIdentity get(User user) { return of(workspace(user)); }
    public static VisualIdentity of(Workspace workspace) {
        return workspace.getBrandProfile() == null || workspace.getBrandProfile().visualIdentity() == null
                ? VisualIdentity.EMPTY : workspace.getBrandProfile().visualIdentity();
    }
    @Transactional
    public VisualIdentity update(User user, VisualIdentityRequest request) {
        if (!request.confirmed() || request.expectedRevision() == null) throw new IllegalArgumentException("Confirma la propuesta visual antes de guardarla.");
        Workspace w = workspaces.findLockedById(user.getWorkspace().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Espacio no disponible."));
        VisualIdentity old = of(w);
        if (request.expectedRevision() != old.revision()) throw new IllegalArgumentException("La identidad visual cambió. Consulta la versión actual antes de guardar.");
        List<UUID> approved = references(w, request.approvedReferences(), old.approvedReferences());
        List<UUID> rejected = references(w, request.rejectedReferences(), old.rejectedReferences());
        List<UUID> chispa = references(w, request.chispaReferences(), old.chispaReferences());
        if (approved.stream().anyMatch(rejected::contains) || chispa.stream().anyMatch(rejected::contains))
            throw new IllegalArgumentException("Una referencia no puede estar aprobada y rechazada a la vez.");
        VisualIdentity next = new VisualIdentity(request.colors() == null ? old.colors() : request.colors().stream().map(String::toUpperCase).distinct().toList(),
                merge(request.style(), old.style(), 600), approved, rejected, chispa,
                merge(request.avoid(), old.avoid(), 500), merge(request.preserve(), old.preserve(), 500), merge(request.adaptable(), old.adaptable(), 500),
                request.logoTreatment() == null ? old.logoTreatment() : request.logoTreatment(),
                request.logoBackground() == null ? old.logoBackground() : request.logoBackground(),
                request.allowShapeChange() == null ? old.allowShapeChange() : request.allowShapeChange(),
                old.revision() + 1, Instant.now().toString(), user.getId());
        w.setBrandProfile((w.getBrandProfile() == null ? BrandProfile.VACIO : w.getBrandProfile()).withVisualIdentity(next));
        workspaces.save(w);
        return next;
    }
    private List<UUID> references(Workspace w, List<UUID> requested, List<UUID> old) {
        if (requested == null) return old;
        for (UUID id : requested) assets.findById(id)
                .filter(a -> w.getId().toString().equals(a.getTenantId()) && a.getType() == MediaType.IMAGE && a.getStatus() == MediaAssetStatus.READY)
                .orElseThrow(() -> new ResourceNotFoundException("Una referencia no está disponible en este espacio."));
        return requested.stream().distinct().toList();
    }
    private static String merge(String next, String old, int max) { return next == null ? old : BrandService.texto(next, max); }
    private Workspace workspace(User user) {
        return workspaces.findById(user.getWorkspace().getId()).orElseThrow(() -> new ResourceNotFoundException("Espacio no disponible."));
    }
}
