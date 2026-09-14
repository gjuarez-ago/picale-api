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
