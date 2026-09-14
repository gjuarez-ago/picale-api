package com.metricol.api.models.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Un workspace nuevo para el mismo usuario: normalmente, un cliente más. */
@Getter
@Setter
public class WorkspaceCreateRequest {

    @NotBlank
    @Size(min = 2, max = 120)
    private String name;
}
