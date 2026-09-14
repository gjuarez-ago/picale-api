package com.metricol.api.models.request;

import com.metricol.api.enums.Platform;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SocialAccountConnectRequest {

    @NotNull
    private Platform platform;

    @NotBlank
    private String accountName;
}
