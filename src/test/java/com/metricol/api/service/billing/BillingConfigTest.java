package com.metricol.api.service.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.config.StripeProperties;
import com.metricol.api.entity.BillingSetting;
import com.metricol.api.entity.CreditPack;
import com.metricol.api.repository.BillingSettingRepository;
import com.metricol.api.repository.CreditPackRepository;

/** La tabla de ajustes de los cobros: lo que se puede cambiar sin desplegar. */
class BillingConfigTest {

    private BillingSettingRepository ajustes;
    private CreditPackRepository paquetes;
    private StripeProperties stripe;
    private BillingConfig config;

    /** La tabla de mentira. */
    private final Map<String, BillingSetting> tabla = new ConcurrentHashMap<>();

    @BeforeEach
    void preparar() {
        ajustes = mock(BillingSettingRepository.class);
        paquetes = mock(CreditPackRepository.class);
        stripe = new StripeProperties();
        config = new BillingConfig(ajustes, paquetes, stripe);

        when(ajustes.findAll()).thenAnswer(i -> new ArrayList<>(tabla.values()));
        when(ajustes.findById(anyString())).thenAnswer(i -> Optional.ofNullable(tabla.get((String) i.getArgument(0))));
        when(ajustes.existsById(anyString())).thenAnswer(i -> tabla.containsKey((String) i.getArgument(0)));
        when(ajustes.save(any(BillingSetting.class))).thenAnswer(i -> {
            BillingSetting s = i.getArgument(0);
            tabla.put(s.getClave(), s);
            return s;
        });
    }

    private void poner(String clave, String valor) {
        tabla.put(clave, BillingSetting.builder().clave(clave).valor(valor).updatedAt(LocalDateTime.now()).build());
        config.invalidar();
    }

    @Test
    @DisplayName("sin ajustes los cobros están APAGADOS y hay valores por defecto razonables")
    void porDefectoApagado() {
        assertThat(config.habilitado()).isFalse();
        assertThat(config.moneda()).isEqualTo("mxn");
        assertThat(config.diasDePruebaAlRegistrarse()).isEqualTo(14);
        assertThat(config.diasDePruebaDeLosExistentes()).isEqualTo(30);
        assertThat(config.diasDeGracia()).isEqualTo(7);
        assertThat(config.creditosMensuales()).isEqualTo(5);
    }

    @Test
    @DisplayName("lo que dice la tabla manda sobre el valor por defecto")
    void mandaLaTabla() {
        poner(BillingConfig.HABILITADO, "true");
        poner(BillingConfig.DIAS_PRUEBA_REGISTRO, "30");
        poner(BillingConfig.CREDITOS_MENSUALES, "20");
        poner(BillingConfig.PRECIO_LICENCIA, "price_abc123");

        assertThat(config.habilitado()).isTrue();
        assertThat(config.diasDePruebaAlRegistrarse()).isEqualTo(30);
        assertThat(config.creditosMensuales()).isEqualTo(20);
        assertThat(config.precioDeLicencia()).isEqualTo("price_abc123");
    }

    @Test
    @DisplayName("un valor que no es un número no rompe nada: vale el de por defecto")
    void numeroInvalido() {
        poner(BillingConfig.DIAS_GRACIA, "siete");
        assertThat(config.diasDeGracia()).isEqualTo(7);
    }

    @Test
    @DisplayName("nunca sale un negativo: 0 días de prueba es 'sin prueba'")
    void sinNegativos() {
        poner(BillingConfig.DIAS_PRUEBA_REGISTRO, "-5");
        assertThat(config.diasDePruebaAlRegistrarse()).isZero();
    }

    @Test
    @DisplayName("guardar cambia el ajuste en el acto y solo acepta claves que existen")
    void guardar() {
        poner(BillingConfig.HABILITADO, "false");
        assertThat(config.habilitado()).isFalse();

        config.guardar(BillingConfig.HABILITADO, " true ");
        assertThat(config.habilitado()).isTrue();

        assertThatThrownBy(() -> config.guardar("billing.no_existe", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("billing.no_existe");
    }

    @Test
    @DisplayName("al arrancar siembra lo que falta y NO pisa lo que ya se editó")
    void sembrar() {
        poner(BillingConfig.DIAS_GRACIA, "3"); // alguien lo cambió a mano
        when(paquetes.count()).thenReturn(0L);

        config.sembrar();

        assertThat(config.diasDeGracia()).as("no se pisa").isEqualTo(3);
        assertThat(tabla).containsKeys(BillingConfig.HABILITADO, BillingConfig.MONEDA, BillingConfig.PRECIO_LICENCIA,
                BillingConfig.DIAS_PRUEBA_REGISTRO, BillingConfig.DIAS_PRUEBA_EXISTENTES,
                BillingConfig.CREDITOS_MENSUALES);
        // Arrancan apagados: encender los cobros es una decisión, no un efecto del despliegue.
        assertThat(tabla.get(BillingConfig.HABILITADO).getValor()).isEqualTo("false");
    }

    @Test
    @DisplayName("el precio de la licencia se siembra desde STRIPE_PRICE_WORKSPACE si está")
    void semillaDelPrecio() {
        stripe.setPriceWorkspace("price_desde_env");
        when(paquetes.count()).thenReturn(0L);

        config.sembrar();

        assertThat(tabla.get(BillingConfig.PRECIO_LICENCIA).getValor()).isEqualTo("price_desde_env");
    }

    @Test
    @DisplayName("siembra tres paquetes de créditos, apagados y sin precio, solo la primera vez")
    void paquetesIniciales() {
        when(paquetes.count()).thenReturn(0L);
        config.sembrar();

        verify(paquetes, times(3)).save(any(CreditPack.class));

        when(paquetes.count()).thenReturn(3L);
        config.sembrar();
        verify(paquetes, times(3)).save(any(CreditPack.class)); // no vuelve a sembrar
    }

    @Test
    @DisplayName("solo se venden los paquetes encendidos y con precio de Stripe")
    void paquetesEnVenta() {
        CreditPack listo = CreditPack.builder().code("A").credits(10).stripePriceId("price_1").active(true).build();
        CreditPack apagado = CreditPack.builder().code("B").credits(25).stripePriceId("price_2").active(false).build();
        CreditPack sinPrecio = CreditPack.builder().code("C").credits(50).stripePriceId("").active(true).build();
        when(paquetes.findAllByOrderBySortOrderAscCreditsAsc()).thenReturn(List.of(listo, apagado, sinPrecio));

        assertThat(config.paquetesEnVenta()).containsExactly(listo);
        verify(paquetes, never()).save(any());
    }
}
