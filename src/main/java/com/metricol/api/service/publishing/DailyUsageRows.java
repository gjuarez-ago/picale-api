package com.metricol.api.service.publishing;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.DailyPublishUsage;
import com.metricol.api.enums.Platform;
import com.metricol.api.repository.DailyPublishUsageRepository;

/**
 * Crea la fila del contador del día cuando todavía no existe.
 *
 * <p>Es una clase aparte y no un método de {@link PublishQuotaService} por
 * dos razones que van juntas. La primera es la transacción: si dos workers
 * crean la fila a la vez, uno choca con la restricción de unicidad, y esa
 * excepción marca la transacción como condenada — dentro de la del publicado
 * se llevaría por delante la publicación entera. En su propia transacción, el
 * choque se traga aquí y el llamador ni se entera. La segunda es que
 * {@code REQUIRES_NEW} solo surte efecto cruzando el proxy de Spring: un
 * método anotado de la misma clase habría corrido en la transacción de
 * siempre y no habría servido de nada.
 */
@Service
public class DailyUsageRows {

    private final DailyPublishUsageRepository repository;

    public DailyUsageRows(DailyPublishUsageRepository repository) {
        this.repository = repository;
    }

    /**
     * Deja la fila creada, exista ya o no. No devuelve nada porque al
     * llamador solo le importa poder hacer el update de después.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void asegurar(UUID workspaceId, Platform platform, LocalDate day) {
        if (repository.findByWorkspaceIdAndPlatformAndDay(workspaceId, platform, day).isPresent()) {
            return;
        }
        try {
            repository.saveAndFlush(DailyPublishUsage.builder()
                    .workspaceId(workspaceId)
                    .platform(platform)
                    .day(day)
                    .used(0)
                    .build());
        } catch (DataIntegrityViolationException ex) {
            // Otro worker la creó entre el findBy y el insert. Es justo lo que
            // se quería: la fila existe, que es lo único que importaba.
        }
    }
}
