package com.metricol.api.controller;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.config.StripeProperties;
import com.metricol.api.service.billing.StripeEventProcessor;
import com.metricol.api.service.billing.StripeEventProcessor.Resultado;
import com.metricol.api.service.billing.StripeWebhookVerifier;

/**
 * Donde Stripe avisa que algo pasó: se pagó, falló un cobro, se canceló.
 *
 * <p>No lleva sesión: lo autentica la <b>firma</b> de cada aviso. Sin firma
 * válida no se procesa nada, sea cual sea el contenido.
 *
 * <p>Lo que contesta importa: 200 = "ya lo tengo, no me lo mandes más"; un error
 * = "mándamelo otra vez", y Stripe reintenta durante días. Por eso un aviso al
 * que le falta contexto (una factura antes que su compra) contesta 409 y no 200.
 */
@RestController
@RequestMapping("/api/v1/billing")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final StripeProperties props;
    private final StripeEventProcessor procesador;
    private final ObjectMapper json = new ObjectMapper();

    public StripeWebhookController(StripeProperties props, StripeEventProcessor procesador) {
        this.props = props;
        this.procesador = procesador;
    }

    /** Los bytes tal cual llegaron: la firma se calcula sobre ellos, y reserializar el JSON la rompería. */
    @PostMapping("/webhook")
    public ResponseEntity<String> recibir(@RequestBody byte[] cuerpo,
            @RequestHeader(value = "Stripe-Signature", required = false) String firma) {
        if (!props.puedeRecibirAvisos()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Los cobros no están configurados.");
        }
        if (!StripeWebhookVerifier.valido(cuerpo, firma, props.getWebhookSecret().strip(),
                props.getWebhookToleranceSeconds(), Instant.now())) {
            log.warn("Aviso de Stripe con firma inválida o vencida: se rechaza");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Firma inválida.");
        }

        JsonNode evento;
        try {
            evento = json.readTree(cuerpo);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("No es un JSON válido.");
        }

        try {
            Resultado resultado = procesador.procesar(evento);
            return resultado == Resultado.REINTENTAR
                    ? ResponseEntity.status(HttpStatus.CONFLICT).body("Falta contexto: vuelve a mandarlo.")
                    : ResponseEntity.ok("ok");
        } catch (RuntimeException ex) {
            // Un error nuestro: Stripe lo reintenta, y la transacción ya se revirtió.
            log.error("Falló el procesamiento del aviso {}: {}", evento.path("id").asText(""), ex.toString(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error interno.");
        }
    }
}
