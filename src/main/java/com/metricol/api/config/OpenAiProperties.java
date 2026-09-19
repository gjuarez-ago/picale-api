package com.metricol.api.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

@Configuration
@ConfigurationProperties(prefix = "openai")
@Getter
@Setter
public class OpenAiProperties {

    private String apiKey;

    private String model;

    /** El modelo que genera las imágenes de campaña. Tiene que estar permitido en el proyecto de OpenAI. */
    private String imageModel = "gpt-image-1.5";

    /** Lo que cobra OpenAI por {@link #imageModel}; mismo criterio que {@link #pricing}. */
    private Pricing imagePricing = new Pricing();

    /**
     * Qué tan fielmente conserva gpt-image los detalles de las fotos de
     * referencia al editarlas (rostros, equipo, letreros): {@code high} o
     * {@code low}. Vacío = no se manda. Con {@code high} las fotos entran a
     * más resolución y cuestan más tokens de entrada.
     */
    private String imageInputFidelity = "high";

    /**
     * El modelo del director de arte: el que mira las fotos y decide la
     * composición de la campaña. Aparte del modelo de texto de siempre porque
     * aquí sí vale la pena uno más capaz —se llama una vez por campaña, y de
     * su decisión depende lo que cuesta cada imagen—. Tiene que estar permitido
     * en el proyecto de OpenAI.
     */
    private String directorModel = "gpt-5-mini";

    /**
     * Cuánto razona el director: {@code minimal}, {@code low}, {@code medium} o
     * {@code high}. Vacío = no se manda (los modelos que no razonan lo
     * rechazan). En {@code low} porque la respuesta se espera con la pantalla
     * abierta y Cloudflare corta a los 100 s.
     */
    private String directorReasoningEffort = "low";

    /** Segundos que se le espera al director antes de seguir sin él. */
    private int directorTimeoutSeconds = 25;

    /** Lo que cobra OpenAI por {@link #directorModel}. */
    private Pricing directorPricing = new Pricing();

    /**
     * Lo que cobra OpenAI por {@link #model}. Con esto se calcula el costo de
     * cada llamada que se anota en {@code ai_usage}.
     *
     * <p>Va en configuración y no escrito en el código porque depende del
     * modelo, y el modelo ya se elige por variable de entorno: cambiar uno sin
     * poder cambiar el otro dejaría el reporte mintiendo en silencio.
     */
    private Pricing pricing = new Pricing();

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Getter
    @Setter
    public static class Pricing {

        /** Dólares por millón de tokens de entrada. */
        private BigDecimal inputUsdPerMillion = BigDecimal.ZERO;

        /** Dólares por millón de tokens de salida. */
        private BigDecimal outputUsdPerMillion = BigDecimal.ZERO;

        /**
         * En cero se siguen contando los tokens, pero el costo sale en cero. Se
         * avisa al arrancar (ver AiUsageRecorder) para que no pase por
         * "la IA es gratis".
         */
        public boolean isConfigured() {
            return positivo(inputUsdPerMillion) || positivo(outputUsdPerMillion);
        }

        private static boolean positivo(BigDecimal valor) {
            return valor != null && valor.signum() > 0;
        }
    }
}
