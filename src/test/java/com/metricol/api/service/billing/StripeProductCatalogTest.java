package com.metricol.api.service.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.CreditPack;

/** El catálogo lo crea la API, con ids fijos, y solo una vez. */
class StripeProductCatalogTest {

    private StripeClient stripe;
    private BillingConfig config;
    private StripeProductCatalog catalogo;

    @BeforeEach
    void preparar() {
        stripe = mock(StripeClient.class);
        config = mock(BillingConfig.class);
        when(config.creditosMensuales()).thenReturn(5);
        catalogo = new StripeProductCatalog(stripe, config);
    }

    @Test
    @DisplayName("la licencia y la licencia adicional son dos productos con id fijo")
    void licencias() {
        assertThat(catalogo.productoDeLicencia(false)).isEqualTo("picale_licencia");
        assertThat(catalogo.productoDeLicencia(true)).isEqualTo("picale_licencia_adicional");

        verify(stripe).asegurarProducto(eq("picale_licencia"), eq("Pícale · Licencia de negocio"), contains("5 imágenes"));
        verify(stripe).asegurarProducto(eq("picale_licencia_adicional"), contains("adicional"), anyString());
    }

    @Test
    @DisplayName("cada paquete de créditos tiene su producto, con el código en el id")
    void paquetes() {
        CreditPack p = CreditPack.builder().code("PACK_25").name("Constante").credits(25).build();

        assertThat(catalogo.productoDePaquete(p)).isEqualTo("picale_paquete_pack_25");
        verify(stripe).asegurarProducto(eq("picale_paquete_pack_25"), contains("Constante"), contains("25 créditos"));
    }

    @Test
    @DisplayName("se le pregunta a Stripe una sola vez por producto, no en cada compra")
    void unaSolaVez() {
        catalogo.productoDeLicencia(false);
        catalogo.productoDeLicencia(false);
        catalogo.productoDeLicencia(false);

        verify(stripe, times(1)).asegurarProducto(eq("picale_licencia"), anyString(), anyString());
    }

    @Test
    @DisplayName("si Stripe falla el producto no se da por creado: la siguiente compra lo intenta de nuevo")
    void siFallaSeReintenta() {
        org.mockito.Mockito.doThrow(new IllegalStateException("Stripe caído")).doNothing()
                .when(stripe).asegurarProducto(eq("picale_licencia"), anyString(), anyString());

        try {
            catalogo.productoDeLicencia(false);
        } catch (IllegalStateException esperado) {
            // la primera vez falla
        }
        assertThat(catalogo.productoDeLicencia(false)).isEqualTo("picale_licencia");
        verify(stripe, times(2)).asegurarProducto(eq("picale_licencia"), anyString(), anyString());
    }
}
