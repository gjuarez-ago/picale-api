package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
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
 * Un teléfono donde la persona recibe avisos (su token de Firebase Cloud
 * Messaging).
 *
 * <p>Es de la persona y no del espacio: un community manager con diez
 * clientes tiene un solo teléfono, y le llegan los avisos de todos los
 * espacios donde puede aprobar. Por eso no lleva {@code @TenantId}.
 */
@Entity
@Table(name = "dispositivos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Dispositivo {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    /** El token de FCM. Único: si otra persona entra en el mismo teléfono, el token pasa a ser suyo. */
    @Column(nullable = false, unique = true, length = 512)
    private String token;

    /** ANDROID o IOS. */
    @Column(length = 10)
    private String plataforma;

    @Builder.Default
    private LocalDateTime creadoEn = LocalDateTime.now();

    /** La última vez que la app lo registró: un token que no se ve en meses ya no sirve. */
    private LocalDateTime vistoEn;
}
