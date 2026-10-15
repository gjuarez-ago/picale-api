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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Un mensaje visible del hilo; herramientas y auditoria viven en tablas separadas. */
@Entity
@Table(name = "assistant_messages", indexes =
        @Index(name = "ix_assistant_messages_thread_created", columnList = "thread_id, created_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssistantMessage {

    public static final int MAX_CONTENT = 12_000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    private String tenantId;

    @Column(name = "thread_id", nullable = false, updatable = false)
    private UUID threadId;

    @Column(name = "run_id")
    private UUID runId;

    /** USER, ASSISTANT o SYSTEM. */
    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** IDs de MediaAsset y metadatos visibles, nunca bytes ni URLs firmadas. */
    @Column(name = "attachments_json", columnDefinition = "text")
    private String attachmentsJson;

    /** Tarjetas estructuradas que la web puede renderizar. */
    @Column(name = "artifacts_json", columnDefinition = "text")
    private String artifactsJson;

    @Column(length = 80)
    private String model;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
