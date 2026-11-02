package com.metricol.api.service.agente;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.EtapaAgente;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.response.PostResponse;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.media.subtitulos.SubtitulosAutomaticos;

import jakarta.annotation.PreDestroy;

/**
 * «Crear publicación» sin preguntas: la persona elige lo que va a publicar
 * (un video, o una o varias fotos) y Pícale lo trabaja como trabaja lo que se
 * sube —subtítulos, música, recorte, texto, redes y hora— pero ya, uno a la
 * vez, y le enseña la propuesta para aprobar. Pedido de Gabriel (2 nov 2026):
 * «técnicamente es como subir un archivo y dejarlo para trabajarlo, pero solo
 * uno, no masivo».
 *
 * <p>Vuelve de inmediato; el trabajo va en un hilo propio. Un video primero
 * espera su versión final (la edición automática): la propuesta sale con
 * ella, nunca con el original. La pantalla pregunta {@link #estado} hasta que
 * hay propuesta, o algo que la persona tenga que decidir.
 */
@Service
public class CreacionManual {

    private static final Logger log = LoggerFactory.getLogger(CreacionManual.class);

    /** Lo más que se espera a que un video tenga su versión final. */
    static final int ESPERA_EDICION_SEGUNDOS = 15 * 60;
    static final int CADA_SEGUNDOS = 5;
    /** Un carrusel admite hasta 10. */
    static final int MAX_FOTOS = 10;

    /**
     * En qué va, para la pantalla.
     *
     * @param fase EDITANDO (subtítulos, música o recorte), PREPARANDO (mirándolo
     *             y escribiendo), LISTA (hay propuesta), DECIDIR (no va con la
     *             marca o tiene poca calidad: la persona decide) o ERROR
     */
    public record Estado(String fase, String mensaje, List<PostResponse> propuestas) {
    }

    private final MediaAssetRepository assets;
    private final AgenteService agente;
    private final ExecutorService hilo = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "crear-manual");
        t.setDaemon(true);
        return t;
    });

    public CreacionManual(MediaAssetRepository assets, AgenteService agente) {
        this.assets = assets;
        this.agente = agente;
    }

    @PreDestroy
    void apagar() {
        hilo.shutdownNow();
    }

    /**
     * Revisa que se pueda y lo encarga. Un video va solo; las fotos, hasta
     * {@link #MAX_FOTOS}, en una sola publicación.
     */
    public void pedir(List<UUID> ids, UUID workspaceId) {
        List<MediaAsset> elegidos = elegidos(ids, workspaceId);
        boolean hayVideo = elegidos.stream().anyMatch(a -> a.getType() == MediaType.VIDEO);
        if (hayVideo && elegidos.size() > 1) {
            throw new IllegalArgumentException("Un video va solo. Para varias, elige solo fotos.");
        }
        if (elegidos.size() > MAX_FOTOS) {
            throw new IllegalArgumentException("Un carrusel lleva hasta " + MAX_FOTOS + " fotos.");
        }
        for (MediaAsset a : elegidos) {
            if (a.getAgenteEtapa() == EtapaAgente.PROPUESTA || a.getAgenteEtapa() == EtapaAgente.APROBADA) {
                throw new IllegalStateException("«" + a.getFileName()
                        + "» ya tiene una propuesta: está en Pendientes o ya la aprobaste.");
            }
        }
        String tenant = workspaceId.toString();
        List<UUID> orden = elegidos.stream().map(MediaAsset::getId).toList();
        hilo.execute(() -> TenantIdentifierResolver.comoTenant(tenant, () -> trabajar(orden, workspaceId)));
    }

    void trabajar(List<UUID> ids, UUID workspaceId) {
        try {
            esperarEdicion(ids.get(0));
            agente.trabajarPedido(ids, workspaceId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            log.warn("Crear publicación con {}: {}", ids, ex.toString());
            String porQue = ex.getMessage() == null ? "algo falló" : ex.getMessage();
            for (UUID id : ids) {
                assets.findById(id).ifPresent(a -> {
                    if (a.getAgenteEtapa() != EtapaAgente.PROPUESTA) {
                        a.setAgenteEtapa(EtapaAgente.PENDIENTE);
                        a.setAgenteMotivo("No pude prepararla: " + porQue);
                        assets.save(a);
                    }
                });
            }
        }
    }

    /** Un video espera su versión final y su portada; una foto, nada. */
    private void esperarEdicion(UUID id) throws InterruptedException {
        for (int s = 0; s < ESPERA_EDICION_SEGUNDOS; s += CADA_SEGUNDOS) {
            MediaAsset a = assets.findById(id).orElse(null);
            if (a == null || a.getType() != MediaType.VIDEO) {
                return;
            }
            boolean editando = SubtitulosAutomaticos.ESPERANDO.equals(a.getSubtitulosEstado())
                    || SubtitulosAutomaticos.HACIENDO.equals(a.getSubtitulosEstado());
            boolean sinPortada = a.getThumbnailUrl() == null || a.getThumbnailUrl().isBlank();
            if (!editando && !sinPortada) {
                return;
            }
            Thread.sleep(CADA_SEGUNDOS * 1000L);
        }
        throw new IllegalStateException("La versión final está tardando más de lo normal; inténtalo en un rato.");
    }

    /** En qué va lo que se pidió. */
    public Estado estado(List<UUID> ids, UUID workspaceId) {
        List<MediaAsset> elegidos = elegidos(ids, workspaceId);
        Set<String> urls = new LinkedHashSet<>();
        for (MediaAsset a : elegidos) {
            urls.add(a.getUrl());
            assets.copiasEditadas(a.getId()).forEach(c -> urls.add(c.getUrl()));
        }
        List<PostResponse> propuestas = agente.propuestasDeArchivos(urls);
        if (!propuestas.isEmpty()) {
            return new Estado("LISTA", "Lista para que la apruebes.", propuestas);
        }
        MediaAsset primero = elegidos.get(0);
        if (SubtitulosAutomaticos.ESPERANDO.equals(primero.getSubtitulosEstado())
                || SubtitulosAutomaticos.HACIENDO.equals(primero.getSubtitulosEstado())) {
            return new Estado("EDITANDO", "Preparando la versión final: subtítulos, música y recorte.", List.of());
        }
        for (MediaAsset a : elegidos) {
            EtapaAgente e = a.getAgenteEtapa();
            if (e == EtapaAgente.OBSERVACION || e == EtapaAgente.DESCARTADA) {
                return new Estado("DECIDIR", a.getAgenteMotivo(), List.of());
            }
            if (e == EtapaAgente.PENDIENTE && a.getAgenteMotivo() != null
                    && a.getAgenteMotivo().startsWith("No pude prepararla")) {
                return new Estado("ERROR", a.getAgenteMotivo(), List.of());
            }
        }
        return new Estado("PREPARANDO", "Mirándola y escribiendo el texto…", List.of());
    }

    private List<MediaAsset> elegidos(List<UUID> ids, UUID workspaceId) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Elige una foto o un video.");
        }
        List<MediaAsset> elegidos = new ArrayList<>();
        for (UUID id : new LinkedHashSet<>(ids)) {
            MediaAsset a = assets.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Ese archivo ya no existe."));
            if (!workspaceId.toString().equals(a.getTenantId())) {
                throw new ResourceNotFoundException("Ese archivo no es de este negocio.");
            }
            if (a.getStatus() != MediaAssetStatus.READY) {
                throw new IllegalStateException("«" + a.getFileName() + "» todavía se está subiendo.");
            }
            elegidos.add(a);
        }
        return elegidos;
    }
}
