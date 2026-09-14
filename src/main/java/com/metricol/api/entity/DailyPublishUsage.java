package com.metricol.api.entity;

import java.time.LocalDate;
import java.util.UUID;

import com.metricol.api.enums.Platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Cuántas publicaciones lleva hoy un workspace en una red.
 *
 * <p>Un contador propio en vez de contar los post_targets publicados hoy. La
 * consulta habría sido gratis de escribir, pero el contador se puede
 * incrementar de forma atómica en la propia base —sumar uno solo si todavía
 * queda margen— y eso es lo que impide que ocho workers publicando a la vez
 * se pasen del límite entre todos, cada uno leyendo un total que ya estaba
 * viejo.
 *
 * <p>Sin TenantId por la misma razón que PublishJob: lo consulta el worker,
 * que no tiene usuario del cual deducirlo.
 */
@Entity
@Table(name = "daily_publish_usage", uniqueConstraints = @UniqueConstraint(name = "uk_daily_usage", columnNames = {
        "workspace_id", "platform", "usage_day" }))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyPublishUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Platform platform;

    /**
     * El día que cuenta esta fila. La columna se llama {@code usage_day} y no
     * {@code day} porque DAY es palabra reservada en varios motores, y una
     * columna con ese nombre rompe el DDL en unos y no en otros — el peor tipo
     * de fallo, el que solo aparece en un entorno.
     */
    @Column(name = "usage_day", nullable = false)
    private LocalDate day;

    @Builder.Default
    private int used = 0;
}
