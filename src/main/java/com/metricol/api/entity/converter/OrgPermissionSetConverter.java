package com.metricol.api.entity.converter;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import com.metricol.api.enums.OrgPermission;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Los permisos de organización como texto separado por comas. Mismo criterio
 * que {@link PermissionSetConverter}: son dos valores como mucho y se leen
 * siempre con su fila; un nombre que ya no exista se ignora en vez de romper.
 */
@Converter
public class OrgPermissionSetConverter implements AttributeConverter<Set<OrgPermission>, String> {

    @Override
    public String convertToDatabaseColumn(Set<OrgPermission> permisos) {
        if (permisos == null || permisos.isEmpty()) {
            return null;
        }
        return permisos.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    @Override
    public Set<OrgPermission> convertToEntityAttribute(String texto) {
        Set<OrgPermission> permisos = EnumSet.noneOf(OrgPermission.class);
        if (texto == null || texto.isBlank()) {
            return permisos;
        }
        Arrays.stream(texto.split(",")).map(String::trim).filter(n -> !n.isEmpty()).forEach(n -> {
            try {
                permisos.add(OrgPermission.valueOf(n));
            } catch (IllegalArgumentException ex) {
                // Permiso retirado: la fila sigue siendo válida.
            }
        });
        return permisos;
    }
}
