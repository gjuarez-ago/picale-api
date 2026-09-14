package com.metricol.api.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.metricol.api.entity.AiUsage;
import com.metricol.api.enums.AiOperacion;

public interface AiUsageRepository extends JpaRepository<AiUsage, UUID> {

    /** Llamadas de un workspace en el rango. Es lo que compara AiQuotaGuard con el tope del dia. */
    long countByWorkspaceIdAndCreatedAtBetween(UUID workspaceId, LocalDateTime desde, LocalDateTime hasta);

    /**
     * Lo que gastó cada workspace en el periodo, del que más al que menos.
     *
     * <p>{@code desde} incluido y {@code hasta} excluido, para que dos periodos
     * seguidos no cuenten dos veces la misma llamada de medianoche.
     */
    @Query("""
            select u.workspaceId as workspaceId,
                   count(u) as llamadas,
                   sum(u.tokensEntrada) as tokensEntrada,
                   sum(u.tokensSalida) as tokensSalida,
                   sum(u.costoUsd) as costoUsd
            from AiUsage u
            where u.createdAt >= :desde and u.createdAt < :hasta
            group by u.workspaceId
            order by sum(u.costoUsd) desc
            """)
    List<PorWorkspace> gastoPorWorkspace(
            @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta);

    /** El mismo periodo, partido además por operación. */
    @Query("""
            select u.workspaceId as workspaceId,
                   u.operacion as operacion,
                   count(u) as llamadas,
                   sum(u.costoUsd) as costoUsd
            from AiUsage u
            where u.createdAt >= :desde and u.createdAt < :hasta
            group by u.workspaceId, u.operacion
            """)
    List<PorOperacion> gastoPorOperacion(
            @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta);

    interface PorWorkspace {
        UUID getWorkspaceId();

        Long getLlamadas();

        Long getTokensEntrada();

        Long getTokensSalida();

        BigDecimal getCostoUsd();
    }

    interface PorOperacion {
        UUID getWorkspaceId();

        AiOperacion getOperacion();

        Long getLlamadas();

        BigDecimal getCostoUsd();
    }
}
