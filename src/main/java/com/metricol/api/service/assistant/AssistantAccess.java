package com.metricol.api.service.assistant;

import org.springframework.stereotype.Component;

import com.metricol.api.entity.User;
import com.metricol.api.exception.ForbiddenException;
import com.metricol.api.service.CuentaSinLimites;
import com.metricol.api.service.brand.BrandAccess;

/** Regla única de acceso al asistente para web y API. */
@Component
public class AssistantAccess {

    private final CuentaSinLimites sinLimites;
    private final BrandAccess marca;

    public AssistantAccess(CuentaSinLimites sinLimites, BrandAccess marca) {
        this.sinLimites = sinLimites;
        this.marca = marca;
    }

    public boolean habilitado(User user) {
        return user != null
                && user.getWorkspace() != null
                && user.getWorkspace().isAssistantEnabled()
                && sinLimites.deWorkspace(user.getWorkspace().getId());
    }

    public void exigir(User user) {
        if (!habilitado(user)) {
            throw new ForbiddenException(
                    "El Asistente no está habilitado para este espacio de trabajo.");
        }
        marca.exigirCompleta(user);
    }
}
