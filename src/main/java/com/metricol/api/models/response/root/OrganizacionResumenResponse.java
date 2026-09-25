package com.metricol.api.models.response.root;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Una organización en la lista de la administración de la plataforma.
 *
 * @param situacion  resumen de cómo está de pagos, para el color de la fila:
 *                   {@code SIN_LIMITES} (la casa), {@code AL_CORRIENTE},
 *                   {@code EN_PRUEBA}, {@code VENCIDA} (algún espacio con la
 *                   licencia caída) o {@code SIN_LICENCIA}
 * @param vigenteHasta la fecha más próxima en que se le cae algo, si aplica
 */
public record OrganizacionResumenResponse(
        UUID id,
        String name,
        boolean sinLimites,
        int maxWorkspaces,
        boolean tieneClienteStripe,
        LocalDateTime createdAt,
        String duenoNombre,
        String duenoEmail,
        int espacios,
        int espaciosActivos,
        int personas,
        int licenciasVigentes,
        int licenciasVencidas,
        int enPrueba,
        String situacion,
        LocalDateTime vigenteHasta) {
}
