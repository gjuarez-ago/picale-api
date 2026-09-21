package com.metricol.api.service.billing;

import java.time.LocalDateTime;
import java.time.ZoneId;
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
     * Cuánto se cobra y por qué producto. El monto viaja EN LÍNEA (no hay un
     * {@code price_...} que crear a mano en Stripe): sale de la tabla de ajustes
     * y lo conserva cada suscripción con el importe con el que nació.
     *
     * @param producto el {@code prod_...} (o id fijo) del catálogo de Stripe
     * @param centavos en la unidad menor de la moneda (34900 = $349.00)
     * @param mensual {@code true} = suscripción mensual (una licencia); {@code false} = pago único (un paquete)
     */
    public record Tarifa(String producto, long centavos, String moneda, boolean mensual) {
    }

    private static void poner(Map<String, String> campos, String prefijo, Tarifa tarifa) {
        campos.put(prefijo + "[price_data][currency]", tarifa.moneda().toLowerCase());
        campos.put(prefijo + "[price_data][product]", tarifa.producto());
        campos.put(prefijo + "[price_data][unit_amount]", String.valueOf(tarifa.centavos()));
        if (tarifa.mensual()) {
            campos.put(prefijo + "[price_data][recurring][interval]", "month");
        }
    }

    /**
     * Crea el producto del catálogo si todavía no existe, con un id fijo.
     *
     * <p>Con id fijo, «buscar o crear» es «pedirlo y, si no está, crearlo»: sin
     * búsquedas —que en Stripe tardan en reflejar lo recién creado— y sin poder
     * acabar con dos productos para lo mismo. Es lo que hace el catálogo de
     * Vivento, y por lo mismo nadie tiene que crear productos a mano.
     */
    public void asegurarProducto(String id, String nombre, String descripcion) {
        exigirDisponible();
        try {
            rest.get().uri("/products/" + id)
                    .header("Authorization", "Bearer " + props.getSecretKey().strip())
                    .retrieve().body(String.class);
            return; // ya existe
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() != 404) {
                throw fallo("/products/" + id, ex);
            }
        }
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("id", id);
        campos.put("name", nombre);
        if (descripcion != null && !descripcion.isBlank()) {
            campos.put("description", descripcion);
        }
        post("/products", campos, null);
        log.info("Producto {} creado en Stripe", id);
    }

    /**
     * Una compra que se paga en la página de Stripe.
     *
     * @param datos lo que Stripe devuelve tal cual en el aviso: qué se compró y para quién. Con una
     *        suscripción también se copia a la suscripción, para que sus avisos lo traigan.
     * @return la dirección a la que se manda a la persona
     */
    public String crearCompra(String cliente, Tarifa tarifa, Map<String, String> datos,
            String urlExito, String urlCancelado, String llaveDeRepeticion) {
        boolean suscripcion = tarifa.mensual();
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("mode", suscripcion ? "subscription" : "payment");
        campos.put("customer", cliente);
        poner(campos, "line_items[0]", tarifa);
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

    /**
     * Cambia el precio de una suscripción para sus PRÓXIMAS renovaciones: lo ya
     * cobrado no se toca ni se prorratea. Se usa cuando un negocio adicional pasa
     * a ser el primero de su organización.
     */
    public JsonNode cambiarPrecio(String suscripcion, Tarifa tarifa) {
        JsonNode actual = obtenerSuscripcion(suscripcion);
        String articulo = texto(actual.path("items").path("data").path(0), "id");
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("items[0][id]", articulo);
        poner(campos, "items[0]", tarifa);
        campos.put("proration_behavior", "none");
        return post("/subscriptions/" + suscripcion, campos, null);
    }

    /**
     * Suma días gratis a una suscripción que YA se pagó: la próxima cobranza pasa a {@code hasta}.
     *
     * <p>Se usa cuando alguien contrata en plena prueba: se le cobra el primer mes, y los días de
     * prueba que le quedaban se agregan al final en vez de perderse. En Stripe eso es mover el fin
     * de la prueba de la suscripción ({@code trial_end}); sin prorrateo, porque lo ya cobrado no se
     * devuelve ni se recalcula. Repetirlo con la misma fecha no cambia nada.
     */
    public JsonNode extenderHasta(String suscripcion, LocalDateTime hasta) {
        long segundos = hasta.atZone(ZoneId.systemDefault()).toEpochSecond();
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("trial_end", String.valueOf(segundos));
        campos.put("proration_behavior", "none");
        return post("/subscriptions/" + suscripcion, campos, "extender-" + suscripcion + "-" + segundos);
    }

    public JsonNode obtenerSuscripcion(String suscripcion) {
        return get("/subscriptions/" + suscripcion);
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
