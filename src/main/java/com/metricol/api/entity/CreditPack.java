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
 * Un paquete de créditos de imagen que se compra suelto.
 *
 * <p>{@code priceMinor} es el precio: lo que se cobra (viaja en línea a Stripe, que
 * tiene un producto por paquete creado por la API) y lo que enseña la página de
 * planes. Aquí también están cuántos créditos trae, cómo se llama y si se ofrece
 * hoy. Editable en la base sin desplegar.
 */
@Entity
@Table(name = "credit_packs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditPack {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Nombre corto y estable: «PACK_10». Es lo que viaja de la web a la API. */
    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false)
    private int credits;

    /** Precio en la unidad menor de la moneda (7900 = $79.00). Nulo = no se vende ni se enseña. */
    @Column
    private Integer priceMinor;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = false;

    @Builder.Default
    @Column(nullable = false)
    private int sortOrder = 0;

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /** Se puede vender: está encendido y tiene precio. */
    public boolean vendible() {
        return active && priceMinor != null && priceMinor > 0 && credits > 0;
    }
}
