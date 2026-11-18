package com.metricol.api.service.reels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.config.ReelsProperties;
import com.metricol.api.service.storage.R2StorageService;

class ReelMusicCatalogTest {

    private final ReelsProperties properties = new ReelsProperties();
    private final ReelMusicCatalog catalog = new ReelMusicCatalog(
            properties, mock(R2StorageService.class), new ObjectMapper());

    @Test
    void combinesBusinessThemeIntentAndVoiceDeterministically() {
        ReelMusicCatalog.Selection first = catalog.select(
                "cafetería y alimentos", "promoción del menú", "Prueba nuestro café", true, "same-seed");
        ReelMusicCatalog.Selection second = catalog.select(
                "cafetería y alimentos", "promoción del menú", "Prueba nuestro café", true, "same-seed");

        assertThat(first).isNotNull();
        assertThat(second.track().id()).isEqualTo(first.track().id());
        assertThat(first.storageKey()).startsWith("music/catalog/v1/").endsWith(".mp3");
        assertThat(first.track().bpm()).isLessThanOrEqualTo(120);
    }

    @Test
    void elAmbienteDeLaCuentaMandaSobreElGiroYLaVoz() {
        assertThat(catalog.ambientes()).contains("alegre", "elegante", "relajada", "energetica");
        for (String ambiente : catalog.ambientes()) {
            ReelMusicCatalog.Selection elegida = catalog.select(
                    "cafetería y alimentos", "promoción del menú", "Prueba nuestro café", true, "s", ambiente);
            assertThat(elegida.track().ambiente()).as(ambiente).isEqualTo(ambiente);
        }
        // Sin ambiente, lo de antes: misma pista que sin el parámetro.
        assertThat(catalog.select("gimnasio", "promoción", "Entrena hoy", false, "s", null).track().id())
                .isEqualTo(catalog.select("gimnasio", "promoción", "Entrena hoy", false, "s").track().id());
    }

    @Test
    void rotatesAcrossAllBestMatchesInsteadOfOnlyFour() {
        long distinct = IntStream.range(0, 200)
                .mapToObj(seed -> catalog.select(null, null, null, false,
                        "seed-" + seed, "juguetona").track().id())
                .distinct().count();

        assertThat(distinct).isGreaterThan(4);
    }

    @Test
    void seriousNoticeStaysSilentAndGlobalSwitchWins() {
        assertThat(catalog.select("restaurante", "comunicado urgente",
                "Lamentamos el fallecimiento", false, "x")).isNull();

        properties.setMusicEnabled(false);
        assertThat(catalog.select("gimnasio", "promoción", "Entrena hoy", false, "x")).isNull();
    }
}
