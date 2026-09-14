package com.metricol.api.models.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.metricol.api.enums.Platform;
import com.metricol.api.enums.SocialAccountStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialAccountResponse {
    private UUID id;
    private Platform platform;
    private String accountName;
    /**
     * La foto de la cuenta o de la Página donde sale la publicación.
     * {@code null} si la red no da foto: la app cae al logo de siempre.
     */
    private String avatarUrl;

    private SocialAccountStatus status;
    private LocalDateTime connectedAt;
}
