package com.metricol.api.entity.converter;

import com.metricol.api.enums.VerificationPurpose;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Guarda el propósito como texto plano, sin {@code @Enumerated}.
 *
 * <p>La diferencia importa con {@code ddl-auto: update}: {@code @Enumerated}
 * deja en la base un {@code check} con los valores del enum de ese momento,
 * y {@code update} nunca lo vuelve a tocar. El día que se añada un segundo
 * propósito, la inserción fallaría en producción con un error de restricción
 * que no apunta a ningún lado. Con un convertidor la columna es un varchar y
 * el enum puede crecer.
 */
@Converter
public class VerificationPurposeConverter implements AttributeConverter<VerificationPurpose, String> {

    @Override
    public String convertToDatabaseColumn(VerificationPurpose purpose) {
        return purpose == null ? null : purpose.name();
    }

    @Override
    public VerificationPurpose convertToEntityAttribute(String valor) {
        return valor == null ? null : VerificationPurpose.valueOf(valor);
    }
}
