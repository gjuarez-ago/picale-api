package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.metricol.api.enums.CreativeBatchStatus;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Planificación durable de una tanda; no usa TenantId para que el worker global pueda reclamarla. */
@Entity
@Table(name = "creative_batch_jobs", indexes = {
        @Index(name = "idx_creative_jobs_due", columnList = "status,run_at"),
        @Index(name = "idx_creative_jobs_workspace", columnList = "workspace_id,created_at")
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class CreativeBatchJob {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false)
    private UUID workspaceId;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "creative_batch_sources", joinColumns = @JoinColumn(name = "job_id"))
    @OrderColumn(name = "position")
    @Column(name = "asset_id", nullable = false)
    @Builder.Default
    private List<UUID> sourceAssetIds = new ArrayList<>();
    @Column(length = 1000)
    private String brief;
    @Column(nullable = false, unique = true, length = 64)
    private String requestHash;
    @Column(nullable = false, length = 40)
    private String recipeVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private CreativeBatchStatus status;
    @Column(nullable = false)
    private LocalDateTime runAt;
    private int attempts;
    private int maxAttempts;
    private String lockedBy;
    private LocalDateTime lockedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    @Column(length = 500)
    private String lastError;
    @Column(columnDefinition = "text")
    private String planJson;
    @CreationTimestamp private LocalDateTime createdAt;
    @UpdateTimestamp private LocalDateTime updatedAt;
}
