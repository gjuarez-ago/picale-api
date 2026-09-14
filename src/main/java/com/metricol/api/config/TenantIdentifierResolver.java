package com.metricol.api.config;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.metricol.api.entity.User;

/**
 * Resuelve el "tenant" actual (workspace) a partir del usuario autenticado en la
 * sesión de seguridad. Hibernate usa este valor para filtrar/asignar automáticamente
 * el campo @TenantId de cada entidad con alcance de workspace (Post, SocialAccount,
 * MediaAsset), sin necesidad de WHERE manuales en repositorios ni servicios.
 */
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

    public static final String GLOBAL_TENANT = "GLOBAL";

    /**
     * Tenant impuesto a mano para el hilo actual, cuando no hay usuario del
     * cual deducirlo.
     *
     * <p>Existe por los procesos de fondo. Un {@code @Scheduled} corre sin
     * sesión de seguridad, así que caía en {@link #GLOBAL_TENANT} y toda
     * consulta a una entidad con {@code @TenantId} volvía vacía —las
     * publicaciones programadas nunca se habrían encontrado, y el síntoma
     * habría sido "el worker corre y no hace nada", que no apunta a ningún
     * lado—.
     */
    private static final ThreadLocal<String> impuesto = new ThreadLocal<>();

    /**
     * Corre {@code accion} como si el workspace indicado estuviera
     * autenticado. El valor anterior se restaura siempre, incluso si la
     * acción falla: los hilos del pool se reutilizan, y dejar uno marcado
     * haría que la siguiente tarea leyera los datos del workspace anterior.
     */
    public static void comoTenant(String tenantId, Runnable accion) {
        String previo = impuesto.get();
        impuesto.set(tenantId);
        try {
            accion.run();
        } finally {
            if (previo == null) {
                impuesto.remove();
            } else {
                impuesto.set(previo);
            }
        }
    }

    @Override
    public String resolveCurrentTenantIdentifier() {
        // El impuesto manda: si alguien lo puso, es porque no hay usuario del
        // cual deducir nada.
        String forzado = impuesto.get();
        if (forzado != null) {
            return forzado;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.getPrincipal() instanceof User user && user.getWorkspace() != null) {
            return user.getWorkspace().getId().toString();
        }

        return GLOBAL_TENANT;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
