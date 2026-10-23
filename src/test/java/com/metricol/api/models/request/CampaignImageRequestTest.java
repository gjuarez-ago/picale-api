package com.metricol.api.models.request;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class CampaignImageRequestTest {
    private CampaignImageRequest request(String format, List<String> texts) {
        return new CampaignImageRequest(1, new CampaignImageRequest.Format(format, null, null), List.of(), null,
                "  Conserva todo.  ", null, List.of(), null, null, false, List.of("INSTAGRAM"),
                new CampaignImageRequest.Creative(null, null, null, false, "", texts, CampaignImageRequest.CreativeMode.PHOTO_RETOUCH));
    }
    @Test void individualRetouchNormalizesOnlyEmptySlidePlaceholders() {
        for (var texts : List.of(List.<String>of(), List.of(""), List.of(" ")))
            assertThat(request("post", texts).creative().slideTexts()).isNull();
        assertThat(request("post", List.of("")).brief()).isEqualTo("  Conserva todo.  ");
        assertThat(request("post", List.of("")).creative().mode()).isEqualTo(CampaignImageRequest.CreativeMode.PHOTO_RETOUCH);
    }
    @Test void doesNotDiscardRealTextsOrReorderCarouselSlides() {
        var texts = List.of("Primero", "");
        assertThat(request("carousel", texts).creative().slideTexts()).isEqualTo(texts);
        assertThat(request("carousel", List.of("", "")).creative().slideTexts()).hasSize(2);
        assertThat(request("post", List.of("No borrar")).creative().slideTexts()).containsExactly("No borrar");
    }
    @Test void leavesInvalidTypesAndOversizedListsForValidation() {
        assertThat(request("post", java.util.Arrays.asList((String) null)).creative().slideTexts()).containsNull();
        assertThat(request("post", List.of("", "", "", "", "", "")).creative().slideTexts()).hasSize(6);
    }
}
