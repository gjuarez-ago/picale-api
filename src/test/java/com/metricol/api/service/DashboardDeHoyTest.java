package com.metricol.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.enums.PostStatus;
import com.metricol.api.models.response.PostResponse;

/** Lo que enseña la pantalla Hoy: lo de hoy y lo que espera algo, no lo de otros días. */
class DashboardDeHoyTest {

    private static final LocalDate HOY = LocalDate.of(2026, 10, 1);

    private static PostResponse post(String caption, PostStatus status, LocalDateTime programada, LocalDateTime publicada) {
        return PostResponse.builder().id(UUID.randomUUID()).caption(caption).status(status)
                .scheduledAt(programada).publishedAt(publicada).build();
    }

    @Test
    @DisplayName("solo lo de hoy, más lo que falló o está saliendo; en el orden del día")
    void soloHoy() {
        List<PostResponse> todas = List.of(
                post("publicada el 24", PostStatus.PUBLISHED, null, LocalDateTime.of(2026, 9, 24, 14, 39)),
                post("publicada hoy 18", PostStatus.PUBLISHED, null, HOY.atTime(18, 0)),
                post("programada hoy 11", PostStatus.SCHEDULED, HOY.atTime(11, 0), null),
                post("programada mañana", PostStatus.SCHEDULED, HOY.plusDays(1).atTime(11, 0), null),
                post("falló ayer", PostStatus.FAILED, HOY.minusDays(1).atTime(9, 0), null),
                post("borrador de hoy", PostStatus.DRAFT, HOY.atTime(12, 0), null));

        assertThat(DashboardService.deHoy(todas, HOY)).extracting(PostResponse::getCaption)
                .containsExactly("falló ayer", "programada hoy 11", "publicada hoy 18");
    }

    @Test
    @DisplayName("un día sin nada da una lista vacía, no lo de otros días")
    void diaSinNada() {
        List<PostResponse> todas = List.of(
                post("publicada el 24", PostStatus.PUBLISHED, null, LocalDateTime.of(2026, 9, 24, 14, 39)));
        assertThat(DashboardService.deHoy(todas, HOY)).isEmpty();
    }
}
