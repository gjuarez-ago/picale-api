package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.TenantId;

import com.metricol.api.enums.AssistantRunStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import lombok.Setter;

/** Una ejecucion durable de un mensaje del usuario. */
@Entity
@Table(name = "assistant_runs", indexes =
        @Index(name = "ix_assistant_runs_thread_created", columnList = "thread_id, created_at"),
        uniqueConstraints = @UniqueConstraint(name = "uk_assistant_runs_workspace_idempotency",
                columnNames = { "workspace_id", "idempotency_key" }))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssistantRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    private String tenantId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "thread_id", nullable = false, updatable = false)
    private UUID threadId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AssistantRunStatus status;

    @Column(length = 120)
    private String intent;

    @Column(name = "idempotency_key", nullable = false, length = 100, updatable = false)
    private String idempotencyKey;

    @Column(length = 80)
    private String model;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    /** Mensaje seguro para la persona; nunca una respuesta cruda del proveedor. */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
