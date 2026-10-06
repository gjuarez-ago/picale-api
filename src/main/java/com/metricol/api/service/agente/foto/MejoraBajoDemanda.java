package com.metricol.api.service.agente.foto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.Post;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.PostStatus;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.PostRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.agente.PresupuestoDelAsistente;
import com.metricol.api.service.agente.TrabajoDeFondo;
import com.metricol.api.service.avisos.AvisosPush;
import com.metricol.api.service.billing.CreditService;
import com.metricol.api.service.campaign.LogoSobreFoto;

/**
 * "Mejorarla con IA": la mejora de una propuesta, solo cuando la persona la
 * pide. Cuesta créditos (los de una generación) y entra en el presupuesto del
 * asistente; si no sale, o sale pero no se ve mejor, se devuelven.
 *
 * <p>Usa la dirección que el director de foto ya dejó en la propuesta: no se
 * vuelve a mirar la foto ni a pagar por eso. Tarda un minuto o dos: se pide,
 * se contesta, y el aviso al teléfono dice cuando está.
 */
@Component
public class MejoraBajoDemanda {

    private static final Logger log = LoggerFactory.getLogger(MejoraBajoDemanda.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Lo que se guarda en la propuesta para mejorarla después. */
    public record Guardada(DirectorDeFoto.Direccion direccion, boolean conLogo, boolean historia) {
    }

    public static String guardar(DirectorDeFoto.Direccion d, boolean conLogo, boolean historia) {
        try {
            String json = JSON.writeValueAsString(new Guardada(d, conLogo, historia));
            return json.length() <= 6000 ? json : null;
        } catch (Exception ex) {
            return null;
        }
    }

    static Guardada leer(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, Guardada.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private final PostRepository posts;
    private final MediaAssetRepository assets;
    private final WorkspaceRepository workspaces;
    private final MejoraDeFoto mejora;
    private final LogoSobreFoto logo;
    private final CreditService creditos;
    private final PresupuestoDelAsistente presupuesto;
    private final AvisosPush avisos;
    private final TrabajoDeFondo fondo;

    public MejoraBajoDemanda(PostRepository posts, MediaAssetRepository assets, WorkspaceRepository workspaces,
            MejoraDeFoto mejora, LogoSobreFoto logo, CreditService creditos, PresupuestoDelAsistente presupuesto,
            AvisosPush avisos, TrabajoDeFondo fondo) {
        this.posts = posts;
        this.assets = assets;
        this.workspaces = workspaces;
        this.mejora = mejora;
        this.logo = logo;
        this.creditos = creditos;
        this.presupuesto = presupuesto;
        this.avisos = avisos;
        this.fondo = fondo;
    }

    /**
     * Pide la mejora: cobra, la marca "mejorando" y la hace en segundo plano.
     *
     * @throws IllegalStateException si no se puede (ya va, no hay qué mejorar, sin presupuesto)
     * @throws com.metricol.api.exception.QuotaExceededException si no alcanzan los créditos
     */
    public void pedir(UUID postId, UUID workspaceId) {
        Post post = posts.findById(postId)
                .filter(p -> p.delAgente() && p.getStatus() == PostStatus.DRAFT && p.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalStateException("Esta propuesta ya no está esperando aprobación."));
        if (Boolean.TRUE.equals(post.getAgenteMejorando())) {
            throw new IllegalStateException("Ya la estoy mejorando: te aviso cuando esté.");
        }
        Guardada g = leer(post.getAgenteDireccion());
        if (g == null || g.direccion() == null || !g.direccion().mejorar() || post.getAgenteFotoUrl() == null) {
            throw new IllegalStateException("Esta foto no necesita mejora.");
        }
        if (!presupuesto.alcanza(workspaceId)) {
            throw new IllegalStateException(
                    "Llegaste al presupuesto del asistente de este mes. Puedes subirlo en Asistente.");
        }
        String referencia = "mejora-" + postId + "-" + UUID.randomUUID();
        creditos.consumirGeneracion(workspaceId, referencia);
        post.setAgenteMejorando(true);
        posts.save(post);
        fondo.enEspacio(workspaceId, () -> ejecutar(postId, workspaceId, referencia));
    }

    void ejecutar(UUID postId, UUID workspaceId, String referencia) {
        Post post = posts.findById(postId).orElse(null);
        Workspace w = workspaces.findById(workspaceId).orElse(null);
        if (post == null || w == null) {
            creditos.devolverGeneracion(workspaceId, referencia);
            return;
        }
        Guardada g = leer(post.getAgenteDireccion());
        MediaAsset original = assets.findByUrlIn(List.of(post.getAgenteFotoUrl())).stream().findFirst().orElse(null);
        MejoraDeFoto.Mejorada r = original == null || g == null
                ? new MejoraDeFoto.Mejorada(null, "Ya no encuentro la foto original.")
                : mejora.mejorar(original, workspaceId, g.direccion());
        try {
            if (r.salio()) {
                DirectorDeFoto.Direccion d = g.direccion();
                String acabada = logo.acabar(r.asset(), w.getLogoUrl(), workspaceId,
                        new LogoSobreFoto.Acabado(post.getAgenteAcabado() != null ? post.getAgenteAcabado() : d.estilo(),
                                d.logoZona(), d.logoTamano().ancho, d.encuadre(), d.rotulo(), w.getName(), g.historia()),
                        g.conLogo());
                post.setMediaUrls(new ArrayList<>(List.of(acabada != null ? acabada : r.asset().getUrl())));
                post.setAgenteTratamiento("MEJORA");
                post.setAgenteMotivo(motivo(post.getAgenteMotivo(), "La mejoré con IA como pediste ("
                        + d.deficienciasEnFrase() + ")" + (d.quitar().isEmpty() ? "." : ", y le quité lo que distraía.")));
                avisos.avisarAlEquipo(workspaceId, "Tu foto mejorada está lista",
                        "La mejoré con IA. Revísala y apruébala cuando quieras.", Map.of("tipo", "nuevas"));
            } else {
                creditos.devolverGeneracion(workspaceId, referencia);
                post.setAgenteMotivo(motivo(post.getAgenteMotivo(), r.noSalio() + " No se te cobró."));
                avisos.avisarAlEquipo(workspaceId, "No mejoré la foto", r.noSalio() + " No se te cobró.",
                        Map.of("tipo", "nuevas"));
            }
            // Ya se intentó: no se vuelve a ofrecer.
            post.setAgenteMejoraSugerida(null);
        } catch (RuntimeException ex) {
            log.warn("La mejora pedida de {} falló al guardarse: {}", postId, ex.toString());
            creditos.devolverGeneracion(workspaceId, referencia);
        } finally {
            post.setAgenteMejorando(false);
            posts.save(post);
        }
    }

    private static String motivo(String antes, String agregado) {
        String m = (antes == null ? "" : antes.strip() + " ") + agregado;
        return m.length() <= 1000 ? m : m.substring(m.length() - 1000);
    }
}
