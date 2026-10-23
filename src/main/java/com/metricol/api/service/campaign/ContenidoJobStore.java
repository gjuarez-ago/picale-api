package com.metricol.api.service.campaign;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.data.domain.PageRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.ContenidoJob;
import com.metricol.api.repository.ContenidoJobRepository;
import com.metricol.api.models.response.ContenidoEstadoResponse;
import com.metricol.api.models.response.ContenidoEstadoResponse.Version;
import com.metricol.api.service.billing.CreditService;
import com.metricol.api.service.campaign.CampaignImageService.*;
import com.metricol.api.exception.ResourceNotFoundException;

/** Transacciones cortas, independientes del HTTP y del proveedor. Jamás llama al proveedor. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class ContenidoJobStore {
    static final Duration LEASE = Duration.ofMinutes(3);
    private static final int MAX_SNAPSHOT = 96 * 1024 * 1024;
    private final ContenidoJobRepository rows;
    private final ObjectMapper json;
    private final CreditService credits;
    private final com.metricol.api.repository.MediaAssetRepository assets;
    public ContenidoJobStore(ContenidoJobRepository rows, ObjectMapper json, CreditService credits,
            com.metricol.api.repository.MediaAssetRepository assets) {
        this.rows = rows;
        this.json = json.copy();
        this.json.getFactory().setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                .maxStringLength(MAX_SNAPSHOT).build());
        this.credits = credits;
        this.assets = assets;
    }
    public UUID create(Preparado p) {
        UUID id = UUID.randomUUID();
        // Serialización y cobro dentro de la misma transacción que reserva el workspace.
        ContenidoJob j = new ContenidoJob();
        j.setId(id); j.setWorkspaceId(p.negocio().id()); j.setActiveWorkspace(p.negocio().id());
        j.setStatus("QUEUED"); j.setCreditReference(p.referenciaCredito());
        j.setRequestJson(write(Map.of("version",1,"request",p.peticion(),"identity",p.identity(),"business",p.negocio())));
        j.setCreatedAt(Instant.now()); j.setUpdatedAt(j.getCreatedAt());
        int remaining = Math.min(p.restantes(), credits.consumirGeneracion(p.negocio().id(), p.referenciaCredito()));
        p = CampaignImageService.conRestantes(p, remaining);
        j.setPrepared(pack(p));
        var versions = p.variantes().stream().map(v -> new Version(v.id(), v.lienzo().etiqueta,
                v.redes().stream().map(Enum::name).toList(), "PENDING", List.of(), null)).toList();
        j.setResponseJson(write(new ContenidoEstadoResponse(id.toString(), "QUEUED", "En cola…", versions,
                null, null, Map.of(), remaining == Integer.MAX_VALUE ? null : remaining, null)));
        rows.saveAndFlush(j); // La unicidad revierte también el débito si otro trabajo ganó.
        return id;
    }
    public List<UUID> candidates() { return rows.dispatchable(Instant.now(), PageRequest.of(0, 30)); }
    public void heartbeat(UUID owner) { Instant now = Instant.now(); rows.heartbeat(owner, now, now.plus(LEASE)); }
    public record Claimed(UUID id, UUID workspace, Preparado prepared) {}
    public Optional<Claimed> claim(UUID id, UUID owner) {
        var found = rows.locked(id);
        if (found.isEmpty()) return Optional.empty();
        ContenidoJob j = found.get();
        if (j.getActiveWorkspace() == null) return Optional.empty();
        Instant now = Instant.now();
        if (!"QUEUED".equals(j.getStatus()) && j.getLeaseUntil() != null && !j.getLeaseUntil().isBefore(now)) return Optional.empty();
        if (j.isExternalStarted()) {
            uncertain(j); // Otro worker perdió su lease: no repetir llamadas pagadas.
            return Optional.empty();
        }
        Preparado p;
        try { p = unpack(j.getPrepared()); }
        catch (RuntimeException corruptSnapshot) {
            credits.devolverGeneracion(j.getWorkspaceId(), j.getCreditReference());
            update(j, with(read(j), "FAILED", "No se pudo recuperar la preparación",
                    "No se inició la generación; se devolvieron los créditos internos que correspondían."));
            release(j);
            return Optional.empty();
        }
        j.setOwner(owner); j.setLeaseUntil(now.plus(LEASE));
        update(j, with(read(j), "THINKING", "Preparando la creación…", null));
        return Optional.of(new Claimed(id, j.getWorkspaceId(), p));
    }
    /** Fence durable ANTES de cualquier llamada pagada. El lease se verifica en cada envío. */
    public void beforeProvider(UUID id, UUID owner) { ContenidoJob j = owned(id, owner); j.setExternalStarted(true); }
    public void stage(UUID id, UUID owner, String stage) {
        ContenidoJob j = owned(id, owner);
        update(j, with(read(j), stage.startsWith("Pensando") ? "THINKING" : "CREATING", stage, null));
    }
    public void version(UUID id, UUID owner, VarianteGenerada v, boolean complete) {
        ContenidoJob j = owned(id, owner); var r = read(j);
        var versions = new ArrayList<>(r.versions());
        for (int n=0; n<versions.size(); n++) if (versions.get(n).id().equals(v.id())) {
            List<String> urls = v.urls().isEmpty() && !versions.get(n).imageUrls().isEmpty() ? versions.get(n).imageUrls() : v.urls();
            versions.set(n, new Version(v.id(), v.lienzo().etiqueta, v.redes().stream().map(Enum::name).toList(),
                    complete ? v.causa() == null ? "READY" : "FAILED" : "PENDING", urls, v.error(), v.review()));
        }
        update(j, new ContenidoEstadoResponse(r.id(), r.status(), r.stage(), versions, r.headline(), r.supportingCopy(), r.captions(), r.creditsRemaining(), r.error()));
    }
    public void finish(UUID id, UUID owner, Generado g) {
        ContenidoJob j = owned(id, owner); var r = read(j);
        boolean allKnown = g.variantes().stream().allMatch(v -> v.causa() == null);
        if (!allKnown) { uncertain(j); return; }
        var captions = new LinkedHashMap<String,String>(); g.captionsPorRed().forEach((k,v) -> captions.put(k.name(),v));
        update(j, new ContenidoEstadoResponse(r.id(), "READY", "Listo", r.versions(), g.titular(), g.subtitulo(), captions,
                g.restantes() == Integer.MAX_VALUE ? null : g.restantes(), null));
        release(j);
    }
    public void failed(UUID id, UUID owner) {
        ContenidoJob j = owned(id, owner);
        if (j.isExternalStarted()) { uncertain(j); return; }
        // Solo un fallo definitivamente anterior al proveedor permite devolución automática.
        credits.devolverGeneracion(j.getWorkspaceId(), j.getCreditReference());
        update(j, with(read(j), "FAILED", "No se pudo iniciar", "No se inició la generación. Se devolvieron los créditos internos que correspondían."));
        release(j);
    }
    public ContenidoEstadoResponse get(UUID workspace, UUID id) {
        ContenidoJob j = rows.findByIdAndWorkspaceId(id, workspace)
                .orElseThrow(() -> new ResourceNotFoundException("Ese contenido ya no está disponible."));
        return "UNAVAILABLE".equals(j.getStatus()) ? recoverPieces(j) : read(j);
    }
    private ContenidoJob owned(UUID id, UUID owner) {
        ContenidoJob j = rows.locked(id).orElseThrow();
        if (j.getActiveWorkspace() == null || !owner.equals(j.getOwner()) || j.getLeaseUntil() == null || j.getLeaseUntil().isBefore(Instant.now()))
            throw new IllegalStateException("El trabajador perdió la propiedad del trabajo; no se repetirá la generación.");
        return j;
    }
    private void uncertain(ContenidoJob j) {
        j.setResponseJson(write(recoverPieces(j)));
        update(j, with(read(j), "UNAVAILABLE", "Pendiente de verificar",
                "Se interrumpió el seguimiento. Conservamos las piezas guardadas; revisa Contenido antes de pedir otra generación. No se repitió ni se devolvieron créditos automáticamente."));
        release(j);
    }
    private ContenidoEstadoResponse recoverPieces(ContenidoJob j) {
        var r = read(j);
        var saved = assets.generatedForJob(j.getId(), j.getWorkspaceId().toString());
        var versions = new ArrayList<Version>();
        for (var v : r.versions()) {
            var pieces = saved.stream().filter(a -> v.id().equals(a.getGenerationVersion())).toList();
            var urls = new ArrayList<String>();
            for (var asset : pieces) if (asset.getUrl()!=null && !urls.contains(asset.getUrl())) urls.add(asset.getUrl());
            for (String url : v.imageUrls()) if (!urls.contains(url)) urls.add(url);
            versions.add(new Version(v.id(),v.ratio(),v.networks(),v.status(),List.copyOf(urls),v.error(),v.review()));
        }
        // Una lectura no pisa el estado del worker. Los assets se pueden recuperar incluso si llegaron tarde.
        return new ContenidoEstadoResponse(r.id(),r.status(),r.stage(),versions,r.headline(),r.supportingCopy(),r.captions(),r.creditsRemaining(),r.error());
    }
    private void release(ContenidoJob j) { j.setActiveWorkspace(null); j.setOwner(null); j.setLeaseUntil(null); j.setPrepared(null); }
    private void update(ContenidoJob j, ContenidoEstadoResponse r) { j.setStatus(r.status()); j.setResponseJson(write(r)); j.setUpdatedAt(Instant.now()); }
    private ContenidoEstadoResponse with(ContenidoEstadoResponse r, String status, String stage, String error) {
        return new ContenidoEstadoResponse(r.id(), status, stage, r.versions(), r.headline(), r.supportingCopy(), r.captions(), r.creditsRemaining(), error);
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (IOException e) { throw new IllegalStateException("No se pudo guardar el trabajo visual.",e); } }
    private ContenidoEstadoResponse read(ContenidoJob j) { try { return json.readValue(j.getResponseJson(), ContenidoEstadoResponse.class); } catch(IOException e) { throw new IllegalStateException("No se pudo leer el trabajo visual.",e); } }
    private byte[] pack(Preparado p) {
        try {
            byte[] raw = json.writeValueAsBytes(p);
            if (raw.length > MAX_SNAPSHOT) throw new IllegalArgumentException("Las referencias de este trabajo son demasiado grandes. Usa menos fotos o archivos más pequeños.");
            var bytes = new ByteArrayOutputStream();
            try (var zip = new GZIPOutputStream(bytes)) { zip.write(raw); }
            return bytes.toByteArray();
        } catch(IOException e) { throw new IllegalStateException("No se pudieron conservar las referencias del trabajo.", e); }
    }
    private Preparado unpack(byte[] value) {
        try (var zip = new GZIPInputStream(new ByteArrayInputStream(value))) {
            byte[] raw = zip.readNBytes(MAX_SNAPSHOT + 1);
            if (raw.length > MAX_SNAPSHOT) throw new IOException("Snapshot demasiado grande");
            return json.readValue(raw, Preparado.class);
        } catch(IOException e) { throw new IllegalStateException("No se pudo recuperar la preparación del trabajo.",e); }
    }
}
