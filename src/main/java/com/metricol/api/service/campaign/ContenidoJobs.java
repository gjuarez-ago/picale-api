package com.metricol.api.service.campaign;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.dao.DataIntegrityViolationException;
import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.User;
import com.metricol.api.models.request.CampaignImageRequest;
import com.metricol.api.models.response.ContenidoEstadoResponse;
import com.metricol.api.service.campaign.CampaignImageService.*;
import jakarta.annotation.PreDestroy;

/** La base es la cola y la autoridad del estado. El executor solo presta hilos. */
@Service
public class ContenidoJobs {
    private static final Logger log = LoggerFactory.getLogger(ContenidoJobs.class);
    private final CampaignImageService generator;
    private final ContenidoJobStore store;
    private final UUID owner = UUID.randomUUID();
    private final Set<UUID> local = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final ExecutorService executor = Executors.newFixedThreadPool(3, task -> {
        Thread t = new Thread(task, "contenido-job"); t.setDaemon(true); return t;
    });
    public ContenidoJobs(CampaignImageService generator, ContenidoJobStore store) { this.generator=generator; this.store=store; }

    public String iniciar(User user, CampaignImageRequest request) {
        if (closing.get()) throw new IllegalStateException("El servicio se está reiniciando; no se inició una generación.");
        if (user.getWorkspace()==null) throw new IllegalStateException("Elige un espacio de trabajo para crear el contenido.");
        Preparado prepared = generator.prepararSinCobro(user, request);
        UUID id;
        try { id = store.create(prepared); }
        catch (DataIntegrityViolationException conflict) { throw new IllegalStateException("Ya estás creando contenido. Espera a que termine para empezar otro.", conflict); }
        submit(id);
        return id.toString();
    }
    public ContenidoEstadoResponse estado(User user, String id) {
        try {
            if (user.getWorkspace()==null) throw new IllegalArgumentException();
            return store.get(user.getWorkspace().getId(), UUID.fromString(id));
        } catch (IllegalArgumentException invalid) {
            throw new com.metricol.api.exception.ResourceNotFoundException("Ese contenido ya no está disponible.");
        }
    }
    @EventListener(ApplicationReadyEvent.class)
    public void ready() { recover(); }

    @Scheduled(fixedDelayString="${app.content-jobs.poll-ms:5000}")
    public void recover() {
        if (closing.get()) return;
        try { for (UUID id: store.candidates()) submit(id); }
        catch (RuntimeException error) { log.warn("No se pudo consultar la cola durable de contenido: {}", error.getClass().getSimpleName()); }
    }
    @Scheduled(fixedDelayString="${app.content-jobs.heartbeat-ms:15000}")
    public void heartbeat() {
        if (!closing.get() && !local.isEmpty()) {
            try { store.heartbeat(owner); }
            catch (RuntimeException error) { log.warn("No se pudo renovar el lease de los trabajos visuales."); }
        }
    }
    private void submit(UUID id) {
        if (closing.get() || local.size()>=3 || !local.add(id)) return;
        try { executor.submit(() -> run(id)); }
        catch (RejectedExecutionException error) { local.remove(id); /* Sigue QUEUED durable; otro poll lo recupera. */ }
    }
    private void run(UUID id) {
        try {
            var claim = store.claim(id, owner);
            if (claim.isEmpty()) return;
            ClaimedRun run = new ClaimedRun(claim.get());
            TenantIdentifierResolver.comoTenant(run.claim.workspace().toString(), run::execute);
        } catch (RuntimeException error) { log.warn("No se pudo adquirir el trabajo visual {}: {}", id, error.getClass().getSimpleName()); }
        finally { local.remove(id); }
    }
    private class ClaimedRun implements Progreso {
        final ContenidoJobStore.Claimed claim;
        ClaimedRun(ContenidoJobStore.Claimed claim) { this.claim=claim; }
        void execute() {
            try { store.finish(claim.id(), owner, generator.ejecutar(claim.prepared(), this)); }
            catch (Throwable error) {
                log.warn("Trabajo visual interrumpido {}: {}", claim.id(), error.getClass().getSimpleName());
                try { store.failed(claim.id(), owner); }
                catch (RuntimeException lostLease) { log.warn("El trabajo {} queda pendiente de recuperación por lease.", claim.id()); }
            }
        }
        public void antesDeProveedor() { store.beforeProvider(claim.id(), owner); }
        public UUID trabajoId() { return claim.id(); }
        public void etapa(String stage) { store.stage(claim.id(), owner, stage); }
        public void varianteLista(VarianteGenerada version) { store.version(claim.id(), owner, version, true); }
        public void piezaLista(VarianteGenerada partial) { store.version(claim.id(), owner, partial, false); }
    }
    @PreDestroy void cerrar() { closing.set(true); executor.shutdownNow(); }
}
