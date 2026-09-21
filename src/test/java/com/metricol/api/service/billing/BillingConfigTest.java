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

import com.metricol.api.entity.BillingSetting;
import com.metricol.api.entity.CreditPack;
import com.metricol.api.repository.BillingSettingRepository;
import com.metricol.api.repository.CreditPackRepository;

/** La tabla de ajustes de los cobros: lo que se puede cambiar sin desplegar. */
class BillingConfigTest {

    private BillingSettingRepository ajustes;
    private CreditPackRepository paquetes;
    private BillingConfig config;

    /** La tabla de mentira. */
    private final Map<String, BillingSetting> tabla = new ConcurrentHashMap<>();

    @BeforeEach
    void preparar() {
        ajustes = mock(BillingSettingRepository.class);
        paquetes = mock(CreditPackRepository.class);
        config = new BillingConfig(ajustes, paquetes);

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
        assertThat(config.diasDePruebaDeLosExistentes()).isEqualTo(14);
        assertThat(config.diasDeGracia()).isEqualTo(7);
        assertThat(config.creditosMensuales()).isEqualTo(5);
    }

    @Test
    @DisplayName("lo que dice la tabla manda sobre el valor por defecto")
    void mandaLaTabla() {
        poner(BillingConfig.HABILITADO, "true");
        poner(BillingConfig.DIAS_PRUEBA_REGISTRO, "30");
        poner(BillingConfig.CREDITOS_MENSUALES, "20");
        poner(BillingConfig.LISTA_LICENCIA, "39900");

        assertThat(config.habilitado()).isTrue();
        assertThat(config.diasDePruebaAlRegistrarse()).isEqualTo(30);
        assertThat(config.creditosMensuales()).isEqualTo(20);
        assertThat(config.listaDeLicencia()).isEqualTo(39900);
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
        assertThat(tabla).containsKeys(BillingConfig.HABILITADO, BillingConfig.MONEDA, BillingConfig.LISTA_LICENCIA,
                BillingConfig.DIAS_PRUEBA_REGISTRO, BillingConfig.DIAS_PRUEBA_EXISTENTES,
                BillingConfig.CREDITOS_MENSUALES);
        // Arrancan apagados: encender los cobros es una decisión, no un efecto del despliegue.
        assertThat(tabla.get(BillingConfig.HABILITADO).getValor()).isEqualTo("false");
    }

    @Test
    @DisplayName("siembra los precios (349 y 249) y los días de aviso")
    void preciosDeLista() {
        when(paquetes.count()).thenReturn(0L);
        config.sembrar();

        assertThat(config.listaDeLicencia()).isEqualTo(34900);
        assertThat(config.listaDeAdicional()).isEqualTo(24900);
        assertThat(config.diasDeAviso()).isEqualTo(5);
        assertThat(tabla).containsKeys(BillingConfig.LISTA_LICENCIA, BillingConfig.LISTA_ADICIONAL, BillingConfig.DIAS_DE_AVISO);
        // Los precios ya no son ids de Stripe: la API crea los productos y manda el monto.
        assertThat(tabla.keySet()).noneMatch(k -> k.contains("stripe_price"));
    }

    @Test
    @DisplayName("por omisión los precios ya incluyen el IVA, y es un ajuste que se puede cambiar")
    void ivaIncluido() {
        when(paquetes.count()).thenReturn(0L);
        config.sembrar();
        assertThat(config.impuestoIncluido()).isTrue();
        assertThat(tabla).containsKey(BillingConfig.IVA_INCLUIDO);

        config.guardar(BillingConfig.IVA_INCLUIDO, "false");
        assertThat(config.impuestoIncluido()).isFalse();
    }

    @Test
    @DisplayName("un precio en pesos en vez de centavos («349») se rechaza al guardarlo: saldría a $3.49")
    void precioEnPesosSeRechaza() {
        poner(BillingConfig.LISTA_LICENCIA, "34900");

        assertThatThrownBy(() -> config.guardar(BillingConfig.LISTA_LICENCIA, "349"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("34900");
        assertThatThrownBy(() -> config.guardar(BillingConfig.LISTA_ADICIONAL, "249"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.guardar(BillingConfig.LISTA_LICENCIA, "trescientos"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(config.listaDeLicencia()).as("no se pisó con el valor malo").isEqualTo(34900);

        config.guardar(BillingConfig.LISTA_LICENCIA, " 39900 ");
        assertThat(config.listaDeLicencia()).isEqualTo(39900);
    }

    @Test
    @DisplayName("los demás ajustes también se validan: booleanos, moneda, días y créditos")
    void otrosAjustes() {
        poner(BillingConfig.HABILITADO, "false");
        assertThatThrownBy(() -> config.guardar(BillingConfig.HABILITADO, "si"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.guardar(BillingConfig.MONEDA, "pesos"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.guardar(BillingConfig.DIAS_GRACIA, "-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.guardar(BillingConfig.DIAS_PRUEBA_REGISTRO, "9999"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.guardar(BillingConfig.CREDITOS_MENSUALES, "cinco"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(config.habilitado()).isFalse();

        poner(BillingConfig.MONEDA, "mxn");
        config.guardar(BillingConfig.MONEDA, "USD");
        assertThat(config.moneda()).isEqualTo("usd");
    }

    @Test
    @DisplayName("un precio de lista mal escrito no rompe: se usa el de siempre")
    void precioDeListaMalEscrito() {
        poner(BillingConfig.LISTA_LICENCIA, "trescientos");
        poner(BillingConfig.LISTA_ADICIONAL, "-5");
        assertThat(config.listaDeLicencia()).isEqualTo(34900);
        assertThat(config.listaDeAdicional()).isZero();
    }

    @Test
    @DisplayName("siembra tres paquetes de créditos, apagados y con su precio de lista, solo la primera vez")
    void paquetesIniciales() {
        when(paquetes.count()).thenReturn(0L);
        config.sembrar();

        org.mockito.ArgumentCaptor<CreditPack> guardados = org.mockito.ArgumentCaptor.forClass(CreditPack.class);
        verify(paquetes, times(3)).save(guardados.capture());
        assertThat(guardados.getAllValues()).extracting(CreditPack::getCredits).containsExactly(10, 25, 50);
        assertThat(guardados.getAllValues()).extracting(CreditPack::getPriceMinor).containsExactly(7900, 17900, 32900);
        assertThat(guardados.getAllValues()).allMatch(p -> !p.isActive());

        when(paquetes.count()).thenReturn(3L);
        config.sembrar();
        verify(paquetes, times(3)).save(any(CreditPack.class)); // no vuelve a sembrar
    }

    @Test
    @DisplayName("solo se venden los paquetes encendidos y con precio")
    void paquetesEnVenta() {
        CreditPack listo = CreditPack.builder().code("A").credits(10).priceMinor(7900).active(true).build();
        CreditPack apagado = CreditPack.builder().code("B").credits(25).priceMinor(17900).active(false).build();
        CreditPack sinPrecio = CreditPack.builder().code("C").credits(50).active(true).build();
        when(paquetes.findAllByOrderBySortOrderAscCreditsAsc()).thenReturn(List.of(listo, apagado, sinPrecio));

        assertThat(config.paquetesEnVenta()).containsExactly(listo);
        verify(paquetes, never()).save(any());
    }
}
