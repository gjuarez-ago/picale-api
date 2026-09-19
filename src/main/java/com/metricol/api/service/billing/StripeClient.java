package com.metricol.api.service.billing;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.config.StripeProperties;

/**
 * Habla con la API de Stripe por REST, sin su SDK.
 *
 * <p>Son cuatro o cinco llamadas y todas son un formulario: un SDK entero para
 * eso es una dependencia más que actualizar y un jar que bajar. Aquí se ve
 * exactamente qué se manda.
 *
 * <p>Con cobros apagados (sin llave) no llama a nadie: cualquier método lanza
 * {@link IllegalStateException} con un mensaje que se le puede enseñar a la
 * persona. Nunca escribe la llave en el log.
 */
@Component
public class StripeClient {

    private static final Logger log = LoggerFactory.getLogger(StripeClient.class);

    private final StripeProperties props;
    private final RestClient rest;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public StripeClient(StripeProperties props) {
        this(props, construir(props));
    }

    /** Para las pruebas, que le ponen un servidor falso. */
    StripeClient(StripeProperties props, RestClient rest) {
        this.props = props;
        this.rest = rest;
    }

    private static RestClient construir(StripeProperties props) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(5_000);
        fabrica.setReadTimeout(20_000);
        return RestClient.builder().baseUrl(props.getApiBaseUrl()).requestFactory(fabrica).build();
    }

    public boolean disponible() {
        return props.cobrosActivos();
    }

    // ------------------------------------------------------------------
    // Clientes, pagos y suscripciones
    // ------------------------------------------------------------------

    /** Crea el cliente de Stripe de una organización. Devuelve su {@code cus_...}. */
    public String crearCliente(String correo, String nombre, String organizacionId) {
        Map<String, String> campos = new LinkedHashMap<>();
        if (correo != null && !correo.isBlank()) {
            campos.put("email", correo);
        }
        if (nombre != null && !nombre.isBlank()) {
            campos.put("name", nombre);
        }
        campos.put("metadata[organization_id]", organizacionId);
        return texto(post("/customers", campos, "cliente-" + organizacionId), "id");
    }

    /**
     * Una compra que se paga en la página de Stripe.
     *
     * @param suscripcion {@code true} = suscripción (una licencia); {@code false} = pago único (un paquete)
     * @param datos lo que Stripe devuelve tal cual en el aviso: qué se compró y para quién. Con una
     *        suscripción también se copia a la suscripción, para que sus avisos lo traigan.
     * @return la dirección a la que se manda a la persona
     */
    public String crearCompra(String cliente, String precio, boolean suscripcion, Map<String, String> datos,
            String urlExito, String urlCancelado, String llaveDeRepeticion) {
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("mode", suscripcion ? "subscription" : "payment");
        campos.put("customer", cliente);
        campos.put("line_items[0][price]", precio);
        campos.put("line_items[0][quantity]", "1");
        campos.put("success_url", urlExito);
        campos.put("cancel_url", urlCancelado);
        campos.put("allow_promotion_codes", "true");
        for (Map.Entry<String, String> dato : datos.entrySet()) {
            campos.put("metadata[" + dato.getKey() + "]", dato.getValue());
            if (suscripcion) {
                campos.put("subscription_data[metadata][" + dato.getKey() + "]", dato.getValue());
            }
        }
        return texto(post("/checkout/sessions", campos, llaveDeRepeticion), "url");
    }

    /** El portal donde la persona cambia su tarjeta y ve sus facturas. */
    public String crearPortal(String cliente, String urlDeRegreso) {
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("customer", cliente);
        campos.put("return_url", urlDeRegreso);
        return texto(post("/billing_portal/sessions", campos, null), "url");
    }

    /**
     * Que la suscripción no se renueve (o que sí, si se arrepintió). Se sigue
     * usando hasta el fin de lo ya pagado.
     */
    public JsonNode cancelarAlFinal(String suscripcion, boolean cancelar) {
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("cancel_at_period_end", String.valueOf(cancelar));
        return post("/subscriptions/" + suscripcion, campos, null);
    }

    public JsonNode obtenerSuscripcion(String suscripcion) {
        return get("/subscriptions/" + suscripcion);
    }

    /** El precio (monto, moneda, ciclo) tal como está en Stripe: es la única verdad del monto. */
    public JsonNode obtenerPrecio(String precio) {
        return get("/prices/" + precio);
    }

    // ------------------------------------------------------------------
    // Lo de abajo
    // ------------------------------------------------------------------

    private JsonNode post(String ruta, Map<String, String> campos, String llaveDeRepeticion) {
        exigirDisponible();
        MultiValueMap<String, String> formulario = new LinkedMultiValueMap<>();
        campos.forEach(formulario::add);
        try {
            RestClient.RequestBodySpec peticion = rest.post().uri(ruta)
                    .header("Authorization", "Bearer " + props.getSecretKey().strip())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED);
            if (llaveDeRepeticion != null) {
                // Si la misma petición se repite (un reintento, un doble clic), Stripe devuelve la
                // primera respuesta en vez de crear otra compra.
                peticion = peticion.header("Idempotency-Key", llaveDeRepeticion);
            }
            return leer(peticion.body(formulario).retrieve().body(String.class));
        } catch (RestClientResponseException ex) {
            throw fallo(ruta, ex);
        }
    }

    private JsonNode get(String ruta) {
        exigirDisponible();
        try {
            return leer(rest.get().uri(ruta)
                    .header("Authorization", "Bearer " + props.getSecretKey().strip())
                    .retrieve().body(String.class));
        } catch (RestClientResponseException ex) {
            throw fallo(ruta, ex);
        }
    }

    private void exigirDisponible() {
        if (!props.cobrosActivos()) {
            throw new IllegalStateException("Los cobros no están configurados todavía.");
        }
    }

    private JsonNode leer(String cuerpo) {
        try {
            return json.readTree(cuerpo == null ? "{}" : cuerpo);
        } catch (Exception ex) {
            throw new IllegalStateException("Stripe contestó algo que no se pudo leer.", ex);
        }
    }

    private IllegalStateException fallo(String ruta, RestClientResponseException ex) {
        // El cuerpo de un error de Stripe no lleva secretos; sirve para diagnosticar.
        log.warn("Stripe rechazó {} ({}): {}", ruta, ex.getStatusCode().value(),
                ex.getResponseBodyAsString().replaceAll("\\s+", " "));
        return new IllegalStateException("No se pudo completar la operación con el proveedor de pagos. "
                + "Inténtalo de nuevo en un momento.");
    }

    private String texto(JsonNode nodo, String campo) {
        String valor = nodo.path(campo).asText("");
        if (valor.isBlank()) {
            throw new IllegalStateException("El proveedor de pagos no devolvió lo esperado (" + campo + ").");
        }
        return valor;
    }
}
