package com.metricol.api.models.response;

import java.time.OffsetDateTime;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ApiResponse<T> {

    private final boolean ok;
    private final T result;
    private final String errorCode;
    private final String userMessage;
    private final OffsetDateTime timestamp;

    public static <T> ApiResponse<T> success(T result) {
        return ApiResponse.<T>builder()
                .ok(true)
                .result(result)
                .timestamp(OffsetDateTime.now())
                .build();
    }

    public static <T> ApiResponse<T> error(String errorCode, String userMessage) {
        return ApiResponse.<T>builder()
                .ok(false)
                .errorCode(errorCode)
                .userMessage(userMessage)
                .timestamp(OffsetDateTime.now())
                .build();
    }
}
