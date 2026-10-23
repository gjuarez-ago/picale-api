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
     * Cuánto razona el modelo de texto, cuando es uno que razona (gpt-5 en
     * adelante): {@code minimal}, {@code low}, {@code medium} o {@code high}.
     * Solo se manda a esos; los demás lo rechazan. En {@code low} porque
     * escribir un pie de publicación no pide pensar diez segundos, y hay
     * pantallas esperando la respuesta.
     */
    private String reasoningEffort = "low";

    /**
     * Cuánto razona el asistente, que es caso aparte.
     *
     * <p>En {@code /v1/chat/completions} ningún modelo gpt-6 admite herramientas y
     * razonamiento a la vez, y el asistente manda su catálogo en cada llamada: con
     * cualquier valor que no sea {@code none} la petición se rechaza con un 400.
     * El resto de las llamadas no llevan herramientas y sí pueden razonar, por eso
     * tienen su propio ajuste. Para tener las dos cosas hay que mover al asistente
     * a {@code /v1/responses}.
     */
    private String assistantReasoningEffort = "none";

    /**
     * Por dónde habla el asistente: {@code responses} o {@code chat}.
     *
     * <p>{@code responses} es el camino bueno —razona y usa herramientas a la vez—
     * y {@code chat} se conserva como salida de emergencia: si algo sale mal en
     * producción se vuelve cambiando esta variable, sin desplegar código. Se
     * retira cuando lleve una semana sin usarse.
     */
    private String assistantApi = "chat";

    /** El modelo que genera las imágenes de campaña. Tiene que estar permitido en el proyecto de OpenAI. */
    private String imageModel = "gpt-image-2";

    /** Se conserva medium hasta comparar resultados reales con high. */
    private String imageQuality = "medium";

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

    /**
     * El modelo que pasa a texto lo que se dice en un video, para que el
     * agente sepa de qué habla (un precio, una promoción, un testimonio).
     */
    private String transcriptionModel = "gpt-4o-mini-transcribe";

    /**
     * El director de foto del agente: mira cada foto en alta resolución y
     * escribe la mejora a su medida. Vacío = el mismo del director de arte.
     */
    private String photoDirectorModel = "";

    /** Cuánto razona el director de foto. Corre en segundo plano: puede pensar más. */
    private String photoDirectorReasoningEffort = "medium";

    /** Calidad de la mejora de fotos ({@code low}, {@code medium}, {@code high}). */
    private String imageEnhanceQuality = "high";

    /** Cuántas fotos mejora con IA el agente al día por espacio. 0 = apagado. */
    private int enhancePerDay = 20;

    /** Segundos que se le espera al director antes de seguir sin él. */
    private int directorTimeoutSeconds = 25;

    /** Lo que cobra OpenAI por {@link #directorModel}. */
    private Pricing directorPricing = new Pricing();

    /**
     * El modelo que escribe las descripciones de las publicaciones (y sus retoques).
     * Aparte del de texto general porque aquí la calidad SE LEE: un pie de publicación
     * plano es lo primero que la gente nota (oct 2026). Vacío = el de siempre
     * ({@link #model}). Tiene que estar permitido en el proyecto de OpenAI.
     */
    private String redactorModel;

    /** Cuánto razona el redactor. Más que el general: aquí se piensa qué aporta el texto. */
    private String redactorReasoningEffort = "medium";

    /** Lo que cobra OpenAI por {@link #redactorModel}. */
    private Pricing redactorPricing = new Pricing();

    /** ¿Esta operación la escribe el redactor con su propio modelo? */
    public boolean usaRedactor(com.metricol.api.enums.AiOperacion operacion) {
        return redactorModel != null && !redactorModel.isBlank()
                && (operacion == com.metricol.api.enums.AiOperacion.REDACTAR
                        || operacion == com.metricol.api.enums.AiOperacion.AJUSTAR
                        || operacion == com.metricol.api.enums.AiOperacion.ACORTAR);
    }

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
