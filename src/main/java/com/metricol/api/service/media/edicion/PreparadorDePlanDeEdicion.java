package com.metricol.api.service.media.edicion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Única frontera de preparación: JSON estricto → control por cuenta → validación → identidad.
 * No crea trabajos, archivos, propuestas ni cobra IA. Persistencia y render se conectan después.
 */
@Service
public class PreparadorDePlanDeEdicion {
    public static final int MAX_JSON_BYTES = 64 * 1024;

    // Mapper aislado: no cambia cómo se leen encargos históricos ni peticiones de otras funciones.
    private final JsonMapper json = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(16).maxStringLength(1024).maxNumberLength(32).build())
            .build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    private final ControlDeEdicionDinamica control;
    private final ValidadorDePlanDeEdicion validador;

    public PreparadorDePlanDeEdicion(ControlDeEdicionDinamica control, ValidadorDePlanDeEdicion validador) {
        this.control = control;
        this.validador = validador;
    }

    /** Valor preparado para guardar después. No tiene estado READY ni una URL de video. */
    public record PlanPreparado(PlanDeEdicionVideo plan, String canonicalJson, String idempotencyKey) { }

    public PlanPreparado preparar(String contenido, ContextoEdicionVideo contexto) {
        if (contexto == null) throw new IllegalArgumentException("Falta el contexto verificado del video.");
        if (contenido == null || contenido.length() > MAX_JSON_BYTES
                || contenido.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("El plan excede el tamaño permitido o está vacío.");
        }
        PlanDeEdicionVideo plan;
        try {
            plan = json.readValue(contenido, PlanDeEdicionVideo.class);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            // No devolver el JSON, rutas o mensajes del parser a logs/respuestas.
            throw new IllegalArgumentException("El plan de edición no tiene un formato válido.");
        }
        if (plan == null) throw new IllegalArgumentException("Falta el plan de edición.");
        var decision = control.evaluar(contexto.workspaceId(), plan.profile(), contexto.alreadyEdited());
        if (decision != ControlDeEdicionDinamica.Decision.ANALYSIS_ALLOWED) {
            throw new IllegalStateException("La edición dinámica no está habilitada para este video: " + decision);
        }
        validador.validar(plan, contexto);
        try {
            String canonico = json.writeValueAsString(plan);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonico.getBytes(StandardCharsets.UTF_8)));
            return new PlanPreparado(plan, canonico, hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("No se pudo preparar la identidad del plan.", ex);
        }
    }
}
