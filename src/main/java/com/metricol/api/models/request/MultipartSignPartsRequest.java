package com.metricol.api.models.request;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.Setter;

/** Qué partes firmar. El tope por petición lo fija también el servidor (app.media.multipart). */
@Getter
@Setter
public class MultipartSignPartsRequest {

    @NotEmpty
    @Size(max = 50)
    private List<@NotNull @Min(1) @Max(10000) Integer> partNumbers;
}
