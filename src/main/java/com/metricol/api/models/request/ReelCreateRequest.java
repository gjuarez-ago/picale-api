package com.metricol.api.models.request;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReelCreateRequest {
    @NotNull
    @Size(min = 4, max = 8)
    private List<UUID> assetIds = new ArrayList<>();

    /** Nulo usa la duración editorial configurada. */
    private Integer durationSeconds;

    @Size(max = 160)
    private String title;

    /** Nulo = automático; false conserva el reel en silencio. */
    private Boolean automaticMusic;

    /** Giro o tema editorial, libre; el catálogo lo normaliza a su taxonomía. */
    @Size(max = 300)
    private String musicTheme;

    /** Promoción, tutorial, testimonio, evento, etc. */
    @Size(max = 300)
    private String musicIntent;

    /** Si habrá narración, se prefieren camas musicales menos invasivas. */
    private Boolean voiceOver;
}
