package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.metricol.api.enums.ReelRenderStatus;

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

/**
 * Encargo persistente para producir un reel de fotos.
 *
 * <p>No lleva {@code @TenantId}: el despachador debe ver trabajos de todos
 * los espacios sin una sesión. Toda lectura iniciada por la API exige además
 * {@link #workspaceId}; el worker impone ese tenant antes de leer los medios.
 */
@Entity
@Table(name = "reel_render_jobs", indexes = {
        @Index(name = "idx_reel_jobs_due", columnList = "status,run_at"),
        @Index(name = "idx_reel_jobs_workspace", columnList = "workspace_id,created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReelRenderJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID workspaceId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "reel_render_sources", joinColumns = @JoinColumn(name = "job_id"))
    @OrderColumn(name = "position")
    @Column(name = "asset_id", nullable = false)
    @Builder.Default
    private List<UUID> sourceAssetIds = new ArrayList<>();

    /** Incluye workspace, orden, duración, titular y versión de receta. */
    @Column(nullable = false, unique = true, length = 64)
    private String recipeHash;

    @Column(nullable = false, length = 40)
    private String recipeVersion;

    @Column(nullable = false, length = 4000)
    private String recipeJson;

    /** Clave determinista: un reintento sobrescribe el mismo objeto, no crea otro. */
    @Column(nullable = false, length = 500)
    private String outputStorageKey;

    private int durationSeconds;

    @Column(length = 160)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReelRenderStatus status;

    @Column(nullable = false)
    private LocalDateTime runAt;

    @Builder.Default
    private int attempts = 0;

    private int maxAttempts;
    private String lockedBy;
    private LocalDateTime lockedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @Column(length = 500)
    private String lastError;

    private UUID resultAssetId;
    private LocalDateTime approvedAt;
    private UUID approvedBy;

    /** Lo pidió el agente automático y debe terminar como una propuesta normal. */
    @Builder.Default
    private boolean agentManaged = false;

    /** Evita crear dos propuestas al observar dos veces el mismo render terminado. */
    private LocalDateTime agentFinalizedAt;

    @Column(length = 300)
    private String agentTheme;

    @Column(length = 500)
    private String agentReason;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
