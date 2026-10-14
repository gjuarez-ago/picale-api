package com.metricol.api.models.request;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class CreativeBatchRequest {
    @NotEmpty @Size(max = 30)
    private List<UUID> assetIds = new ArrayList<>();
    @Size(max = 1000)
    private String brief;
}
