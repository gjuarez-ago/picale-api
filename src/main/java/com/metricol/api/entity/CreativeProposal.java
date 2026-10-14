package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.metricol.api.enums.CreativePieceStatus;
import com.metricol.api.enums.CreativePieceType;
import com.metricol.api.enums.CreativeApprovalStatus;

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

@Entity
@Table(name = "creative_proposals", indexes = @Index(name = "idx_creative_proposals_job", columnList = "job_id,position"))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class CreativeProposal {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private UUID jobId;
    @Column(nullable = false) private UUID workspaceId;
    private int position;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private CreativePieceType type;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "creative_proposal_sources", joinColumns = @JoinColumn(name = "proposal_id"))
    @OrderColumn(name = "position")
    @Column(name = "asset_id", nullable = false)
    @Builder.Default private List<UUID> sourceAssetIds = new ArrayList<>();
    @Column(length = 300) private String objective;
    @Column(length = 160) private String title;
    @Column(columnDefinition = "text") private String captionsJson;
    @Column(length = 200) private String networksCsv;
    private Integer durationSeconds;
    @Column(nullable = false, length = 40) private String recipeVersion;
    @Column(length = 1000) private String selectionReason;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private CreativePieceStatus generationStatus;
    @Column(length = 500) private String generationError;
    private UUID reelJobId;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "creative_proposal_previews", joinColumns = @JoinColumn(name = "proposal_id"))
    @OrderColumn(name = "position")
    @Column(name = "url", nullable = false, length = 2000)
    @Builder.Default private List<String> previewUrls = new ArrayList<>();
    @Column(length = 2000) private String previewThumbnailUrl;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    @Builder.Default private CreativeApprovalStatus approvalStatus = CreativeApprovalStatus.PENDING;
    private LocalDateTime approvedAt;
    private UUID approvedBy;
    @CreationTimestamp private LocalDateTime createdAt;
}
