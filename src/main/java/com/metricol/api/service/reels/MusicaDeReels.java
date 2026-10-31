package com.metricol.api.service.reels;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.metricol.api.entity.Workspace;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.repository.WorkspaceRepository;

/**
 * La música de cada cuenta: si los reels que se arman con fotos llevan, si los
 * videos que se suben llevan de fondo, y con qué ambiente. Sin tocar nada, los
 * reels llevan música elegida por el giro del negocio y los videos subidos se
 * quedan con su sonido.
 */
@Service
public class MusicaDeReels {

    /**
     * @param activo   música en los reels de fotos
     * @param ambiente nulo = lo elige el giro del negocio
     * @param videos   música de fondo en los videos que se suben; al guardar,
     *                 nulo = dejarlo como estaba
     */
    public record Ajustes(boolean activo, String ambiente, Boolean videos) {
    }

    private final WorkspaceRepository workspaces;
    private final ReelMusicCatalog catalogo;

    public MusicaDeReels(WorkspaceRepository workspaces, ReelMusicCatalog catalogo) {
        this.workspaces = workspaces;
        this.catalogo = catalogo;
    }

    /** Sin la cuenta a la mano, lo de siempre: con música, elegida por el giro. */
    public Ajustes ajustes(UUID workspaceId) {
        return workspaces.findById(workspaceId).map(this::ajustesDe).orElse(new Ajustes(true, null, false));
    }

    /** Un ambiente que ya no está en el catálogo cuenta como "automático", no rompe el reel. */
    public Ajustes ajustesDe(Workspace espacio) {
        String ambiente = espacio.getMusicaAmbiente();
        return new Ajustes(!Boolean.FALSE.equals(espacio.getMusicaReels()),
                ambiente != null && catalogo.ambientes().contains(ambiente) ? ambiente : null,
                Boolean.TRUE.equals(espacio.getMusicaVideos()));
    }

    public Ajustes guardar(UUID workspaceId, Ajustes nuevos) {
        String ambiente = nuevos.ambiente() == null || nuevos.ambiente().isBlank() ? null : nuevos.ambiente().strip();
        if (ambiente != null && !catalogo.ambientes().contains(ambiente)) {
            throw new IllegalArgumentException("Ese ambiente de música no existe.");
        }
        Workspace espacio = espacio(workspaceId);
        espacio.setMusicaReels(nuevos.activo());
        espacio.setMusicaAmbiente(ambiente);
        if (nuevos.videos() != null) {
            espacio.setMusicaVideos(nuevos.videos());
        }
        workspaces.save(espacio);
        return ajustesDe(espacio);
    }

    private Workspace espacio(UUID workspaceId) {
        return workspaces.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Ese negocio no existe."));
    }
}
