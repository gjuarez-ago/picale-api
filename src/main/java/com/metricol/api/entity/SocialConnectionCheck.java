package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.TenantId;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Lo último que se supo de cierto sobre la salud de la conexión de una red,
 * por workspace. Portado de {@code SocialConnectionCheck} de vivento366.api.
 *
 * <p>Existe porque ninguna red publica de antemano cuándo vence el permiso
 * que concedió: lo único real es cuándo se verificó por última vez que
 * seguía sirviendo, y desde cuándo dejó de servir. Se actualiza cada vez que
 * se consulta el estado en upload-post.com.
 *
 * <p>A diferencia de vivento, aquí lleva {@link TenantId}: allá el registro
 * se busca solo por plataforma, así que dos inmobiliarias comparten la misma
 * fila y la verificación de una sobreescribe la de la otra. Con un solo
 * tenant real no se nota, pero metricol es multi-workspace desde el primer
 * día y ahí el dato saldría cruzado.
 */
@Entity
@Table(name = "social_connection_checks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SocialConnectionCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    private String tenantId;

    /**
     * El nombre de la red tal como lo usa upload-post.com ({@code facebook},
     * {@code instagram}, {@code tiktok}, {@code linkedin}...), en minúsculas
     * y no el enum {@link com.metricol.api.enums.Platform}: aquí se guarda lo
     * que contesta el proveedor, incluidas redes que metricol todavía no
     * modela.
     */
    private String platform;

    private LocalDateTime lastVerifiedAt;

    private LocalDateTime expiredSince;
}
