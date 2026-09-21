package com.metricol.api.service.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.metricol.api.config.StripeProperties;

/** Lo que se le manda a Stripe y lo que se hace con lo que contesta. */
class StripeClientTest {

    private static final String BASE = "https://api.stripe.com/v1";

    private StripeProperties props;
    private MockRestServiceServer servidor;
    private StripeClient cliente;

    @BeforeEach
    void preparar() {
        props = new StripeProperties();
        props.setSecretKey("sk_test_secretisima");
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        servidor = MockRestServiceServer.bindTo(builder).build();
        cliente = new StripeClient(props, builder.build());
    }

    @Test
    @DisplayName("sin llave los cobros no están disponibles y no se llama a nadie")
    void sinLlave() {
        props.setSecretKey("");

        assertThat(cliente.disponible()).isFalse();
        assertThatThrownBy(() -> cliente.crearCliente("a@b.com", "Org", "org-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no están configurados");
    }

    @Test
    @DisplayName("crear el cliente manda su correo, su nombre y la organización, con llave de repetición")
    void crearCliente() {
        servidor.expect(requestTo(BASE + "/customers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk_test_secretisima"))
                .andExpect(header("Idempotency-Key", "cliente-org-1"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(containsString("email=a%40b.com")))
                .andExpect(content().string(containsString("metadata%5Borganization_id%5D=org-1")))
                .andRespond(withSuccess("{\"id\":\"cus_123\"}", MediaType.APPLICATION_JSON));

        assertThat(cliente.crearCliente("a@b.com", "Mi Org", "org-1")).isEqualTo("cus_123");
        servidor.verify();
    }

    @Test
    @DisplayName("una suscripción manda el modo, el precio, las direcciones y los datos también a la suscripción")
    void compraDeLicencia() {
        Map<String, String> datos = new LinkedHashMap<>();
        datos.put("kind", "license");
        datos.put("organization_id", "org-1");

        servidor.expect(requestTo(BASE + "/checkout/sessions"))
                .andExpect(content().string(containsString("mode=subscription")))
                .andExpect(content().string(containsString("customer=cus_1")))
                // El monto viaja en línea, con el producto del catálogo: no hay price_... que crear a mano.
                .andExpect(content().string(containsString("line_items%5B0%5D%5Bprice_data%5D%5Bproduct%5D=picale_licencia")))
                .andExpect(content().string(containsString("line_items%5B0%5D%5Bprice_data%5D%5Bunit_amount%5D=34900")))
                .andExpect(content().string(containsString("line_items%5B0%5D%5Bprice_data%5D%5Bcurrency%5D=mxn")))
                .andExpect(content().string(containsString("line_items%5B0%5D%5Bprice_data%5D%5Brecurring%5D%5Binterval%5D=month")))
                .andExpect(content().string(containsString("line_items%5B0%5D%5Bquantity%5D=1")))
                .andExpect(content().string(containsString("metadata%5Bkind%5D=license")))
                // Los avisos de la suscripción (renovaciones) también tienen que traer de quién es.
                .andExpect(content().string(containsString("subscription_data%5Bmetadata%5D%5Borganization_id%5D=org-1")))
                .andExpect(content().string(containsString("success_url=")))
                .andRespond(withSuccess("{\"id\":\"cs_1\",\"url\":\"https://checkout.stripe.com/c/pay/cs_1\"}",
                        MediaType.APPLICATION_JSON));

        String url = cliente.crearCompra("cus_1", new StripeClient.Tarifa("picale_licencia", 34900, "MXN", true), datos,
                "https://x/ok?s={CHECKOUT_SESSION_ID}", "https://x/no", null);

        assertThat(url).isEqualTo("https://checkout.stripe.com/c/pay/cs_1");
        servidor.verify();
    }

    @Test
    @DisplayName("un paquete es un pago único: sin datos de suscripción")
    void compraDePaquete() {
        servidor.expect(requestTo(BASE + "/checkout/sessions"))
                .andExpect(content().string(containsString("mode=payment")))
                .andExpect(content().string(containsString("line_items%5B0%5D%5Bprice_data%5D%5Bunit_amount%5D=7900")))
                .andExpect(content().string(not(containsString("recurring"))))
                .andExpect(content().string(containsString("metadata%5Bkind%5D=pack")))
                .andExpect(content().string(not(containsString("subscription_data"))))
                .andRespond(withSuccess("{\"url\":\"https://checkout.stripe.com/c/pay/cs_2\"}", MediaType.APPLICATION_JSON));

        String url = cliente.crearCompra("cus_1", new StripeClient.Tarifa("picale_paquete_pack_10", 7900, "mxn", false),
                Map.of("kind", "pack"), "https://x/ok", "https://x/no", null);

        assertThat(url).endsWith("cs_2");
    }

    @Test
    @DisplayName("cancelar al final manda la bandera; reanudar la apaga")
    void cancelarAlFinal() {
        servidor.expect(requestTo(BASE + "/subscriptions/sub_1"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string("cancel_at_period_end=true"))
                .andRespond(withSuccess("{\"id\":\"sub_1\",\"cancel_at_period_end\":true}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/subscriptions/sub_1"))
                .andExpect(content().string("cancel_at_period_end=false"))
                .andRespond(withSuccess("{\"id\":\"sub_1\",\"cancel_at_period_end\":false}", MediaType.APPLICATION_JSON));

        assertThat(cliente.cancelarAlFinal("sub_1", true).path("cancel_at_period_end").asBoolean()).isTrue();
        assertThat(cliente.cancelarAlFinal("sub_1", false).path("cancel_at_period_end").asBoolean()).isFalse();
        servidor.verify();
    }

    @Test
    @DisplayName("sumar días gratis mueve el fin de la prueba de una suscripción ya pagada, sin prorratear")
    void extenderHasta() {
        java.time.LocalDateTime hasta = java.time.LocalDateTime.of(2026, 11, 20, 12, 0);
        long segundos = hasta.atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
        servidor.expect(requestTo(BASE + "/subscriptions/sub_1"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("trial_end=" + segundos)))
                .andExpect(content().string(containsString("proration_behavior=none")))
                .andExpect(header("Idempotency-Key", "extender-sub_1-" + segundos))
                .andRespond(withSuccess("{\"id\":\"sub_1\",\"status\":\"trialing\"}", MediaType.APPLICATION_JSON));

        assertThat(cliente.extenderHasta("sub_1", hasta).path("status").asText()).isEqualTo("trialing");
        servidor.verify();
    }

    @Test
    @DisplayName("cambiar el precio de una suscripción cambia su artículo, sin prorratear lo ya cobrado")
    void cambiarPrecio() {
        servidor.expect(requestTo(BASE + "/subscriptions/sub_1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"sub_1\",\"items\":{\"data\":[{\"id\":\"si_9\"}]}}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/subscriptions/sub_1"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("items%5B0%5D%5Bid%5D=si_9")))
                .andExpect(content().string(containsString("items%5B0%5D%5Bprice_data%5D%5Bproduct%5D=picale_licencia")))
                .andExpect(content().string(containsString("items%5B0%5D%5Bprice_data%5D%5Bunit_amount%5D=34900")))
                .andExpect(content().string(containsString("items%5B0%5D%5Bprice_data%5D%5Brecurring%5D%5Binterval%5D=month")))
                .andExpect(content().string(containsString("proration_behavior=none")))
                .andRespond(withSuccess("{\"id\":\"sub_1\"}", MediaType.APPLICATION_JSON));

        assertThat(cliente.cambiarPrecio("sub_1", new StripeClient.Tarifa("picale_licencia", 34900, "mxn", true)).path("id").asText()).isEqualTo("sub_1");
        servidor.verify();
    }

    @Test
    @DisplayName("si la suscripción no trae artículos no se intenta cambiar nada")
    void cambiarPrecioSinArticulos() {
        servidor.expect(requestTo(BASE + "/subscriptions/sub_1"))
                .andRespond(withSuccess("{\"id\":\"sub_1\",\"items\":{\"data\":[]}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> cliente.cambiarPrecio("sub_1", new StripeClient.Tarifa("picale_licencia", 34900, "mxn", true)))
                .isInstanceOf(IllegalStateException.class);
        servidor.verify();
    }

    @Test
    @DisplayName("el portal se abre para el cliente y regresa a donde se le diga")
    void portal() {
        servidor.expect(requestTo(BASE + "/billing_portal/sessions"))
                .andExpect(content().string(containsString("customer=cus_1")))
                .andExpect(content().string(containsString("return_url=")))
                .andRespond(withSuccess("{\"url\":\"https://billing.stripe.com/p/session/x\"}", MediaType.APPLICATION_JSON));

        assertThat(cliente.crearPortal("cus_1", "https://x/panel/facturacion")).contains("billing.stripe.com");
    }

    @Test
    @DisplayName("leer una suscripción es un GET con la llave")
    void lecturas() {
        servidor.expect(requestTo(BASE + "/subscriptions/sub_1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer sk_test_secretisima"))
                .andRespond(withSuccess("{\"status\":\"active\"}", MediaType.APPLICATION_JSON));

        assertThat(cliente.obtenerSuscripcion("sub_1").path("status").asText()).isEqualTo("active");
    }

    @Test
    @DisplayName("un producto que ya existe no se vuelve a crear")
    void productoQueYaExiste() {
        servidor.expect(requestTo(BASE + "/products/picale_licencia"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"picale_licencia\"}", MediaType.APPLICATION_JSON));

        cliente.asegurarProducto("picale_licencia", "Pícale · Licencia", "Todo incluido");
        servidor.verify(); // y ningún POST: no se esperaba
    }

    @Test
    @DisplayName("un producto que no existe se crea con su id fijo, su nombre y su descripción")
    void productoNuevo() {
        servidor.expect(requestTo(BASE + "/products/picale_licencia"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"resource_missing\"}}"));
        servidor.expect(requestTo(BASE + "/products"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("id=picale_licencia")))
                .andExpect(content().string(containsString("name=")))
                .andExpect(content().string(containsString("description=")))
                .andRespond(withSuccess("{\"id\":\"picale_licencia\"}", MediaType.APPLICATION_JSON));

        cliente.asegurarProducto("picale_licencia", "Pícale · Licencia", "Todo incluido");
        servidor.verify();
    }

    @Test
    @DisplayName("cualquier otro fallo al buscar el producto (llave inválida, Stripe caído) no se toma por «no existe»")
    void productoFalloRealNoCrea() {
        servidor.expect(requestTo(BASE + "/products/picale_licencia"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"Invalid API Key\"}}"));

        assertThatThrownBy(() -> cliente.asegurarProducto("picale_licencia", "X", null))
                .isInstanceOf(IllegalStateException.class);
        servidor.verify(); // no hubo POST
    }

    @Test
    @DisplayName("un error de Stripe se traduce a un mensaje para la persona, sin filtrar la llave ni el detalle")
    void errorDeStripe() {
        servidor.expect(requestTo(BASE + "/checkout/sessions"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"No such price: 'price_x'\"}}"));

        assertThatThrownBy(() -> cliente.crearCompra("cus_1", new StripeClient.Tarifa("picale_licencia", 34900, "mxn", true), Map.of(),
                "https://x/ok", "https://x/no", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Inténtalo de nuevo")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("sk_test").doesNotContain("price_x"));
    }

    @Test
    @DisplayName("si Stripe no devuelve lo esperado, se dice en vez de seguir con nulos")
    void respuestaSinLoEsperado() {
        servidor.expect(requestTo(BASE + "/checkout/sessions"))
                .andRespond(withSuccess("{\"id\":\"cs_1\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> cliente.crearCompra("cus_1", new StripeClient.Tarifa("picale_licencia", 34900, "mxn", true), Map.of(),
                "https://x/ok", "https://x/no", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("url");
    }
}
