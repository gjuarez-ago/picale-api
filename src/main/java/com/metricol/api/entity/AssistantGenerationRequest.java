package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;

/** Reserva durable ANTES del efecto externo. No contiene prompts, fotos ni credenciales. */
@Entity
@Table(name = "assistant_generation_requests", uniqueConstraints = @UniqueConstraint(
        name = "uk_assistant_generation_request", columnNames = {"workspace_id", "request_id"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AssistantGenerationRequest {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @TenantId @Column(name = "tenant_id", nullable = false) private String tenantId;
    @Column(name = "workspace_id", nullable = false, updatable = false) private UUID workspaceId;
    @Column(name = "request_id", nullable = false, updatable = false) private UUID requestId;
    @Column(nullable = false, length = 64, updatable = false) private String fingerprint;
    @Column(name = "job_id", length = 80) private String jobId;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
}
