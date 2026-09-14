package com.metricol.api.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.metricol.api.entity.DailyPublishUsage;
import com.metricol.api.enums.Platform;

public interface DailyPublishUsageRepository extends JpaRepository<DailyPublishUsage, UUID> {

    Optional<DailyPublishUsage> findByWorkspaceIdAndPlatformAndDay(
            UUID workspaceId, Platform platform, LocalDate day);

    List<DailyPublishUsage> findByWorkspaceIdAndDay(UUID workspaceId, LocalDate day);

    /**
     * Suma uno solo si todavía queda margen. Devuelve 1 si se pudo consumir y
     * 0 si la cuota ya estaba agotada.
     *
     * <p>La condición va dentro del update, y no en un lee-compara-escribe en
     * Java, porque con varios workers publicando a la vez ese patrón se pasa
     * del límite: los dos leen 24 de 25, los dos deciden que caben, y la red
     * recibe 26. Aquí la base es la que compara.
     */
    @Modifying
    @Query("""
            update DailyPublishUsage u set u.used = u.used + 1
            where u.workspaceId = :workspaceId and u.platform = :platform
              and u.day = :day and u.used < :tope
            """)
    int consumirSiCabe(
            @Param("workspaceId") UUID workspaceId,
            @Param("platform") Platform platform,
            @Param("day") LocalDate day,
            @Param("tope") int tope);

    /** Suma uno sin comparar, para las redes sin tope conocido. */
    @Modifying
    @Query("""
            update DailyPublishUsage u set u.used = u.used + 1
            where u.workspaceId = :workspaceId and u.platform = :platform and u.day = :day
            """)
    int consumir(
            @Param("workspaceId") UUID workspaceId,
            @Param("platform") Platform platform,
            @Param("day") LocalDate day);

    /**
     * Devuelve un consumo que al final no se usó: la llamada al proveedor
     * falló y esa publicación no llegó a ninguna red. Sin esto, un mal día
     * del proveedor le comería la cuota del día a alguien que no publicó nada.
     */
    @Modifying
    @Query("""
            update DailyPublishUsage u set u.used = u.used - 1
            where u.workspaceId = :workspaceId and u.platform = :platform
              and u.day = :day and u.used > 0
            """)
    int devolver(
            @Param("workspaceId") UUID workspaceId,
            @Param("platform") Platform platform,
            @Param("day") LocalDate day);

    /** Limpia el histórico: para decidir solo hace falta el día de hoy. */
    @Modifying
    @Query("delete from DailyPublishUsage u where u.day < :limite")
    int borrarAnterioresA(@Param("limite") LocalDate limite);
}
