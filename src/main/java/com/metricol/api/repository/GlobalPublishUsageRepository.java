package com.metricol.api.repository;

import java.time.LocalDate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.metricol.api.entity.GlobalPublishUsage;

public interface GlobalPublishUsageRepository extends JpaRepository<GlobalPublishUsage, LocalDate> {

    /** Suma uno solo si queda margen. 1 si se pudo; 0 si el día está lleno. */
    @Modifying
    @Query("""
            update GlobalPublishUsage g set g.used = g.used + 1
            where g.day = :day and g.used < :tope
            """)
    int consumirSiCabe(@Param("day") LocalDate day, @Param("tope") long tope);

    /** Devuelve un hueco que no se llegó a usar. */
    @Modifying
    @Query("""
            update GlobalPublishUsage g set g.used = g.used - 1
            where g.day = :day and g.used > 0
            """)
    int devolver(@Param("day") LocalDate day);

    @Modifying
    @Query("delete from GlobalPublishUsage g where g.day < :limite")
    int borrarAnterioresA(@Param("limite") LocalDate limite);
}
