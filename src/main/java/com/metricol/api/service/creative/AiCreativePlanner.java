package com.metricol.api.service.creative;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.config.CreativeDirectorProperties;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.enums.CreativePieceType;
import com.metricol.api.enums.MediaType;
import com.metricol.api.enums.Platform;
import com.metricol.api.service.ai.OpenAiClient;

/** La IA decide; esta clase convierte su respuesta en un plan seguro y ejecutable. */
@Component
public class AiCreativePlanner implements CreativePlanner {
    private static final Set<String> ROOT_FIELDS = Set.of("recipeVersion", "pieces");
    private static final Set<String> PIECE_FIELDS = Set.of("type", "assetIds", "objective", "title",
            "captions", "networks", "durationSeconds", "reason");
    private static final String SYSTEM = """
            Eres el director creativo de una marca. Recibes una tanda de archivos ya subidos y decides
            qué piezas útiles crear. Devuelve SOLO JSON, sin markdown, con esta forma exacta:
            {"recipeVersion":"creative-plan-v1","pieces":[{"type":"REEL|CAROUSEL|IMAGE|STORY",
            "assetIds":["uuid"],"objective":"...","title":"... o null",
            "captions":{"INSTAGRAM":"..."},"networks":["INSTAGRAM"],
            "durationSeconds":20,"reason":"razón interna breve"}]}
            Reglas: no inventes hechos ni IDs; usa cada ID solo cuando aporta; omite formatos sin material.
            REEL usa 4 a 8 imágenes, orden editorial, 15 a 30 segundos y sin música. CAROUSEL usa 2 a 10
            imágenes sin duplicados. IMAGE y STORY usan una imagen. Una pieza puede complementar a otra.
            Redes válidas: INSTAGRAM, FACEBOOK, LINKEDIN, TIKTOK, YOUTUBE. STORY solo Instagram/Facebook.
            El texto debe basarse únicamente en el brief y las descripciones disponibles.
            """;

    private final OpenAiClient client;
    private final CreativeDirectorProperties properties;
    private final ObjectMapper json = new ObjectMapper();

    public AiCreativePlanner(OpenAiClient client, CreativeDirectorProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public CreativePlan plan(List<AssetInput> assets, String brief) {
        String raw = client.completeJson(AiOperacion.DIRECTOR_CREATIVO, SYSTEM, prompt(assets, brief));
        try {
            return parseStrict(raw, assets);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("La IA devolvió un plan inválido.", ex);
        }
    }

    private String prompt(List<AssetInput> assets, String brief) {
        StringBuilder out = new StringBuilder("Objetivo opcional: ")
                .append(brief == null || brief.isBlank() ? "(no indicado)" : brief.strip()).append("\nArchivos:\n");
        for (AssetInput a : assets) {
            out.append("- id=").append(a.id()).append(" type=").append(a.type())
                    .append(" name=").append(clean(a.fileName())).append(" description=")
                    .append(clean(a.description())).append(" visualAnalysis=")
                    .append(clean(a.visualAnalysis())).append('\n');
        }
        return out.toString();
    }

    CreativePlan parseStrict(String raw, List<AssetInput> inputs) throws Exception {
        if (raw == null || raw.isBlank() || !raw.strip().startsWith("{") || !raw.strip().endsWith("}")) {
            throw new IllegalArgumentException("La IA devolvió un plan inválido.");
        }
        JsonNode root = json.readTree(raw);
        exactFields(root, ROOT_FIELDS);
        if (!root.path("recipeVersion").asText().equals(properties.getRecipeVersion())
                || !root.path("pieces").isArray()) {
            throw new IllegalArgumentException("La versión o estructura del plan no es válida.");
        }
        Map<UUID, AssetInput> allowed = new LinkedHashMap<>();
        inputs.forEach(a -> allowed.put(a.id(), a));
        List<CreativePlan.Piece> pieces = new ArrayList<>();
        for (JsonNode node : root.path("pieces")) {
            exactFields(node, PIECE_FIELDS);
            CreativePieceType type = CreativePieceType.valueOf(required(node, "type").toUpperCase(Locale.ROOT));
            List<UUID> ids = uuids(node.path("assetIds"));
            if (ids.isEmpty() || new HashSet<>(ids).size() != ids.size()
                    || ids.stream().anyMatch(id -> !allowed.containsKey(id))) {
                throw new IllegalArgumentException("El plan contiene archivos inválidos o repetidos.");
            }
            validateShape(type, ids, allowed, node.path("durationSeconds"));
            List<String> networks = strings(node.path("networks"));
            validateNetworks(type, networks);
            Map<String, String> captions = captions(node.path("captions"), networks);
            String objective = required(node, "objective");
            String reason = required(node, "reason");
            String title = node.path("title").isNull() ? null : blankToNull(node.path("title").asText());
            Integer duration = type == CreativePieceType.REEL ? node.path("durationSeconds").asInt() : null;
            pieces.add(new CreativePlan.Piece(type, ids, cut(objective, 300), cut(title, 160), captions,
                    networks, duration, cut(reason, 1000)));
        }
        if (pieces.isEmpty()) {
            throw new IllegalArgumentException("La IA no encontró una pieza útil en esta tanda.");
        }
        return new CreativePlan(properties.getRecipeVersion(), List.copyOf(pieces));
    }

    private static void validateShape(CreativePieceType type, List<UUID> ids, Map<UUID, AssetInput> allowed,
            JsonNode duration) {
        boolean allImages = ids.stream().allMatch(id -> allowed.get(id).type() == MediaType.IMAGE);
        if (!allImages) throw new IllegalArgumentException("Este MVP solo compone piezas nuevas con imágenes.");
        switch (type) {
            case REEL -> {
                if (ids.size() < 4 || ids.size() > 8 || !duration.canConvertToInt()
                        || duration.asInt() < 15 || duration.asInt() > 30) {
                    throw new IllegalArgumentException("La receta de reel no es válida.");
                }
            }
            case CAROUSEL -> {
                if (ids.size() < 2 || ids.size() > 10) throw new IllegalArgumentException("El carrusel no es válido.");
            }
            case IMAGE, STORY -> {
                if (ids.size() != 1) throw new IllegalArgumentException("La pieza debe usar una imagen.");
            }
        }
    }

    private static void validateNetworks(CreativePieceType type, List<String> networks) {
        if (networks.isEmpty()) throw new IllegalArgumentException("La pieza no tiene redes recomendadas.");
        for (String name : networks) {
            Platform p;
            try { p = Platform.valueOf(name); } catch (Exception ex) {
                throw new IllegalArgumentException("El plan contiene una red no soportada.");
            }
            if (type == CreativePieceType.STORY && p != Platform.INSTAGRAM && p != Platform.FACEBOOK)
                throw new IllegalArgumentException("Una historia tiene una red incompatible.");
            if (type == CreativePieceType.IMAGE && p == Platform.TIKTOK)
                throw new IllegalArgumentException("Una imagen individual no se recomienda para TikTok.");
        }
    }

    private static Map<String, String> captions(JsonNode node, List<String> networks) {
        if (!node.isObject()) throw new IllegalArgumentException("Faltan los textos por red.");
        Map<String, String> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            if (!networks.contains(e.getKey()) || e.getValue().asText().isBlank())
                throw new IllegalArgumentException("Los textos por red no coinciden con el plan.");
            result.put(e.getKey(), cut(e.getValue().asText().strip(), 2200));
        });
        if (!result.keySet().containsAll(networks)) throw new IllegalArgumentException("Falta texto para una red.");
        return result;
    }

    private static void exactFields(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) throw new IllegalArgumentException("El plan no es un objeto JSON válido.");
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw new IllegalArgumentException("El plan contiene campos no admitidos.");
        });
        if (!allowed.stream().allMatch(node::has)) throw new IllegalArgumentException("Al plan le faltan campos.");
    }

    private static List<UUID> uuids(JsonNode node) {
        if (!node.isArray()) throw new IllegalArgumentException("assetIds debe ser una lista.");
        List<UUID> out = new ArrayList<>();
        node.forEach(n -> out.add(UUID.fromString(n.asText())));
        return out;
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) throw new IllegalArgumentException("networks debe ser una lista.");
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asText().strip().toUpperCase(Locale.ROOT)));
        if (new HashSet<>(out).size() != out.size()) throw new IllegalArgumentException("Hay redes repetidas.");
        return out;
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText().strip();
        if (value.isBlank()) throw new IllegalArgumentException("Falta " + field + " en el plan.");
        return value;
    }
    private static String clean(String value) { return value == null ? "(sin dato)" : value.replaceAll("[\\r\\n]+", " ").strip(); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static String cut(String value, int max) { return value == null || value.length() <= max ? value : value.substring(0, max); }
}
