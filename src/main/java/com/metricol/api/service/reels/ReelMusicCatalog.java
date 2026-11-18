package com.metricol.api.service.reels;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.config.ReelsProperties;
import com.metricol.api.service.storage.R2StorageService;

/** Selección determinista y materialización del catálogo musical CC0. */
@Component
public class ReelMusicCatalog {

    private static final List<String> SENSITIVE = List.of(
            "luto", "pesame", "fallec", "funeral", "velorio", "qepd", "condolencia",
            "desaparecid", "accidente", "emergencia", "incendio", "inundacion", "sismo",
            "disculpa", "perdon", "cancelado", "cerrado por", "sin servicio", "comunicado urgente");

    private final ReelsProperties properties;
    private final R2StorageService storage;
    private final Map<String, Track> tracks;
    private final JsonNode taxonomy;

    public ReelMusicCatalog(ReelsProperties properties, R2StorageService storage, ObjectMapper json) {
        this.properties = properties;
        this.storage = storage;
        try {
            JsonNode catalog = json.readTree(new ClassPathResource("music/catalogo.json").getInputStream());
            Map<String, Track> loaded = new LinkedHashMap<>();
            for (JsonNode node : catalog.path("tracks")) {
                Track track = new Track(node.path("id").asText(), node.path("title").asText(),
                        node.path("ambiente").asText(), node.path("bpm").asInt(),
                        node.path("durationSeconds").asInt(), node.path("sha256").asText());
                if (track.id().isBlank() || loaded.put(track.id(), track) != null) {
                    throw new IllegalStateException("El catálogo musical contiene un ID inválido o repetido.");
                }
            }
            if (loaded.isEmpty()) {
                throw new IllegalStateException("El catálogo musical está vacío.");
            }
            this.tracks = Map.copyOf(loaded);
            this.taxonomy = json.readTree(new ClassPathResource("music/taxonomia.json").getInputStream());
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo cargar el catálogo musical.", ex);
        }
    }

    /**
     * Escoge siempre la misma pista para la misma semilla. Tema e intención
     * suman puntos; con voz se favorecen camas tranquilas. Los avisos sensibles
     * quedan en silencio por defecto.
     */
    public Selection select(String theme, String intent, String title, boolean voiceOver, String seed) {
        return select(theme, intent, title, voiceOver, seed, null);
    }

    /**
     * @param ambiente el que eligió la cuenta (alegre, elegante…); pesa más que todo
     *                 lo demás junto (tema, intención y voz solo desempatan dentro de él).
     *                 Nulo = sin preferencia.
     */
    public Selection select(String theme, String intent, String title, boolean voiceOver, String seed,
            String ambiente) {
        if (!properties.isMusicEnabled() || sensitive(theme, intent, title)) {
            return null;
        }
        Map<String, Integer> score = new HashMap<>();
        add(score, preferred("temas", match("temas", theme + " " + title)), 5);
        add(score, preferred("intenciones", match("intenciones", intent + " " + title)), 6);
        if (ambiente != null) {
            add(score, tracks.values().stream().filter(track -> ambiente.equals(track.ambiente()))
                    .map(Track::id).toList(), 20);
        }
        if (voiceOver) {
            add(score, strings(taxonomy.path("voiceoverPreferredTrackIds")), 4);
            tracks.values().stream().filter(track -> track.bpm() <= 120)
                    .forEach(track -> score.merge(track.id(), 1, Integer::sum));
        }
        if (score.isEmpty()) {
            add(score, strings(taxonomy.path("rules").path("fallbackTrackIds")), 1);
        }
        List<Track> ranked = tracks.values().stream()
                .filter(track -> score.containsKey(track.id()))
                .sorted(Comparator.<Track>comparingInt(track -> score.getOrDefault(track.id(), 0)).reversed()
                        .thenComparing(Track::id))
                .toList();
        if (ranked.isEmpty()) {
            return null;
        }
        int best = score.getOrDefault(ranked.get(0).id(), 0);
        List<Track> top = ranked.stream()
                .filter(track -> score.getOrDefault(track.id(), 0) == best)
                .toList();
        Track chosen = top.get(stableIndex(seed, top.size()));
        return new Selection(chosen, key(chosen.id()));
    }

    /** Copia desde el catálogo local; en servidor sin catálogo local baja la clave estable de R2. */
    public Path materialize(Selection selection, Path workDirectory) {
        if (selection == null) {
            return null;
        }
        Path destination = workDirectory.resolve("music-" + selection.track().id() + ".mp3");
        Path root = Path.of(properties.getMusicLocalDirectory()).toAbsolutePath().normalize();
        Path local = root.resolve(selection.track().id() + ".mp3").normalize();
        try {
            if (local.startsWith(root) && Files.isRegularFile(local)) {
                Files.copy(local, destination, StandardCopyOption.REPLACE_EXISTING);
                return destination;
            }
            Files.createFile(destination);
            if (!storage.descargar(selection.storageKey(), destination)) {
                throw new ReelRenderException("La pista musical ya no existe en el almacenamiento.");
            }
            return destination;
        } catch (IOException ex) {
            throw new ReelRenderException("No se pudo preparar la pista musical.", ex);
        }
    }

    /** Los ambientes que de verdad tienen canciones: lo único que una cuenta puede elegir. */
    public java.util.Set<String> ambientes() {
        return tracks.values().stream().map(Track::ambiente).filter(a -> !a.isBlank())
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    }

    public Track track(String id) {
        return id == null ? null : tracks.get(id);
    }

    public Selection selection(String id) {
        Track track = track(id);
        return track == null ? null : new Selection(track, key(id));
    }

    private String key(String id) {
        return properties.getMusicStoragePrefix().replaceAll("/+$", "") + "/" + id + ".mp3";
    }

    private String match(String group, String text) {
        String clean = normalize(text);
        JsonNode choices = taxonomy.path(group);
        for (var iterator = choices.fields(); iterator.hasNext();) {
            var entry = iterator.next();
            if (clean.contains(normalize(entry.getKey()))) return entry.getKey();
            for (String keyword : strings(entry.getValue().path("keywords"))) {
                if (clean.contains(normalize(keyword))) return entry.getKey();
            }
        }
        if ("intenciones".equals(group)) {
            if (contains(clean, "promo", "oferta", "descuento")) return "promocion_oferta";
            if (contains(clean, "tutorial", "explica", "como ")) return "tutorial_explicacion";
            if (contains(clean, "testimonio", "entrevista", "opinion")) return "testimonio";
            if (contains(clean, "evento", "fiesta", "celebra")) return "evento";
            if (contains(clean, "historia", "emocion", "conoce")) return "historia_emocional";
            if (contains(clean, "urgente", "ultimo dia", "cuenta regresiva")) return "urgencia";
            return "presentacion_marca";
        }
        return null;
    }

    private List<String> preferred(String group, String key) {
        return key == null ? List.of() : strings(taxonomy.path(group).path(key).path("preferredTrackIds"));
    }

    private static void add(Map<String, Integer> score, List<String> ids, int points) {
        ids.forEach(id -> score.merge(id, points, Integer::sum));
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static int stableIndex(String seed, int size) {
        if (size <= 1) return 0;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(seed).getBytes(StandardCharsets.UTF_8));
            return Math.floorMod(java.nio.ByteBuffer.wrap(hash).getInt(), size);
        } catch (Exception ex) {
            return 0;
        }
    }

    private static boolean sensitive(String... texts) {
        String clean = normalize(String.join(" ", java.util.Arrays.stream(texts)
                .map(value -> value == null ? "" : value).toList()));
        return SENSITIVE.stream().anyMatch(clean::contains);
    }

    private static boolean contains(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replace('_', ' ').replaceAll("\\s+", " ").strip();
    }

    public record Track(String id, String title, String ambiente, int bpm,
            int durationSeconds, String sha256) { }

    public record Selection(Track track, String storageKey) { }
}
