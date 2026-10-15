package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.TenantId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Evento durable y reanudable de una ejecucion del asistente. */
@Entity
@Table(name = "assistant_run_events",
        indexes = @Index(name = "ix_assistant_run_events_run_sequence", columnList = "run_id, event_sequence"),
        uniqueConstraints = @UniqueConstraint(name = "uk_assistant_run_events_run_sequence",
                columnNames = { "run_id", "event_sequence" }))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssistantRunEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    private String tenantId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "event_sequence", nullable = false, updatable = false)
    private long sequence;

    @Column(nullable = false, length = 60, updatable = false)
    private String type;

    @Column(name = "payload_json", nullable = false, columnDefinition = "text", updatable = false)
    private String payloadJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
