package com.metricol.api.entity.converter;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import com.metricol.api.enums.Permission;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Guarda un conjunto de permisos como texto separado por comas.
 *
 * <p>Una columna y no una tabla aparte: son como mucho nueve valores por
 * persona, nunca se consultan sueltos ("quién tiene POST_PUBLISH" no es una
 * pregunta que haga nadie) y se leen siempre junto a su fila. Una tabla de
 * unión aquí solo añadiría un JOIN a cada comprobación de permisos, que es la
 * consulta más repetida del sistema.
 *
 * <p>Un nombre que ya no exista en el enum se ignora al leer, en vez de
 * reventar: si algún día se quita un permiso, las filas viejas siguen
 * abriéndose y la persona simplemente no lo tiene.
 */
@Converter
public class PermissionSetConverter implements AttributeConverter<Set<Permission>, String> {

    @Override
    public String convertToDatabaseColumn(Set<Permission> permisos) {
        if (permisos == null || permisos.isEmpty()) {
            return null;
        }
        return permisos.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    @Override
    public Set<Permission> convertToEntityAttribute(String texto) {
        Set<Permission> permisos = EnumSet.noneOf(Permission.class);
        if (texto == null || texto.isBlank()) {
            return permisos;
        }

        Arrays.stream(texto.split(","))
                .map(String::trim)
                .filter(nombre -> !nombre.isEmpty())
                .forEach(nombre -> {
                    try {
                        permisos.add(Permission.valueOf(nombre));
                    } catch (IllegalArgumentException ex) {
                        // Permiso retirado del enum: la fila sigue siendo válida.
                    }
                });
        return permisos;
    }
}
