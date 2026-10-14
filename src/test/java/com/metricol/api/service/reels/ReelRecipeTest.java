package com.metricol.api.service.reels;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ReelRecipeTest {

    @Test
    void sameInputsProduceSameHashAndOrderMatters() {
        UUID workspace = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ReelRecipe a = new ReelRecipe("photos-v1", workspace, List.of(first, second), 20, "  Café del día ");
        ReelRecipe b = new ReelRecipe("photos-v1", workspace, List.of(first, second), 20, "Café del día");
        ReelRecipe reversed = new ReelRecipe("photos-v1", workspace, List.of(second, first), 20, "Café del día");

        assertThat(a.hash()).isEqualTo(b.hash()).hasSize(64);
        assertThat(a.json()).contains(first.toString(), second.toString(), "Café del día");
        assertThat(reversed.hash()).isNotEqualTo(a.hash());
    }
}
