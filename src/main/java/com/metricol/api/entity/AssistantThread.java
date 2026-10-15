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

/** Una conversacion persistente y aislada dentro de un workspace. */
@Entity
@Table(name = "assistant_threads", indexes = {
        @Index(name = "ix_assistant_threads_workspace_updated", columnList = "workspace_id, updated_at"),
        @Index(name = "ix_assistant_threads_creator_updated", columnList = "created_by, updated_at") })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssistantThread {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    private String tenantId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "campaign_id")
    private UUID campaignId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
