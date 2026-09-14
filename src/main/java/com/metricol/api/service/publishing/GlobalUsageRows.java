package com.metricol.api.service.publishing;

import java.time.LocalDate;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.GlobalPublishUsage;
import com.metricol.api.repository.GlobalPublishUsageRepository;

/**
 * Crea la fila del contador global del día cuando todavía no existe.
 *
 * <p>Clase aparte y en su propia transacción por lo mismo que
 * {@link DailyUsageRows}: si dos workers la crean a la vez uno choca con la
 * llave primaria, y esa excepción condena la transacción en la que ocurra.
 * Aquí se traga sin que el publicado se entere.
 */
@Service
public class GlobalUsageRows {

    private final GlobalPublishUsageRepository repository;

    public GlobalUsageRows(GlobalPublishUsageRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void asegurar(LocalDate day) {
        if (repository.existsById(day)) {
            return;
        }
        try {
            repository.saveAndFlush(GlobalPublishUsage.builder().day(day).used(0).build());
        } catch (DataIntegrityViolationException ex) {
            // Otro worker llegó primero. La fila existe, que es lo que importaba.
        }
    }
}
