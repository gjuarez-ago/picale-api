package com.metricol.api.entity;

import com.metricol.api.enums.LicenseStatus;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** {@link LicenseStatus} como texto libre: la columna no lleva restricción CHECK. */
@Converter
public class LicenseStatusConverter implements AttributeConverter<LicenseStatus, String> {

    @Override
    public String convertToDatabaseColumn(LicenseStatus estado) {
        return estado == null ? null : estado.name();
    }

    @Override
    public LicenseStatus convertToEntityAttribute(String texto) {
        return texto == null || texto.isBlank() ? null : LicenseStatus.valueOf(texto.trim());
    }
}
