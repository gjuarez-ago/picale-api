package com.metricol.api.service.billing;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.metricol.api.entity.CreditPack;

/**
 * El catálogo de Stripe, que crea la API y no una persona.
 *
 * <p>Un producto por cosa que se vende, con id fijo: la licencia de un negocio,
 * la licencia de un negocio adicional y cada paquete de créditos. Se crean la
 * primera vez que se venden (ver {@link StripeClient#asegurarProducto}); el
 * monto no vive aquí sino en la tabla de ajustes y viaja con cada cobro, así que
 * cambiar un precio no obliga a tocar Stripe, y quien ya se suscribió conserva
 * el importe con el que entró.
 *
 * <p>Por qué productos y no solo importes sueltos: en el panel de Stripe cada
 * cosa se ve como una sola línea con sus ingresos, en vez de un producto
 * distinto por cada cliente que paga.
 */
@Service
public class StripeProductCatalog {

    public static final String LICENCIA = "picale_licencia";
    public static final String LICENCIA_ADICIONAL = "picale_licencia_adicional";

    private final StripeClient stripe;
    private final BillingConfig config;
    private final Set<String> asegurados = ConcurrentHashMap.newKeySet();

    public StripeProductCatalog(StripeClient stripe, BillingConfig config) {
        this.stripe = stripe;
        this.config = config;
    }

    /** El producto de la licencia de un negocio: la del plan, o la de un negocio adicional. */
    public String productoDeLicencia(boolean adicional) {
        String id = adicional ? LICENCIA_ADICIONAL : LICENCIA;
        asegurar(id, adicional ? "Pícale · Licencia de negocio adicional" : "Pícale · Licencia de negocio",
                "Publica en todas tus redes con IA. Incluye " + config.creditosMensuales() + " imágenes con IA al mes.");
        return id;
    }

    /** El producto de un paquete de créditos. */
    public String productoDePaquete(CreditPack paquete) {
        String id = "picale_paquete_" + paquete.getCode().toLowerCase().replaceAll("[^a-z0-9]+", "_");
        asegurar(id, "Pícale · Paquete " + paquete.getName(), paquete.getCredits() + " créditos de imagen con IA. No vencen.");
        return id;
    }

    private void asegurar(String id, String nombre, String descripcion) {
        if (asegurados.contains(id)) {
            return;
        }
        stripe.asegurarProducto(id, nombre, descripcion);
        asegurados.add(id);
    }
}
