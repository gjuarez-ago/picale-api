package com.metricol.api.entity.converter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.BrandProfile;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * El perfil de marca como JSON en una sola columna de texto.
 *
 * <p>Nunca tumba una lectura: si el JSON guardado no se entiende (un campo de
 * una versión futura, una edición a mano) el espacio se abre igual, sin perfil,
 * y se avisa en el log. Perder el perfil de un espacio es recuperable; que no
 * abra, no.
 */
@Converter
public class BrandProfileConverter implements AttributeConverter<BrandProfile, String> {

    private static final Logger log = LoggerFactory.getLogger(BrandProfileConverter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(BrandProfile perfil) {
        if (perfil == null || perfil.vacio()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(perfil);
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo guardar el perfil de marca.", ex);
        }
    }

    @Override
    public BrandProfile convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, BrandProfile.class);
        } catch (Exception ex) {
            log.warn("El perfil de marca guardado no se pudo leer y se ignora: {}", ex.getMessage());
            return null;
        }
    }
}
