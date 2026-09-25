package com.metricol.api.models.request.root;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/** Cuántos espacios activos puede tener una organización. */
@Getter
@Setter
public class CupoRequest {

    @Min(1)
    @Max(500)
    private int maxWorkspaces;
}
