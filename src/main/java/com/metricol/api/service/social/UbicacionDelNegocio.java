package com.metricol.api.service.social;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.config.UploadPostProperties;
import com.metricol.api.entity.Workspace;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.repository.WorkspaceRepository;

/**
 * La ubicación con la que salen las publicaciones del negocio. Se configura una
 * vez y se manda sola en cada envío a Instagram y TikTok (ver
 * {@link UploadPostClient#ubicacion}).
 */
@Service
public class UbicacionDelNegocio {

    private static final Logger log = LoggerFactory.getLogger(UbicacionDelNegocio.class);

    /** Lo que se ve y se edita en la pantalla. */
    public record Vista(String nombre, String instagramId, String tiktokId, String tiktokNombre) {
    }

    /**
     * @param instagram el enlace de la ubicación en Instagram o su número; vacío = quitarla
     */
    public record Pedido(String nombre, String instagram, String tiktokId, String tiktokNombre) {
    }

    private final WorkspaceRepository workspaces;
    private final UploadPostClient client;
    private final UploadPostProperties props;

    public UbicacionDelNegocio(WorkspaceRepository workspaces, UploadPostClient client, UploadPostProperties props) {
        this.workspaces = workspaces;
        this.client = client;
        this.props = props;
    }

    public Vista ver(UUID workspaceId) {
        Workspace w = workspace(workspaceId);
        return new Vista(w.getUbicacionNombre(), w.getUbicacionInstagramId(), w.getUbicacionTiktokId(),
                w.getUbicacionTiktokNombre());
    }

    @Transactional
    public Vista guardar(UUID workspaceId, Pedido pedido) {
        Workspace w = workspace(workspaceId);
        String instagram = limpio(pedido.instagram());
        String idInstagram = instagram == null ? null : Ubicacion.idDeInstagram(instagram);
        if (instagram != null && idInstagram == null) {
            throw new IllegalArgumentException("Pega el enlace de la ubicación en Instagram "
                    + "(instagram.com/explore/locations/…) o solo su número.");
        }
        String tiktokId = limpio(pedido.tiktokId());
        String tiktokNombre = limpio(pedido.tiktokNombre());
        if ((tiktokId == null) != (tiktokNombre == null)) {
            throw new IllegalArgumentException("Elige el lugar de TikTok de la lista: TikTok pide su id y su nombre.");
        }
        String nombre = limpio(pedido.nombre());
        if (nombre == null) {
            nombre = tiktokNombre;
        }
        w.setUbicacionNombre(recortar(nombre, 200));
        w.setUbicacionInstagramId(idInstagram);
        w.setUbicacionTiktokId(recortar(tiktokId, 80));
        w.setUbicacionTiktokNombre(recortar(tiktokNombre, 200));
        workspaces.save(w);
        return ver(workspaceId);
    }

    /** Lugares de TikTok que coinciden con lo escrito; vacío si upload-post no contesta. */
    public List<UploadPostClient.LugarTiktok> buscarEnTiktok(UUID workspaceId, String texto) {
        String q = limpio(texto);
        if (q == null || q.length() < 2 || !props.isConfigured()) {
            return List.of();
        }
        Workspace w = workspace(workspaceId);
        String perfil = w.getUploadPostProfile() == null || w.getUploadPostProfile().isBlank()
                ? w.getId().toString() : w.getUploadPostProfile();
        try {
            return client.buscarLugaresTiktok(perfil, q.length() > 100 ? q.substring(0, 100) : q);
        } catch (Exception ex) {
            log.warn("No se pudo buscar lugares de TikTok ({}): {}", q, ex.toString());
            return List.of();
        }
    }

    private Workspace workspace(UUID id) {
        return workspaces.findById(id).orElseThrow(() -> new ResourceNotFoundException("Espacio no encontrado."));
    }

    private static String limpio(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String recortar(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
