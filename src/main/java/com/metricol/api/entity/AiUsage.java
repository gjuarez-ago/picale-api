package com.metricol.api.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.metricol.api.enums.AiOperacion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Una llamada a OpenAI: qué workspace la pidió, para qué, cuántos tokens y
 * cuánto costó.
 *
 * <p>Sin TenantId, igual que DailyPublishUsage, pero por otro motivo: esta
 * tabla existe para COMPARAR workspaces entre sí —saber qué cliente gasta
 * más—, y con el filtro automático de Hibernate cada consulta vería solo el
 * suyo. El workspace va en una columna normal.
 *
 * <p>El costo se guarda ya calculado, con el precio vigente en el momento de
 * la llamada: si OpenAI cambia precios, lo gastado el mes pasado no debe
 * cambiar con él. Los tokens van al lado por si hace falta recalcular.
 */
@Entity
@Table(name = "ai_usage", indexes = {
        @Index(name = "ix_ai_usage_fecha", columnList = "created_at"),
        @Index(name = "ix_ai_usage_ws_fecha", columnList = "workspace_id, created_at") })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * {@code null} si la llamada no venía de ningún workspace (sin sesión). No
     * debería pasar hoy —toda llamada a la IA sale de un endpoint autenticado—
     * pero si pasa se anota igual: es gasto real aunque no tenga a quién
     * cargárselo.
     */
    private UUID workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AiOperacion operacion;

    /** El modelo que contestó, tal como lo nombra OpenAI en la respuesta. */
    @Column(nullable = false, length = 80)
    private String modelo;

    @Column(nullable = false)
    private int tokensEntrada;

    @Column(nullable = false)
    private int tokensSalida;

    @Column(nullable = false, precision = 14, scale = 8)
    private BigDecimal costoUsd;

    /**
     * Con valor por defecto y no {@code @CreationTimestamp}: esa anotación pisa
     * lo que se le ponga, y las pruebas del reporte necesitan anotar llamadas
     * en fechas concretas para comprobar los periodos.
     */
    @Builder.Default
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
