package com.metricol.api.util;

import java.util.Locale;

/**
 * La única forma en que un correo entra a la base o se busca en ella.
 *
 * <p>Existe por un fallo concreto: {@code Ana@x.com} y {@code ana@x.com} eran
 * dos cuentas. El registro comprobaba con {@code existsByEmail}, sensible a
 * mayúsculas, así que la segunda pasaba; y desde ese momento entrar con
 * cualquiera de las dos era una moneda al aire, porque {@code findByEmail}
 * devolvía una u otra según cómo se escribiera. Con la columna ya única en la
 * base, el segundo registro falla; con esto, ni siquiera lo intenta.
 *
 * <p>Se aplica en los dos extremos —al guardar y al buscar— y en un solo
 * sitio. Normalizar solo al guardar dejaría fuera a quien ya existe con
 * mayúsculas; normalizar solo al buscar dejaría entrar duplicados nuevos.
 */
public final class Correos {

    private Correos() {
    }

    /** Sin espacios alrededor y en minúsculas. {@code null} se queda en {@code null}. */
    public static String normalizar(String correo) {
        if (correo == null) {
            return null;
        }
        return correo.strip().toLowerCase(Locale.ROOT);
    }
}
