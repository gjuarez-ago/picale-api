package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.metricol.api.enums.Platform;

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

/** La explicación durable de por qué un video fue o no fue a cada red. */
@Entity
@Table(name = "decisiones_ruteo", indexes = {
        @Index(name = "ix_decisiones_ruteo_ws_fecha", columnList = "workspace_id, created_at"),
        @Index(name = "ix_decisiones_ruteo_post", columnList = "post_id"),
        @Index(name = "ix_decisiones_ruteo_media", columnList = "media_id") })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecisionRuteo {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private UUID postId;

    @Column(nullable = false)
    private UUID mediaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Platform plataforma;

    /** ENVIAR o DEJAR_FUERA. */
    @Column(nullable = false, length = 20)
    private String decision;

    /** REEL, STORY, VIDEO o SHORT, tal como lo entiende la red. */
    @Column(length = 20)
    private String formatoResultante;

    /** OK, PESO, DURACION, FORMA, FORMATO o RENDIMIENTO. */
    @Column(nullable = false, length = 24)
    private String codigoRazon;

    @Column(length = 80)
    private String valor;

    @Column(length = 80)
    private String limite;

    @Column(nullable = false, length = 700)
    private String frase;

    @Column(length = 30)
    private String arregloOfrecido;

    /** AGENTE o PERSONA. */
    @Column(nullable = false, length = 20)
    private String decididoPor;

    /** 0 a 100 contra la mejor red comparable; nulo cuando aún no hay muestra. */
    private Double puntajeHistorico;

    private Integer muestrasHistoricas;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
