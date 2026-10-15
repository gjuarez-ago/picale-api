package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.TenantId;

import com.metricol.api.enums.AssistantToolCallStatus;

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

/** Recibo auditable de una herramienta solicitada durante un run. */
@Entity
@Table(name = "assistant_tool_calls", indexes =
        @Index(name = "ix_assistant_tool_calls_run_created", columnList = "run_id, created_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssistantToolCall {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    private String tenantId;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "tool_name", nullable = false, length = 80, updatable = false)
    private String toolName;

    @Column(name = "risk_level", nullable = false)
    private int riskLevel;

    @Column(length = 50)
    private String permission;

    @Column(name = "arguments_json", columnDefinition = "text")
    private String argumentsJson;

    @Column(name = "result_summary", length = 1000)
    private String resultSummary;

    @Column(name = "affected_entity_type", length = 50)
    private String affectedEntityType;

    @Column(name = "affected_entity_id", length = 80)
    private String affectedEntityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AssistantToolCallStatus status;

    @Column(name = "confirmation_token_hash", length = 100)
    private String confirmationTokenHash;

    @Column(name = "confirmation_expires_at")
    private LocalDateTime confirmationExpiresAt;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
