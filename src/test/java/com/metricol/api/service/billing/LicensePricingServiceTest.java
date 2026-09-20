package com.metricol.api.service.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.License;
import com.metricol.api.enums.LicenseStatus;
import com.metricol.api.repository.LicenseRepository;

/**
 * La grieta del precio adicional: pagar el primero a precio completo, agregar
 * otro a precio adicional y cancelar el primero no puede dejar a la
 * organización con un solo negocio al precio adicional.
 */
class LicensePricingServiceTest {

    private BillingConfig config;
    private StripeClient stripe;
    private LicenseRepository licencias;
    private StripeProductCatalog catalogo;
    private LicensePricingService servicio;

    private final UUID org = UUID.randomUUID();

    @BeforeEach
    void preparar() {
        config = mock(BillingConfig.class);
        stripe = mock(StripeClient.class);
        licencias = mock(LicenseRepository.class);
        catalogo = mock(StripeProductCatalog.class);
        servicio = new LicensePricingService(config, stripe, licencias, catalogo);

        when(config.habilitado()).thenReturn(true);
        when(config.listaDeLicencia()).thenReturn(34900);
        when(config.listaDeAdicional()).thenReturn(24900);
        when(config.moneda()).thenReturn("mxn");
        when(catalogo.productoDeLicencia(false)).thenReturn("picale_licencia");
        when(stripe.disponible()).thenReturn(true);
    }

    /** La tarifa completa: el producto del plan al precio de lista del plan. */
    private static StripeClient.Tarifa completa() {
        return argThat(t -> t != null && t.producto().equals("picale_licencia") && t.centavos() == 34900
                && t.mensual() && t.moneda().equals("mxn"));
    }

    private License licencia(String suscripcion, LicenseStatus estado, boolean comoAdicional, boolean cancela, int diasDeAntiguedad) {
        return License.builder().organizationId(org).workspaceId(UUID.randomUUID()).status(estado)
                .stripeSubscriptionId(suscripcion).pricedAsExtra(comoAdicional).cancelAtPeriodEnd(cancela)
                .createdAt(LocalDateTime.now().minusDays(diasDeAntiguedad)).build();
    }

    @Test
    @DisplayName("terminó la del precio completo y queda un adicional: pasa al precio completo en su próxima renovación")
    void promueveAlQueSeQueda() {
        License terminada = licencia("sub_1", LicenseStatus.ENDED, false, false, 60);
        License queda = licencia("sub_2", LicenseStatus.ACTIVE, true, false, 30);
        when(licencias.findAll()).thenReturn(List.of(terminada, queda));

        int promovidas = servicio.reconciliar();

        assertThat(promovidas).isEqualTo(1);
        verify(stripe).cambiarPrecio(eq("sub_2"), completa());
        assertThat(queda.isPricedAsExtra()).isFalse();
        verify(licencias).save(queda);
    }

    @Test
    @DisplayName("mientras la del precio completo siga en uso, aunque ya no se renueve, nadie más se promueve")
    void noPromueveMientrasLaCompletaSigaVigente() {
        License completa = licencia("sub_1", LicenseStatus.ACTIVE, false, true, 60); // cancelada, pero aún pagada
        License extra = licencia("sub_2", LicenseStatus.ACTIVE, true, false, 30);
        when(licencias.findAll()).thenReturn(List.of(completa, extra));

        assertThat(servicio.reconciliar()).isZero();
        verify(stripe, never()).cambiarPrecio(anyString(), any());
    }

    @Test
    @DisplayName("con varias adicionales se promueve la más antigua que siga renovándose, no una que ya va a terminar")
    void laMasAntiguaQueRenueva() {
        License porTerminar = licencia("sub_a", LicenseStatus.ACTIVE, true, true, 90);
        License antigua = licencia("sub_b", LicenseStatus.ACTIVE, true, false, 50);
        License reciente = licencia("sub_c", LicenseStatus.ACTIVE, true, false, 10);
        when(licencias.findAll()).thenReturn(List.of(porTerminar, reciente, antigua));

        servicio.reconciliar();

        verify(stripe).cambiarPrecio(eq("sub_b"), completa());
        verify(stripe, never()).cambiarPrecio(eq("sub_a"), any());
        verify(stripe, never()).cambiarPrecio(eq("sub_c"), any());
    }

    @Test
    @DisplayName("si todas las que quedan están por terminar no hay a quién promover")
    void todasPorTerminar() {
        when(licencias.findAll()).thenReturn(List.of(licencia("sub_1", LicenseStatus.ACTIVE, true, true, 10)));

        assertThat(servicio.reconciliar()).isZero();
        verify(stripe, never()).cambiarPrecio(anyString(), any());
    }

    @Test
    @DisplayName("las pruebas gratis, las terminadas y las sin suscripción no cuentan como pagadas")
    void soloLasPagadas() {
        License prueba = licencia(null, LicenseStatus.TRIALING, false, false, 5);
        License terminada = licencia("sub_1", LicenseStatus.ENDED, true, false, 40);
        when(licencias.findAll()).thenReturn(List.of(prueba, terminada));

        assertThat(servicio.reconciliar()).isZero();
        verify(stripe, never()).cambiarPrecio(anyString(), any());
    }

    @Test
    @DisplayName("cada organización se revisa por su cuenta: una con precio completo no libra a otra sin él")
    void porOrganizacion() {
        UUID otra = UUID.randomUUID();
        License conCompleta = licencia("sub_1", LicenseStatus.ACTIVE, false, false, 60);
        License sinCompleta = License.builder().organizationId(otra).workspaceId(UUID.randomUUID())
                .status(LicenseStatus.ACTIVE).stripeSubscriptionId("sub_9").pricedAsExtra(true)
                .createdAt(LocalDateTime.now().minusDays(20)).build();
        when(licencias.findAll()).thenReturn(List.of(conCompleta, sinCompleta));

        assertThat(servicio.reconciliar()).isEqualTo(1);
        verify(stripe).cambiarPrecio(eq("sub_9"), completa());
        verify(stripe, never()).cambiarPrecio(eq("sub_1"), any());
    }

    @Test
    @DisplayName("una vez promovida no se vuelve a tocar en el siguiente barrido")
    void idempotente() {
        License queda = licencia("sub_2", LicenseStatus.ACTIVE, true, false, 30);
        when(licencias.findAll()).thenReturn(List.of(queda));

        assertThat(servicio.reconciliar()).isEqualTo(1);
        assertThat(servicio.reconciliar()).isZero();
        verify(stripe).cambiarPrecio(eq("sub_2"), completa()); // una sola vez
    }

    @Test
    @DisplayName("si Stripe falla no se marca como promovida: se reintenta en el siguiente barrido")
    void stripeFalla() {
        License queda = licencia("sub_2", LicenseStatus.ACTIVE, true, false, 30);
        when(licencias.findAll()).thenReturn(List.of(queda));
        doThrow(new IllegalStateException("Stripe caído")).when(stripe).cambiarPrecio(anyString(), any());

        assertThat(servicio.reconciliar()).isZero();
        assertThat(queda.isPricedAsExtra()).isTrue();
        verify(licencias, never()).save(any());
    }

    @Test
    @DisplayName("sin cobros, sin Stripe o con un solo precio no hace nada")
    void sinNadaQueHacer() {
        License queda = licencia("sub_2", LicenseStatus.ACTIVE, true, false, 30);
        when(licencias.findAll()).thenReturn(List.of(queda));

        when(config.habilitado()).thenReturn(false);
        assertThat(servicio.reconciliar()).isZero();
        when(config.habilitado()).thenReturn(true);

        when(stripe.disponible()).thenReturn(false);
        assertThat(servicio.reconciliar()).isZero();
        when(stripe.disponible()).thenReturn(true);

        when(config.listaDeAdicional()).thenReturn(34900); // el mismo precio para todos
        assertThat(servicio.reconciliar()).isZero();
        when(config.listaDeAdicional()).thenReturn(24900);
        when(config.listaDeLicencia()).thenReturn(0); // sin precio configurado
        assertThat(servicio.reconciliar()).isZero();

        verify(stripe, never()).cambiarPrecio(anyString(), any());
    }
}
