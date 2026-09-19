package com.metricol.api.service.billing;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.config.StripeProperties;
import com.metricol.api.entity.BillingSetting;
import com.metricol.api.entity.CreditPack;
import com.metricol.api.repository.BillingSettingRepository;
import com.metricol.api.repository.CreditPackRepository;

/**
 * Los ajustes de los cobros, leídos de la tabla {@code billing_settings}.
 *
 * <p>Es el único sitio por donde se pregunta «¿están encendidos los cobros?»,
 * «¿cuántos días de prueba?», «¿cuántos créditos trae una licencia?». Cambiar
 * un ajuste es un {@code UPDATE} en la base (o el endpoint de operación) y en
 * medio minuto lo ven todos los procesos: nada de esto requiere desplegar.
 *
 * <p>Como {@code app_limits}, las propiedades de entorno solo siembran lo que
 * falta al arrancar; una fila que ya existe no se toca jamás.
 *
 * <p><b>Por omisión los cobros están APAGADOS</b> y la aplicación se comporta
 * como siempre: sin licencias, sin créditos, sin archivar a nadie. Se encienden
 * con {@code billing.enabled = true}, cuando Stripe está listo.
 */
@Service
public class BillingConfig {

    private static final Logger log = LoggerFactory.getLogger(BillingConfig.class);

    public static final String HABILITADO = "billing.enabled";
    public static final String MONEDA = "billing.currency";
    public static final String PRECIO_LICENCIA = "billing.license.stripe_price_id";
    public static final String DIAS_PRUEBA_REGISTRO = "billing.trial_days.signup";
    public static final String DIAS_PRUEBA_EXISTENTES = "billing.trial_days.existing";
    public static final String DIAS_GRACIA = "billing.grace_days";
    public static final String CREDITOS_MENSUALES = "billing.credits.monthly_per_license";

    private static final Duration VIGENCIA_DE_LA_CACHE = Duration.ofSeconds(30);

    private final BillingSettingRepository ajustes;
    private final CreditPackRepository paquetes;
    private final StripeProperties stripe;

    private volatile Map<String, String> cache = Map.of();
    private volatile Instant cargado = Instant.EPOCH;

    public BillingConfig(BillingSettingRepository ajustes, CreditPackRepository paquetes, StripeProperties stripe) {
        this.ajustes = ajustes;
        this.paquetes = paquetes;
        this.stripe = stripe;
    }

    // ------------------------------------------------------------------
    // Lo que preguntan los demás
    // ------------------------------------------------------------------

    /** ¿Están encendidos los cobros? Apagados, nada de licencias ni créditos aplica. */
    public boolean habilitado() {
        return booleano(HABILITADO, false);
    }

    /** La moneda de las licencias y los paquetes: «mxn». */
    public String moneda() {
        return texto(MONEDA, "mxn");
    }

    /** El {@code price_...} de Stripe con el que se cobra una licencia nueva. */
    public String precioDeLicencia() {
        return texto(PRECIO_LICENCIA, "");
    }

    /** Días gratis para quien se registra. */
    public int diasDePruebaAlRegistrarse() {
        return Math.max(0, entero(DIAS_PRUEBA_REGISTRO, 14));
    }

    /** Días gratis para los workspaces que ya existían cuando se encendieron los cobros. */
    public int diasDePruebaDeLosExistentes() {
        return Math.max(0, entero(DIAS_PRUEBA_EXISTENTES, 30));
    }

    /** Días que se sigue usando un workspace después de un cobro fallido. */
    public int diasDeGracia() {
        return Math.max(0, entero(DIAS_GRACIA, 7));
    }

    /** Créditos de imagen que trae cada licencia cada mes. */
    public int creditosMensuales() {
        return Math.max(0, entero(CREDITOS_MENSUALES, 5));
    }

    public String texto(String clave, String porDefecto) {
        refrescarSiToca();
        String guardado = cache.get(clave);
        return guardado == null || guardado.isBlank() ? porDefecto : guardado.trim();
    }

    public int entero(String clave, int porDefecto) {
        try {
            return Integer.parseInt(texto(clave, String.valueOf(porDefecto)));
        } catch (NumberFormatException ex) {
            return porDefecto;
        }
    }

    public boolean booleano(String clave, boolean porDefecto) {
        return Boolean.parseBoolean(texto(clave, String.valueOf(porDefecto)));
    }

    /** Todos los ajustes, ordenados: para el panel de operación. */
    public Map<String, String> todos() {
        refrescarSiToca();
        return new TreeMap<>(cache);
    }

    /** Los paquetes de créditos que se venden hoy. */
    public java.util.List<CreditPack> paquetesEnVenta() {
        return paquetes.findAllByOrderBySortOrderAscCreditsAsc().stream().filter(CreditPack::vendible).toList();
    }

    /**
     * Cambia un ajuste y lo hace efectivo en el acto para este proceso. Solo
     * claves que existen: un error de dedo no debe crear un ajuste huérfano.
     */
    @Transactional
    public void guardar(String clave, String valor) {
        BillingSetting fila = ajustes.findById(clave)
                .orElseThrow(() -> new IllegalArgumentException("No existe el ajuste \"" + clave + "\"."));
        fila.setValor(valor == null ? "" : valor.trim());
        fila.setUpdatedAt(LocalDateTime.now());
        ajustes.save(fila);
        invalidar();
    }

    public void invalidar() {
        cargado = Instant.EPOCH;
    }

    // ------------------------------------------------------------------
    // Carga y siembra
    // ------------------------------------------------------------------

    private void refrescarSiToca() {
        if (Duration.between(cargado, Instant.now()).compareTo(VIGENCIA_DE_LA_CACHE) < 0) {
            return;
        }
        try {
            Map<String, String> nuevo = new LinkedHashMap<>();
            for (BillingSetting fila : ajustes.findAll()) {
                nuevo.put(fila.getClave(), fila.getValor());
            }
            cache = nuevo;
        } catch (Exception ex) {
            // Sin la tabla se sigue con lo que hubiera: y sin nada, los cobros quedan apagados.
            log.warn("No se pudieron leer los ajustes de cobros: {}", ex.getMessage());
        }
        cargado = Instant.now();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void sembrar() {
        try {
            int nuevos = 0;
            for (Map.Entry<String, String[]> e : semillas().entrySet()) {
                if (ajustes.existsById(e.getKey())) {
                    continue;
                }
                ajustes.save(BillingSetting.builder()
                        .clave(e.getKey())
                        .valor(e.getValue()[0])
                        .descripcion(e.getValue()[1])
                        .updatedAt(LocalDateTime.now())
                        .build());
                nuevos++;
            }
            if (nuevos > 0) {
                log.info("Ajustes de cobros sembrados en billing_settings: {} clave(s) nueva(s)", nuevos);
            }
            sembrarPaquetes();
        } catch (Exception ex) {
            log.warn("No se pudo sembrar billing_settings; los cobros siguen apagados: {}", ex.getMessage());
        } finally {
            invalidar();
        }
    }

    private Map<String, String[]> semillas() {
        Map<String, String[]> s = new LinkedHashMap<>();
        s.put(HABILITADO, new String[] { "false",
                "Enciende los cobros. false = todo funciona como siempre: sin licencias, sin creditos, sin archivar." });
        s.put(MONEDA, new String[] { "mxn", "Moneda de las licencias y los paquetes (la de los precios de Stripe)." });
        s.put(PRECIO_LICENCIA, new String[] { stripe.getPriceWorkspace() == null ? "" : stripe.getPriceWorkspace(),
                "price_... de Stripe de la licencia mensual de un workspace. El monto se cambia en Stripe." });
        s.put(DIAS_PRUEBA_REGISTRO, new String[] { "14", "Dias gratis al registrarse. 0 = sin prueba." });
        s.put(DIAS_PRUEBA_EXISTENTES, new String[] { "30",
                "Dias gratis para los workspaces que ya existian al encender los cobros." });
        s.put(DIAS_GRACIA, new String[] { "7", "Dias que se sigue usando un workspace tras un cobro fallido." });
        s.put(CREDITOS_MENSUALES, new String[] { "5", "Creditos de imagen (1 = 1 generacion) que trae cada licencia al mes." });
        return s;
    }

    private void sembrarPaquetes() {
        if (paquetes.count() > 0) {
            return;
        }
        // Apagados y sin precio: se venden cuando se les pone un price_ de Stripe y se encienden.
        int orden = 1;
        for (int creditos : new int[] { 10, 25, 50 }) {
            paquetes.save(CreditPack.builder()
                    .code("PACK_" + creditos)
                    .name(creditos + " creditos de imagen")
                    .credits(creditos)
                    .active(false)
                    .sortOrder(orden++)
                    .build());
        }
        log.info("Paquetes de creditos sembrados (apagados, sin precio)");
    }
}
