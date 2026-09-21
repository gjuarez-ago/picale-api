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
 * La agencia: quien tiene clientes, no el cliente.
 *
 * <p>Cada {@link Workspace} es un cliente y pertenece a una organización. La
 * capa existe para que el equipo se administre una sola vez: dar de alta a un
 * socio en la organización lo mete a todos los clientes, y darlo de baja lo
 * saca de todos, sin recorrer treinta espacios acordándose de cuáles.
 *
 * <p>Quien se registra solo también tiene la suya, con un cliente dentro. No
 * se le enseña en ningún lado —sería preguntar por una agencia a quien solo
 * tiene su taquería— pero está ahí, y el día que crezca no hay que migrar
 * nada.
 *
 * <p>Sin {@code @TenantId}: se consulta por encima del workspace activo —para
 * saber a qué clientes se puede entrar— y el filtro automático dejaría ver
 * solo el de ahora.
 */
@Entity
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 150)
    private String name;

    /**
     * Cuántos espacios de trabajo puede tener.
     *
     * <p>No es una traba comercial: cada espacio crea su propio perfil en
     * upload-post, y upload-post cobra por perfil. Sin tope, una cuenta puede
     * crear doscientos espacios y la factura llega igual. Se guarda por
     * organización —y no en la configuración global— para poder ampliárselo a
     * un cliente concreto sin abrírselo a todos.
     */
    @Builder.Default
    @Column(name = "max_workspaces", nullable = false)
    private int maxWorkspaces = 3;

    /**
     * Sin límites ni vigencia: la cuenta de la casa (Pícale usando Pícale). No paga, su licencia no
     * vence, no se archiva y no le aplican los cupos de IA, publicaciones, créditos ni espacios.
     *
     * <p>Solo la enciende el arranque con la cuenta raíz ({@code CuentaRaizService}); no hay
     * endpoint que la cambie.
     */
    @Builder.Default
    @Column(name = "sin_limites", nullable = false, columnDefinition = "boolean default false")
    private boolean sinLimites = false;

    /**
     * El cliente de Stripe de esta organización ({@code cus_...}): con él se
     * cobran todas sus licencias y se abre su portal de facturación. Se crea la
     * primera vez que compra algo.
     */
    @Column(name = "stripe_customer_id", length = 80)
    private String stripeCustomerId;

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
