package com.metricol.api.service.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.config.OpenAiProperties;
import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.AiUsage;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.repository.AiUsageRepository;

/**
 * Anota cada llamada a OpenAI a nombre del workspace que la pidió.
 *
 * <p>El workspace sale del mismo sitio que usa Hibernate para filtrar
 * ({@link TenantIdentifierResolver}): toda llamada a la IA ocurre dentro de
 * una petición autenticada, así que en ese momento se sabe de quién es sin
 * tener que pasarlo de mano en mano por cada servicio.
 */
@Service
public class AiUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(AiUsageRecorder.class);

    private static final BigDecimal MILLON = BigDecimal.valueOf(1_000_000);

    private final AiUsageRepository repository;
    private final OpenAiProperties props;
    private final TenantIdentifierResolver tenants;

    public AiUsageRecorder(AiUsageRepository repository, OpenAiProperties props,
            TenantIdentifierResolver tenants) {
        this.repository = repository;
        this.props = props;
        this.tenants = tenants;

        if (props.isConfigured() && !props.getPricing().isConfigured()) {
            log.warn("OpenAI esta configurado pero sus precios no (OPENAI_PRICING_*): "
                    + "se contaran los tokens y el costo saldra en cero.");
        }
    }

    /**
     * Anota una llamada ya contestada.
     *
     * <p>En su propia transacción: se llama desde dentro de otras
     * —VisorDeMedios guarda la descripción de la foto en la suya— y un fallo
     * al anotar no puede deshacer lo que la IA ya contestó y ya se pagó. Quien
     * llama lo envuelve además en try/catch, porque el commit de esta
     * transacción ocurre al salir del método, fuera de cualquier catch de aquí.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrar(AiOperacion operacion, String modelo, int tokensEntrada, int tokensSalida) {
        repository.save(AiUsage.builder()
                .workspaceId(workspaceActual())
                .operacion(operacion)
                .modelo(nombreDeModelo(modelo))
                .tokensEntrada(Math.max(0, tokensEntrada))
                .tokensSalida(Math.max(0, tokensSalida))
                .costoUsd(costo(tokensEntrada, tokensSalida, props.getPricing()))
                .build());
    }

    /** Dólares, con ocho decimales: una llamada suelta cuesta fracciones de centavo. */
    static BigDecimal costo(int tokensEntrada, int tokensSalida, OpenAiProperties.Pricing precio) {
        BigDecimal entrada = precio == null ? null : precio.getInputUsdPerMillion();
        BigDecimal salida = precio == null ? null : precio.getOutputUsdPerMillion();

        return cero(entrada).multiply(BigDecimal.valueOf(Math.max(0, tokensEntrada)))
                .add(cero(salida).multiply(BigDecimal.valueOf(Math.max(0, tokensSalida))))
                .divide(MILLON, 8, RoundingMode.HALF_UP);
    }

    private UUID workspaceActual() {
        String tenant = tenants.resolveCurrentTenantIdentifier();
        if (tenant == null || TenantIdentifierResolver.GLOBAL_TENANT.equals(tenant)) {
            return null;
        }
        try {
            return UUID.fromString(tenant);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String nombreDeModelo(String modelo) {
        if (modelo == null || modelo.isBlank()) {
            return "desconocido";
        }
        // La columna aguanta 80; los nombres de OpenAI rondan los 25.
        return modelo.length() <= 80 ? modelo : modelo.substring(0, 80);
    }

    private static BigDecimal cero(BigDecimal valor) {
        return valor == null ? BigDecimal.ZERO : valor;
    }
}
