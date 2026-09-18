package com.metricol.api.entity.converter;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Guarda un conjunto de etiquetas como texto separado por comas.
 *
 * <p>Conserva el orden en que se escribieron ({@link LinkedHashSet}): las
 * etiquetas las pone una persona, y verlas reordenadas solas al guardar se
 * siente como si el sistema hubiera cambiado algo por su cuenta.
 *
 * <p>La coma es el separador, así que se quita de cada etiqueta al guardar. Es
 * la única forma de que "Restaurantes, Mérida" no se convierta en dos.
 */
@Converter
public class StringSetConverter implements AttributeConverter<Set<String>, String> {

    @Override
    public String convertToDatabaseColumn(Set<String> valores) {
        if (valores == null || valores.isEmpty()) {
            return null;
        }
        return valores.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(v -> v.strip().replace(",", " "))
                .collect(Collectors.joining(","));
    }

    @Override
    public Set<String> convertToEntityAttribute(String texto) {
        Set<String> valores = new LinkedHashSet<>();
        if (texto == null || texto.isBlank()) {
            return valores;
        }
        Arrays.stream(texto.split(","))
                .map(String::strip)
                .filter(v -> !v.isEmpty())
                .forEach(valores::add);
        return valores;
    }
}
