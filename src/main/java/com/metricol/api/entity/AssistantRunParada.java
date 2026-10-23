package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.TenantId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * La persona pidió detener un turno del asistente.
 *
 * <p>Tabla aparte y no un campo del turno: mientras corre, el turno tiene SU fila
 * bloqueada dentro de una sola transacción, y marcarla esperaría a que terminara —justo
 * lo contrario de detener—. Insertar aquí no choca con ese bloqueo, y el turno lo lee
 * antes de cada paso. En la base y no en memoria para que funcione igual con varios
 * servidores o si el que recibe el "detener" no es el que corre el turno.
 */
@Entity
@Table(name = "assistant_run_paradas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssistantRunParada {

    @Id
    @Column(name = "run_id")
    private UUID runId;

    @TenantId
    private String tenantId;

    @Column(name = "solicitada_en", nullable = false)
    private LocalDateTime solicitadaEn;
}
