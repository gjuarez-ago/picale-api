package com.metricol.api.service.creative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.metricol.api.config.CreativeDirectorProperties;
import com.metricol.api.enums.CreativePieceType;
import com.metricol.api.enums.MediaType;

class AiCreativePlannerTest {
    private AiCreativePlanner planner;
    private List<CreativePlanner.AssetInput> photos;

    @BeforeEach
    void setUp() {
        CreativeDirectorProperties properties = new CreativeDirectorProperties();
        planner = new AiCreativePlanner(null, properties);
        photos = List.of(photo(), photo(), photo(), photo(), photo(), photo(), photo(), photo());
    }

    @Test
    void acceptsOrderedReelAndComplementaryStory() throws Exception {
        String json = """
                {"recipeVersion":"creative-plan-v1","pieces":[
                  {"type":"REEL","assetIds":["%s","%s","%s","%s"],"objective":"Mostrar el recorrido",
                   "title":"Un día con nosotros","captions":{"INSTAGRAM":"Así se vivió."},
                   "networks":["INSTAGRAM"],"durationSeconds":20,"reason":"La secuencia cuenta una historia"},
                  {"type":"STORY","assetIds":["%s"],"objective":"Cerrar con detalle","title":null,
                   "captions":{"FACEBOOK":"El detalle final."},"networks":["FACEBOOK"],
                   "durationSeconds":null,"reason":"Funciona como cierre"}]}
                """.formatted(photos.get(3).id(), photos.get(0).id(), photos.get(2).id(), photos.get(1).id(),
                        photos.get(4).id());
        CreativePlan plan = planner.parseStrict(json, photos);
        assertThat(plan.pieces()).hasSize(2);
        assertThat(plan.pieces().get(0).type()).isEqualTo(CreativePieceType.REEL);
        assertThat(plan.pieces().get(0).assetIds()).containsExactly(
                photos.get(3).id(), photos.get(0).id(), photos.get(2).id(), photos.get(1).id());
    }

    @Test
    void acceptsAllFourFormatsWhenTheMaterialSupportsThem() throws Exception {
        String json = """
                {"recipeVersion":"creative-plan-v1","pieces":[
                  {"type":"REEL","assetIds":["%s","%s","%s","%s","%s","%s","%s","%s"],
                   "objective":"Contar la experiencia completa","title":"Ocho momentos",
                   "captions":{"INSTAGRAM":"La historia completa."},"networks":["INSTAGRAM"],
                   "durationSeconds":24,"reason":"Hay variedad y una secuencia clara"},
                  {"type":"CAROUSEL","assetIds":["%s","%s","%s"],"objective":"Explicar tres detalles",
                   "title":"Detalles","captions":{"LINKEDIN":"Tres decisiones del proceso."},
                   "networks":["LINKEDIN"],"durationSeconds":null,"reason":"Cada foto aporta un paso"},
                  {"type":"IMAGE","assetIds":["%s"],"objective":"Destacar el resultado","title":null,
                   "captions":{"FACEBOOK":"El resultado final."},"networks":["FACEBOOK"],
                   "durationSeconds":null,"reason":"Es la toma mas fuerte"},
                  {"type":"STORY","assetIds":["%s"],"objective":"Generar cercania","title":null,
                   "captions":{"INSTAGRAM":"Mira el detalle."},"networks":["INSTAGRAM"],
                   "durationSeconds":null,"reason":"Funciona en vertical"}]}
                """.formatted(
                        photos.get(0).id(), photos.get(1).id(), photos.get(2).id(), photos.get(3).id(),
                        photos.get(4).id(), photos.get(5).id(), photos.get(6).id(), photos.get(7).id(),
                        photos.get(1).id(), photos.get(3).id(), photos.get(5).id(),
                        photos.get(6).id(), photos.get(7).id());

        CreativePlan plan = planner.parseStrict(json, photos);

        assertThat(plan.pieces()).extracting(CreativePlan.Piece::type).containsExactly(
                CreativePieceType.REEL, CreativePieceType.CAROUSEL,
                CreativePieceType.IMAGE, CreativePieceType.STORY);
        assertThat(plan.pieces().get(0).assetIds()).containsExactlyElementsOf(
                photos.stream().map(CreativePlanner.AssetInput::id).toList());
    }

    @Test
    void acceptsOnlyOneUsefulPieceWhenThereIsNotEnoughMaterialForMore() throws Exception {
        List<CreativePlanner.AssetInput> onePhoto = List.of(photo());
        String json = piece("IMAGE", List.of(onePhoto.get(0).id()), null);

        CreativePlan plan = planner.parseStrict(json, onePhoto);

        assertThat(plan.pieces()).singleElement()
                .extracting(CreativePlan.Piece::type).isEqualTo(CreativePieceType.IMAGE);
    }

    @Test
    void rejectsInventedOrRepeatedIdsAndBadJson() {
        String invented = piece("REEL", List.of(photos.get(0).id(), photos.get(1).id(), photos.get(2).id(), UUID.randomUUID()), 20);
        assertThatThrownBy(() -> planner.parseStrict(invented, photos)).isInstanceOf(IllegalArgumentException.class);
        String repeated = piece("CAROUSEL", List.of(photos.get(0).id(), photos.get(0).id()), null);
        assertThatThrownBy(() -> planner.parseStrict(repeated, photos)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner.parseStrict("```json {} ```", photos)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void omitsUnsupportedInsteadOfAcceptingFiller() {
        String invalidStory = """
                {"recipeVersion":"creative-plan-v1","pieces":[{"type":"STORY","assetIds":["%s","%s"],
                "objective":"x","title":null,"captions":{"TIKTOK":"x"},"networks":["TIKTOK"],
                "durationSeconds":null,"reason":"x"}]}
                """.formatted(photos.get(0).id(), photos.get(1).id());
        assertThatThrownBy(() -> planner.parseStrict(invalidStory, photos)).isInstanceOf(IllegalArgumentException.class);
    }

    private String piece(String type, List<UUID> ids, Integer duration) {
        return "{\"recipeVersion\":\"creative-plan-v1\",\"pieces\":[{\"type\":\"" + type
                + "\",\"assetIds\":[\"" + String.join("\",\"", ids.stream().map(UUID::toString).toList())
                + "\"],\"objective\":\"x\",\"title\":null,\"captions\":{\"INSTAGRAM\":\"x\"},"
                + "\"networks\":[\"INSTAGRAM\"],\"durationSeconds\":" + duration + ",\"reason\":\"x\"}]}";
    }

    private static CreativePlanner.AssetInput photo() {
        return new CreativePlanner.AssetInput(UUID.randomUUID(), MediaType.IMAGE, "foto.jpg", "producto real",
                "orientación vertical, fuerza 4/5");
    }
}
