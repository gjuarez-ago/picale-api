package com.metricol.api.service.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.metricol.api.config.MediaLimitsProperties;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.UploadDecision;
import com.metricol.api.exception.ConflictoException;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.response.MediaAssetResponse;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.MediaService;

/** Decisión durable + lease con token. El bloqueo de fila termina ANTES de hablar con R2. */
@Service
public class CoordinadorDeSubidas {
    public static final Duration LEASE = Duration.ofMinutes(5);
    public static final Duration RETENCION = Duration.ofHours(24);
    public static final Duration MARGEN = Duration.ofMinutes(10);
    private final MediaAssetRepository repository;
    private final R2StorageService storage;
    private final MediaLimitsProperties limites;
    private final TransactionTemplate tx;
    private MediaService media;
    private AperturaDeSubidas aperturas;
    @Autowired void setAperturas(AperturaDeSubidas aperturas) { this.aperturas = aperturas; }

    public CoordinadorDeSubidas(MediaAssetRepository repository, R2StorageService storage,
            MediaLimitsProperties limites, PlatformTransactionManager manager) {
        this.repository = repository;
        this.storage = storage;
        this.limites = limites;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Autowired
    void setMedia(@Lazy MediaService media) { this.media = media; }

    /** También para filas heredadas: adopción conservadora, una sola vez bajo bloqueo. */
    public void adoptar(MediaAsset a) {
        if (a.getUploadExpiresAt() == null) {
            long segundos = Math.max(RETENCION.toSeconds(),
                    limites.getMultipart().getTtlParteSegundos() + MARGEN.toSeconds());
            a.setUploadExpiresAt(Instant.now().plusSeconds(segundos));
        }
    }

    public static void exigirAbierta(MediaAsset a) {
        if (a.getStatus() != MediaAssetStatus.PENDING || a.getUploadDecision() != null
                || "SENT".equals(a.getUploadOpeningState()) || "RESERVED".equals(a.getUploadOpeningState())) {
            throw conflicto("UPLOAD_NOT_OPEN", "Esta subida no admite nuevas partes. Consulta su estado.");
        }
    }

    /** Se invoca con la fila bloqueada y antes de entregar firmas al cliente. */
    public long protegerFirmas(MediaAsset a, long ttl) {
        adoptar(a);
        exigirAbierta(a);
        Instant ahora = Instant.now();
        long disponible = Duration.between(ahora, a.getUploadExpiresAt().minus(MARGEN)).toSeconds();
        long vigencia = Math.min(ttl, disponible);
        if (vigencia <= 0) throw conflicto("UPLOAD_EXPIRED", "El plazo de esta subida terminó.");
        Instant protegida = ahora.plusSeconds(vigencia).plus(MARGEN);
        if (a.getUploadProtectedUntil() == null || protegida.isAfter(a.getUploadProtectedUntil())) {
            a.setUploadProtectedUntil(protegida);
        }
        return vigencia;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MediaAssetResponse completar(UUID id, UUID workspace) {
        MediaAsset inicial = buscar(id, workspace);
        if ("SENT".equals(inicial.getUploadOpeningState()) || "RESERVED".equals(inicial.getUploadOpeningState())) {
            aperturas.resolver(id, workspace);
        }
        UUID token = reclamar(id, workspace, false, false);
        if (token == null) return media.respuestaDeSubidaLista(id);
        try {
            return media.unirPartesCoordinadas(id, workspace, token);
        } catch (RuntimeException ex) {
            diferir(id, token, "COMPLETE_RETRY");
            throw ex;
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void cancelar(UUID id, UUID workspace) {
        cancelar(id, workspace, false);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean limpiar(UUID id, UUID workspace) {
        MediaAsset a = buscar(id, workspace);
        if (a.getStatus() == MediaAssetStatus.READY) return true;
        if (a.getUploadDecision() == UploadDecision.COMPLETING) {
            completar(id, workspace);
            return true;
        }
        if (a.getUploadDecision() == UploadDecision.CANCELLING || a.getUploadDecision() == UploadDecision.EXPIRING) {
            cancelar(id, workspace, a.getUploadDecision() == UploadDecision.EXPIRING);
        } else if (a.getUploadExpiresAt() != null && vencida(a, Instant.now())) {
            cancelar(id, workspace, true);
        }
        return repository.findById(id).map(m -> m.getStatus() == MediaAssetStatus.READY).orElse(false);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void recuperar(UUID id, UUID workspace) {
        MediaAsset a = buscar(id, workspace);
        if (a.getStatus() != MediaAssetStatus.PENDING) return;
        if (a.getUploadDecision() == UploadDecision.CANCELLING || a.getUploadDecision() == UploadDecision.EXPIRING
                || (a.getUploadDecision() != UploadDecision.COMPLETING && a.getUploadExpiresAt() != null
                    && vencida(a, Instant.now()))) {
            limpiar(id, workspace);
        } else {
            completar(id, workspace);
        }
    }

    /** CHECKING sólo lee. Antes de cualquier mutación R2 se persiste COMPLETING. */
    public void decidirCompletar(UUID id, UUID token) {
        tx.executeWithoutResult(s -> {
            MediaAsset a = bloqueada(id);
            exigirToken(a, token);
            if (a.getUploadDecision() != UploadDecision.CHECKING && a.getUploadDecision() != UploadDecision.COMPLETING) {
                throw conflicto("UPLOAD_DECISION_CONFLICT", "Otra operación decidió esta subida.");
            }
            a.setUploadDecision(UploadDecision.COMPLETING);
            repository.save(a);
        });
    }

    public static void exigirCompletar(MediaAsset a, UUID token) {
        exigirToken(a, token);
        if (a.getUploadDecision() != UploadDecision.COMPLETING) {
            throw conflicto("UPLOAD_DECISION_CONFLICT", "La subida no está cerrándose.");
        }
    }

    public static void terminada(MediaAsset a) {
        a.setUploadDecision(UploadDecision.DONE);
        a.setMultipartUploadId(null);
        a.setUploadLeaseOwner(null);
        a.setUploadLeaseUntil(null);
        a.setUploadNextAttemptAt(null);
        a.setUploadLastError(null);
    }

    private void cancelar(UUID id, UUID workspace, boolean expirar) {
        MediaAsset inicial = buscar(id, workspace);
        if ("SENT".equals(inicial.getUploadOpeningState())) {
            tx.executeWithoutResult(s -> {
                MediaAsset a = bloqueada(id);
                if ("SENT".equals(a.getUploadOpeningState()) && a.getStatus() == MediaAssetStatus.PENDING) {
                    if (a.getUploadDecision() == null) a.setUploadDecision(expirar ? UploadDecision.EXPIRING : UploadDecision.CANCELLING);
                    repository.save(a);
                }
            });
            MediaAsset recuperada = aperturas.resolver(id, workspace);
            if ("SENT".equals(recuperada.getUploadOpeningState())) {
                throw conflicto("UPLOAD_OPENING_UNCERTAIN", "La apertura se está reconciliando; se conserva la reserva.");
            }
        }
        UUID token = reclamar(id, workspace, true, expirar);
        if (token == null) return; // Tombstone: repetir cancelación no toca R2 ni cuota.
        try {
            MediaAsset a = buscar(id, workspace);
            if (a.getMultipartUploadId() != null) {
                storage.abortarPorPartes(a.getStorageKey(), a.getMultipartUploadId());
                if (!storage.partesSubidas(a.getStorageKey(), a.getMultipartUploadId()).isEmpty()) {
                    throw new IllegalStateException("R2 aún conserva partes. La limpieza se reintentará.");
                }
            }
            R2StorageService.Consulta objeto = storage.consultar(a.getStorageKey());
            if (objeto != null) {
                // Una unión heredada ya terminada gana: JAMÁS borrar el objeto final.
                if (a.getSizeBytes() == null || objeto.sizeBytes() != a.getSizeBytes()) {
                    throw new IllegalStateException("El objeto final necesita revisión; se conserva la reserva.");
                }
                tx.executeWithoutResult(s -> {
                    MediaAsset actual = bloqueada(id);
                    exigirToken(actual, token);
                    actual.setUploadDecision(UploadDecision.COMPLETING);
                    repository.save(actual);
                });
                media.confirmarSesionCoordinada(id, token);
                throw conflicto("UPLOAD_ALREADY_READY", "El archivo ya está disponible en Contenido.");
            }
            tx.executeWithoutResult(s -> {
                MediaAsset actual = bloqueada(id);
                exigirToken(actual, token);
                boolean vencio = actual.getUploadDecision() == UploadDecision.EXPIRING;
                actual.setUploadDecision(vencio ? UploadDecision.EXPIRED : UploadDecision.CANCELLED);
                actual.setStatus(MediaAssetStatus.RELEASED);
                actual.setMultipartUploadId(null);
                actual.setUploadLeaseOwner(null);
                actual.setUploadLeaseUntil(null);
                actual.setUploadNextAttemptAt(null);
                actual.setUploadLastError(null);
                repository.save(actual);
            });
        } catch (RuntimeException ex) {
            diferir(id, token, "ABORT_RETRY");
            throw ex;
        }
    }

    private UUID reclamar(UUID id, UUID workspace, boolean cancelar, boolean expirar) {
        return tx.execute(s -> {
            MediaAsset a = bloqueada(id);
            if (!workspace.toString().equals(a.getTenantId())) throw noExiste();
            if (a.getStatus() == MediaAssetStatus.READY) {
                if (cancelar) throw conflicto("UPLOAD_ALREADY_READY", "El archivo ya está disponible en Contenido.");
                return null;
            }
            if (a.getUploadDecision() == UploadDecision.CANCELLED || a.getUploadDecision() == UploadDecision.EXPIRED) {
                if (cancelar) return null;
                throw conflicto("UPLOAD_TERMINAL", "Esta subida fue cancelada o venció.");
            }
            if (a.getMultipartPartSize() == null || a.getStatus() != MediaAssetStatus.PENDING) {
                throw conflicto("UPLOAD_NOT_MULTIPART", "Esta subida no es una sesión por partes pendiente.");
            }
            if ("SENT".equals(a.getUploadOpeningState()) || (!cancelar && "RESERVED".equals(a.getUploadOpeningState()))) {
                throw conflicto("UPLOAD_OPENING_UNCERTAIN", "La apertura se está reconciliando.");
            }
            Instant ahora = Instant.now();
            adoptar(a);
            boolean abortando = a.getUploadDecision() == UploadDecision.CANCELLING
                    || a.getUploadDecision() == UploadDecision.EXPIRING;
            if (cancelar && a.getUploadDecision() == UploadDecision.COMPLETING) {
                throw conflicto("UPLOAD_COMPLETING", "El archivo está terminando. Consulta su estado.");
            }
            if (!cancelar && abortando) throw conflicto("UPLOAD_CANCELLING", "La subida se está cancelando.");
            if (a.getUploadLeaseUntil() != null && a.getUploadLeaseUntil().isAfter(ahora)) {
                throw conflicto("UPLOAD_BUSY", "La subida está siendo revisada. Intenta de nuevo en un momento.");
            }
            if (expirar && !abortando && !vencida(a, ahora)) {
                throw conflicto("UPLOAD_PROTECTED", "La sesión todavía está protegida.");
            }
            if (!cancelar && a.getUploadDecision() != UploadDecision.COMPLETING && vencida(a, ahora)) {
                throw conflicto("UPLOAD_EXPIRED", "El plazo de esta subida terminó.");
            }
            UUID token = UUID.randomUUID();
            a.setUploadDecision(cancelar ? (abortando ? a.getUploadDecision()
                    : expirar ? UploadDecision.EXPIRING : UploadDecision.CANCELLING)
                    : a.getUploadDecision() == UploadDecision.COMPLETING ? UploadDecision.COMPLETING : UploadDecision.CHECKING);
            a.setUploadLeaseOwner(token);
            a.setUploadLeaseUntil(ahora.plus(LEASE));
            a.setUploadAttempts(a.getUploadAttempts() == null ? 1 : a.getUploadAttempts() + 1);
            repository.save(a);
            return token;
        });
    }

    private void diferir(UUID id, UUID token, String codigo) {
        tx.executeWithoutResult(s -> repository.findForUploadUpdate(id).ifPresent(a -> {
            if (!token.equals(a.getUploadLeaseOwner())) return; // El lease ya fue tomado por otro proceso.
            boolean consulta = a.getUploadDecision() == UploadDecision.CHECKING;
            if (consulta) a.setUploadDecision(null);
            a.setUploadLeaseOwner(null);
            a.setUploadLeaseUntil(null);
            long segundos = consulta ? 180 : Math.min(300, 15L << Math.min(5, Math.max(0, a.getUploadAttempts() - 1)));
            a.setUploadNextAttemptAt(Instant.now().plusSeconds(segundos));
            a.setUploadLastError(codigo);
            repository.save(a);
        }));
    }

    private static boolean vencida(MediaAsset a, Instant ahora) {
        return a.getUploadExpiresAt() != null && !a.getUploadExpiresAt().isAfter(ahora)
                && (a.getUploadProtectedUntil() == null || !a.getUploadProtectedUntil().isAfter(ahora));
    }

    private static void exigirToken(MediaAsset a, UUID token) {
        if (token == null || !token.equals(a.getUploadLeaseOwner()) || a.getUploadLeaseUntil() == null
                || !a.getUploadLeaseUntil().isAfter(Instant.now()) || a.getStatus() != MediaAssetStatus.PENDING) {
            throw conflicto("UPLOAD_LEASE_LOST", "Otra operación está recuperando esta subida. Consulta su estado.");
        }
    }

    private MediaAsset bloqueada(UUID id) {
        MediaAsset a = repository.findForUploadUpdate(id).orElseThrow(CoordinadorDeSubidas::noExiste);
        IdentidadDeSubida.acceso(a, UUID.fromString(a.getTenantId())); return a;
    }
    private MediaAsset buscar(UUID id, UUID workspace) {
        MediaAsset a = repository.findById(id).filter(m -> workspace.toString().equals(m.getTenantId()))
                .orElseThrow(CoordinadorDeSubidas::noExiste);
        IdentidadDeSubida.acceso(a, workspace); return a;
    }
    private static ResourceNotFoundException noExiste() { return new ResourceNotFoundException("Esa subida no existe en este negocio."); }
    private static ConflictoException conflicto(String codigo, String mensaje) { return new ConflictoException(codigo, mensaje); }
}
