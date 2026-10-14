package com.metricol.api.models.request;

import java.util.Map;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreativeProposalTextRequest {
    @Size(max = 160)
    private String title;

    @NotEmpty
    @Size(max = 5)
    private Map<String, @Size(min = 1, max = 2200) String> captions;
}
