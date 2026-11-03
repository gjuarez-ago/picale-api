package com.metricol.api.service.storage;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.metricol.api.config.MediaLimitsProperties;
import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.MediaService;

/**
 * La limpieza de cada semana del bucket (pedido de Gabriel, 3 nov 2026).
 *
 * <p>Recoge lo que las limpiezas de todos los días dejan atrás. Una revisión
 * a mano del 2 nov encontró casi un giga que nadie iba a borrar nunca:
 * <ul>
 *   <li><b>Archivos sueltos en {@code media/}</b>: sin ninguna fila que los
 *       nombre, casi todos de espacios que ya no existen. Ninguna otra
 *       limpieza los ve, porque todas parten de una fila.</li>
 *   <li><b>Versiones finales viejas</b>: al rehacer la de un video, la
 *       anterior se archiva, y lo archivado no lo toca nadie.</li>
 * </ul>
 *
 * <p>Solo {@code media/}: el bucket lo comparten otros sistemas (Vivento,
 * cursos, comprobantes…) cuyos archivos no salen en esta base y no son
 * basura. Las copias adaptadas de {@code derivados/} ya las barre
 * {@link MediaDerivadosWorker} cada madrugada.
 *
 * <p>Los repetidos que alguien subió a propósito no se borran solos: son la
 * biblioteca de la persona, y desde la subida por huella ya no se acumulan.
 */
@Component
public class LimpiezaSemanal {

    private static final Logger log = LoggerFactory.getLogger(LimpiezaSemanal.class);

    static final String PREFIJO = "media/";

    private final MediaAssetRepository repository;
    private final R2StorageService storage;
    private final MediaService mediaService;

    /** Lo más nuevo que se puede tocar: una subida en curso nunca tiene una semana. */
    @Value("${app.media.limpieza-semanal-dias:7}")
    private long dias;

    @Value("${app.media.limpieza-semanal-tope:500}")
    private int tope;

    public LimpiezaSemanal(MediaAssetRepository repository, R2StorageService storage, MediaService mediaService) {
        this.repository = repository;
        this.storage = storage;
        this.mediaService = mediaService;
    }

    /** Domingo de madrugada, después de la barrida de derivados (3:40) y de liberar lo publicado (4:10). */
    @Scheduled(cron = "${app.media.limpieza-semanal-cron:0 30 4 * * SUN}")
    public void barrer() {
        // Cada parte en su propio intento: si una falla, la otra corre igual.
        try {
            versionesViejas();
        } catch (Exception ex) {
            log.error("Limpieza semanal: no se pudieron soltar las versiones viejas: {}", ex.getMessage(), ex);
        }
        try {
            sueltos();
        } catch (Exception ex) {
            log.error("Limpieza semanal: no se pudieron borrar los archivos sueltos: {}", ex.getMessage(), ex);
        }
    }

    void versionesViejas() {
        List<Object[]> viejas = repository.findVersionesViejasSinUsar(
                LocalDateTime.now().minusDays(dias), tope);
        int borradas = 0;
        for (Object[] fila : viejas) {
            String id = String.valueOf(fila[0]);
            Object tenant = fila[1];
            if (tenant == null || String.valueOf(tenant).isBlank()) {
                continue;
            }
            try {
                // El tenant antes de entrar a MediaService, como en MediaSinUsarWorker.
                TenantIdentifierResolver.comoTenant(String.valueOf(tenant),
                        () -> mediaService.delete(UUID.fromString(id)));
                borradas++;
            } catch (Exception ex) {
                log.error("Limpieza semanal: no se pudo borrar la versión vieja {}: {}", id, ex.getMessage());
            }
        }
        if (borradas > 0) {
            log.info("Limpieza semanal: {} versión(es) final(es) vieja(s) borrada(s)", borradas);
        }
    }

    void sueltos() {
        List<R2StorageService.ObjetoGuardado> objetos = storage.listarObjetos(PREFIJO);
        if (objetos.isEmpty()) {
            return;
        }
        Set<String> vivas = new HashSet<>();
        for (Object[] fila : repository.findClavesVivas()) {
            if (fila[1] != null) vivas.add(String.valueOf(fila[1]));
        }
        vivas.addAll(repository.findClavesDeReels());

        List<R2StorageService.ObjetoGuardado> aBorrar =
                sueltos(objetos, vivas, Instant.now().minus(dias, ChronoUnit.DAYS));
        if (aBorrar.isEmpty()) {
            return;
        }
        if (MediaDerivadosWorker.pareceOtraBase(aBorrar.size(), objetos.size())) {
            log.error("La limpieza semanal se detuvo: iba a borrar {} de {} archivos de media/. Eso no son "
                    + "sueltos, es una base que no conoce este bucket. No se borró nada.",
                    aBorrar.size(), objetos.size());
            return;
        }
        long bytes = aBorrar.stream().mapToLong(R2StorageService.ObjetoGuardado::bytes).sum();
        storage.borrarClaves(aBorrar.stream().map(R2StorageService.ObjetoGuardado::clave).toList());
        log.info("Limpieza semanal: {} archivo(s) suelto(s) borrado(s) de media/ ({})",
                aBorrar.size(), MediaLimitsProperties.legible(bytes));
    }

    /** Los objetos que ninguna fila nombra y que ya tienen el plazo cumplido. */
    static List<R2StorageService.ObjetoGuardado> sueltos(
            Collection<R2StorageService.ObjetoGuardado> objetos, Set<String> vivas, Instant antesDe) {
        List<R2StorageService.ObjetoGuardado> fuera = new ArrayList<>();
        for (R2StorageService.ObjetoGuardado o : objetos) {
            if (o.clave() == null || !o.clave().startsWith(PREFIJO) || vivas.contains(o.clave())) {
                continue;
            }
            // Sin fecha no se sabe si es de ahora mismo: se queda.
            if (o.modificado() == null || !o.modificado().isBefore(antesDe)) {
                continue;
            }
            fuera.add(o);
        }
        return fuera;
    }
}
